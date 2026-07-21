package io.github.greytaiwolf.fakeaiplayer.pathfinding;

import io.github.greytaiwolf.fakeaiplayer.AIBotConfig;
import io.github.greytaiwolf.fakeaiplayer.action.ActionPack;
import io.github.greytaiwolf.fakeaiplayer.action.ActionResult;
import io.github.greytaiwolf.fakeaiplayer.action.BuildAction;
import io.github.greytaiwolf.fakeaiplayer.action.InventoryAction;
import io.github.greytaiwolf.fakeaiplayer.action.LookAction;
import io.github.greytaiwolf.fakeaiplayer.action.MiningController;
import io.github.greytaiwolf.fakeaiplayer.action.WalkToController;
import io.github.greytaiwolf.fakeaiplayer.entity.AIPlayerEntity;
import io.github.greytaiwolf.fakeaiplayer.log.BotLog;
import io.github.greytaiwolf.fakeaiplayer.log.LogCategory;
import io.github.greytaiwolf.fakeaiplayer.log.LogFields;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

public final class PathExecutor {
    private static final int REPLAN_COOLDOWN_TICKS = 40;
    private static final int ROUTE_REVALIDATE_LOOKAHEAD = 3;
    private static final int MAX_TRANSIENT_EXCLUSIONS = 8;

    private List<Node> path;
    private int index = 1;
    private final BlockPos originalGoal;
    private final boolean exactGoal;
    private final boolean allowPillarOnReplan;
    private final boolean allowDigOnReplan;
    private final TraversalPolicy traversalPolicy;
    private final TraversalBounds bounds;
    private final Set<BlockPos> persistentExclusions;
    private final Predicate<BlockPos> positionConstraint;
    private final Set<BlockPos> transientExclusions = new LinkedHashSet<>();
    private WalkToController subWalker;
    private MiningController subMiner;
    private boolean digWalking;
    private boolean replanTried;
    private final PathProgressTracker progressTracker = new PathProgressTracker();
    private int totalTicks;
    private int replanCount;
    private int lastReplanTick = -REPLAN_COOLDOWN_TICKS;
    private int activeWalkTargetIndex = -1;
    private int nodeRetry;
    private boolean forceSingleStep;
    private boolean advancedThisTick;
    private int noProgressEvents;
    private int oscillationEvents;
    private String lastReplanReason = "";

    public PathExecutor(List<Node> path, BlockPos originalGoal) {
        this(path, originalGoal, false, true, true, TraversalPolicy.TASK_MUTATING_DRY);
    }

    public PathExecutor(List<Node> path, BlockPos originalGoal, boolean exactGoal) {
        this(path, originalGoal, exactGoal, true, true, TraversalPolicy.TASK_MUTATING_DRY);
    }

    public PathExecutor(List<Node> path,
                        BlockPos originalGoal,
                        boolean exactGoal,
                        boolean allowPillarOnReplan,
                        boolean allowDigOnReplan) {
        this(path, originalGoal, exactGoal, allowPillarOnReplan, allowDigOnReplan,
                TraversalPolicy.TASK_WALK_DRY);
    }

    public PathExecutor(List<Node> path,
                        BlockPos originalGoal,
                        boolean exactGoal,
                        boolean allowPillarOnReplan,
                        boolean allowDigOnReplan,
                        TraversalPolicy traversalPolicy) {
        this(path, originalGoal, exactGoal, allowPillarOnReplan, allowDigOnReplan,
                traversalPolicy, TraversalBounds.unbounded());
    }

    public PathExecutor(List<Node> path,
                        BlockPos originalGoal,
                        boolean exactGoal,
                        boolean allowPillarOnReplan,
                        boolean allowDigOnReplan,
                        TraversalPolicy traversalPolicy,
                        TraversalBounds bounds) {
        this(path, originalGoal, exactGoal, allowPillarOnReplan, allowDigOnReplan,
                traversalPolicy, bounds, Set.of(), ignored -> true);
    }

