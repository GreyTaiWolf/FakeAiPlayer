package io.github.greytaiwolf.fakeaiplayer.action;

import io.github.greytaiwolf.fakeaiplayer.entity.AIPlayerEntity;
import io.github.greytaiwolf.fakeaiplayer.log.BotLog;
import io.github.greytaiwolf.fakeaiplayer.log.LogCategory;
import io.github.greytaiwolf.fakeaiplayer.pathfinding.AStarPathfinder;
import io.github.greytaiwolf.fakeaiplayer.pathfinding.PathExecutor;
import io.github.greytaiwolf.fakeaiplayer.pathfinding.PathfindingResult;
import io.github.greytaiwolf.fakeaiplayer.pathfinding.Standability;
import io.github.greytaiwolf.fakeaiplayer.pathfinding.TraversalPolicy;
import io.github.greytaiwolf.fakeaiplayer.pathfinding.TraversalBounds;
import io.github.greytaiwolf.fakeaiplayer.runtime.PauseOwner;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

public final class ActionPack {
    private static final int PATHFIND_SUCCESS_COOLDOWN_TICKS = 5;
    private static final int PATHFIND_FAILURE_COOLDOWN_TICKS = 20;
    // NAV-OPT 两阶段寻路预算:纯步行只搜空气格(空间小,给足额度);挖穿限额更小,压住被困/地下时的 3D 体积爆搜。
    private static final int WALK_MAX_NODES = 10_000;
    private static final int DIG_MAX_NODES = 4_000;
    private static final int AMBIENT_MAX_NODES = 2_000;
    // 接近原语专用大预算:接近被包裹的矿必然要挖,直接 DIG 且预算放大(挖掘邻居分支因子小,
    // 24k 节点覆盖 ~40 格穿山直达;普通 startPathTo 的小预算 DIG 仅作走路兜底,语义不变)。
    private static final int DIG_APPROACH_MAX_NODES = 24_000;
    private static final long PATHFIND_MAX_MILLIS = 50L;
    private static final long AMBIENT_PATHFIND_MAX_MILLIS = 8L;

    private final AIPlayerEntity player;

    private float forward;
    private float strafing;
    private boolean sneaking;
    private boolean sprinting;
    private boolean jumping;
    private int jumpTicks;

    private WalkToController walkTo;
    private MiningController mining;
    private PathExecutor pathExecutor;
    private int itemUseCooldown;
    private int blockHitDelay;
    private BlockPos lastPathGoal;
    private BlockPos activePathGoal;
    private int nextPathfindTick;
    private final EnumSet<PauseOwner> suspensionOwners = EnumSet.noneOf(PauseOwner.class);
    private final EnumSet<PauseOwner> controllerFreezeOwners = EnumSet.noneOf(PauseOwner.class);

    public ActionPack(AIPlayerEntity player) {
        this.player = player;
    }

    public AIPlayerEntity player() {
        return player;
    }

    public void setForward(float value) {
        this.forward = clampInput(value);
    }

    public void setStrafing(float value) {
        this.strafing = clampInput(value);
    }

    public void setSneaking(boolean sneaking) {
        this.sneaking = sneaking;
        player.setShiftKeyDown(sneaking);
        if (sneaking && sprinting) {
            setSprinting(false);
        }
    }

    public void setSprinting(boolean sprinting) {
        this.sprinting = sprinting;
        player.setSprinting(sprinting);
        if (sprinting && sneaking) {
            setSneaking(false);
        }
    }

    public void setJumping(boolean jumping) {
        this.jumping = jumping;
    }

    public void jumpOnce() {
        this.jumpTicks = 2;
    }

    public ActionResult startWalkTo(Vec3 target) {
        this.walkTo = new WalkToController(target);
        this.mining = null;
        this.pathExecutor = null;
        return ActionResult.IN_PROGRESS;
    }

