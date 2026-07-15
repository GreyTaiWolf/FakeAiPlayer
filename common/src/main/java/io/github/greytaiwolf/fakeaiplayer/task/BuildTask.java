package io.github.greytaiwolf.fakeaiplayer.task;

import io.github.greytaiwolf.fakeaiplayer.action.ActionResult;
import io.github.greytaiwolf.fakeaiplayer.action.BuildAction;
import io.github.greytaiwolf.fakeaiplayer.action.InventoryAction;
import io.github.greytaiwolf.fakeaiplayer.action.MaterialPalette;
import io.github.greytaiwolf.fakeaiplayer.action.MiningAction;
import io.github.greytaiwolf.fakeaiplayer.building.plan.CellOperation;
import io.github.greytaiwolf.fakeaiplayer.building.plan.ReplacePolicy;
import io.github.greytaiwolf.fakeaiplayer.entity.AIPlayerEntity;
import io.github.greytaiwolf.fakeaiplayer.goal.StructureVerifier;
import io.github.greytaiwolf.fakeaiplayer.log.BotLog;
import io.github.greytaiwolf.fakeaiplayer.mode.CapabilityRuntime;
import io.github.greytaiwolf.fakeaiplayer.mode.ObservableWorldQuery;
import io.github.greytaiwolf.fakeaiplayer.mode.PrivilegedCapability;
import io.github.greytaiwolf.fakeaiplayer.pathfinding.Standability;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;

public final class BuildTask extends AbstractTask {
    private static final int WORK_STAND_HORIZONTAL_RADIUS = 3;
    // A standing player's eye is roughly 1.62 blocks above the feet. Searching five blocks below
    // the target is therefore required for a legal 4.5-block interaction from the loft deck.
    private static final int WORK_STAND_BELOW_TARGET = 5;
    private enum Phase {
        SITE,
        FLATTEN,
        BUILD
    }

    private enum FlattenKind {
        CLEAR,
        FILL
    }

    private final BlueprintSchema blueprint;
    private final Map<LocalCell, Integer> placementIndexByCell;
    private BlockPos anchor;
    private final boolean autoSite;
    private final boolean flatten;
    private final String expectedBlueprintDigest;
    private final String validatedBlueprintDigest;
    private final String blueprintBindingProblem;
    private final String expectedDimension;
    private final Deque<FlattenTarget> flattenTargets = new ArrayDeque<>();
    private Phase phase = Phase.SITE;
    private FlattenTarget currentFlattenTarget;
    private int flattenTargetTick;   // 当前整地格起算 tick:够不到的格超预算即跳过,防 nearbyStand 退化"走向自己"死循环
    private int nextIndex;
    private int buildTargetTick;      // 当前落块格起算 tick:放不到的块超预算即跳过(best-effort),防 moveWithinReach 永续寻路空转
    private int buildTargetIndex = -1;
    private int retryTicks;
    private int placeDelayTicks;
    private int placedBlocks;
    private int skippedBlocks;
    private final Set<Integer> completedPlacementIndices = new HashSet<>();
    private boolean flattenMiningStarted;
    private boolean buildMiningStarted;
    private String note = "";

    public BuildTask(BlueprintSchema blueprint, BlockPos anchor) {
        this(blueprint, anchor, false, false);
    }

    public BuildTask(BlueprintSchema blueprint, BlockPos anchor, boolean autoSite, boolean flatten) {
        this(blueprint, anchor, autoSite, flatten, null, null);
    }

    /** Constructor used by a confirmed generated mission with immutable content/world binding. */
    public BuildTask(BlueprintSchema blueprint,
                     BlockPos anchor,
                     boolean autoSite,
                     boolean flatten,
                     String expectedBlueprintDigest,
                     String expectedDimension) {
        BlueprintSchema executionBlueprint = blueprint;
        String validatedDigest = null;
        String bindingProblem = null;
        if (expectedBlueprintDigest != null) {
            try {
                // Freeze the verified program into the loader's deep-immutable expanded form.
                // Runtime ticks can then compare two cached SHA-256 strings instead of resolving
                // up to 4096 BlockStates and hashing the complete plan on the server thread.
                executionBlueprint = BlueprintLoader.expand(blueprint);
                validatedDigest = BlueprintLoader.canonicalDigest(executionBlueprint);
                if (!expectedBlueprintDigest.equals(validatedDigest)) {
                    bindingProblem = "confirmed_blueprint_digest_mismatch";
                }
            } catch (java.io.IOException exception) {
                bindingProblem = "confirmed_blueprint_invalid";
            }
        }
        this.blueprint = executionBlueprint;
        Map<LocalCell, Integer> indices = new HashMap<>();
        for (int index = 0; index < executionBlueprint.placements().size(); index++) {
            BlueprintSchema.BlockPlacement placement = executionBlueprint.placements().get(index);
            indices.putIfAbsent(
                    new LocalCell(placement.dx(), placement.dy(), placement.dz()), index);
        }
        this.placementIndexByCell = Map.copyOf(indices);
        this.anchor = anchor == null ? null : anchor.immutable();
        this.autoSite = autoSite;
        this.flatten = flatten;
        this.expectedBlueprintDigest = expectedBlueprintDigest;
        this.validatedBlueprintDigest = validatedDigest;
        this.blueprintBindingProblem = bindingProblem;
        this.expectedDimension = expectedDimension;
    }

    @Override
    public String name() {
        return "build";
    }

    @Override
    public String describe() {
        String anchorText = anchor == null ? "auto" : compact(anchor);
        return "Building " + blueprint.name() + " at " + anchorText + " " + nextIndex + "/" + blueprint.placements().size()
                + " phase=" + phase + (note.isBlank() ? "" : " note=" + note);
    }

    @Override
    public double progress() {
        if (state == TaskState.COMPLETED) {
            return 1.0D;
        }
        double buildProgress = blueprint.placements().isEmpty() ? 1.0D : Math.min(1.0D, (double) nextIndex / blueprint.placements().size());
        return switch (phase) {
            case SITE -> 0.0D;
            case FLATTEN -> Math.min(0.25D, flattenTargets.isEmpty() ? 0.25D : 0.10D);
            case BUILD -> 0.25D + buildProgress * 0.75D;
        };
    }

    @Override
    public boolean isWaiting() {
        // 建造=原地立着逐格挖/放(整地+砌房),位置长时间不变是正常作业,不是卡死。
        // 不豁免则 StuckWatcher 200t 位置不变即误杀(real_build 实测 task_stuck_aborted reason=stuck:build,
        // 真实地形整地阶段静立施工被斩,phase=FLATTEN progress=0.1)。卡死保护交本任务 build_timeout(7200t)+
        // moveWithinReach 自身 retryTicks(寻路真失败才计)兜底,比 StuckWatcher 更懂建造语义。
        return true;
    }

