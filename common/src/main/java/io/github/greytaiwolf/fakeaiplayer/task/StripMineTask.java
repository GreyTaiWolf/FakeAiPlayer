package io.github.greytaiwolf.fakeaiplayer.task;

import io.github.greytaiwolf.fakeaiplayer.AIBotConfig;
import io.github.greytaiwolf.fakeaiplayer.action.ActionResult;
import io.github.greytaiwolf.fakeaiplayer.action.BuildAction;
import io.github.greytaiwolf.fakeaiplayer.action.ContainerAction;
import io.github.greytaiwolf.fakeaiplayer.action.InventoryAction;
import io.github.greytaiwolf.fakeaiplayer.action.BlockMiner;
import io.github.greytaiwolf.fakeaiplayer.action.ToolSelector;
import io.github.greytaiwolf.fakeaiplayer.entity.AIPlayerEntity;
import io.github.greytaiwolf.fakeaiplayer.log.BotLog;
import io.github.greytaiwolf.fakeaiplayer.memory.BotMemoryStore;
import io.github.greytaiwolf.fakeaiplayer.mining.OreScan;
import io.github.greytaiwolf.fakeaiplayer.mining.ToolTier;
import io.github.greytaiwolf.fakeaiplayer.pathfinding.Standability;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;

public final class StripMineTask extends AbstractTask {
    private enum Phase {
        PREP,
        TUNNEL,
        MINE_BLOCK,
        SCAN_VEIN,
        MINE_VEIN,
        LIGHT,
        MOVE,
        RETURN,
        RETURN_DEPOSIT,
        RETURN_TO_WORK,
        DONE
    }

    private enum StepKind {
        TUNNEL,
        DESCEND,
        MOVE_ONLY
    }

    private static final int MAX_VEIN_BLOCKS = 64;
    private static final double REACH_SQUARED = 20.25D;

    private final Direction direction;
    private final int length;
    private final int branchSpacing;
    private final BlockPos depotChest;
    private final Set<Block> targetOres;
    private final boolean veinOnly;
    private final boolean autoDescend;
    private final Deque<Step> steps = new ArrayDeque<>();
    private final Deque<BlockPos> blocksToMine = new ArrayDeque<>();
    private final Deque<BlockPos> veinBlocks = new ArrayDeque<>();
    private final Set<BlockPos> queuedVeinBlocks = new HashSet<>();
    private final BlockMiner miner = new BlockMiner();
    private Phase phase = Phase.PREP;
    private Step currentStep;
    private BlockPos origin;
    private BlockPos activeDepotChest;
    private BlockPos returnStand;
    private BlockPos currentMiningBlock;
    private BlockPos currentVeinBlock;
    private boolean miningStarted;
    private boolean returningForFinalStop;
    private int tunnelBlocksMined;
    private int veinBlocksMined;
    private int distanceCompleted;
    private int descentStepsPlanned;
    private String note = "";

    public StripMineTask(Direction direction, int length, int branchSpacing, BlockPos depotChest, Set<Block> targetOres) {
        this(direction, length, branchSpacing, depotChest, targetOres, false);
    }

    public static StripMineTask mineNearbyVein(Set<Block> targetOres) {
        return new StripMineTask(Direction.NORTH, 0, 0, null, targetOres, true);
    }

    public static StripMineTask forOre(Block targetOre, int count) {
        int length = Math.min(128, Math.max(64, count * 16));
        return new StripMineTask(Direction.NORTH, length, 4, null, OreScan.oreFamily(targetOre), false, true);
    }

    private StripMineTask(Direction direction,
                          int length,
                          int branchSpacing,
                          BlockPos depotChest,
                          Set<Block> targetOres,
                          boolean veinOnly) {
        this(direction, length, branchSpacing, depotChest, targetOres, veinOnly,
                !veinOnly && targetOres != null && !targetOres.isEmpty());
    }