    // 统一接近原语入口:挖掘感知寻路(大预算 DIG 直达,目标可为"挖开即站"的实心格——见
    // AStarPathfinder.resolveEndpoint 的挖掘终点豁免)。接近被包裹的矿/穿山直达用这个;
    // 普通走路仍用 startPathTo(先 WALK 后小预算 DIG)。
    public ActionResult startDigPathTo(BlockPos goal) {
        int now = player.getServer().getTickCount();
        BlockPos immutableGoal = goal.immutable();
        if (lastPathGoal != null && lastPathGoal.equals(immutableGoal) && now < nextPathfindTick) {
            return ActionResult.failed("pathfinding_throttled");
        }
        if (!snapPlayerToNearestStandable("path_start_invalid", TraversalPolicy.TASK_MUTATING_DRY)) {
            nextPathfindTick = now + PATHFIND_FAILURE_COOLDOWN_TICKS;
            return ActionResult.failed("pathfinding_failed: NO_START");
        }
        boolean canPillar = PathExecutor.hasPlaceableBlock(player);
        PathfindingResult result = new AStarPathfinder(
                player.serverLevel(), player.blockPosition(), goal,
                DIG_APPROACH_MAX_NODES, PATHFIND_MAX_MILLIS,
                canPillar, true, TraversalPolicy.TASK_MUTATING_DRY, 10.0D, java.util.Set.of()).findPath();
        if (!result.success()) {
            lastPathGoal = immutableGoal;
            activePathGoal = null;
            nextPathfindTick = now + PATHFIND_FAILURE_COOLDOWN_TICKS;
            return ActionResult.failed("pathfinding_failed: " + result.reason());
        }
        lastPathGoal = immutableGoal;
        nextPathfindTick = now + PATHFIND_SUCCESS_COOLDOWN_TICKS;
        BlockPos resolvedGoal = result.resolvedGoal() == null ? immutableGoal : result.resolvedGoal();
        activePathGoal = resolvedGoal;
        this.pathExecutor = new PathExecutor(
                result.path(), resolvedGoal, false, canPillar, true,
                TraversalPolicy.TASK_MUTATING_DRY);
        this.walkTo = null;
        this.mining = null;
        return ActionResult.IN_PROGRESS;
    }

    public ActionResult startPathTo(BlockPos goal) {
        return startPathTo(
                goal, PathExecutor.hasPlaceableBlock(player), true, false,
                TraversalPolicy.TASK_MUTATING_DRY);
    }

    /**
     * Navigate only through already walkable cells. Reviewed construction uses this path so a
     * work-pose request cannot silently dig a wall or leave an untracked pillar beside the plan.
     */
    public ActionResult startNonMutatingPathTo(BlockPos goal) {
        return startPathTo(goal, false, false, true);
    }

    /** Ambient movement is walk-only and rejects enclosed terminal cells. */
    public ActionResult startAmbientPathTo(BlockPos goal) {
        return startAmbientPathTo(goal, player.blockPosition());
    }

    public ActionResult startAmbientPathTo(BlockPos goal, BlockPos idleAnchor) {
        return startPathTo(
                goal, false, false, false, TraversalPolicy.AMBIENT_DRY_OPEN,
                TraversalBounds.around(idleAnchor, 10, 3));
    }

    /** Explicit opt-in for tasks that intentionally enter water; it never grants world edits. */
    public ActionResult startWaterCapablePathTo(BlockPos goal) {
        return startPathTo(goal, false, false, false, TraversalPolicy.WATER_CAPABLE);
    }

    private ActionResult startPathTo(BlockPos goal,
                                     boolean canPillar,
                                     boolean allowDigFallback,
                                     boolean exactGoal) {
        return startPathTo(
                goal, canPillar, allowDigFallback, exactGoal, TraversalPolicy.TASK_WALK_DRY);
    }

    private ActionResult startPathTo(BlockPos goal,
                                     boolean canPillar,
                                     boolean allowDigFallback,
                                     boolean exactGoal,
                                     TraversalPolicy traversalPolicy) {
        return startPathTo(
                goal, canPillar, allowDigFallback, exactGoal,
                traversalPolicy, TraversalBounds.unbounded());
    }