    @Override
    protected void onStart(AIPlayerEntity bot) {
        nextIndex = 0;
        buildTargetTick = 0;
        buildTargetIndex = -1;
        retryTicks = 0;
        placeDelayTicks = 0;
        placedBlocks = 0;
        skippedBlocks = 0;
        flattenTargets.clear();
        currentFlattenTarget = null;
        flattenMiningStarted = false;
        buildMiningStarted = false;
        phase = Phase.SITE;
    }

    @Override
    protected void onTick(AIPlayerEntity bot) {
        if (!confirmedBindingStillValid(bot)) {
            return;
        }
        if (elapsed > 16000) {
            // 真实地形建房=整地(挖高填低)+逐格砌 100+ 块,且重活拖低 tps;7200t 不够(实测只到 81/116)。
            // 放宽到 16000t 让真实地形也能整地+落成;lab 平整建房 346t 远不触此上限,零影响。
            bot.getActionPack().stopAll();
            fail("build_timeout");
            return;
        }
        if (placeDelayTicks > 0) {
            placeDelayTicks--;
            return;
        }
        switch (phase) {
            case SITE -> site(bot);
            case FLATTEN -> flatten(bot);
            case BUILD -> build(bot);
        }
    }

    /**
     * Re-proves the reviewed content and dimension before every task tick, including delay,
     * navigation and final verification ticks. A dimension transfer can therefore never resume
     * the same anchor coordinates in a different world.
     */
    private boolean confirmedBindingStillValid(AIPlayerEntity bot) {
        if (expectedDimension != null
                && !expectedDimension.equals(bot.serverLevel().dimension().location().toString())) {
            bot.getActionPack().stopAll();
            fail("confirmed_build_dimension_mismatch: expected=" + expectedDimension
                    + " actual=" + bot.serverLevel().dimension().location());
            return false;
        }
        // The strict constructor already deep-froze and hashed the schema. Every tick still
        // compares the binding, but performs no O(placements) registry work on the server thread.
        if (expectedBlueprintDigest != null
                && (blueprintBindingProblem != null
                        || !expectedBlueprintDigest.equals(validatedBlueprintDigest))) {
            bot.getActionPack().stopAll();
            fail(blueprintBindingProblem == null
                    ? "confirmed_blueprint_digest_mismatch" : blueprintBindingProblem);
            return false;
        }
        return true;
    }

    private void site(AIPlayerEntity bot) {
        if (anchor == null) {
            if (!autoSite) {
                fail("missing_anchor");
                return;
            }
            // flatten 开启时用 lenient 选址:真实起伏地形罕有现成平地,选最平可用点交 FLATTEN 整平
            //(治 real_build no_flat_site 5/10);flatten 关闭时严格(平整画布零回归)。
            anchor = SiteFinder.findSite(bot, blueprint.width(), blueprint.depth(), 16, flatten).orElse(null);
            if (anchor == null) {
                fail("no_flat_site");
                return;
            }
            note = "auto_site=" + compact(anchor);
        }
        if (flatten) {
            // Generic site flattening is a legacy, separately requested operation. It can both
            // bypass a reviewed V2 replacement policy and remove a neighbour/support, thereby
            // breaking PRESERVE indirectly. V2 plans must execute at their confirmed anchor with
            // explicit CLEAR cells instead.
            if (blueprint.placements().stream()
                    .anyMatch(placement -> placement.operation() == null
                            || placement.replacePolicy() == null
                            || placement.sequence() != null
                            || placement.operation() == CellOperation.PRESERVE
                            || placement.operation() == CellOperation.TEMPORARY
                            || (placement.operation() == CellOperation.PLACE
                                    && placement.replacePolicy() != ReplacePolicy.REPLACE_REPLACEABLE)
                            || (placement.operation() == CellOperation.CLEAR
                                    && placement.replacePolicy() != ReplacePolicy.CLEAR_AUTHORIZED))) {
                bot.getActionPack().stopAll();
                fail("flatten_not_allowed_with_reviewed_cell_semantics");
                return;
            }
            planFlatten(bot);
            phase = Phase.FLATTEN;
        } else {
            phase = Phase.BUILD;
        }
    }

    private void planFlatten(AIPlayerEntity bot) {
        ServerLevel world = bot.serverLevel();
        boolean rawTerrainRead = CapabilityRuntime.decide(
                bot, PrivilegedCapability.HIDDEN_BLOCK_SCAN, "build_flatten_plan").allowed();
        flattenTargets.clear();
        for (int dx = 0; dx < blueprint.width(); dx++) {
            for (int dz = 0; dz < blueprint.depth(); dz++) {
                BlockPos ground = anchor.offset(dx, -1, dz);
                if (!rawTerrainRead
                        || world.getBlockState(ground).isAir()
                        || !world.getFluidState(ground).isEmpty()) {
                    flattenTargets.addLast(new FlattenTarget(ground, FlattenKind.FILL));
                }
                for (int dy = 0; dy < Math.max(blueprint.height(), 1); dy++) {
                    BlockPos clear = anchor.offset(dx, dy, dz);
                    if (!rawTerrainRead
                            || (!world.getBlockState(clear).isAir() && world.getFluidState(clear).isEmpty())) {
                        flattenTargets.addLast(new FlattenTarget(clear, FlattenKind.CLEAR));
                    }
                }
            }
        }
    }

    private void flatten(AIPlayerEntity bot) {
        if (currentFlattenTarget == null) {
            currentFlattenTarget = flattenTargets.pollFirst();
            flattenMiningStarted = false;
            flattenTargetTick = elapsed;
            retryTicks = 0;
            if (currentFlattenTarget == null) {
                phase = Phase.BUILD;
                return;
            }
        }
        // 整地格预算:够不到的格(nearbyStand 无落脚点会退化成 startPathTo 自己→原地死循环,real_build 实测
        // path_idle 0 进度 build_timeout)→ 50t 内没搞定就跳过,best-effort 继续整地/盖房,站不到的格不强求。
        if (elapsed - flattenTargetTick > 50) {
            if (flattenMiningStarted || !bot.getActionPack().isMiningIdle()) {
                bot.getActionPack().stopAll();
                flattenMiningStarted = false;
            }
            note = "flatten_skip=" + compact(currentFlattenTarget.pos()); // 够不到的整地格跳过(防原地死循环)
            currentFlattenTarget = null;
            return;
        }
        if (currentFlattenTarget.kind() == FlattenKind.CLEAR) {
            clearFlattenBlock(bot);
        } else {
            fillFlattenBlock(bot);
        }
    }