    private StripMineTask(Direction direction,
                          int length,
                          int branchSpacing,
                          BlockPos depotChest,
                          Set<Block> targetOres,
                          boolean veinOnly,
                          boolean autoDescend) {
        this.direction = direction.get2DDataValue() == -1 ? Direction.NORTH : direction;
        this.length = Math.max(0, length);
        this.branchSpacing = Math.max(0, branchSpacing);
        this.depotChest = depotChest == null ? null : depotChest.immutable();
        this.targetOres = targetOres == null || targetOres.isEmpty() ? OreScan.COMMON_ORES : OreScan.expandOreFamilies(targetOres);
        this.veinOnly = veinOnly;
        this.autoDescend = autoDescend;
    }

    @Override
    public String name() {
        return veinOnly ? "mine_vein" : "strip_mine";
    }

    @Override
    public String describe() {
        String ores = targetOres.stream()
                .map(BuiltInRegistries.BLOCK::getKey)
                .map(Object::toString)
                .sorted()
                .collect(Collectors.joining(","));
        return name() + " dir=" + direction
                + " distance=" + distanceCompleted + "/" + length
                + " tunnel_blocks=" + tunnelBlocksMined
                + " vein_blocks=" + veinBlocksMined
                + " phase=" + phase
                + (note.isBlank() ? "" : " note=" + note)
                + " ores=" + ores;
    }

    @Override
    public double progress() {
        if (state == TaskState.COMPLETED) {
            return 1.0D;
        }
        if (veinOnly) {
            return veinBlocks.isEmpty() ? 0.0D : Math.min(0.95D, veinBlocksMined / (double) (veinBlocksMined + veinBlocks.size()));
        }
        return length == 0 ? 0.0D : Math.min(0.95D, (double) distanceCompleted / length);
    }

    @Override
    protected void onStart(AIPlayerEntity bot) {
        phase = Phase.PREP;
        origin = bot.blockPosition().immutable();
        activeDepotChest = resolveDepotChest(bot);
        steps.clear();
        blocksToMine.clear();
        veinBlocks.clear();
        queuedVeinBlocks.clear();
        currentStep = null;
        currentMiningBlock = null;
        currentVeinBlock = null;
        miningStarted = false;
        returningForFinalStop = false;
        descentStepsPlanned = 0;
    }

    @Override
    protected void onTick(AIPlayerEntity bot) {
        if (elapsed > Math.max(2400, (descentStepsPlanned + length + branchSpacing * Math.max(1, length / Math.max(1, branchSpacing))) * 400)) {
            fail("strip_mine_timeout");
            return;
        }
        // F2:工具前置闸——完全没镐时禁止空手刷坑道(避免"不做工具直接手挖")。
        // 引导改用 mine_ore(确定性目标会自动备镐再挖),全程自力更生不求助。
        if (ToolTier.bestPickaxeTier(bot) <= ToolTier.NONE) {
            BotLog.action(bot, "strip_mine_tool_gate", "result", "fail", "reason", "no_pickaxe");
            fail("need_pickaxe:use mine_ore to auto-prepare a pickaxe first");
            return;
        }
        switch (phase) {
            case PREP -> prep(bot);
            case TUNNEL -> tunnel(bot);
            case MINE_BLOCK -> mineBlock(bot);
            case SCAN_VEIN -> scanVein(bot);
            case MINE_VEIN -> mineVein(bot);
            case LIGHT -> light(bot);
            case MOVE -> move(bot);
            case RETURN -> returnToDepot(bot);
            case RETURN_DEPOSIT -> deposit(bot);
            case RETURN_TO_WORK -> returnToWork(bot);
            case DONE -> complete();
        }
    }

    private void prep(AIPlayerEntity bot) {
        if (veinOnly) {
            scanNearbyVeins(bot, bot.blockPosition(), 6);
            if (veinBlocks.isEmpty()) {
                fail("no_ore_vein_in_range");
                return;
            }
            phase = Phase.MINE_VEIN;
            return;
        }
        buildPlan(bot);
        phase = Phase.TUNNEL;
    }