    public PathExecutor(List<Node> path,
                        BlockPos originalGoal,
                        boolean exactGoal,
                        boolean allowPillarOnReplan,
                        boolean allowDigOnReplan,
                        TraversalPolicy traversalPolicy,
                        TraversalBounds bounds,
                        Set<BlockPos> persistentExclusions,
                        Predicate<BlockPos> positionConstraint) {
        this.path = List.copyOf(path);
        this.originalGoal = originalGoal.immutable();
        this.exactGoal = exactGoal;
        this.allowPillarOnReplan = allowPillarOnReplan;
        this.allowDigOnReplan = allowDigOnReplan;
        this.traversalPolicy = traversalPolicy;
        this.bounds = bounds == null ? TraversalBounds.unbounded() : bounds;
        this.persistentExclusions = persistentExclusions == null
                ? Set.of()
                : persistentExclusions.stream()
                .map(BlockPos::immutable)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        this.positionConstraint = positionConstraint == null
                ? ignored -> true : positionConstraint;
    }

    public ActionResult tick(ActionPack pack) {
        totalTicks++;
        advancedThisTick = false;
        BlockPos currentPosition = pack.player().blockPosition();
        if (persistentExclusions.contains(currentPosition)
                || !positionConstraint.test(currentPosition)) {
            cleanup(pack);
            return ActionResult.failed("path_skill_constraint_violated");
        }
        if (!bounds.contains(pack.player().blockPosition())) {
            cleanup(pack);
            return ActionResult.failed("path_bounds_exceeded");
        }
        if (path.isEmpty() || index >= path.size()) {
            cleanup(pack);
            double distSq = pack.player().blockPosition().distSqr(originalGoal);
            boolean arrived = exactGoal
                    ? hasReachedNode(pack.player().blockPosition(), originalGoal)
                    : distSq <= 4.0D;
            if (!arrived) {
                BotLog.warn(LogCategory.PATH, pack.player(), "path_end_not_exact",
                        "dist_sq", distSq, "goal", LogFields.pos(originalGoal));
                return ActionResult.failed("ended_before_goal dist_sq=" + (int) distSq);
            }
            return ActionResult.SUCCESS;
        }

        Node next = path.get(index);
        RouteProblem routeProblem = validateUpcomingRoute(pack);
        if (routeProblem != null) {
            rememberExclusion(routeProblem.node());
            BotLog.warn(LogCategory.PATH, pack.player(), "path_route_invalidated",
                    "at_node", LogFields.pos(routeProblem.node()),
                    "reason", routeProblem.reason(),
                    "policy", traversalPolicy.name());
            return handleStuck(pack, "route_invalidated:" + routeProblem.reason(), true);
        }

        ActionResult result = switch (next.moveType()) {
            case WALK, DIAGONAL, JUMP_UP, DROP_DOWN -> tickWalk(pack, next);
            case DIG_THROUGH -> tickDigThrough(pack, next);
            case PILLAR_UP -> tickPillar(pack, next);
        };
        if (!result.isInProgress()) {
            return result;
        }
        if (advancedThisTick) {
            // advanceTo resets the tracker. Sampling the old local `next` here would seed that
            // fresh tracker with a completed node and make genuine progress to the new node look
            // like a 40-tick stall.
            return ActionResult.IN_PROGRESS;
        }
        Node progressNode = activeWalkTargetIndex >= index && activeWalkTargetIndex < path.size()
                ? path.get(activeWalkTargetIndex)
                : next;
        // Mining has its own monotonic destroy progress and a 600 tick timeout. Counting the
        // stationary mining phase as locomotion stall resets slow block breaking forever.
        if (next.moveType() == MoveType.DIG_THROUGH && !digWalking) {
            return ActionResult.IN_PROGRESS;
        }
        return checkProgress(pack, progressNode);
    }

    public void abort(ActionPack pack) {
        cleanup(pack);
    }

    public int totalTicks() {
        return totalTicks;
    }

    public int replanCount() {
        return replanCount;
    }

    public int pathLength() {
        return path.size();
    }

    public int remainingNodes() {
        return Math.max(0, path.size() - index);
    }

    public BlockPos currentNode() {
        return index >= 0 && index < path.size() ? path.get(index).pos() : originalGoal;
    }

    public double bestNodeDistance() {
        return progressTracker.bestDistance();
    }

    public double pathCost() {
        return path.isEmpty() ? 0.0D : Math.max(0.0D, path.get(path.size() - 1).gCost());
    }

    public double remainingPathCost() {
        if (path.isEmpty() || index >= path.size()) {
            return 0.0D;
        }
        double completedCost = index <= 0 ? 0.0D : path.get(index - 1).gCost();
        return Math.max(0.0D, path.get(path.size() - 1).gCost() - completedCost);
    }

    public int noProgressEvents() {
        return noProgressEvents;
    }

    public int oscillationEvents() {
        return oscillationEvents;
    }