    private void clearFlattenBlock(AIPlayerEntity bot) {
        BlockPos pos = currentFlattenTarget.pos();
        if (!ensureObservableWorkPose(bot, pos, "flatten_clear")) {
            return;
        }
        ServerLevel world = bot.serverLevel();
        BlockState current = world.getBlockState(pos);
        if (current.isAir()) {
            if (flattenMiningStarted || !bot.getActionPack().isMiningIdle()) {
                bot.getActionPack().stopAll();
            }
            Standability.clearCache();
            currentFlattenTarget = null;
            retryTicks = 0;
            return;
        }
        // Re-check on every tick, including while the action pack is mining. A container or an
        // unbreakable state installed after site planning must cancel the pending mutation.
        if (world.getBlockEntity(pos) != null) {
            failUnsafeMutation(bot, "flatten_target_has_block_entity", pos);
            return;
        }
        if (current.getDestroySpeed(world, pos) < 0.0F) {
            failUnsafeMutation(bot, "flatten_target_unbreakable", pos);
            return;
        }
        if (!flattenMiningStarted && bot.getActionPack().isMiningIdle()) {
            current = world.getBlockState(pos);
            if (current.isAir()) {
                currentFlattenTarget = null;
                return;
            }
            if (world.getBlockEntity(pos) != null || current.getDestroySpeed(world, pos) < 0.0F) {
                failUnsafeMutation(bot, "flatten_target_changed_before_mining", pos);
                return;
            }
            ActionResult result = MiningAction.startMining(bot, pos, Direction.getApproximateNearest(bot.getEyePosition().subtract(pos.getCenter())));
            if (result.isFailed()) {
                fail(result.reason());
                return;
            }
            flattenMiningStarted = true;
        }
    }

    private void fillFlattenBlock(AIPlayerEntity bot) {
        BlockPos pos = currentFlattenTarget.pos();
        if (!ensureObservableWorkPose(bot, pos, "flatten_fill")) {
            return;
        }
        if (!bot.serverLevel().getBlockState(pos).isAir()) {
            Standability.clearCache();
            currentFlattenTarget = null;
            retryTicks = 0;
            return;
        }
        OptionalInt slot = MaterialPalette.pickSlot(bot, "dirt_like");
        if (slot.isEmpty()) {
            slot = MaterialPalette.pickAnyBlockSlot(bot);
        }
        if (slot.isEmpty()) {
            fail("missing_flatten_material");
            return;
        }
        InventoryAction.equipFromSlot(bot, slot.getAsInt());
        // Site fill is REQUIRE_EMPTY. Do not let a late replaceable block or block entity be
        // overwritten merely because the first observation happened before inventory work.
        if (!bot.serverLevel().getBlockState(pos).isAir()
                || bot.serverLevel().getBlockEntity(pos) != null) {
            currentFlattenTarget = null;
            retryTicks = 0;
            return;
        }
        ActionResult result = BuildAction.placeBlockAt(bot, pos);
        if (result.isSuccess()) {
            Standability.clearCache();
            currentFlattenTarget = null;
            retryTicks = 0;
            placeDelayTicks = 2;
            return;
        }
        retryTicks++;
        BlockPos stand = nearbyStand(bot, pos);
        if (stand != null && bot.getActionPack().isPathExecutorIdle()) {
            bot.getActionPack().startNonMutatingPathTo(stand);
        }
        if (retryTicks > 12) {
            fail("flatten_fill_failed: " + result.reason() + " at " + compact(pos));
        }
    }

    private void build(AIPlayerEntity bot) {
        if (nextIndex >= blueprint.placements().size()) {
            var report = StructureVerifier.verify(
                    bot.serverLevel(), blueprint, anchor, placedBlocks, skippedBlocks);
            bot.getActionPack().stopAll();
            if (report.mismatched() > 0 || report.matched() != report.expected()) {
                fail("structure_incomplete: matched=" + report.matched()
                        + "/" + report.expected()
                        + " skipped=" + report.skipped());
            } else {
                complete();
            }
            return;
        }
        BlueprintSchema.BlockPlacement placement = blueprint.placements().get(nextIndex);
        BlockPos pos = anchor.offset(placement.dx(), placement.dy(), placement.dz());
        // Registry validation is pure and must happen before visibility/path budgets. Otherwise an
        // invalid ID can be skipped while still unseen and reach terminal structure verification,
        // where it used to surface as an uncaught Identifier exception.
        Block block = resolveBlock(placement.blockId());
        if (block == null) {
            return;
        }
        String operationProblem = executableOperationProblem(placement, block);
        if (operationProblem != null) {
            failUnsafeMutation(bot, operationProblem, pos);
            return;
        }
        String prerequisiteProblem = prerequisiteProblem(bot.serverLevel(), placement);
        if (prerequisiteProblem != null) {
            failUnsafeMutation(bot, prerequisiteProblem, pos);
            return;
        }
        String foundationProblem = reviewedFoundationSupportProblem(
                bot.serverLevel(), pos, placement);
        if (foundationProblem != null) {
            failUnsafeMutation(bot, foundationProblem, pos);
            return;
        }
        // 落块格预算(镜像 flatten 50t skip):同一块连续放不到时先记录并继续其余结构，避免单格把
        // 整个执行器卡到 build_timeout。末尾按完整 blueprint（包括 AIR）核验世界状态；只有 exact
        // match 才完成，否则以 structure_incomplete 失败，不能把 best-effort 误报为完工。
        if (nextIndex != buildTargetIndex) {
            buildTargetIndex = nextIndex;
            buildTargetTick = elapsed;
            retryTicks = 0;
            buildMiningStarted = false;
        } else if (elapsed - buildTargetTick > 80) {
            if (placement.operation() == CellOperation.PRESERVE) {
                failUnsafeMutation(bot, "preserve_target_timeout", pos);
                return;
            }
            skipBuildTarget(bot, pos, "target_timeout");
            return;
        }
        // Recovery/retry is world-first: a previously completed reviewed cell needs no work pose.
        // Multi-cell items only skip when the whole atomic group matches, never on a partial door.
        if (placementAlreadySatisfied(bot.serverLevel(), placement)) {
            if (buildMiningStarted || !bot.getActionPack().isMiningIdle()) {
                bot.getActionPack().stopAll();
            }
            finishBuildTarget(false);
            return;
        }
        if (!ensureObservableWorkPose(bot, pos, "build", placement)) {
            return;
        }
        switch (placement.operation()) {
            case PLACE -> placeBlueprintBlock(bot, placement, pos, block);
            case CLEAR -> clearBlueprintCell(bot, placement, pos);
            case PRESERVE -> verifyPreservedCell(bot, placement, pos);
            case TEMPORARY -> failUnsafeMutation(bot, "temporary_cell_requires_cleanup_executor", pos);
        }
    }