    private ActionResult startPathTo(BlockPos goal,
                                     boolean canPillar,
                                     boolean allowDigFallback,
                                     boolean exactGoal,
                                     TraversalPolicy traversalPolicy,
                                     TraversalBounds bounds) {
        int now = player.getServer().getTickCount();
        BlockPos immutableGoal = goal.immutable();
        if (lastPathGoal != null && lastPathGoal.equals(immutableGoal) && now < nextPathfindTick) {
            return ActionResult.failed("pathfinding_throttled");
        }
        Standability.clearCache();
        boolean validAmbientStart = traversalPolicy != TraversalPolicy.AMBIENT_DRY_OPEN
                || (bounds.contains(player.blockPosition())
                && Standability.isStandable(
                player.serverLevel(), player.blockPosition(), traversalPolicy));
        if (!validAmbientStart || (traversalPolicy != TraversalPolicy.AMBIENT_DRY_OPEN
                && !snapPlayerToNearestStandable("path_start_invalid", traversalPolicy))) {
            lastPathGoal = immutableGoal;
            activePathGoal = null;
            nextPathfindTick = now + PATHFIND_FAILURE_COOLDOWN_TICKS;
            return ActionResult.failed("pathfinding_failed: NO_START");
        }
        ServerLevel world = player.serverLevel();
        BlockPos from = player.blockPosition();
        int walkBudget = traversalPolicy == TraversalPolicy.AMBIENT_DRY_OPEN
                ? AMBIENT_MAX_NODES : WALK_MAX_NODES;
        long timeBudget = traversalPolicy == TraversalPolicy.AMBIENT_DRY_OPEN
                ? AMBIENT_PATHFIND_MAX_MILLIS : PATHFIND_MAX_MILLIS;
        // NAV-OPT 两阶段寻路:先纯步行(禁挖,搜索空间=空气格,收敛快、不会被挖穿邻居撑爆到 SEARCH_LIMIT);
        // 纯步行无解再允许挖穿兜底(隧道/破障),挖穿预算更小以限制被困/地下时的 3D 体积爆搜。
        PathfindingResult result = new AStarPathfinder(
                world, from, goal, walkBudget, timeBudget,
                canPillar, false, traversalPolicy, 1.0D, java.util.Set.of(), bounds).findPath();
        if (!result.success() && allowDigFallback) {
            PathfindingResult dig = new AStarPathfinder(
                    world, from, goal, DIG_MAX_NODES, PATHFIND_MAX_MILLIS,
                    canPillar, true, traversalPolicy, 1.0D, java.util.Set.of(), bounds).findPath();
            if (dig.success()) {
                result = dig;
            }
        }
        if (!result.success()) {
            lastPathGoal = immutableGoal;
            activePathGoal = null;
            nextPathfindTick = now + PATHFIND_FAILURE_COOLDOWN_TICKS;
            return ActionResult.failed("pathfinding_failed: " + result.reason());
        }
        lastPathGoal = immutableGoal;
        nextPathfindTick = now + PATHFIND_SUCCESS_COOLDOWN_TICKS;
        BlockPos resolvedGoal = result.resolvedGoal() == null ? immutableGoal : result.resolvedGoal();
        activePathGoal = resolvedGoal;
        this.pathExecutor = new PathExecutor(
                result.path(), resolvedGoal, exactGoal, canPillar, allowDigFallback,
                traversalPolicy, bounds);
        this.walkTo = null;
        this.mining = null;
        return ActionResult.IN_PROGRESS;
    }

    public BlockPos activePathGoal() {
        return activePathGoal;
    }

    public boolean snapPlayerToNearestStandable(String reason) {
        ServerLevel world = player.serverLevel();
        BlockPos current = player.blockPosition();
        Standability.clearCache();
        if (Standability.isStandable(world, current)) {
            return true;
        }
        return snapPlayerToNearestStandable(reason, TraversalPolicy.TASK_WALK_DRY);
    }

    public boolean snapPlayerToNearestStandable(String reason, TraversalPolicy policy) {
        ServerLevel world = player.serverLevel();
        BlockPos current = player.blockPosition();
        Standability.clearCache();
        if (Standability.isStandable(world, current, policy)) {
            return true;
        }
        // A valid current start is ordinary pathfinding and must not require an emergency
        // capability. Only the fallback relocation to a different cell is privileged.
        if (!io.github.greytaiwolf.fakeaiplayer.mode.CapabilityRuntime.decide(
                player, io.github.greytaiwolf.fakeaiplayer.mode.PrivilegedCapability.EMERGENCY_TELEPORT,
                "action_pack_snap:" + reason).allowed()) {
            return false;
        }
        Optional<BlockPos> snapped = Standability.findNearestStandable(world, current, 8, 128, 32, policy);
        if (snapped.isEmpty()) {
            BotLog.warn(LogCategory.PATH, player, "path_start_snap_failed", "reason", reason, "from", io.github.greytaiwolf.fakeaiplayer.log.LogFields.pos(current));
            return false;
        }
        BlockPos safe = snapped.get();
        stopMovement();
        player.teleportTo(world,
                safe.getX() + 0.5D,
                safe.getY(),
                safe.getZ() + 0.5D,
                Collections.emptySet(),
                player.getYRot(),
                player.getXRot(),
                true);
        Standability.clearCache();
        BotLog.path(player, "path_start_snapped",
                "reason", reason,
                "from", io.github.greytaiwolf.fakeaiplayer.log.LogFields.pos(current),
                "to", io.github.greytaiwolf.fakeaiplayer.log.LogFields.pos(safe));
        return true;
    }