    public String lastReplanReason() {
        return lastReplanReason;
    }

    public TraversalPolicy traversalPolicy() {
        return traversalPolicy;
    }

    public static boolean hasPlaceableBlock(AIPlayerEntity player) {
        return findPlaceableBlock(player) >= 0;
    }

    private ActionResult tickWalk(ActionPack pack, Node next) {
        if (hasArrived(pack.player().blockPosition(), next.pos(), index)) {
            advance();
            return ActionResult.IN_PROGRESS;
        }
        if (subWalker == null) {
            activeWalkTargetIndex = chooseWalkTargetIndex(pack);
            Node target = path.get(activeWalkTargetIndex);
            if (activeWalkTargetIndex > index) {
                BotLog.path(pack.player(), "path_skip",
                        "from_index", index,
                        "to_index", activeWalkTargetIndex,
                        "from", LogFields.pos(next.pos()),
                        "to", LogFields.pos(target.pos()));
            }
            // Every intermediate node is a real route obligation. Only an explicitly non-exact
            // terminal goal retains the legacy near policy used by follow/combat/entity targets.
            BlockPos requiredColumn = exactGoal || activeWalkTargetIndex < path.size() - 1
                    ? target.pos()
                    : null;
            subWalker = new WalkToController(Vec3.atCenterOf(target.pos()), requiredColumn);
        }
        Node target = path.get(activeWalkTargetIndex);
        if (hasArrived(pack.player().blockPosition(), target.pos(), activeWalkTargetIndex)) {
            advanceTo(activeWalkTargetIndex + 1);
            return ActionResult.IN_PROGRESS;
        }
        ActionResult result = subWalker.tick(pack);
        if (result.isSuccess()) {
            advanceTo(activeWalkTargetIndex + 1);
        }
        if (result.isFailed()) {
            return handleWalkFailure(pack, "walk_failed: " + result.reason());
        }
        return ActionResult.IN_PROGRESS;
    }

    private ActionResult tickDigThrough(ActionPack pack, Node next) {
        if (!digWalking) {
            if (subMiner == null) {
                Direction face = faceFromPlayer(pack, next.pos());
                LookAction.lookAtBlock(pack.player(), next.pos(), face);
                subMiner = new MiningController(next.pos(), face);
            }
            ActionResult mine = subMiner.tick(pack);
            if (mine.isFailed()) {
                return handleStuck(pack, "dig_failed: " + mine.reason());
            }
            if (mine.isInProgress()) {
                return ActionResult.IN_PROGRESS;
            }
            // 穿山双格挖:脚位挖完后头位仍有碰撞(实心山体内部每步如此)→ 再挖头位,人才进得去。
            // 配合 NeighborEnumerator.hasHeadroom 的"头位可挖即可"放宽,挖掘寻路从贴地刨坑升级为穿山打洞。
            BlockPos headPos = next.pos().above();
            if (!pack.player().serverLevel().getBlockState(headPos)
                    .getCollisionShape(pack.player().serverLevel(), headPos).isEmpty()) {
                Direction headFace = faceFromPlayer(pack, headPos);
                LookAction.lookAtBlock(pack.player(), headPos, headFace);
                subMiner = new MiningController(headPos, headFace);
                return ActionResult.IN_PROGRESS;
            }
            subMiner = null;
            digWalking = true;
            subWalker = new WalkToController(Vec3.atCenterOf(next.pos()), next.pos());
        }

        ActionResult walk = subWalker.tick(pack);
        if (walk.isSuccess()) {
            advance();
        }
        if (walk.isFailed()) {
            return handleWalkFailure(pack, "dig_walk_failed: " + walk.reason());
        }
        return ActionResult.IN_PROGRESS;
    }

    // NAV-9:垫方块上升一格。看向脚下→起跳→升空瞬间在原脚位放支撑方块→落到其上。
    private ActionResult tickPillar(ActionPack pack, Node next) {
        AIPlayerEntity player = pack.player();
        BlockPos placeSlot = next.pos().below(); // 当前脚位,支撑方块放这里
        if (player.getBlockY() >= next.pos().getY() && player.onGround()) {
            advance();
            return ActionResult.IN_PROGRESS;
        }
        int slot = findPlaceableBlock(player);
        if (slot < 0) {
            return handleStuck(pack, "pillar_no_block");
        }
        InventoryAction.equipFromSlot(player, slot);
        LookAction.lookAtBlock(player, placeSlot, Direction.UP);
        pack.setForward(0.0F);
        pack.setJumping(true);
        pack.jumpOnce();
        double rise = player.getY() - placeSlot.getY();
        if (rise > 0.5D && rise < 1.2D && player.serverLevel().getBlockState(placeSlot).isAir()) {
            BuildAction.placeBlockAt(player, placeSlot);
        }
        return ActionResult.IN_PROGRESS;
    }