    private void placeBlueprintBlock(AIPlayerEntity bot,
                                     BlueprintSchema.BlockPlacement placement,
                                     BlockPos pos,
                                     Block block) {
        String atomicProblem = atomicGroupMutationProblem(bot.serverLevel(), placement);
        if (atomicProblem != null) {
            failUnsafeMutation(bot, atomicProblem, pos);
            return;
        }
        if (StructureVerifier.matches(bot.serverLevel(), anchor, placement)) {
            finishBuildTarget(false);
            return;
        }
        String targetProblem = placeMutationProblem(bot.serverLevel(), pos, placement.replacePolicy());
        if (targetProblem != null) {
            failUnsafeMutation(bot, targetProblem, pos);
            return;
        }
        OptionalInt slot = materialSlot(bot, placement, block);
        if (slot.isEmpty()) {
            fail("missing_material: " + materialName(placement));
            return;
        }
        InventoryAction.equipFromSlot(bot, slot.getAsInt());

        // Inventory selection, callbacks and another server subsystem may have changed the cell.
        // Re-read immediately before the player-use mutation so the reviewed replacement policy
        // is an execution-time invariant, not merely a preview-time check.
        if (StructureVerifier.matches(bot.serverLevel(), anchor, placement)) {
            finishBuildTarget(false);
            return;
        }
        atomicProblem = atomicGroupMutationProblem(bot.serverLevel(), placement);
        if (atomicProblem != null) {
            failUnsafeMutation(bot, atomicProblem + "_before_place", pos);
            return;
        }
        targetProblem = placeMutationProblem(bot.serverLevel(), pos, placement.replacePolicy());
        if (targetProblem != null) {
            failUnsafeMutation(bot, targetProblem + "_before_place", pos);
            return;
        }
        String foundationProblem = reviewedFoundationSupportProblem(
                bot.serverLevel(), pos, placement);
        if (foundationProblem != null) {
            failUnsafeMutation(bot, foundationProblem + "_before_place", pos);
            return;
        }
        ActionResult result = BuildAction.placeBlockAt(
                bot, pos, placement.blockId(), placement.palette(), placement.properties());
        if (result.isSuccess()) {
            if (!StructureVerifier.matches(bot.serverLevel(), anchor, placement)) {
                failUnsafeMutation(bot, "place_postcondition_mismatch", pos);
                return;
            }
            finishBuildTarget(true);
            placeDelayTicks = 2;
            return;
        }
        retryTicks++;
        if (result.reason().startsWith("placed_state_")) {
            skipBuildTarget(bot, pos, result.reason());
            return;
        }
        BlockPos stand = nearbyStand(bot, pos, placement);
        if (stand != null && bot.getActionPack().isPathExecutorIdle()) {
            bot.getActionPack().startNonMutatingPathTo(stand);
        }
        placeDelayTicks = 5;
        if (retryTicks > 12) {
            // best-effort execution:单块反复失败时先继续其余结构；终态仍会因 skippedBlocks>0
            // 明确失败。与上面的 80t 预算双保险，谁先到谁记录该缺口。
            skipBuildTarget(bot, pos, "place_failed:" + result.reason());
        }
    }

    private void clearBlueprintCell(AIPlayerEntity bot,
                                    BlueprintSchema.BlockPlacement placement,
                                    BlockPos pos) {
        ServerLevel world = bot.serverLevel();
        if (StructureVerifier.matches(world, anchor, placement)) {
            if (buildMiningStarted || !bot.getActionPack().isMiningIdle()) {
                // Do not leave a stale break action armed at a cell that is currently correct;
                // another actor could place a protected block there before the next task tick.
                bot.getActionPack().stopAll();
            }
            finishBuildTarget(buildMiningStarted);
            return;
        }
        String targetProblem = clearMutationProblem(world, pos, placement.replacePolicy());
        if (targetProblem != null) {
            failUnsafeMutation(bot, targetProblem, pos);
            return;
        }
        if (!bot.getActionPack().isMiningIdle()) {
            return;
        }
        if (buildMiningStarted) {
            buildMiningStarted = false;
            retryTicks++;
        }
        // Mining may have stopped because the target changed. Re-run the complete safety check
        // immediately before arming a new break action.
        if (StructureVerifier.matches(world, anchor, placement)) {
            finishBuildTarget(false);
            return;
        }
        targetProblem = clearMutationProblem(world, pos, placement.replacePolicy());
        if (targetProblem != null) {
            failUnsafeMutation(bot, targetProblem + "_before_mining", pos);
            return;
        }
        ActionResult result = MiningAction.startMining(
                bot, pos, Direction.getApproximateNearest(bot.getEyePosition().subtract(pos.getCenter())));
        if (result.isFailed()) {
            retryTicks++;
            if (retryTicks > 12) {
                skipBuildTarget(bot, pos, "air_clear_failed:" + result.reason());
            }
            return;
        }
        buildMiningStarted = true;
    }

    private void verifyPreservedCell(AIPlayerEntity bot,
                                     BlueprintSchema.BlockPlacement placement,
                                     BlockPos pos) {
        if (!StructureVerifier.matches(bot.serverLevel(), anchor, placement)) {
            failUnsafeMutation(bot, "preserve_state_mismatch", pos);
            return;
        }
        finishBuildTarget(false);
    }

    private static String executableOperationProblem(BlueprintSchema.BlockPlacement placement,
                                                     Block expectedBlock) {
        CellOperation operation = placement.operation();
        ReplacePolicy policy = placement.replacePolicy();
        if (operation == null || policy == null) {
            return "operation_or_policy_missing";
        }
        if (operation == CellOperation.CLEAR && !expectedBlock.defaultBlockState().isAir()) {
            return "clear_requires_air_state";
        }
        if ((operation == CellOperation.PLACE || operation == CellOperation.TEMPORARY)
                && expectedBlock.defaultBlockState().isAir()) {
            return "place_requires_non_air_state";
        }
        return switch (operation) {
            case PLACE -> switch (policy) {
                case REQUIRE_EMPTY, REPLACE_REPLACEABLE, REPLACE_NATURAL -> null;
                case FORCE_AUTHORIZED -> "force_policy_requires_authorized_executor";
                default -> "place_policy_mismatch:" + policy;
            };
            case CLEAR -> switch (policy) {
                case CLEAR_AUTHORIZED -> null;
                case FORCE_AUTHORIZED -> "force_policy_requires_authorized_executor";
                default -> "clear_policy_mismatch:" + policy;
            };
            case PRESERVE -> policy == ReplacePolicy.PRESERVE_EXISTING
                    ? null : "preserve_policy_mismatch:" + policy;
            case TEMPORARY -> "temporary_cell_requires_cleanup_executor";
        };
    }