    private void buildPlan(AIPlayerEntity bot) {
        steps.clear();
        descentStepsPlanned = 0;
        BlockPos miningOrigin = origin;
        if (shouldDescendToOreLayer(bot)) {
            int targetY = Math.max(bot.serverLevel().getMinY() + 6, OreScan.preferredMiningY(targetOres));
            descentStepsPlanned = Math.max(0, origin.getY() - targetY);
            // FLOW-1:斜楼梯下挖——每级 横移1格 + 下降1格(1:1),形成可回头走的阶梯,
            // 而非竖直直坠。每级 DESCEND 步会挖 stand + 其上方 2 格(身位),其下方为实心地面。
            BlockPos cursor = origin;
            for (int step = 1; step <= descentStepsPlanned; step++) {
                cursor = cursor.relative(direction).below().immutable();
                steps.addLast(new Step(cursor, StepKind.DESCEND, 0));
            }
            miningOrigin = cursor;
            note = "descending_stairs_to_y:" + targetY;
        }
        Direction left = direction.getCounterClockWise();
        Direction right = direction.getClockWise();
        int branchDepth = branchSpacing <= 0 ? 0 : Math.min(branchSpacing, 8);
        for (int distance = 1; distance <= length; distance++) {
            BlockPos main = miningOrigin.relative(direction, distance);
            steps.addLast(new Step(main, StepKind.TUNNEL, distance));
            if (branchDepth > 0 && distance % branchSpacing == 0) {
                addBranch(main, left, branchDepth);
                addBranch(main, right, branchDepth);
            }
        }
    }

    private boolean shouldDescendToOreLayer(AIPlayerEntity bot) {
        if (!autoDescend || veinOnly || targetOres.stream().noneMatch(OreScan::isOreBlock)) {
            return false;
        }
        int targetY = Math.max(bot.serverLevel().getMinY() + 6, OreScan.preferredMiningY(targetOres));
        if (origin.getY() <= targetY + 2) {
            return false;
        }
        return !hasExposedOreNearby(bot, origin, 12, 8);
    }