    private static int findPlaceableBlock(AIPlayerEntity player) {
        var main = player.getInventory().items;
        for (int i = 0; i < main.size(); i++) {
            ItemStack stack = main.get(i);
            if (stack.isEmpty() || !(stack.getItem() instanceof BlockItem blockItem)) {
                continue;
            }
            var block = blockItem.getBlock();
            if (isPathFillerBlock(block)) {
                return i;
            }
        }
        return -1;
    }

    private static boolean isPathFillerBlock(net.minecraft.world.level.block.Block block) {
        return block == Blocks.COBBLESTONE || block == Blocks.DIRT || block == Blocks.STONE
                || block == Blocks.COBBLED_DEEPSLATE || block == Blocks.DEEPSLATE
                || block == Blocks.NETHERRACK || block == Blocks.ANDESITE
                || block == Blocks.DIORITE || block == Blocks.GRANITE;
    }

    private ActionResult checkProgress(ActionPack pack, Node next) {
        PathProgressTracker.Stall stall = progressTracker.sample(
                pack.player().position(), Vec3.atCenterOf(next.pos()));
        return switch (stall) {
            case NONE -> ActionResult.IN_PROGRESS;
            case NO_GOAL_PROGRESS -> {
                noProgressEvents++;
                yield handleStuck(pack, "no_goal_progress_at:" + compact(next.pos()));
            }
            case OSCILLATION -> {
                oscillationEvents++;
                yield handleStuck(pack, "oscillation_at:" + compact(next.pos()));
            }
        };
    }

    private void advance() {
        advanceTo(index + 1);
    }

    private void advanceTo(int nextIndex) {
        Node next = path.get(index);
        BotLog.path(null, "path_advance", "index", index, "total", path.size(), "move_type", next.moveType(), "pos", LogFields.pos(next.pos()));
        index = Math.max(index + 1, Math.min(nextIndex, path.size()));
        subWalker = null;
        subMiner = null;
        digWalking = false;
        progressTracker.reset();
        activeWalkTargetIndex = -1;
        nodeRetry = 0;
        replanTried = false;
        forceSingleStep = false;
        advancedThisTick = true;
    }

    private int chooseWalkTargetIndex(ActionPack pack) {
        int best = index;
        if (forceSingleStep) {
            return best;
        }
        BlockPos from = pack.player().blockPosition();
        int max = Math.min(path.size() - 1, index + AIBotConfig.get().nav().lookahead());
        for (int candidate = index + 1; candidate <= max; candidate++) {
            if (!canStringPullTo(pack, from, candidate)) {
                break;
            }
            best = candidate;
        }
        return best;
    }

    private boolean canStringPullTo(ActionPack pack, BlockPos from, int candidateIndex) {
        for (int i = index; i <= candidateIndex; i++) {
            MoveType type = path.get(i).moveType();
            if (type != MoveType.WALK && type != MoveType.DIAGONAL && type != MoveType.JUMP_UP) {
                return false;
            }
        }
        BlockPos target = path.get(candidateIndex).pos();
        int dy = target.getY() - from.getY();
        if (dy < -1 || dy > 1) {
            return false;
        }
        return lineClearForStringPull(
                pack.player(), from, target, traversalPolicy,
                persistentExclusions, positionConstraint);
    }

    private static boolean lineClearForStringPull(AIPlayerEntity player,
                                                  BlockPos from,
                                                  BlockPos target,
                                                  TraversalPolicy policy,
                                                  Set<BlockPos> persistentExclusions,
                                                  Predicate<BlockPos> positionConstraint) {
        var world = player.serverLevel();
        AABB swept = playerBox(from).minmax(playerBox(target)).deflate(1.0E-4D);
        if (!world.noCollision(player, swept)) {
            return false;
        }
        int dx = target.getX() - from.getX();
        int dy = target.getY() - from.getY();
        int dz = target.getZ() - from.getZ();
        int samples = Math.max(1, Math.max(Math.abs(dx), Math.abs(dz)) * 2);
        for (int i = 1; i <= samples; i++) {
            double t = (double) i / samples;
            BlockPos sample = BlockPos.containing(
                    from.getX() + 0.5D + dx * t,
                    from.getY() + dy * t,
                    from.getZ() + 0.5D + dz * t);
            if (persistentExclusions.contains(sample)
                    || !positionConstraint.test(sample)
                    || !passableColumn(world, sample, policy)) {
                return false;
            }
            if (!hasSupport(world, sample) && !sample.equals(from)) {
                return false;
            }
        }
        return Standability.isStandable(world, target, policy);
    }