    private static String placeMutationProblem(ServerLevel world,
                                               BlockPos pos,
                                               ReplacePolicy policy) {
        BlockState current = world.getBlockState(pos);
        if (world.getBlockEntity(pos) != null) {
            return "place_target_has_block_entity";
        }
        if (!current.isAir() && current.getDestroySpeed(world, pos) < 0.0F) {
            return "place_target_unbreakable";
        }
        return switch (policy) {
            case REQUIRE_EMPTY -> current.isAir() ? null : "place_target_must_be_empty";
            case REPLACE_REPLACEABLE -> current.isAir()
                    || current.canBeReplaced() && current.getFluidState().isEmpty()
                    ? null : "place_target_not_replaceable";
            // Solid natural terrain needs an explicit CLEAR step. BuildAction only performs a
            // vanilla placement and must not turn this broader policy into an implicit break.
            case REPLACE_NATURAL -> current.isAir()
                    || current.canBeReplaced() && current.getFluidState().isEmpty()
                    ? null : "replace_natural_requires_explicit_clear";
            case FORCE_AUTHORIZED -> "force_policy_requires_authorized_executor";
            default -> "place_policy_mismatch:" + policy;
        };
    }

    private static String clearMutationProblem(ServerLevel world,
                                               BlockPos pos,
                                               ReplacePolicy policy) {
        if (policy != ReplacePolicy.CLEAR_AUTHORIZED) {
            return policy == ReplacePolicy.FORCE_AUTHORIZED
                    ? "force_policy_requires_authorized_executor"
                    : "clear_policy_mismatch:" + policy;
        }
        BlockState current = world.getBlockState(pos);
        if (current.isAir()) {
            return null;
        }
        if (world.getBlockEntity(pos) != null) {
            return "clear_target_has_block_entity";
        }
        if (current.getDestroySpeed(world, pos) < 0.0F) {
            return "clear_target_unbreakable";
        }
        return null;
    }

    /**
     * Rechecks every affected cell immediately around a multi-cell item mutation. This closes the
     * preview-to-execution race for doors: the lower and upper cells must either both already match
     * or both remain safe placement targets, and a late container/protected block aborts the click.
     */
    private String atomicGroupMutationProblem(ServerLevel world,
                                              BlueprintSchema.BlockPlacement placement) {
        String group = placement.atomicGroup();
        if (group == null || group.isBlank()) {
            return null;
        }
        int members = 0;
        int satisfied = 0;
        for (BlueprintSchema.BlockPlacement member : blueprint.placements()) {
            if (!group.equals(member.atomicGroup())) {
                continue;
            }
            members++;
            if (StructureVerifier.matches(world, anchor, member)) {
                satisfied++;
                continue;
            }
            if (member.operation() != CellOperation.PLACE) {
                return "atomic_group_non_place:" + group;
            }
            BlockPos memberPos = anchor.offset(member.dx(), member.dy(), member.dz());
            String memberProblem = placeMutationProblem(world, memberPos, member.replacePolicy());
            if (memberProblem != null) {
                return "atomic_group_unsafe:" + group + ":" + memberProblem;
            }
        }
        if (members > 1 && satisfied > 0 && satisfied < members) {
            return "atomic_group_partial:" + group;
        }
        return null;
    }

    private boolean placementAlreadySatisfied(ServerLevel world,
                                              BlueprintSchema.BlockPlacement placement) {
        if (!StructureVerifier.matches(world, anchor, placement)) {
            return false;
        }
        String group = placement.atomicGroup();
        if (group == null || group.isBlank()) {
            return true;
        }
        for (BlueprintSchema.BlockPlacement member : blueprint.placements()) {
            if (group.equals(member.atomicGroup())
                    && !StructureVerifier.matches(world, anchor, member)) {
                return false;
            }
        }
        return true;
    }

    /**
     * A reviewed plan's graph remains an execution invariant after persistence. Sequence alone is
     * insufficient: a support can be removed after it was built, or an earlier placement can fail.
     * Every declared prerequisite must therefore still exactly match before its dependent mutates
     * the world.
     */
    private String prerequisiteProblem(ServerLevel world,
                                       BlueprintSchema.BlockPlacement placement) {
        if (placement.sequence() == null) {
            return placement.prerequisites().isEmpty()
                    ? null
                    : "legacy_placement_has_prerequisites";
        }
        for (Integer prerequisite : placement.prerequisites()) {
            if (prerequisite == null
                    || prerequisite < 0
                    || prerequisite >= nextIndex
                    || prerequisite >= blueprint.placements().size()) {
                return "invalid_prerequisite_index:" + prerequisite;
            }
            BlueprintSchema.BlockPlacement required = blueprint.placements().get(prerequisite);
            if (!StructureVerifier.matches(world, anchor, required)) {
                return "prerequisite_not_satisfied:" + prerequisite;
            }
        }
        return null;
    }

    /**
     * Confirmation-time terrain evidence can change while the bot gathers materials. The current
     * generated-plan format does not persist a phase field, but every reviewed modular foundation
     * cell is a sequenced PLACE at local y=0. Recheck its external support at execution time and
     * again immediately before useItemOn so a dug-out, flooded or unloaded base cannot become a
     * floating structure through a confirmation-to-build race.
     */
    private String reviewedFoundationSupportProblem(
            ServerLevel world,
            BlockPos pos,
            BlueprintSchema.BlockPlacement placement
    ) {
        if (expectedBlueprintDigest == null
                || placement.sequence() == null
                || placement.operation() != CellOperation.PLACE
                || placement.dy() != 0) {
            return null;
        }
        BlockPos supportPos = pos.below();
        if (supportPos.getY() < world.getMinY()
                || supportPos.getY() >= world.getMinY() + world.getHeight()
                || !world.getWorldBorder().isWithinBounds(supportPos)
                || !world.hasChunkAt(supportPos)) {
            return "foundation_support_not_inspectable";
        }
        BlockState support = world.getBlockState(supportPos);
        if (!support.getFluidState().isEmpty()) {
            return "foundation_support_wet";
        }
        return support.isFaceSturdy(world, supportPos, Direction.UP)
                ? null
                : "foundation_support_not_sturdy";
    }

    private void finishBuildTarget(boolean mutated) {
        if (mutated) {
            placedBlocks++;
        }
        completedPlacementIndices.add(nextIndex);
        buildMiningStarted = false;
        nextIndex++;
        retryTicks = 0;
    }

    private void failUnsafeMutation(AIPlayerEntity bot, String reason, BlockPos pos) {
        bot.getActionPack().stopAll();
        BotLog.action(bot, "build_safety_rejected",
                "index", nextIndex,
                "pos", compact(pos),
                "reason", reason);
        fail(reason + " at " + compact(pos));
    }

    private boolean ensureObservableWorkPose(AIPlayerEntity bot, BlockPos pos, String reason) {
        return ensureObservableWorkPose(bot, pos, reason, null);
    }