    /**
     * 主动把 bot 下沉一格到指定(已为空气的)格子。
     * 关键:bot 是 ServerPlayerEntity,服务端**不跑 travel()**(真实玩家的移动/重力由客户端驱动,
     * fake player 没有客户端),因此**没有被动重力**——挖空脚下不会自动下落。竖井下挖类任务
     *(DigDownTask / OreDigTask.digDownOneLayer)必须靠本方法主动驱动下沉,否则会站着空转直到看门狗失败
     *(实测:dig_down 全程 y 恒定、200t no_progress 卡死的共享根因)。
     * 幂等:bot 已在该层或更低则不动。teleport 会清零 fallDistance,不会摔伤。
     */
    public void descendInto(BlockPos target) {
        if (player.blockPosition().getY() <= target.getY()) {
            return;
        }
        io.github.greytaiwolf.fakeaiplayer.mode.FakePlayerMotion.stepTo(player, target, "descend_into");
    }

    public ActionResult startMining(BlockPos pos, Direction face) {
        this.mining = new MiningController(pos, face);
        this.pathExecutor = null;
        this.forward = 0.0F;
        this.strafing = 0.0F;
        return ActionResult.IN_PROGRESS;
    }

    public void stopMining() {
        if (this.mining != null) {
            this.mining.abort(player);
            this.mining = null;
        }
    }

    public void stopMovement() {
        setSneaking(false);
        setSprinting(false);
        this.forward = 0.0F;
        this.strafing = 0.0F;
        this.jumping = false;
        this.jumpTicks = 0;
        player.setJumping(false);
    }

    public void stopAll() {
        if (pathExecutor != null) {
            pathExecutor.abort(this);
            pathExecutor = null;
        }
        activePathGoal = null;
        stopMining();
        this.walkTo = null;
        stopMovement();
        player.releaseUsingItem();
    }

    /**
     * Freezes controller progression without discarding path, walk or mining executors. Multiple
     * independent owners may suspend the pack; execution resumes only after every owner releases.
     */
    public boolean suspend(PauseOwner owner) {
        boolean changed = suspensionOwners.add(owner);
        clearLiveInput();
        if (changed) {
            BotLog.action(player, "action_pack_suspended", "owner", owner,
                    "owners", suspensionOwners);
        }
        return changed;
    }

    public boolean resume(PauseOwner owner) {
        boolean changed = suspensionOwners.remove(owner);
        if (changed) {
            BotLog.action(player, "action_pack_resumed", "owner", owner,
                    "owners", suspensionOwners);
        }
        return changed;
    }

    public boolean isSuspended() {
        return !suspensionOwners.isEmpty();
    }

    public boolean isSuspendedBy(PauseOwner owner) {
        return suspensionOwners.contains(owner);
    }

    public void clearSuspensions() {
        suspensionOwners.clear();
        controllerFreezeOwners.clear();
        clearLiveInput();
    }

    /**
     * Freezes existing executors while still applying live movement inputs supplied by NavSafety.
     * This differs from inventory suspension, which intentionally clears every input.
     */
    public boolean freezeControllers(PauseOwner owner) {
        boolean changed = controllerFreezeOwners.add(owner);
        if (changed) {
            BotLog.action(player, "action_controllers_frozen", "owner", owner);
        }
        return changed;
    }

    public boolean resumeControllers(PauseOwner owner) {
        boolean changed = controllerFreezeOwners.remove(owner);
        if (changed) {
            BotLog.action(player, "action_controllers_resumed", "owner", owner);
        }
        return changed;
    }