    private static boolean passableColumn(net.minecraft.server.level.ServerLevel world,
                                          BlockPos feet,
                                          TraversalPolicy policy) {
        if (!world.getBlockState(feet).getCollisionShape(world, feet).isEmpty()
                || !world.getBlockState(feet.above()).getCollisionShape(world, feet.above()).isEmpty()) {
            return false;
        }
        return !policy.requiresDryPath()
                || (world.getFluidState(feet).isEmpty()
                && world.getFluidState(feet.above()).isEmpty());
    }

    private static boolean hasSupport(net.minecraft.server.level.ServerLevel world, BlockPos feet) {
        BlockPos below = feet.below();
        return !world.getBlockState(below).getCollisionShape(world, below).isEmpty();
    }

    private RouteProblem validateUpcomingRoute(ActionPack pack) {
        var world = pack.player().serverLevel();
        // Other players and world ticks are not routed through our invalidation hooks.
        Standability.clearCache();
        NeighborEnumerator validator = new NeighborEnumerator(
                allowPillarOnReplan, allowDigOnReplan, traversalPolicy);
        validator.setPathGoal(originalGoal);
        int last = Math.min(path.size() - 1, index + ROUTE_REVALIDATE_LOOKAHEAD - 1);
        for (int candidateIndex = index; candidateIndex <= last; candidateIndex++) {
            Node candidate = path.get(candidateIndex);
            if (!bounds.contains(candidate.pos())) {
                return new RouteProblem(candidate.pos(), "outside_path_bounds");
            }
            if (persistentExclusions.contains(candidate.pos())
                    || !positionConstraint.test(candidate.pos())) {
                return new RouteProblem(candidate.pos(), "skill_constraint_violated");
            }
            String danger = DangerCheck.scan(world, candidate.pos(), traversalPolicy);
            if (danger != null) {
                return new RouteProblem(candidate.pos(), danger);
            }
            Node previous = path.get(candidateIndex - 1);
            boolean transitionValid = validator.getNeighbors(previous.pos(), world).stream()
                    .anyMatch(neighbor -> neighbor.pos().equals(candidate.pos())
                            && neighbor.moveType() == candidate.moveType());
            if (!transitionValid
                    && (candidate.moveType() == MoveType.DIG_THROUGH
                    || candidate.moveType() == MoveType.PILLAR_UP)) {
                // A world-edit step that has already finished naturally becomes a normal dry
                // stand position. Treat that as progress rather than invalidating the route.
                transitionValid = Standability.isStandable(world, candidate.pos(), traversalPolicy);
            }
            if (!transitionValid) {
                return new RouteProblem(candidate.pos(), "transition_blocked");
            }
        }
        return null;
    }

    private void rememberExclusion(BlockPos position) {
        transientExclusions.add(position.immutable());
        while (transientExclusions.size() > MAX_TRANSIENT_EXCLUSIONS) {
            Iterator<BlockPos> iterator = transientExclusions.iterator();
            iterator.next();
            iterator.remove();
        }
    }

    private static AABB playerBox(BlockPos feet) {
        double centerX = feet.getX() + 0.5D;
        double centerZ = feet.getZ() + 0.5D;
        return new AABB(
                centerX - 0.3D, feet.getY(), centerZ - 0.3D,
                centerX + 0.3D, feet.getY() + 1.8D, centerZ + 0.3D);
    }

    private boolean hasArrived(BlockPos current, BlockPos target, int targetIndex) {
        if (!exactGoal && targetIndex == path.size() - 1) {
            return current.distSqr(target) <= 4.0D;
        }
        return hasReachedNode(current, target);
    }

    /**
     * Directional placement only needs the reviewed horizontal work cell. A player standing on a
     * stair or slab can report a feet Y one block below the path node, so vertical equality would
     * reject an otherwise exact and stable placement column.
     */
    static boolean hasReachedNode(BlockPos current, BlockPos target) {
        return current.getX() == target.getX()
                && current.getZ() == target.getZ()
                && Math.abs(current.getY() - target.getY()) <= 1;
    }