    private boolean ensureObservableWorkPose(AIPlayerEntity bot,
                                             BlockPos pos,
                                             String reason,
                                             BlueprintSchema.BlockPlacement placement) {
        double reach = bot.blockInteractionRange();
        if (ObservableWorldQuery.canObserveCell(bot, pos)
                && moveWithinReach(bot, pos, reason, reach * reach, placement)) {
            return true;
        }
        if (bot.getActionPack().isPathExecutorIdle()) {
            BlockPos stand = nearbyStand(bot, pos, placement);
            if (stand != null) {
                ActionResult path = bot.getActionPack().startNonMutatingPathTo(stand);
                if (path.isFailed() && "pathfinding_throttled".equals(path.reason())) {
                    placeDelayTicks = 4;
                }
            }
        }
        return false;
    }

    private void skipBuildTarget(AIPlayerEntity bot, BlockPos pos, String reason) {
        BlueprintSchema.BlockPlacement placement = blueprint.placements().get(nextIndex);
        if (placement.sequence() != null) {
            // A reviewed V2 program is dependency-bearing and fail-closed. Continuing after one
            // cell fails can consume materials and build a child subtree whose support is absent.
            failUnsafeMutation(bot, "reviewed_placement_failed:" + reason, pos);
            return;
        }
        bot.getActionPack().stopAll();
        BotLog.action(bot, "build_block_skipped",
                "index", nextIndex,
                "pos", compact(pos),
                "reason", reason);
        note = "build_skip=" + nextIndex + ":" + reason;
        skippedBlocks++;
        nextIndex++;
        retryTicks = 0;
        buildMiningStarted = false;
    }

    private OptionalInt materialSlot(AIPlayerEntity bot, BlueprintSchema.BlockPlacement placement, Block block) {
        if (placement.palette() != null && !placement.palette().isBlank()) {
            if (!MaterialPalette.isKnown(placement.palette())) {
                fail("unknown_palette: " + placement.palette());
                return OptionalInt.empty();
            }
            OptionalInt slot = MaterialPalette.pickSlot(bot, placement.palette());
            if (slot.isPresent()) {
                return slot;
            }
        }
        Item item = block.asItem();
        if (!(item instanceof BlockItem)) {
            fail("not_placeable_item: " + placement.blockId());
            return OptionalInt.empty();
        }
        return InventoryAction.findItem(bot, item);
    }

    private Block resolveBlock(String blockId) {
        ResourceLocation id;
        try {
            id = ResourceLocation.parse(blockId);
        } catch (RuntimeException exception) {
            fail("invalid_block_id: " + blockId);
            return null;
        }
        return BuiltInRegistries.BLOCK.getOptional(id)
                .orElseGet(() -> {
                    fail("unknown_block_id: " + id);
                    return null;
                });
    }

    private boolean moveWithinReach(AIPlayerEntity bot,
                                    BlockPos pos,
                                    String reason,
                                    double maxDistanceSquared,
                                    BlueprintSchema.BlockPlacement placement) {
        // Never place a blueprint block through the bot's feet or head. Operator mode used to
        // hide this mistake with an emergency teleport; strict survival must first walk to a
        // real adjacent stand position.
        BlockPos feet = bot.blockPosition();
        if (pos.equals(feet) || pos.equals(feet.above())) {
            if (bot.getActionPack().isPathExecutorIdle()) {
                BlockPos stand = nearbyStand(bot, pos, placement);
                if (stand == null || stand.equals(feet)) {
                    fail("no_safe_stand_for_" + reason + ": " + compact(pos));
                    return false;
                }
                ActionResult path = bot.getActionPack().startNonMutatingPathTo(stand);
                if (path.isFailed() && !"pathfinding_throttled".equals(path.reason())) {
                    retryTicks++;
                    if (retryTicks > 12) {
                        fail("path_to_safe_stand_for_" + reason + "_failed: " + path.reason());
                    }
                }
            }
            return false;
        }
        // A path started to gain a safe angle is part of this placement attempt. Let it finish;
        // stopping it merely because the block is already within raw reach leaves the bot on the
        // same occluded side forever.
        if (!bot.getActionPack().isPathExecutorIdle()) {
            return false;
        }
        if (bot.getEyePosition().distanceToSqr(pos.getCenter()) > maxDistanceSquared
                || !hasUsablePlacementRayFrom(bot, feet, pos, placement)) {
            BlockPos stand = nearbyStand(bot, pos, placement);
            if (stand == null) {
                fail("no_stand_position_for_" + reason + ": " + compact(pos));
                return false;
            }
            ActionResult path = bot.getActionPack().startNonMutatingPathTo(stand);
            if (path.isFailed()) {
                if ("pathfinding_throttled".equals(path.reason())) {
                    // 节流退避:寻路是全局速率限。整地逐块大量请求时每 tick 重请会【永远撞限流】→
                    // bot 到不了第一个整地目标、0 place/0 mine → build_timeout(real_build 实测 flatten 0 进度)。
                    // 退避 4 tick 让速率窗口清掉再重试(不计失败预算);onTick 顶部 placeDelayTicks>0 正好跳过。
                    placeDelayTicks = 4;
                } else {
                    // 真实无路(无 stand/障碍)才累计,>12 判死。
                    retryTicks++;
                    if (retryTicks > 12) {
                        fail("path_to_" + reason + "_failed: " + path.reason());
                    }
                }
            }
            return false;
        }
        return true;
    }

    private BlockPos nearbyStand(AIPlayerEntity bot, BlockPos pos) {
        return nearbyStand(bot, pos, null);
    }

