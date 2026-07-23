package io.github.greytaiwolf.fakeaiplayer.action;

import io.github.greytaiwolf.fakeaiplayer.entity.AIPlayerEntity;
import io.github.greytaiwolf.fakeaiplayer.log.BotLog;
import io.github.greytaiwolf.fakeaiplayer.log.LogCategory;
import io.github.greytaiwolf.fakeaiplayer.pathfinding.AStarPathfinder;
import io.github.greytaiwolf.fakeaiplayer.pathfinding.NavigationSnapshot;
import io.github.greytaiwolf.fakeaiplayer.pathfinding.NavigationHandle;
import io.github.greytaiwolf.fakeaiplayer.pathfinding.NavigationPlanner;
import io.github.greytaiwolf.fakeaiplayer.pathfinding.NavigationRequest;
import io.github.greytaiwolf.fakeaiplayer.pathfinding.NavigationState;
import io.github.greytaiwolf.fakeaiplayer.pathfinding.NavGoal;
import io.github.greytaiwolf.fakeaiplayer.pathfinding.FailureReason;
import io.github.greytaiwolf.fakeaiplayer.pathfinding.Node;
import io.github.greytaiwolf.fakeaiplayer.pathfinding.PathExecutor;
import io.github.greytaiwolf.fakeaiplayer.pathfinding.PathfindingResult;
import io.github.greytaiwolf.fakeaiplayer.pathfinding.Standability;
import io.github.greytaiwolf.fakeaiplayer.pathfinding.TraversalPolicy;
import io.github.greytaiwolf.fakeaiplayer.pathfinding.TraversalBounds;
import io.github.greytaiwolf.fakeaiplayer.runtime.PauseOwner;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
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
    // 24k 节点覆盖 ~40 格穿山直达;普通 startPathTo 保持纯步行，破障必须走显式入口)。
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
    private long navigationRequestId;
    private long navigationRequestSequence;
    private NavigationSnapshot navigationSnapshot = NavigationSnapshot.idle();
    private final NavigationHandle.Authority navigationAuthority =
            new NavigationHandle.Authority();
    private NavigationHandle navigationHandle;
    private boolean formalNavigation;
    private int publishedExecutorReplans;
    private int publishedExecutorFailureReplans;
    private int formalReplansUsed;
    private double formalCompletedPathCost;
    private boolean formalSegmentPending;
    private boolean formalPendingDynamicRefresh;
    private Direction formalIncomingHeading;
    private int nextFormalPlanTick;
    private String lastPathRequestKey = "";
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
        cancelPathNavigation(NavigationState.CANCELLED, "replaced_by_direct_walk");
        stopMining();
        this.walkTo = new WalkToController(target);
        return ActionResult.IN_PROGRESS;
    }

    // 统一接近原语入口:挖掘感知寻路(大预算 DIG 直达,目标可为"挖开即站"的实心格——见
    // AStarPathfinder.resolveEndpoint 的挖掘终点豁免)。接近被包裹的矿/穿山直达用这个;
    // 普通走路始终走纯步行路径；需要破障的任务必须显式调用这个入口。
    public ActionResult startDigPathTo(BlockPos goal) {
        int now = player.getServer().getTickCount();
        BlockPos immutableGoal = goal.immutable();
        boolean canPillar = PathExecutor.hasPlaceableBlock(player);
        NavigationRequest legacyRequest = legacyNavigationRequest(
                NavGoal.near(immutableGoal, 2, 2),
                TraversalPolicy.TASK_MUTATING_DRY,
                TraversalBounds.unbounded(), true, canPillar,
                Set.of(), null, "none", "legacy_dig_path");
        String requestKey = legacyPathRequestKey(
                immutableGoal, TraversalPolicy.TASK_MUTATING_DRY,
                true, canPillar, false,
                TraversalBounds.unbounded());
        if (requestKey.equals(lastPathRequestKey) && now < nextPathfindTick) {
            return rejectNavigation(legacyRequest, immutableGoal, "pathfinding_throttled");
        }
        Standability.clearCache();
        if (hasActiveActions()
                && !Standability.isStandable(
                player.serverLevel(), player.blockPosition(), TraversalPolicy.TASK_MUTATING_DRY)) {
            // A replacement request is speculative until its A* succeeds. Never teleport the bot
            // out from under a still-valid path/walk/mining/item controller merely to obtain a
            // planning start.
            return rejectNavigation(
                    legacyRequest, immutableGoal, "replacement_path_start_invalid");
        }
        if (!snapPlayerToNearestStandable("path_start_invalid", TraversalPolicy.TASK_MUTATING_DRY)) {
            lastPathGoal = immutableGoal;
            lastPathRequestKey = requestKey;
            nextPathfindTick = now + PATHFIND_FAILURE_COOLDOWN_TICKS;
            return rejectNavigation(
                    legacyRequest, immutableGoal, "pathfinding_failed: NO_START");
        }
        PathfindingResult result = new AStarPathfinder(
                player.serverLevel(), player.blockPosition(), goal,
                DIG_APPROACH_MAX_NODES, PATHFIND_MAX_MILLIS,
                canPillar, true, TraversalPolicy.TASK_MUTATING_DRY, 10.0D, java.util.Set.of()).findPath();
        if (!result.success()) {
            lastPathGoal = immutableGoal;
            lastPathRequestKey = requestKey;
            nextPathfindTick = now + PATHFIND_FAILURE_COOLDOWN_TICKS;
            return rejectNavigation(
                    legacyRequest, immutableGoal,
                    "pathfinding_failed: " + result.reason());
        }
        lastPathGoal = immutableGoal;
        lastPathRequestKey = requestKey;
        nextPathfindTick = now + PATHFIND_SUCCESS_COOLDOWN_TICKS;
        BlockPos resolvedGoal = result.resolvedGoal() == null ? immutableGoal : result.resolvedGoal();
        PathExecutor executor = new PathExecutor(
                result.path(), resolvedGoal, false, canPillar, true,
                TraversalPolicy.TASK_MUTATING_DRY);
        beginNavigation(resolvedLegacyRequest(legacyRequest, immutableGoal, resolvedGoal));
        this.walkTo = null;
        stopMining();
        activateNavigation(immutableGoal, resolvedGoal, executor);
        return ActionResult.IN_PROGRESS;
    }

    public ActionResult startPathTo(BlockPos goal) {
        return startPathTo(goal, false, false, false, TraversalPolicy.TASK_WALK_DRY);
    }

    /** Explicit world-mutating navigation for reviewed skills that may dig or pillar. */
    public ActionResult startMutatingPathTo(BlockPos goal) {
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

    /**
     * Starts an already-reviewed dry walking path without running A* a second time. Interaction
     * pose selection uses this to preserve the exact route whose cost was compared against other
     * poses. The path is rejected if the bot moved since planning or if it contains a world edit.
     */
    public ActionResult startPlannedNonMutatingPath(BlockPos goal, List<Node> plannedPath) {
        return startPlannedNonMutatingPath(
                goal, plannedPath, Set.of(), ignored -> true);
    }

    /**
     * Starts a reviewed route while retaining the skill's exclusions and world-sensitive feet
     * invariant for route revalidation and any later local replan.
     */
    public ActionResult startPlannedNonMutatingPath(
            BlockPos goal,
            List<Node> plannedPath,
            Set<BlockPos> pathExclusions,
            Predicate<BlockPos> positionConstraint) {
        BlockPos immutableGoal = goal.immutable();
        NavigationRequest intendedRequest = NavigationRequest.walk(
                NavGoal.exact(immutableGoal), "legacy_planned_path");
        if (plannedPath == null || plannedPath.isEmpty()) {
            return rejectNavigation(intendedRequest, immutableGoal, "planned_path_empty");
        }
        List<Node> path = List.copyOf(plannedPath);
        Set<BlockPos> exclusions = pathExclusions == null
                ? Set.of()
                : pathExclusions.stream()
                .map(BlockPos::immutable)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        Predicate<BlockPos> constraint = positionConstraint == null
                ? ignored -> true : positionConstraint;
        intendedRequest = intendedRequest.withConstraint(
                exclusions, constraint, "legacy_planned_constraint");
        BlockPos current = player.blockPosition();
        BlockPos plannedStart = path.get(0).pos();
        if (!sameArrivalColumn(current, plannedStart)) {
            return rejectNavigation(
                    intendedRequest, immutableGoal, "planned_path_stale_start");
        }
        if (exclusions.contains(current)
                || !constraint.test(current)
                || path.stream().anyMatch(node -> exclusions.contains(node.pos())
                || !constraint.test(node.pos()))) {
            return rejectNavigation(
                    intendedRequest, immutableGoal,
                    "planned_path_violates_skill_constraint");
        }
        if (path.stream().anyMatch(node -> node.moveType()
                == io.github.greytaiwolf.fakeaiplayer.pathfinding.MoveType.DIG_THROUGH
                || node.moveType()
                == io.github.greytaiwolf.fakeaiplayer.pathfinding.MoveType.PILLAR_UP)) {
            return rejectNavigation(
                    intendedRequest, immutableGoal, "planned_path_contains_world_edit");
        }
        BlockPos resolvedGoal = path.get(path.size() - 1).pos();
        if (!resolvedGoal.equals(immutableGoal)) {
            return rejectNavigation(
                    intendedRequest, immutableGoal, "planned_path_wrong_goal");
        }
        lastPathGoal = immutableGoal;
        // Planned routes are exempt from the ordinary debounce; do not let a previous request's
        // expiry accidentally throttle a later normal route to this goal.
        nextPathfindTick = player.getServer().getTickCount();
        lastPathRequestKey = "";
        beginNavigation(intendedRequest);
        this.walkTo = null;
        stopMining();
        if (sameArrivalColumn(current, resolvedGoal)) {
            navigationSnapshot = new NavigationSnapshot(
                    navigationRequestId, NavigationState.ARRIVED,
                    current, immutableGoal, resolvedGoal, resolvedGoal,
                    TraversalPolicy.TASK_WALK_DRY,
                    path.size(), 0,
                    Math.max(0.0D, path.get(path.size() - 1).gCost()),
                    0.0D, 0, 0, 0.0D, 0, 0, "", "");
            if (navigationHandle != null) {
                navigationHandle.finish(
                        navigationAuthority,
                        NavigationState.ARRIVED, FailureReason.NONE, "",
                        resolvedGoal,
                        Math.max(0.0D, path.get(path.size() - 1).gCost()),
                        AStarPathfinder.worldVersion());
            }
            activePathGoal = null;
            return ActionResult.SUCCESS;
        }
        PathExecutor executor = new PathExecutor(
                path, resolvedGoal, true, false, false, TraversalPolicy.TASK_WALK_DRY,
                TraversalBounds.unbounded(), exclusions, constraint);
        activateNavigation(immutableGoal, resolvedGoal, executor);
        return ActionResult.IN_PROGRESS;
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
        NavGoal legacyGoal = exactGoal
                ? NavGoal.exact(immutableGoal) : NavGoal.near(immutableGoal, 2, 2);
        NavigationRequest legacyRequest = legacyNavigationRequest(
                legacyGoal, traversalPolicy, bounds, allowDigFallback, canPillar,
                Set.of(), null, "none", "legacy_action_pack");
        String requestKey = legacyPathRequestKey(
                immutableGoal, traversalPolicy, allowDigFallback, canPillar,
                exactGoal, bounds);
        if (requestKey.equals(lastPathRequestKey) && now < nextPathfindTick) {
            return rejectNavigation(legacyRequest, immutableGoal, "pathfinding_throttled");
        }
        Standability.clearCache();
        boolean currentStartStandable = Standability.isStandable(
                player.serverLevel(), player.blockPosition(), traversalPolicy);
        boolean validAmbientStart = traversalPolicy != TraversalPolicy.AMBIENT_DRY_OPEN
                || (bounds.contains(player.blockPosition()) && currentStartStandable);
        if (traversalPolicy != TraversalPolicy.AMBIENT_DRY_OPEN
                && !currentStartStandable
                && hasActiveActions()) {
            // rejectNavigation deliberately retains any active controller. Keeping the position
            // unchanged is equally important: otherwise that controller would resume from a
            // location outside the movement/mining action it reviewed.
            return rejectNavigation(
                    legacyRequest, immutableGoal, "replacement_path_start_invalid");
        }
        if (!validAmbientStart || (traversalPolicy != TraversalPolicy.AMBIENT_DRY_OPEN
                && !currentStartStandable
                && !snapPlayerToNearestStandable("path_start_invalid", traversalPolicy))) {
            lastPathGoal = immutableGoal;
            lastPathRequestKey = requestKey;
            nextPathfindTick = now + PATHFIND_FAILURE_COOLDOWN_TICKS;
            return rejectNavigation(
                    legacyRequest, immutableGoal, "pathfinding_failed: NO_START");
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
            lastPathRequestKey = requestKey;
            nextPathfindTick = now + PATHFIND_FAILURE_COOLDOWN_TICKS;
            return rejectNavigation(
                    legacyRequest, immutableGoal,
                    "pathfinding_failed: " + result.reason());
        }
        lastPathGoal = immutableGoal;
        lastPathRequestKey = requestKey;
        nextPathfindTick = now + PATHFIND_SUCCESS_COOLDOWN_TICKS;
        BlockPos resolvedGoal = result.resolvedGoal() == null ? immutableGoal : result.resolvedGoal();
        PathExecutor executor = new PathExecutor(
                result.path(), resolvedGoal, exactGoal, canPillar, allowDigFallback,
                traversalPolicy, bounds);
        beginNavigation(resolvedLegacyRequest(legacyRequest, immutableGoal, resolvedGoal));
        this.walkTo = null;
        stopMining();
        activateNavigation(immutableGoal, resolvedGoal, executor);
        return ActionResult.IN_PROGRESS;
    }

    public BlockPos activePathGoal() {
        return activePathGoal;
    }

    /** Latest lifecycle state, retained after the executor becomes idle for task recovery/tests. */
    public NavigationSnapshot navigationSnapshot() {
        return navigationSnapshot;
    }

    /**
     * Formal P2 navigation entrypoint. Planning a replacement is transactional: a failed plan
     * returns its own terminal handle without disturbing the currently executing request.
     */
    public NavigationHandle navigate(NavigationRequest request) {
        NavigationHandle candidate = new NavigationHandle(
                allocateNavigationRequestId(), request, player.blockPosition(),
                AStarPathfinder.worldVersion(), navigationAuthority);

        NavigationPlanner.PlanningOutcome outcome = NavigationPlanner.plan(
                player, request, false);
        PathfindingResult result = outcome.result();
        if (!result.success()) {
            NavigationState terminalState = terminalState(result.reason());
            candidate.finish(
                    navigationAuthority,
                    terminalState, result.reason(),
                    "pathfinding_failed: " + result.reason(),
                    null, 0.0D, result.worldVersion(),
                    outcome.unrestrictedEvidenceScope());
            installRejectedHandleIfIdle(candidate);
            return candidate;
        }

        // A successful replacement atomically preempts the old route only after its plan exists.
        cancelPathNavigation(NavigationState.PREEMPTED, "replaced_by_new_navigation");
        navigationHandle = candidate;
        navigationRequestId = candidate.requestId();
        formalNavigation = true;
        formalReplansUsed = 0;
        formalCompletedPathCost = 0.0D;
        formalSegmentPending = false;
        formalPendingDynamicRefresh = false;
        formalIncomingHeading = null;
        this.walkTo = null;
        stopMining();
        stopMovement();
        BlockPos resolvedGoal = result.resolvedGoal();
        if (NavigationPlanner.isSatisfiedAt(player, request, player.blockPosition())) {
            candidate.finish(
                    navigationAuthority,
                    NavigationState.ARRIVED, FailureReason.NONE, "",
                    player.blockPosition(), result.pathCost(), result.worldVersion());
            formalNavigation = false;
            formalIncomingHeading = null;
            formalPendingDynamicRefresh = false;
            activePathGoal = null;
            navigationSnapshot = snapshotWithoutExecutor(
                    candidate, NavigationState.ARRIVED, resolvedGoal,
                    result.path().size(), result.pathCost(), "");
            return candidate;
        }
        PathExecutor executor = new PathExecutor(outcome, request);
        activateNavigation(request.goal().anchor(player.serverLevel()), resolvedGoal, executor, false);
        return candidate;
    }

    /** Latest installed handle. A failed speculative replacement is returned to its caller only. */
    public Optional<NavigationHandle> navigationHandle() {
        return Optional.ofNullable(navigationHandle);
    }

    /** Request-scoped cancellation; a stale handle can never cancel its successor. */
    public boolean cancelNavigation(NavigationHandle handle, String reason) {
        if (handle == null || handle != navigationHandle || handle.terminal()) {
            return false;
        }
        cancelPathNavigation(
                NavigationState.CANCELLED,
                reason == null || reason.isBlank() ? "explicit_cancel" : reason);
        return true;
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
        cancelPathNavigation(NavigationState.CANCELLED, "replaced_by_mining");
        this.walkTo = null;
        stopMovement();
        this.mining = new MiningController(pos, face);
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
        cancelPathNavigation(NavigationState.CANCELLED, "stop_all");
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
                || formalSegmentPending
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
        if (pathExecutor == null && !formalSegmentPending) {
            return false;
        }
        cancelPathNavigation(NavigationState.PREEMPTED, reason);
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
            tickPendingFormalNavigation();
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
            activePathGoal = pathExecutor.resolvedGoal();
            if (formalNavigation && pathExecutor.planningDeferred()) {
                if (navigationHandle != null) {
                    navigationHandle.publishPlanning(
                            navigationAuthority,
                            "local_replan_budget_deferred",
                            pathExecutor.worldVersion());
                }
                navigationSnapshot = snapshotFromExecutor(
                        NavigationState.PLANNING, "local_replan_budget_deferred");
                return;
            }
            if (navigationHandle != null) {
                boolean replan = pathExecutor.replanCount() > publishedExecutorReplans;
                if (replan) {
                    if (formalNavigation) {
                        formalReplansUsed += Math.max(
                                0, pathExecutor.failureReplanCount()
                                - publishedExecutorFailureReplans);
                    }
                    publishedExecutorReplans = pathExecutor.replanCount();
                    publishedExecutorFailureReplans = pathExecutor.failureReplanCount();
                }
                navigationHandle.publishFollowing(
                        navigationAuthority,
                        pathExecutor.resolvedGoal(), logicalPathCost(pathExecutor),
                        pathExecutor.worldVersion(), replan);
            }
            navigationSnapshot = snapshotFromExecutor(NavigationState.FOLLOWING, "");
            return;
        }

        if (result.isSuccess()) {
            if (formalNavigation && navigationHandle != null) {
                NavGoal requestedGoal = navigationHandle.requestedGoal();
                if (!requestedGoal.resolvable(player.serverLevel())) {
                    navigationHandle.finish(
                            navigationAuthority,
                            NavigationState.STALE_WORLD, FailureReason.STALE_WORLD,
                            "navigation_goal_stale", pathExecutor.resolvedGoal(),
                            logicalPathCost(pathExecutor), pathExecutor.worldVersion());
                    navigationSnapshot = snapshotFromExecutor(
                            NavigationState.STALE_WORLD, "navigation_goal_stale");
                } else if (!requestedGoal.accepts(
                        player.serverLevel(), player.blockPosition())) {
                    if (continueFormalNavigation(pathExecutor)) {
                        return;
                    }
                    forward = 0.0F;
                    strafing = 0.0F;
                    jumping = false;
                    player.setJumping(false);
                    return;
                } else {
                    navigationHandle.finish(
                            navigationAuthority,
                            NavigationState.ARRIVED, FailureReason.NONE, "",
                            player.blockPosition(), logicalCompletedPathCost(pathExecutor),
                            pathExecutor.worldVersion());
                    navigationSnapshot = snapshotFromExecutor(NavigationState.ARRIVED, "");
                }
            } else {
                if (navigationHandle != null) {
                    navigationHandle.finish(
                            navigationAuthority,
                            NavigationState.ARRIVED, FailureReason.NONE, "",
                            pathExecutor.resolvedGoal(), logicalPathCost(pathExecutor),
                            pathExecutor.worldVersion());
                }
                navigationSnapshot = snapshotFromExecutor(NavigationState.ARRIVED, "");
            }
            BotLog.path(player, "path_complete", "ticks", pathExecutor.totalTicks());
        } else {
            BotLog.warn(LogCategory.ERROR, player, "path_failed", "reason", result.reason());
            FailureReason failure = pathExecutor.failureReason() == FailureReason.NONE
                    ? FailureReason.PATH_BLOCKED : pathExecutor.failureReason();
            NavigationState terminal = formalNavigation
                    ? terminalState(failure) : NavigationState.FAILED;
            if (navigationHandle != null) {
                navigationHandle.finish(
                        navigationAuthority,
                        terminal, failure, result.reason(),
                        pathExecutor.resolvedGoal(), logicalPathCost(pathExecutor),
                        pathExecutor.worldVersion(),
                        pathExecutor.failureEvidenceUnrestricted());
            }
            navigationSnapshot = snapshotFromExecutor(terminal, result.reason());
        }
        pathExecutor = null;
        activePathGoal = null;
        formalNavigation = false;
        formalSegmentPending = false;
        formalPendingDynamicRefresh = false;
        formalIncomingHeading = null;
        forward = 0.0F;
        strafing = 0.0F;
        jumping = false;
        player.setJumping(false);
    }

    private boolean continueFormalNavigation(PathExecutor completedSegment) {
        NavigationHandle handle = navigationHandle;
        handle.publishPlanning(
                navigationAuthority, "segment_relay_reached", AStarPathfinder.worldVersion());
        if (completedSegment.activateProvenContinuation(player)) {
            formalSegmentPending = false;
            formalPendingDynamicRefresh = false;
            formalIncomingHeading = null;
            activateNavigation(
                    handle.requestedGoal().anchor(player.serverLevel()),
                    completedSegment.resolvedGoal(), completedSegment, true);
            return true;
        }
        formalCompletedPathCost += completedSegment.completedPathCost();
        formalIncomingHeading = completedSegment.currentHeading();
        formalPendingDynamicRefresh = completedSegment.semanticGoalChanged(player);
        pathExecutor = null;
        activePathGoal = null;
        formalSegmentPending = true;
        nextFormalPlanTick = player.getServer().getTickCount();
        navigationSnapshot = snapshotWithoutExecutor(
                handle, NavigationState.PLANNING, completedSegment.resolvedGoal(),
                0, formalCompletedPathCost, "segment_relay_reached");
        planPendingFormalSegment();
        return true;
    }

    private void tickPendingFormalNavigation() {
        if (!formalSegmentPending || navigationHandle == null
                || navigationHandle.terminal()
                || player.getServer().getTickCount() < nextFormalPlanTick) {
            return;
        }
        planPendingFormalSegment();
    }

    private void planPendingFormalSegment() {
        NavigationHandle handle = navigationHandle;
        NavigationRequest request = handle.request();
        if (!request.goal().resolvable(player.serverLevel())) {
            finishPendingFormal(
                    NavigationState.STALE_WORLD, FailureReason.STALE_WORLD,
                    "navigation_goal_stale", AStarPathfinder.worldVersion());
            return;
        }
        if (NavigationPlanner.isSatisfiedAt(player, request, player.blockPosition())) {
            finishPendingFormal(
                    NavigationState.ARRIVED, FailureReason.NONE, "",
                    AStarPathfinder.worldVersion());
            return;
        }
        if (!formalPendingDynamicRefresh
                && formalReplansUsed >= request.maxReplans()) {
            finishPendingFormal(
                    NavigationState.BLOCKED, FailureReason.PATH_BLOCKED,
                    "segment_replan_limit", AStarPathfinder.worldVersion());
            return;
        }
        NavigationPlanner.PlanningOutcome outcome = NavigationPlanner.plan(
                player, request, true, formalIncomingHeading);
        PathfindingResult plan = outcome.result();
        if (!plan.success()) {
            if (plan.reason() == FailureReason.SEARCH_BUDGET) {
                nextFormalPlanTick = player.getServer().getTickCount() + 1;
                handle.publishPlanning(
                        navigationAuthority,
                        "segment_budget_deferred",
                        plan.worldVersion());
                navigationSnapshot = snapshotWithoutExecutor(
                        handle, NavigationState.PLANNING, handle.resolvedGoal(),
                        0, formalCompletedPathCost, "segment_budget_deferred");
                return;
            }
            NavigationState terminal = terminalState(plan.reason());
            finishPendingFormal(
                    terminal, plan.reason(),
                    "segment_replan_failed: " + plan.reason(), plan.worldVersion(),
                    outcome.unrestrictedEvidenceScope());
            return;
        }
        if (plan.path().size() <= 1
                && !request.goal().accepts(player.serverLevel(), player.blockPosition())) {
            finishPendingFormal(
                    NavigationState.BLOCKED, FailureReason.PATH_BLOCKED,
                    "segment_made_no_progress", plan.worldVersion());
            return;
        }
        if (!formalPendingDynamicRefresh) {
            formalReplansUsed++;
        }
        NavigationRequest executionRequest = request.withMaxReplans(
                Math.max(0, request.maxReplans() - formalReplansUsed));
        PathExecutor next = new PathExecutor(outcome, executionRequest);
        formalSegmentPending = false;
        formalPendingDynamicRefresh = false;
        formalIncomingHeading = null;
        activateNavigation(
                request.goal().anchor(player.serverLevel()), plan.resolvedGoal(), next, true);
    }

    private void finishPendingFormal(NavigationState state,
                                     FailureReason failure,
                                     String reason,
                                     long worldVersion) {
        finishPendingFormal(
                state, failure, reason, worldVersion,
                navigationHandle != null
                        && navigationHandle.request().unrestrictedEvidenceScope());
    }

    private void finishPendingFormal(NavigationState state,
                                     FailureReason failure,
                                     String reason,
                                     long worldVersion,
                                     boolean unrestrictedEvidenceScope) {
        NavigationHandle handle = navigationHandle;
        BlockPos endpoint = state == NavigationState.ARRIVED
                ? player.blockPosition() : handle.resolvedGoal();
        handle.finish(
                navigationAuthority, state, failure, reason,
                endpoint, formalCompletedPathCost, worldVersion,
                unrestrictedEvidenceScope);
        navigationSnapshot = snapshotWithoutExecutor(
                handle, state, endpoint, 0,
                formalCompletedPathCost, reason);
        formalSegmentPending = false;
        formalNavigation = false;
        formalPendingDynamicRefresh = false;
        formalIncomingHeading = null;
        activePathGoal = null;
        stopMovement();
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

    private void beginNavigation(NavigationRequest request) {
        cancelPathNavigation(NavigationState.PREEMPTED, "replaced_by_new_navigation");
        navigationRequestId = allocateNavigationRequestId();
        BlockPos requestedGoal = request.goal().anchor(player.serverLevel());
        if (requestedGoal == null) {
            throw new IllegalArgumentException("legacy navigation requires a resolvable anchor");
        }
        navigationHandle = new NavigationHandle(
                navigationRequestId, request, player.blockPosition(),
                AStarPathfinder.worldVersion(), navigationAuthority);
        formalNavigation = false;
        formalSegmentPending = false;
        formalPendingDynamicRefresh = false;
        formalIncomingHeading = null;
        formalCompletedPathCost = 0.0D;
        formalReplansUsed = 0;
        navigationSnapshot = new NavigationSnapshot(
                navigationRequestId,
                NavigationState.PLANNING,
                player.blockPosition(),
                requestedGoal,
                null,
                null,
                request.traversalPolicy(),
                0,
                0,
                0.0D,
                0.0D,
                0,
                0,
                Double.POSITIVE_INFINITY,
                0,
                0,
                "",
                "");
    }

    private static NavigationRequest legacyNavigationRequest(
            NavGoal goal,
            TraversalPolicy policy,
            TraversalBounds bounds,
            boolean allowDig,
            boolean allowPillar,
            Set<BlockPos> exclusions,
            Predicate<BlockPos> constraint,
            String constraintKey,
            String source) {
        NavigationRequest request = switch (policy) {
            case TASK_MUTATING_DRY -> {
                if (!allowDig) {
                    throw new IllegalArgumentException(
                            "legacy mutating policy must declare its dig permission");
                }
                yield NavigationRequest.mutating(goal, allowPillar, source);
            }
            case AMBIENT_DRY_OPEN -> NavigationRequest.ambient(goal, bounds, source);
            case WATER_CAPABLE -> NavigationRequest.water(goal, source).withBounds(bounds);
            case TASK_WALK_DRY -> NavigationRequest.walk(goal, source).withBounds(bounds);
            case ESCAPE_DRY_OPEN -> throw new IllegalArgumentException(
                    "legacy ActionPack has no implicit escape adapter");
        };
        if (policy == TraversalPolicy.TASK_MUTATING_DRY) {
            request = request.withBounds(bounds);
        }
        if ((exclusions != null && !exclusions.isEmpty()) || constraint != null) {
            request = request.withConstraint(
                    exclusions == null ? Set.of() : exclusions,
                    constraint,
                    constraintKey);
        }
        return request;
    }

    /**
     * Legacy A* may conservatively snap an obstructed anchor to a standable interaction cell.
     * Preserve the original target as the diagnostic anchor while making the structured handle's
     * success predicate exactly match the endpoint that the legacy executor will publish.
     */
    private NavigationRequest resolvedLegacyRequest(NavigationRequest request,
                                                    BlockPos requestedGoal,
                                                    BlockPos resolvedGoal) {
        if (request.goal().accepts(player.serverLevel(), resolvedGoal)) {
            return request;
        }
        return request.withGoal(NavGoal.interaction(requestedGoal, Set.of(resolvedGoal)));
    }

    private void activateNavigation(
            BlockPos requestedGoal, BlockPos resolvedGoal, PathExecutor executor) {
        activateNavigation(requestedGoal, resolvedGoal, executor, false);
    }

    private void activateNavigation(
            BlockPos requestedGoal,
            BlockPos resolvedGoal,
            PathExecutor executor,
            boolean routeRevision) {
        this.pathExecutor = executor;
        this.activePathGoal = resolvedGoal.immutable();
        this.publishedExecutorReplans = routeRevision ? executor.replanCount() : 0;
        this.publishedExecutorFailureReplans = routeRevision
                ? executor.failureReplanCount() : 0;
        if (navigationHandle != null) {
            navigationHandle.publishFollowing(
                    navigationAuthority,
                    resolvedGoal, logicalPathCost(executor), executor.worldVersion(), routeRevision);
        }
        navigationSnapshot = new NavigationSnapshot(
                navigationRequestId,
                NavigationState.FOLLOWING,
                player.blockPosition(),
                requestedGoal,
                resolvedGoal,
                executor.currentNode(),
                executor.traversalPolicy(),
                executor.pathLength(),
                executor.remainingNodes(),
                logicalPathCost(executor),
                executor.remainingPathCost(),
                executor.totalTicks(),
                executor.replanCount(),
                executor.bestNodeDistance(),
                executor.noProgressEvents(),
                executor.oscillationEvents(),
                executor.lastReplanReason(),
                "");
    }

    private void installRejectedHandleIfIdle(NavigationHandle rejected) {
        if (pathExecutor != null || walkTo != null || formalSegmentPending) {
            return;
        }
        navigationHandle = rejected;
        navigationRequestId = rejected.requestId();
        formalNavigation = false;
        formalPendingDynamicRefresh = false;
        formalIncomingHeading = null;
        activePathGoal = null;
        NavigationState state = rejected.state();
        navigationSnapshot = snapshotWithoutExecutor(
                rejected, state, rejected.resolvedGoal(), 0, 0.0D, rejected.reason());
    }

    private NavigationSnapshot snapshotWithoutExecutor(NavigationHandle handle,
                                                       NavigationState state,
                                                       BlockPos resolvedGoal,
                                                       int pathLength,
                                                       double pathCost,
                                                       String reason) {
        BlockPos requested = handle.requestedGoal().anchor(player.serverLevel());
        return new NavigationSnapshot(
                handle.requestId(),
                state,
                handle.start(),
                requested,
                resolvedGoal,
                resolvedGoal,
                handle.request().traversalPolicy(),
                Math.max(0, pathLength),
                state == NavigationState.ARRIVED ? 0 : Math.max(0, pathLength),
                Math.max(0.0D, pathCost),
                state == NavigationState.ARRIVED ? 0.0D : Math.max(0.0D, pathCost),
                0,
                handle.routeRevision(),
                state == NavigationState.ARRIVED ? 0.0D : Double.POSITIVE_INFINITY,
                0,
                0,
                "",
                reason);
    }

    private static NavigationState terminalState(FailureReason reason) {
        return switch (reason) {
            case STALE_WORLD -> NavigationState.STALE_WORLD;
            case NO_START, GOAL_UNREACHABLE, GOAL_NOT_STANDABLE, PATH_BLOCKED ->
                    NavigationState.BLOCKED;
            default -> NavigationState.FAILED;
        };
    }

    private ActionResult rejectNavigation(BlockPos requestedGoal, String reason) {
        NavigationRequest unknownScope = NavigationRequest.walk(
                NavGoal.near(requestedGoal, 2, 2), "legacy_rejected_unknown")
                .withConstraint(Set.of(), ignored -> true, "legacy_unknown_scope");
        return rejectNavigation(unknownScope, requestedGoal, reason);
    }

    private ActionResult rejectNavigation(NavigationRequest rejectedRequest,
                                          BlockPos requestedGoal,
                                          String reason) {
        if (pathExecutor != null || formalSegmentPending) {
            // Planning is synchronous: a rejected replacement must leave the previously valid
            // executor and its request snapshot untouched.
            return ActionResult.failed(reason);
        }
        navigationRequestId = allocateNavigationRequestId();
        activePathGoal = null;
        FailureReason failure = legacyFailureReason(reason);
        NavigationState state = terminalState(failure);
        navigationHandle = new NavigationHandle(
                navigationRequestId, rejectedRequest, player.blockPosition(),
                AStarPathfinder.worldVersion(), navigationAuthority);
        navigationHandle.finish(
                navigationAuthority,
                state, failure, reason, null, 0.0D, AStarPathfinder.worldVersion());
        formalNavigation = false;
        formalPendingDynamicRefresh = false;
        formalIncomingHeading = null;
        navigationSnapshot = new NavigationSnapshot(
                navigationRequestId,
                state,
                player.blockPosition(),
                requestedGoal,
                null,
                null,
                null,
                0,
                0,
                0.0D,
                0.0D,
                0,
                0,
                Double.POSITIVE_INFINITY,
                0,
                0,
                "",
                reason);
        return ActionResult.failed(reason);
    }

    private static FailureReason legacyFailureReason(String reason) {
        if (reason == null) {
            return FailureReason.PATH_BLOCKED;
        }
        for (FailureReason candidate : FailureReason.values()) {
            if (candidate != FailureReason.NONE && reason.contains(candidate.name())) {
                return candidate;
            }
        }
        return "pathfinding_throttled".equals(reason)
                ? FailureReason.SEARCH_BUDGET : FailureReason.PATH_BLOCKED;
    }

    private long allocateNavigationRequestId() {
        navigationRequestSequence = Math.max(navigationRequestSequence, navigationRequestId) + 1L;
        return navigationRequestSequence;
    }

    private void cancelPathNavigation(NavigationState state, String reason) {
        if (pathExecutor == null) {
            if (!formalSegmentPending || navigationHandle == null
                    || navigationHandle.terminal()) {
                return;
            }
            navigationHandle.finish(
                    navigationAuthority,
                    state,
                    state == NavigationState.STALE_WORLD
                            ? FailureReason.STALE_WORLD : FailureReason.NONE,
                    reason,
                    navigationHandle.resolvedGoal(),
                    formalCompletedPathCost,
                    AStarPathfinder.worldVersion());
            navigationSnapshot = snapshotWithoutExecutor(
                    navigationHandle, state, navigationHandle.resolvedGoal(),
                    0, formalCompletedPathCost, reason);
            formalSegmentPending = false;
            formalNavigation = false;
            formalPendingDynamicRefresh = false;
            formalIncomingHeading = null;
            activePathGoal = null;
            stopMovement();
            return;
        }
        NavigationSnapshot terminal = snapshotFromExecutor(state, reason);
        if (navigationHandle != null) {
            navigationHandle.finish(
                    navigationAuthority,
                    state,
                    state == NavigationState.STALE_WORLD
                            ? FailureReason.STALE_WORLD : FailureReason.NONE,
                    reason,
                    pathExecutor.resolvedGoal(),
                    logicalPathCost(pathExecutor),
                    pathExecutor.worldVersion());
        }
        pathExecutor.abort(this);
        pathExecutor = null;
        activePathGoal = null;
        formalNavigation = false;
        formalPendingDynamicRefresh = false;
        formalIncomingHeading = null;
        navigationSnapshot = terminal;
    }

    private NavigationSnapshot snapshotFromExecutor(NavigationState state, String reason) {
        boolean formalArrival = formalNavigation && state == NavigationState.ARRIVED;
        BlockPos resolved = formalArrival
                ? player.blockPosition() : pathExecutor.resolvedGoal();
        double totalCost = formalArrival
                ? logicalCompletedPathCost(pathExecutor) : logicalPathCost(pathExecutor);
        return new NavigationSnapshot(
                navigationRequestId,
                state,
                navigationSnapshot.start(),
                navigationSnapshot.requestedGoal(),
                resolved,
                formalArrival ? resolved : pathExecutor.currentNode(),
                pathExecutor.traversalPolicy(),
                pathExecutor.pathLength(),
                formalArrival ? 0 : pathExecutor.remainingNodes(),
                totalCost,
                formalArrival ? 0.0D : pathExecutor.remainingPathCost(),
                pathExecutor.totalTicks(),
                pathExecutor.replanCount(),
                pathExecutor.bestNodeDistance(),
                pathExecutor.noProgressEvents(),
                pathExecutor.oscillationEvents(),
                pathExecutor.lastReplanReason(),
                reason);
    }

    private double logicalPathCost(PathExecutor executor) {
        return Math.max(0.0D,
                (formalNavigation ? formalCompletedPathCost : 0.0D) + executor.pathCost());
    }

    private double logicalCompletedPathCost(PathExecutor executor) {
        return Math.max(0.0D,
                (formalNavigation ? formalCompletedPathCost : 0.0D)
                        + executor.completedPathCost());
    }

    private static boolean sameArrivalColumn(BlockPos first, BlockPos second) {
        return first.getX() == second.getX()
                && first.getZ() == second.getZ()
                && Math.abs(first.getY() - second.getY()) <= 1;
    }

    private static String legacyPathRequestKey(BlockPos goal,
                                               TraversalPolicy policy,
                                               boolean allowDig,
                                               boolean allowPillar,
                                               boolean exact,
                                               TraversalBounds bounds) {
        return goal.asLong() + "|" + policy.name()
                + "|dig=" + allowDig
                + "|pillar=" + allowPillar
                + "|exact=" + exact
                + "|bounds=" + (bounds == null ? TraversalBounds.unbounded() : bounds);
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