    private ActionResult handleWalkFailure(ActionPack pack, String reason) {
        if (reason.contains("stuck_blocked") && nodeRetry < AIBotConfig.get().nav().nodeRetry()) {
            nodeRetry++;
            subWalker = null;
            activeWalkTargetIndex = -1;
            progressTracker.reset();
            forceSingleStep = true;
            pack.stopMovement();
            BotLog.path(pack.player(), "path_node_retry",
                    "reason", reason,
                    "retry", nodeRetry,
                    "index", index,
                    "single_step", true);
            return ActionResult.IN_PROGRESS;
        }
        return handleStuck(pack, reason);
    }

    private ActionResult handleStuck(ActionPack pack, String reason) {
        return handleStuck(pack, reason, false);
    }

    private ActionResult handleStuck(ActionPack pack, String reason, boolean forceFresh) {
        lastReplanReason = reason;
        if (!replanTried) {
            int now = pack.player().getServer().getTickCount();
            if (now - lastReplanTick < REPLAN_COOLDOWN_TICKS) {
                cleanup(pack);
                return ActionResult.failed(reason + "; replan_throttled");
            }
            lastReplanTick = now;
            replanTried = true;
            BotLog.path(pack.player(), "path_stuck", "at_node", reason,
                    "no_progress_ticks", progressTracker.noProgressTicks());
            if (!Standability.isStandable(
                    pack.player().serverLevel(), pack.player().blockPosition(), traversalPolicy)) {
                if (traversalPolicy == TraversalPolicy.AMBIENT_DRY_OPEN
                        || !pack.snapPlayerToNearestStandable(
                        "path_replan_start_invalid", traversalPolicy)
                        || !bounds.contains(pack.player().blockPosition())) {
                    cleanup(pack);
                    return ActionResult.failed(reason + "; replan_failed: NO_START");
                }
            }
            boolean canPillar = allowPillarOnReplan && hasPlaceableBlock(pack.player());
            Set<BlockPos> replanExclusions = new LinkedHashSet<>(persistentExclusions);
            replanExclusions.addAll(transientExclusions);
            AStarPathfinder finder = new AStarPathfinder(
                    pack.player().serverLevel(),
                    pack.player().blockPosition(),
                    originalGoal,
                    10_000,
                    50L,
                    canPillar,
                    allowDigOnReplan,
                    traversalPolicy,
                    1.0D,
                    replanExclusions,
                    bounds,
                    positionConstraint);
            PathfindingResult fresh = finder.findPath(forceFresh);
            if (fresh.success()) {
                replanCount++;
                BotLog.path(pack.player(), "path_replan", "at_node", reason, "new_path_size", fresh.path().size());
                path = fresh.path();
                index = 1;
                subWalker = null;
                subMiner = null;
                digWalking = false;
                progressTracker.reset();
                forceSingleStep = false;
                replanTried = false;
                return ActionResult.IN_PROGRESS;
            }
            reason = reason + "; replan_failed: " + fresh.reason();
        }
        cleanup(pack);
        return ActionResult.failed(reason);
    }

    private void cleanup(ActionPack pack) {
        if (subMiner != null) {
            subMiner.abort(pack.player());
            subMiner = null;
        }
        subWalker = null;
        digWalking = false;
        pack.stopMovement();
    }

    private static Direction faceFromPlayer(ActionPack pack, BlockPos pos) {
        Direction raw = Direction.getApproximateNearest(pack.player().getEyePosition().subtract(pos.getCenter()));
        if (raw == Direction.UP || raw == Direction.DOWN) {
            double dx = pack.player().getX() - (pos.getX() + 0.5D);
            double dz = pack.player().getZ() - (pos.getZ() + 0.5D);
            if (Math.abs(dx) >= Math.abs(dz)) {
                return dx > 0.0D ? Direction.EAST : Direction.WEST;
            }
            return dz > 0.0D ? Direction.SOUTH : Direction.NORTH;
        }
        return raw;
    }

    private static String compact(BlockPos pos) {
        return pos.getX() + "," + pos.getY() + "," + pos.getZ();
    }

    private record RouteProblem(BlockPos node, String reason) {
        private RouteProblem {
            node = node.immutable();
        }
    }
}