    private boolean hasExposedOreNearby(AIPlayerEntity bot, BlockPos center, int horizontalRadius, int verticalRadius) {
        BlockPos min = center.offset(-horizontalRadius, -verticalRadius, -horizontalRadius);
        BlockPos max = center.offset(horizontalRadius, verticalRadius, horizontalRadius);
        for (BlockPos pos : BlockPos.betweenClosed(min, max)) {
            if (Math.abs(pos.getY() - center.getY()) > 3) {
                continue;
            }
            if (OreScan.isOre(bot.serverLevel().getBlockState(pos), targetOres)
                    && isExposed(bot.serverLevel(), pos)
                    && io.github.greytaiwolf.fakeaiplayer.mode.ObservableWorldQuery.canObserveBlock(bot, pos)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isExposed(ServerLevel world, BlockPos pos) {
        for (Direction direction : Direction.values()) {
            if (world.getBlockState(pos.relative(direction)).isAir()) {
                return true;
            }
        }
        return false;
    }

    private void addBranch(BlockPos base, Direction side, int depth) {
        for (int branch = 1; branch <= depth; branch++) {
            steps.addLast(new Step(base.relative(side, branch), StepKind.TUNNEL, base.distManhattan(origin)));
        }
        for (int branch = depth - 1; branch >= 0; branch--) {
            steps.addLast(new Step(base.relative(side, branch), StepKind.MOVE_ONLY, base.distManhattan(origin)));
        }
    }

    private void tunnel(AIPlayerEntity bot) {
        if (shouldReturn(bot)) {
            beginReturn(bot);
            return;
        }
        currentStep = steps.pollFirst();
        if (currentStep == null) {
            note = "completed";
            phase = Phase.DONE;
            return;
        }
        distanceCompleted = Math.max(distanceCompleted, Math.min(length, currentStep.distance()));
        if (currentStep.kind() == StepKind.MOVE_ONLY) {
            phase = Phase.MOVE;
            move(bot);
            return;
        }
        if (!safeStandTarget(bot.serverLevel(), currentStep.stand())) {
            fail("unsafe_tunnel_target: " + shortPos(currentStep.stand()));
            return;
        }
        blocksToMine.clear();
        addIfSolid(bot.serverLevel(), currentStep.stand());
        addIfSolid(bot.serverLevel(), currentStep.stand().above());
        if (currentStep.kind() == StepKind.DESCEND) {
            addIfSolid(bot.serverLevel(), currentStep.stand().above(2));
        }
        if (blocksToMine.isEmpty()) {
            phase = Phase.SCAN_VEIN;
        } else {
            phase = Phase.MINE_BLOCK;
        }
    }

    private void mineBlock(AIPlayerEntity bot) {
        if (currentMiningBlock == null) {
            currentMiningBlock = blocksToMine.pollFirst();
            miningStarted = false;
            if (currentMiningBlock == null) {
                phase = Phase.SCAN_VEIN;
                return;
            }
        }
        if (bot.serverLevel().getBlockState(currentMiningBlock).isAir()) {
            Standability.clearCache();
            currentMiningBlock = null;
            tunnelBlocksMined++;
            return;
        }
        if (OreScan.adjacentHazard(bot.serverLevel(), currentMiningBlock)) {
            fail("hazard_near: " + shortPos(currentMiningBlock));
            return;
        }
        if (!canReach(bot, currentMiningBlock)) {
            fail("block_out_of_reach: " + shortPos(currentMiningBlock));
            return;
        }
        // P1-b:挖掘走共享 BlockMiner(只在空闲发起、绝不重发清零进度、正确 face)。
        if (miner.target() == null || !miner.target().equals(currentMiningBlock)) {
            miner.begin(bot, currentMiningBlock);
        }
        if (miner.tick(bot) == BlockMiner.Status.FAILED) {
            fail(miner.failureReason());
        }
    }

    private void scanVein(AIPlayerEntity bot) {
        scanNearbyVeins(bot, currentStep == null ? bot.blockPosition() : currentStep.stand(), 3);
        if (!veinBlocks.isEmpty()) {
            phase = Phase.MINE_VEIN;
            return;
        }
        phase = Phase.LIGHT;
    }

    private void mineVein(AIPlayerEntity bot) {
        if (currentVeinBlock == null) {
            currentVeinBlock = veinBlocks.pollFirst();
            miningStarted = false;
            if (currentVeinBlock == null) {
                if (veinOnly) {
                    complete();
                } else {
                    phase = Phase.LIGHT;
                }
                return;
            }
        }
        if (bot.serverLevel().getBlockState(currentVeinBlock).isAir()) {
            Standability.clearCache();
            currentVeinBlock = null;
            veinBlocksMined++;
            return;
        }
        if (!OreScan.isOre(bot.serverLevel().getBlockState(currentVeinBlock), targetOres)) {
            currentVeinBlock = null;
            return;
        }
        if (OreScan.adjacentHazard(bot.serverLevel(), currentVeinBlock)) {
            note = "skip_hazard_ore:" + shortPos(currentVeinBlock);
            currentVeinBlock = null;
            return;
        }
        if (!canReach(bot, currentVeinBlock)) {
            BlockPos stand = adjacentStand(bot, currentVeinBlock);
            if (stand == null) {
                note = "skip_unreachable_ore:" + shortPos(currentVeinBlock);
                currentVeinBlock = null;
                return;
            }
            if (!near(bot, stand)) {
                if (bot.getActionPack().isPathExecutorIdle()) {
                    ActionResult result = bot.getActionPack().startPathTo(stand);
                    if (result.isFailed()) {
                        note = "skip_unreachable_ore:" + result.reason();
                        currentVeinBlock = null;
                    }
                }
                return;
            }
            bot.getActionPack().stopAll();
        }
        // P1-b:矿脉块挖掘也走 BlockMiner。
        if (miner.target() == null || !miner.target().equals(currentVeinBlock)) {
            miner.begin(bot, currentVeinBlock);
        }
        if (miner.tick(bot) == BlockMiner.Status.FAILED) {
            note = miner.failureReason();
            currentVeinBlock = null;
        }
    }

    private void light(AIPlayerEntity bot) {
        if (!AIBotConfig.get().mining().placeTorches()
                || distanceCompleted == 0
                || distanceCompleted % 8 != 0
                || bot.serverLevel().getBrightness(net.minecraft.world.level.LightLayer.BLOCK, bot.blockPosition()) >= 8) {
            phase = Phase.MOVE;
            return;
        }
        int torchSlot = InventoryAction.findItem(bot, Items.TORCH).orElse(-1);
        if (torchSlot < 0) {
            note = "no_torch";
            phase = Phase.MOVE;
            return;
        }
        InventoryAction.equipFromSlot(bot, torchSlot);
        Optional<BlockPos> torchPos = torchPosition(bot);
        if (torchPos.isPresent()) {
            ActionResult result = BuildAction.placeBlockAt(bot, torchPos.get());
            if (result.isFailed()) {
                note = "torch_failed:" + result.reason();
            }
        }
        phase = Phase.MOVE;
    }

    private void move(AIPlayerEntity bot) {
        if (currentStep == null || near(bot, currentStep.stand())) {
            bot.getActionPack().stopAll();
            phase = Phase.TUNNEL;
            return;
        }
        if ((currentStep.kind() == StepKind.TUNNEL || currentStep.kind() == StepKind.DESCEND)
                && currentStep.stand().distManhattan(bot.blockPosition()) <= 4) {
            if (bot.getActionPack().isWalkToIdle()) {
                bot.getActionPack().startWalkTo(currentStep.stand().getCenter());
            }
            return;
        }
        if (bot.getActionPack().isPathExecutorIdle()) {
            ActionResult result = bot.getActionPack().startPathTo(currentStep.stand());
            if (result.isFailed()) {
                fail("path_to_tunnel_failed: " + result.reason());
            }
        }
    }

    private boolean shouldReturn(AIPlayerEntity bot) {
        AIBotConfig.Mining mining = AIBotConfig.get().mining();
        if (freeMainSlots(bot) < mining.returnWhenFreeSlots()) {
            note = "inventory_near_full";
            return true;
        }
        ItemStack selected = bot.getInventory().getSelected();
        if (selected.isDamageableItem()
                && selected.getMaxDamage() > 0
                && selected.getMaxDamage() - selected.getDamageValue() <= selected.getMaxDamage() * mining.toolDurabilityFloor()) {
            note = "tool_durability_low";
            return true;
        }
        return false;
    }

    private void beginReturn(AIPlayerEntity bot) {
        returnStand = bot.blockPosition().immutable();
        returningForFinalStop = "tool_durability_low".equals(note);
        if (activeDepotChest == null) {
            phase = Phase.DONE;
            return;
        }
        phase = Phase.RETURN;
    }

    private void returnToDepot(AIPlayerEntity bot) {
        BlockPos stand = adjacentStand(bot, activeDepotChest);
        if (stand == null) {
            fail("no_stand_position_for_depot");
            return;
        }
        if (near(bot, stand)) {
            bot.getActionPack().stopAll();
            phase = Phase.RETURN_DEPOSIT;
            return;
        }
        if (bot.getActionPack().isPathExecutorIdle()) {
            ActionResult result = bot.getActionPack().startPathTo(stand);
            if (result.isFailed()) {
                fail("return_path_failed: " + result.reason());
            }
        }
    }

    private void deposit(AIPlayerEntity bot) {
        if (activeDepotChest == null
                || bot.getEyePosition().distanceToSqr(activeDepotChest.getCenter()) > REACH_SQUARED
                || !io.github.greytaiwolf.fakeaiplayer.mode.ObservableWorldQuery.canObserveBlock(bot, activeDepotChest)) {
            phase = Phase.RETURN;
            return;
        }
        Container container = ContainerAction.resolve(bot, activeDepotChest).orElse(null);
        if (container == null) {
            fail("depot_missing");
            return;
        }
        ContainerAction.TransferResult result = ContainerAction.depositOne(container, bot, depositFilter(), 64);
        if (result.movedAny()) {
            return;
        }
        if (returningForFinalStop) {
            phase = Phase.DONE;
            return;
        }
        phase = Phase.RETURN_TO_WORK;
    }

    private void returnToWork(AIPlayerEntity bot) {
        if (returnStand == null || near(bot, returnStand)) {
            bot.getActionPack().stopAll();
            phase = Phase.TUNNEL;
            return;
        }
        if (bot.getActionPack().isPathExecutorIdle()) {
            ActionResult result = bot.getActionPack().startPathTo(returnStand);
            if (result.isFailed()) {
                fail("return_to_work_failed: " + result.reason());
            }
        }
    }

    private Predicate<ItemStack> depositFilter() {
        return stack -> !ContainerAction.isReservedTool(stack)
                && !stack.is(Items.TORCH)
                && !stack.has(DataComponents.FOOD);
    }

    private BlockPos resolveDepotChest(AIPlayerEntity bot) {
        if (depotChest != null) {
            return depotChest;
        }
        return BotMemoryStore.INSTANCE.of(bot.getUUID())
                .placeIn(bot.serverLevel(), "depot", "home", "base", "chest")
                .flatMap(pos -> ContainerAction.resolve(bot, pos).isPresent()
                        ? Optional.of(pos.immutable())
                        : ContainerTask.nearestContainerNear(bot, pos, 4))
                .orElse(null);
    }

    private void addIfSolid(ServerLevel world, BlockPos pos) {
        if (!world.getBlockState(pos).isAir()) {
            blocksToMine.addLast(pos.immutable());
        }
    }

    private void scanNearbyVeins(AIPlayerEntity bot, BlockPos center, int radius) {
        BlockPos.betweenClosedStream(center.offset(-radius, -radius, -radius), center.offset(radius, radius, radius))
                .map(BlockPos::immutable)
                .filter(pos -> io.github.greytaiwolf.fakeaiplayer.mode.ObservableWorldQuery.canObserveBlock(bot, pos))
                .filter(pos -> OreScan.isOre(bot.serverLevel().getBlockState(pos), targetOres))
                .sorted(Comparator.comparingDouble(pos -> pos.distSqr(bot.blockPosition())))
                .findFirst()
                .ifPresent(seed -> OreScan.veinFrom(bot, seed, targetOres, MAX_VEIN_BLOCKS)
                        .stream()
                        .filter(pos -> io.github.greytaiwolf.fakeaiplayer.mode.ObservableWorldQuery.canObserveBlock(bot, pos))
                        .forEach(pos -> {
                            if (queuedVeinBlocks.add(pos)) {
                                veinBlocks.addLast(pos);
                            }
                        }));
    }

    private Optional<BlockPos> torchPosition(AIPlayerEntity bot) {
        BlockPos base = bot.blockPosition();
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            BlockPos pos = base.relative(direction);
            if (bot.serverLevel().getBlockState(pos).isAir()
                    && !bot.serverLevel().getBlockState(pos.below()).isAir()) {
                return Optional.of(pos.immutable());
            }
        }
        return Optional.empty();
    }

    private static boolean safeStandTarget(ServerLevel world, BlockPos stand) {
        if (OreScan.adjacentHazard(world, stand)) {
            return false;
        }
        if (!world.getFluidState(stand).isEmpty() || !world.getFluidState(stand.above()).isEmpty()) {
            return false;
        }
        return !world.getBlockState(stand.below()).isAir()
                && world.getFluidState(stand.below()).isEmpty();
    }

    private static boolean canReach(AIPlayerEntity bot, BlockPos target) {
        return bot.getEyePosition().distanceToSqr(target.getCenter()) <= REACH_SQUARED;
    }

    private static boolean near(AIPlayerEntity bot, BlockPos target) {
        return bot.blockPosition().distSqr(target) <= 1.0D;
    }

    private static BlockPos adjacentStand(AIPlayerEntity bot, BlockPos target) {
        Standability.clearCache();
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            BlockPos candidate = target.relative(direction);
            if (Standability.isStandable(bot.serverLevel(), candidate)) {
                return candidate.immutable();
            }
        }
        return null;
    }

    private static int freeMainSlots(AIPlayerEntity bot) {
        int free = 0;
        for (ItemStack stack : bot.getInventory().items) {
            if (stack.isEmpty()) {
                free++;
            }
        }
        return free;
    }

    private static String shortPos(BlockPos pos) {
        return pos.getX() + " " + pos.getY() + " " + pos.getZ();
    }

    private record Step(BlockPos stand, StepKind kind, int distance) {
    }
}