    public boolean hasActiveActions() {
        return pathExecutor != null
                || walkTo != null
                || mining != null
                || forward != 0.0F
                || strafing != 0.0F
                || sneaking
                || sprinting
                || jumping
                || jumpTicks > 0
                || player.isUsingItem();
    }

    public boolean isPathExecutorIdle() {
        return pathExecutor == null;
    }

    public boolean hasWaterCapablePath() {
        return pathExecutor != null && pathExecutor.traversalPolicy().allowsWater();
    }

    /** Cancels only navigation, leaving the owning task intact so it can recover after safety. */
    public boolean cancelActivePathForSafety(String reason) {
        if (pathExecutor == null) {
            return false;
        }
        pathExecutor.abort(this);
        pathExecutor = null;
        activePathGoal = null;
        BotLog.path(player, "path_cancelled_for_safety", "reason", reason);
        return true;
    }

    public boolean isWalkToIdle() {
        return walkTo == null;
    }

    public boolean isMiningIdle() {
        return mining == null;
    }

    public void onUpdate() {
        if (isSuspended()) {
            // Do not tick executors or cooldowns while a menu/session owns the bot. Clearing only
            // live inputs keeps the executor objects intact for a validated next-tick continuation.
            clearLiveInput();
            return;
        }
        if (controllerFreezeOwners.isEmpty()) {
            tickPathExecutor();
            tickWalkTo();
            tickMining();
        }

        if (itemUseCooldown > 0) {
            itemUseCooldown--;
        }
        if (blockHitDelay > 0) {
            blockHitDelay--;
        }

        float velocity = sneaking ? 0.3F : 1.0F;
        player.zza = forward * velocity;
        player.xxa = strafing * velocity;
        boolean jumpNow = jumping || jumpTicks > 0;
        player.setJumping(jumpNow);
        if (jumpTicks > 0) {
            jumpTicks--;
        }
    }

    public int itemUseCooldown() {
        return itemUseCooldown;
    }

    public void setItemUseCooldown(int itemUseCooldown) {
        this.itemUseCooldown = Math.max(0, itemUseCooldown);
    }

    public int blockHitDelay() {
        return blockHitDelay;
    }

    public void setBlockHitDelay(int blockHitDelay) {
        this.blockHitDelay = Math.max(0, blockHitDelay);
    }

    private void tickWalkTo() {
        if (walkTo == null) {
            return;
        }

        ActionResult result = walkTo.tick(this);
        if (result.isInProgress()) {
            return;
        }

        if (result.isSuccess()) {
            BotLog.action(player, "walk_complete");
        } else {
            BotLog.warn(LogCategory.ERROR, player, "walk_failed", "reason", result.reason());
        }
        walkTo = null;
        forward = 0.0F;
        strafing = 0.0F;
        jumping = false;
        player.setJumping(false);
    }

    private void tickPathExecutor() {
        if (pathExecutor == null) {
            return;
        }

        ActionResult result = pathExecutor.tick(this);
        if (result.isInProgress()) {
            return;
        }

        if (result.isSuccess()) {
            BotLog.path(player, "path_complete", "ticks", pathExecutor.totalTicks());
        } else {
            BotLog.warn(LogCategory.ERROR, player, "path_failed", "reason", result.reason());
        }
        pathExecutor = null;
        activePathGoal = null;
        forward = 0.0F;
        strafing = 0.0F;
        jumping = false;
        player.setJumping(false);
    }

    private void tickMining() {
        if (mining == null) {
            return;
        }

        ActionResult result = mining.tick(this);
        if (result.isInProgress()) {
            return;
        }

        if (result.isSuccess()) {
            BotLog.action(player, "mine_complete");
        } else {
            BotLog.warn(LogCategory.ERROR, player, "mine_failed", "reason", result.reason());
        }
        mining = null;
    }

    private static float clampInput(float value) {
        return Math.max(-1.0F, Math.min(1.0F, value));
    }

    private void clearLiveInput() {
        if (sneaking) {
            player.setShiftKeyDown(false);
        }
        if (sprinting) {
            player.setSprinting(false);
        }
        sneaking = false;
        sprinting = false;
        forward = 0.0F;
        strafing = 0.0F;
        jumping = false;
        jumpTicks = 0;
        player.zza = 0.0F;
        player.xxa = 0.0F;
        player.setJumping(false);
    }
}