    private BlockPos nearbyStand(AIPlayerEntity bot,
                                 BlockPos pos,
                                 BlueprintSchema.BlockPlacement placement) {
        Standability.clearCache();
        BlockPos current = bot.blockPosition();
        Direction expectedFacing = expectedHorizontalFacing(placement);
        BlockPos facingStand = preferredFacingStand(
                bot, pos, current, expectedFacing, placement);
        if (facingStand != null) {
            return facingStand;
        }
        BlockPos exterior = preferredExteriorStand(bot, pos, current, placement);
        if (exterior != null) {
            return exterior;
        }
        BlockPos best = null;
        double bestScore = Double.MAX_VALUE;
        // Search a small shell around the target at practical foot heights. Using only target.y
        // misses the ordinary case where a wall block is one to four blocks above the ground.
        for (int radius = 1; radius <= WORK_STAND_HORIZONTAL_RADIUS; radius++) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != radius) {
                        continue;
                    }
                    for (int y = pos.getY() - WORK_STAND_BELOW_TARGET; y <= pos.getY() + 1; y++) {
                        BlockPos candidate = new BlockPos(pos.getX() + dx, y, pos.getZ() + dz);
                        if (candidate.equals(current)
                                || candidate.distSqr(pos) > 100.0D
                                || !canReachFromStand(bot, candidate, pos)
                                || !isObservableStandable(bot, candidate)
                                || !hasUsablePlacementRayFrom(bot, candidate, pos, placement)) {
                            continue;
                        }
                        double score = candidate.distSqr(current)
                                + candidate.distSqr(pos) * 0.05D;
                        if (score < bestScore) {
                            bestScore = score;
                            best = candidate.immutable();
                        }
                    }
                }
            }
        }
        return best;
    }

    /**
     * Stairs and doors derive their facing from the placing player's horizontal direction. Search
     * the opposite side of the target first so the later real ray/click produces the reviewed
     * BlockState instead of repeatedly approaching the nearest, but semantically wrong, facade.
     */
    private BlockPos preferredFacingStand(AIPlayerEntity bot,
                                          BlockPos target,
                                          BlockPos current,
                                          Direction expectedFacing,
                                          BlueprintSchema.BlockPlacement placement) {
        if (expectedFacing == null || expectedFacing.getAxis().isVertical()) {
            return null;
        }
        Direction standSide = expectedFacing.getOpposite();
        int perpendicularX = -standSide.getStepZ();
        int perpendicularZ = standSide.getStepX();
        BlockPos best = null;
        double bestScore = Double.MAX_VALUE;
        for (int radius = 1; radius <= WORK_STAND_HORIZONTAL_RADIUS; radius++) {
            BlockPos line = target.relative(standSide, radius);
            for (int lateral = -1; lateral <= 1; lateral++) {
                int x = line.getX() + perpendicularX * lateral;
                int z = line.getZ() + perpendicularZ * lateral;
                for (int y = target.getY() - WORK_STAND_BELOW_TARGET; y <= target.getY() + 1; y++) {
                    BlockPos candidate = new BlockPos(x, y, z);
                    if (candidate.equals(current)
                            || candidate.distSqr(target) > 100.0D
                            || !canReachFromStand(bot, candidate, target)
                            || !isObservableStandable(bot, candidate)
                            || !hasUsablePlacementRayFrom(bot, candidate, target, placement)) {
                        continue;
                    }
                    double score = candidate.distSqr(current)
                            + candidate.distSqr(target) * 0.05D
                            + Math.abs(lateral) * 2.0D;
                    if (score < bestScore) {
                        bestScore = score;
                        best = candidate.immutable();
                    }
                }
            }
        }
        return best;
    }

    private static Direction expectedHorizontalFacing(BlueprintSchema.BlockPlacement placement) {
        if (placement == null) {
            return null;
        }
        String facing = placement.properties().get("facing");
        return switch (facing == null ? "" : facing) {
            case "north" -> Direction.NORTH;
            case "south" -> Direction.SOUTH;
            case "west" -> Direction.WEST;
            case "east" -> Direction.EAST;
            default -> null;
        };
    }

    /**
     * Pick a predictable work position outside the known blueprint bounds. This keeps the bot
     * from slowly walling itself into the structure and gives it a clear angle to the nearest
     * facade without inspecting hidden terrain inside the footprint.
     */
    private BlockPos preferredExteriorStand(AIPlayerEntity bot,
                                            BlockPos target,
                                            BlockPos current,
                                            BlueprintSchema.BlockPlacement placement) {
        if (anchor == null) {
            return null;
        }
        int localX = target.getX() - anchor.getX();
        int localZ = target.getZ() - anchor.getZ();
        int left = Math.abs(localX);
        int right = Math.abs(blueprint.width() - 1 - localX);
        int front = Math.abs(localZ);
        int back = Math.abs(blueprint.depth() - 1 - localZ);
        int min = Math.min(Math.min(left, right), Math.min(front, back));

        BlockPos horizontal;
        if (min == left) {
            horizontal = new BlockPos(anchor.getX() - 2, anchor.getY(), target.getZ());
        } else if (min == right) {
            horizontal = new BlockPos(anchor.getX() + blueprint.width() + 1, anchor.getY(), target.getZ());
        } else if (min == front) {
            horizontal = new BlockPos(target.getX(), anchor.getY(), anchor.getZ() - 2);
        } else {
            horizontal = new BlockPos(target.getX(), anchor.getY(), anchor.getZ() + blueprint.depth() + 1);
        }

        for (int delta = 0; delta <= 4; delta++) {
            for (int y : delta == 0
                    ? new int[]{anchor.getY()}
                    : new int[]{anchor.getY() + delta, anchor.getY() - delta}) {
                BlockPos candidate = new BlockPos(horizontal.getX(), y, horizontal.getZ());
                if (!candidate.equals(current)
                        && candidate.distSqr(target) <= 100.0D
                        && canReachFromStand(bot, candidate, target)
                        && isObservableStandable(bot, candidate)
                        && hasUsablePlacementRayFrom(bot, candidate, target, placement)) {
                    return candidate.immutable();
                }
            }
        }
        return null;
    }

    /** Reject attractive but useless ground positions before asking the pathfinder to use them. */
    private static boolean canReachFromStand(AIPlayerEntity bot, BlockPos stand, BlockPos target) {
        Vec3 hypotheticalEye = eyeAtStand(bot, stand);
        double reach = bot.blockInteractionRange();
        return hypotheticalEye.distanceToSqr(target.getCenter()) <= reach * reach;
    }

    /**
     * A nearby cell is useful only if the real placement primitive can see a legal clicked face
     * from it. Distance alone accepts impossible poses such as looking at the inside of a ring
     * beam while the requested eave needs its outside face, or trying to click the top of a tall
     * post while the eye is still below that plane.
     */
    private boolean hasUsablePlacementRayFrom(
            AIPlayerEntity bot,
            BlockPos stand,
            BlockPos target,
            BlueprintSchema.BlockPlacement placement
    ) {
        if (placement == null || placement.operation() != CellOperation.PLACE) {
            return true;
        }
        ServerLevel world = bot.serverLevel();
        Vec3 eye = eyeAtStand(bot, stand);
        double reachSquared = bot.blockInteractionRange() * bot.blockInteractionRange();
        Direction expectedFacing = expectedHorizontalFacing(placement);
        Direction.Axis expectedAxis = expectedPlacementAxis(placement);
        boolean currentPose = stand.equals(bot.blockPosition());

        // Mirror BuildAction's first branch for flowers, grass and snow layers. Its aim point is
        // the outline bounds center rather than a neighboring support face.
        BlockState targetState = ObservableWorldQuery.canObserveCell(bot, target)
                ? world.getBlockState(target)
                : null;
        if (targetState != null && !targetState.isAir()
                && (!targetState.canBeReplaced() || !targetState.getFluidState().isEmpty())) {
            // Exact-state recovery already ran before work-pose planning. Let the later reviewed
            // mutation-policy check diagnose any remaining solid/fluid conflict; it must not be
            // disguised as a navigation failure merely because the occupied target blocks rays.
            return true;
        }
        if (currentPose
                && targetState != null
                && !targetState.isAir()
                && targetState.canBeReplaced()
                && targetState.getFluidState().isEmpty()) {
            var outline = targetState.getShape(world, target, CollisionContext.of(bot));
            if (!outline.isEmpty()) {
                var bounds = outline.bounds();
                Vec3 aim = new Vec3(
                        target.getX() + (bounds.minX + bounds.maxX) * 0.5D,
                        target.getY() + (bounds.minY + bounds.maxY) * 0.5D,
                        target.getZ() + (bounds.minZ + bounds.maxZ) * 0.5D);
                Direction rayFacing = horizontalFacingFromRay(eye, aim);
                if (eye.distanceToSqr(target.getCenter()) <= reachSquared
                        && (expectedFacing == null || rayFacing == expectedFacing)) {
                    BlockHitResult hit = world.clip(new ClipContext(
                            eye, aim, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, bot));
                    if (hit.getType() == HitResult.Type.BLOCK
                            && target.equals(hit.getBlockPos())
                            && (expectedAxis == null
                                    || hit.getDirection().getAxis() == expectedAxis)) {
                        return true;
                    }
                }
            }
        }

        for (Direction face : Direction.values()) {
            BlockPos against = target.relative(face.getOpposite());
            boolean knownSupport = hasCompletedPlanSupport(against)
                    || ObservableWorldQuery.canObserveBlock(bot, against);
            if (!knownSupport
                    || world.getBlockState(against).isAir()
                    || eye.distanceToSqr(against.getCenter()) > reachSquared) {
                continue;
            }
            Vec3 faceCenter = Vec3.atCenterOf(against).add(
                    face.getStepX() * 0.5D,
                    face.getStepY() * 0.5D,
                    face.getStepZ() * 0.5D);
            Direction rayFacing = horizontalFacingFromRay(eye, faceCenter);
            if (expectedFacing != null && rayFacing != expectedFacing) {
                continue;
            }
            if (expectedAxis != null && face.getAxis() != expectedAxis) {
                continue;
            }
            double outwardSide = (eye.x - faceCenter.x) * face.getStepX()
                    + (eye.y - faceCenter.y) * face.getStepY()
                    + (eye.z - faceCenter.z) * face.getStepZ();
            if (outwardSide <= 1.0E-4D) {
                continue;
            }
            if (!currentPose) {
                // Strict survival must not raycast from an eye position the bot has not reached;
                // doing so would turn work-pose selection into a hidden-block scan. The face
                // half-space, reach, reviewed state and known support are sufficient to choose a
                // candidate. The exact OUTLINE ray is repeated from the real eye after arrival.
                return true;
            }
            // End just inside the support so clip returns the face that LookAction targets. If
            // another block occludes it, or the eye is on the wrong side, the hit/face differs.
            Vec3 insideSupport = faceCenter.add(
                    face.getStepX() * -0.01D,
                    face.getStepY() * -0.01D,
                    face.getStepZ() * -0.01D);
            BlockHitResult hit = world.clip(new ClipContext(
                    eye, insideSupport, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, bot));
            if (hit.getType() == HitResult.Type.BLOCK
                    && against.equals(hit.getBlockPos())
                    && hit.getDirection() == face) {
                return true;
            }
        }
        return false;
    }

    private static Direction horizontalFacingFromRay(Vec3 eye, Vec3 aim) {
        double dx = aim.x - eye.x;
        double dz = aim.z - eye.z;
        double yaw = Math.toDegrees(Math.atan2(dz, dx)) - 90.0D;
        return Direction.fromYRot(yaw);
    }

    private static Direction.Axis expectedPlacementAxis(
            BlueprintSchema.BlockPlacement placement
    ) {
        String axis = placement.properties().get("axis");
        return switch (axis == null ? "" : axis) {
            case "x" -> Direction.Axis.X;
            case "y" -> Direction.Axis.Y;
            case "z" -> Direction.Axis.Z;
            default -> null;
        };
    }

    private static Vec3 eyeAtStand(AIPlayerEntity bot, BlockPos stand) {
        if (stand.equals(bot.blockPosition())) {
            return bot.getEyePosition();
        }
        double eyeOffset = bot.getEyePosition().y - bot.getY();
        return new Vec3(
                stand.getX() + 0.5D,
                stand.getY() + eyeOffset,
                stand.getZ() + 0.5D);
    }

    /**
     * Work-pose selection is part of planning, not the pathfinder's local collision adapter. In
     * strict survival it proves the ground, feet, and head cells observable before consulting raw
     * standability. A support that this immutable plan already placed and verified is also known,
     * while operator mode can pass the remaining queries through its explicit hidden-scan ability.
     */
    private boolean isObservableStandable(AIPlayerEntity bot, BlockPos candidate) {
        // A completed blueprint support is not hidden terrain: the bot selected, placed and
        // postcondition-checked it earlier in this same immutable sequence. Remembering that deck
        // lets a non-mutating path cross to the far eave after walls occlude direct sight. The
        // current collision state is still checked, so a later obstruction cannot be walked into.
        boolean rememberedPlanDeck = hasCompletedPlanSupport(candidate.below());
        return (rememberedPlanDeck
                || ObservableWorldQuery.canObserveBlock(bot, candidate.below())
                        && ObservableWorldQuery.canObserveCell(bot, candidate)
                        && ObservableWorldQuery.canObserveCell(bot, candidate.above()))
                && Standability.isStandable(bot.serverLevel(), candidate);
    }

    private boolean hasCompletedPlanSupport(BlockPos worldSupport) {
        if (anchor == null) {
            return false;
        }
        LocalCell local = new LocalCell(
                worldSupport.getX() - anchor.getX(),
                worldSupport.getY() - anchor.getY(),
                worldSupport.getZ() - anchor.getZ());
        Integer index = placementIndexByCell.get(local);
        return index != null
                && completedPlacementIndices.contains(index)
                && blueprint.placements().get(index).operation() == CellOperation.PLACE;
    }

    private static String materialName(BlueprintSchema.BlockPlacement placement) {
        return placement.palette() == null || placement.palette().isBlank() ? placement.blockId() : "palette:" + placement.palette();
    }

    private static String compact(BlockPos pos) {
        return pos.getX() + "," + pos.getY() + "," + pos.getZ();
    }

    public BlockPos anchor() {
        return anchor == null ? null : anchor.immutable();
    }

    public BlueprintSchema blueprint() {
        return blueprint;
    }

    public int placedBlocks() {
        return placedBlocks;
    }

    public int skippedBlocks() {
        return skippedBlocks;
    }

    public void restoreAnchor(BlockPos restoredAnchor) {
        if (restoredAnchor != null && phase == Phase.SITE) {
            this.anchor = restoredAnchor.immutable();
            this.note = "restored_anchor=" + compact(this.anchor);
        }
    }

    private record FlattenTarget(BlockPos pos, FlattenKind kind) {
    }

    private record LocalCell(int x, int y, int z) {
    }
}
