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
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

public final class PathExecutor {
    private static final int REPLAN_COOLDOWN_TICKS = 40;
    private static final int ROUTE_REVALIDATE_LOOKAHEAD = 3;
    private static final int MAX_TRANSIENT_EXCLUSIONS = 8;
    // Must match MoveTask's 1.5-block completion radius; a wider executor success previously made
    // MoveTask treat a successful walk as unfinished and incorrectly enter destructive dig fallback.
    private static final double NEAR_ARRIVAL_DISTANCE_SQ = 2.25D;

    private List<Node> path;
    private int index = 1;
    private BlockPos resolvedGoal;
    private NavGoal replanGoal;
    private final NavigationRequest navigationRequest;
    private long plannedWorldVersion;
    private String plannedGoalFingerprint;
    private String plannedSemanticGoalFingerprint;
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
    private int failureReplanCount;
    private int lastReplanTick = -REPLAN_COOLDOWN_TICKS;
    private int activeWalkTargetIndex = -1;
    private int nodeRetry;
    private boolean forceSingleStep;
    private boolean advancedThisTick;
    private int noProgressEvents;
    private int oscillationEvents;
    private String lastReplanReason = "";
    private FailureReason failureReason = FailureReason.NONE;
    private double sunkPathCost;
    private int unsafeAcceptedTicks;
    private boolean failureEvidenceUnrestricted;
    private boolean planningDeferred;
    private ProvenRoute provenContinuation;

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
        this.resolvedGoal = originalGoal.immutable();
        this.replanGoal = NavGoal.exact(originalGoal);
        this.navigationRequest = null;
        this.plannedWorldVersion = AStarPathfinder.worldVersion();
        this.plannedGoalFingerprint = replanGoal.identityKey();
        this.plannedSemanticGoalFingerprint = replanGoal.identityKey();
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
        this.failureEvidenceUnrestricted = false;
    }

    /** Formal P2 constructor retaining the semantic goal, permission snapshot, and world version. */
    public PathExecutor(PathfindingResult plan,
                        NavGoal plannedGoal,
                        NavigationRequest request) {
        if (plan == null || !plan.success() || plan.path().isEmpty()
                || plan.resolvedGoal() == null || plannedGoal == null || request == null) {
            throw new IllegalArgumentException("formal path executor requires a successful plan");
        }
        this.path = List.copyOf(plan.path());
        this.resolvedGoal = plan.resolvedGoal().immutable();
        this.replanGoal = plannedGoal;
        this.navigationRequest = request;
        this.plannedWorldVersion = plan.worldVersion();
        this.plannedGoalFingerprint = plan.goalFingerprint();
        this.plannedSemanticGoalFingerprint = plan.goalFingerprint();
        this.exactGoal = true;
        this.allowPillarOnReplan = request.allowPillar();
        this.allowDigOnReplan = request.allowDig();
        this.traversalPolicy = request.traversalPolicy();
        this.bounds = request.bounds();
        this.persistentExclusions = request.excludedPositions();
        this.positionConstraint = request.positionConstraint();
        this.failureEvidenceUnrestricted = request.unrestrictedEvidenceScope();
    }

    /** Formal constructor retaining the already-proven suffix for budget-free execution relays. */
    public PathExecutor(NavigationPlanner.PlanningOutcome outcome,
                        NavigationRequest request) {
        this(outcome.result(), outcome.plannedGoal(), request);
        this.provenContinuation = outcome.continuation();
        this.failureEvidenceUnrestricted = outcome.unrestrictedEvidenceScope();
    }

    public ActionResult tick(ActionPack pack) {
        totalTicks++;
        advancedThisTick = false;
        planningDeferred = false;
        BlockPos currentPosition = pack.player().blockPosition();
        if (navigationRequest != null
                && !navigationRequest.goal().resolvable(pack.player().serverLevel())) {
            cleanup(pack);
            failureReason = FailureReason.STALE_WORLD;
            return ActionResult.failed("navigation_goal_stale");
        }
        if (persistentExclusions.contains(currentPosition)
                || !positionConstraint.test(currentPosition)) {
            cleanup(pack);
            failureReason = FailureReason.STALE_WORLD;
            return ActionResult.failed("path_skill_constraint_violated");
        }
        if (!bounds.contains(currentPosition)) {
            cleanup(pack);
            failureReason = FailureReason.PATH_BLOCKED;
            return ActionResult.failed("path_bounds_exceeded");
        }
        // Movement/string-pulling can cross a discrete node between ActionPack ticks. Commit that
        // fact before any semantic-goal change or route invalidation can trigger a fresh search, so
        // sunk cost and the incoming heading describe the position the bot actually reached.
        commitReachedPathProgress(currentPosition);
        if (navigationRequest != null) {
            boolean semanticallyAccepted = navigationRequest.goal().accepts(
                    pack.player().serverLevel(), currentPosition);
            if (NavigationPlanner.isSatisfiedAt(
                    pack.player(), navigationRequest, currentPosition)) {
                unsafeAcceptedTicks = 0;
                cleanup(pack);
                return ActionResult.SUCCESS;
            }
            if (semanticallyAccepted && !Standability.isStandableFresh(
                    pack.player().serverLevel(), currentPosition, traversalPolicy)) {
                // A jump can pass through an accepted follow/flee ring while airborne. Wait for
                // the movement controller/physics to establish a legal feet cell; never publish
                // ARRIVED from the unsupported intermediate block position.
                boolean transientAirborne = !pack.player().onGround()
                        && pack.player().serverLevel().getFluidState(currentPosition).isEmpty()
                        && pack.player().serverLevel().getFluidState(
                        currentPosition.above()).isEmpty();
                if (transientAirborne && ++unsafeAcceptedTicks <= 20) {
                    return ActionResult.IN_PROGRESS;
                }
                unsafeAcceptedTicks = 0;
                return handleStuck(pack, "accepted_goal_not_standable", true);
            }
            unsafeAcceptedTicks = 0;
            if (semanticallyAccepted && traversalPolicy.requiresOpenGoal()) {
                return handleStuck(pack, "accepted_goal_not_open", true);
            }
            String currentFingerprint = navigationRequest.goal().fingerprint(
                    pack.player().serverLevel());
            if (!currentFingerprint.equals(plannedSemanticGoalFingerprint)) {
                int now = pack.player().getServer().getTickCount();
                boolean cadenceDue = !replanGoal.dynamic()
                        || now - lastReplanTick >= 10;
                if (cadenceDue) {
                    return handleStuck(pack, "dynamic_goal_changed", true);
                }
                // A tracked entity can cross a block boundary every tick. Keep following the last
                // validated route between the 10-tick replanning cadence instead of stopping the
                // bot for nine ticks out of ten; the live goal is still rechecked before ARRIVED.
            }
        }
        if (path.isEmpty() || index >= path.size()) {
            cleanup(pack);
            double distSq = pack.player().blockPosition().distSqr(resolvedGoal);
            boolean arrived = exactGoal
                    ? hasReachedNode(pack.player().blockPosition(), resolvedGoal)
                    : distSq <= NEAR_ARRIVAL_DISTANCE_SQ;
            if (!arrived) {
                BotLog.warn(LogCategory.PATH, pack.player(), "path_end_not_exact",
                        "dist_sq", distSq, "goal", LogFields.pos(resolvedGoal));
                failureReason = FailureReason.PATH_BLOCKED;
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

    /** Recovery replans only; expected dynamic-target refreshes do not consume this budget. */
    public int failureReplanCount() {
        return failureReplanCount;
    }

    public int pathLength() {
        return path.size();
    }

    public int remainingNodes() {
        return Math.max(0, path.size() - index);
    }

    public BlockPos currentNode() {
        return index >= 0 && index < path.size() ? path.get(index).pos() : resolvedGoal;
    }

    public double bestNodeDistance() {
        return progressTracker.bestDistance();
    }

    public double pathCost() {
        double currentPlan = path.isEmpty()
                ? 0.0D : Math.max(0.0D, path.get(path.size() - 1).gCost());
        return Math.max(0.0D, sunkPathCost + currentPlan);
    }

    public double remainingPathCost() {
        if (path.isEmpty() || index >= path.size()) {
            return 0.0D;
        }
        double completedCost = index <= 0 ? 0.0D : path.get(index - 1).gCost();
        return Math.max(0.0D, path.get(path.size() - 1).gCost() - completedCost);
    }

    /** Cost of route edges already consumed, including sunk edges before any successful replan. */
    public double completedPathCost() {
        return Math.max(0.0D, pathCost() - remainingPathCost());
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

    public BlockPos resolvedGoal() {
        return resolvedGoal;
    }

    public long worldVersion() {
        return plannedWorldVersion;
    }

    public FailureReason failureReason() {
        return failureReason;
    }

    /** Incoming heading of the last completed search state, retained across segment boundaries. */
    public Direction currentHeading() {
        if (path.isEmpty()) {
            return null;
        }
        int completedIndex = Math.max(0, Math.min(path.size() - 1, index - 1));
        return path.get(completedIndex).heading();
    }

    public boolean failureEvidenceUnrestricted() {
        return failureEvidenceUnrestricted;
    }

    /** True only for the tick whose local replan was deferred by the shared search ledger. */
    public boolean planningDeferred() {
        return planningDeferred;
    }

    public boolean semanticGoalChanged(AIPlayerEntity bot) {
        return navigationRequest != null && navigationRequest.goal().dynamic()
                && !plannedSemanticGoalFingerprint.equals(
                navigationRequest.goal().fingerprint(bot.serverLevel()));
    }

    /**
     * Installs the next segment of the same complete proof without starting another A* frontier.
     * Returns false when world/goal/start provenance changed, in which case ActionPack performs a
     * fresh bounded plan using {@link #currentHeading()}.
     */
    public boolean activateProvenContinuation(AIPlayerEntity bot) {
        if (navigationRequest == null || provenContinuation == null) {
            return false;
        }
        Set<BlockPos> continuationExclusions = new LinkedHashSet<>(persistentExclusions);
        continuationExclusions.addAll(transientExclusions);
        NavigationRequest validationRequest = navigationRequest.withConstraint(
                continuationExclusions,
                positionConstraint,
                navigationRequest.constraintKey());
        java.util.Optional<ProvenRoute.Slice> resumed = provenContinuation.resume(
                bot, validationRequest, navigationRequest.segmentLength());
        if (resumed.isEmpty()) {
            provenContinuation = null;
            return false;
        }
        ProvenRoute.Slice slice = resumed.orElseThrow();
        sunkPathCost += completedCurrentRouteCost();
        PathfindingResult next = slice.result();
        path = next.path();
        resolvedGoal = next.resolvedGoal().immutable();
        provenContinuation = slice.continuation();
        plannedWorldVersion = next.worldVersion();
        plannedGoalFingerprint = next.goalFingerprint();
        plannedSemanticGoalFingerprint = navigationRequest.goal().fingerprint(bot.serverLevel());
        index = 1;
        subWalker = null;
        subMiner = null;
        digWalking = false;
        progressTracker.reset();
        activeWalkTargetIndex = -1;
        nodeRetry = 0;
        forceSingleStep = false;
        advancedThisTick = false;
        replanTried = false;
        failureReason = FailureReason.NONE;
        planningDeferred = false;
        unsafeAcceptedTicks = 0;
        return true;
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

    /** Commits discrete path cost that movement completed between ActionPack ticks. */
    private void commitReachedPathProgress(BlockPos currentPosition) {
        if (path.isEmpty() || index >= path.size()) {
            return;
        }
        int furthestCandidate = activeWalkTargetIndex >= index
                ? Math.min(activeWalkTargetIndex, path.size() - 1)
                : index;
        // A string-pulled walk can be sampled on an intermediate discrete node before its farther
        // controller target. Commit the furthest node actually reached, not only the two ends, so
        // a same-tick dynamic replan retains every sunk edge and the real incoming heading.
        for (int candidate = furthestCandidate; candidate >= index; candidate--) {
            if (canPrecommitReachedNode(currentPosition, candidate)) {
                advanceTo(candidate + 1);
                return;
            }
        }
    }

    private boolean canPrecommitReachedNode(BlockPos currentPosition, int candidateIndex) {
        Node candidate = path.get(candidateIndex);
        return canPrecommitMovement(
                candidate.moveType(), currentPosition, candidate.pos(),
                hasArrived(currentPosition, candidate.pos(), candidateIndex));
    }

    static boolean canPrecommitMovement(MoveType moveType,
                                        BlockPos currentPosition,
                                        BlockPos targetPosition,
                                        boolean tolerantArrival) {
        return switch (moveType) {
            case WALK, DIAGONAL -> tolerantArrival;
            case JUMP_UP, DROP_DOWN -> currentPosition.equals(targetPosition);
            // Mining and pillaring own their postconditions. The ordinary Y±1 arrival tolerance
            // would otherwise skip a same-column vertical edit before it happened.
            case DIG_THROUGH, PILLAR_UP -> false;
        };
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
        if (navigationRequest == null) {
            validator.setPathGoal(resolvedGoal);
        } else {
            validator.setPathGoalPredicate(position ->
                    replanGoal.accepts(world, position));
        }
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
            boolean transitionValid = validator.getNeighbors(previous, world).stream()
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
            return current.distSqr(target) <= NEAR_ARRIVAL_DISTANCE_SQ;
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
        if (navigationRequest != null) {
            rememberEntityObstacles(pack);
        }
        return handleStuck(pack, reason);
    }

    private ActionResult handleStuck(ActionPack pack, String reason) {
        return handleStuck(pack, reason, false);
    }

    private ActionResult handleStuck(ActionPack pack, String reason, boolean forceFresh) {
        lastReplanReason = reason;
        boolean dynamicRefresh = navigationRequest != null
                && replanGoal.dynamic()
                && "dynamic_goal_changed".equals(reason);
        if (!replanTried) {
            int now = pack.player().getServer().getTickCount();
            int cooldown = navigationRequest != null && replanGoal.dynamic()
                    ? 10 : REPLAN_COOLDOWN_TICKS;
            if (now - lastReplanTick < cooldown) {
                if (navigationRequest != null) {
                    cleanup(pack);
                    return ActionResult.IN_PROGRESS;
                }
                cleanup(pack);
                failureReason = FailureReason.PATH_BLOCKED;
                return ActionResult.failed(reason + "; replan_throttled");
            }
            if (navigationRequest != null
                    && !dynamicRefresh
                    && failureReplanCount >= navigationRequest.maxReplans()) {
                cleanup(pack);
                failureReason = FailureReason.PATH_BLOCKED;
                return ActionResult.failed(reason + "; replan_limit");
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
                    failureReason = FailureReason.NO_START;
                    return ActionResult.failed(reason + "; replan_failed: NO_START");
                }
            }
            Set<BlockPos> replanExclusions = new LinkedHashSet<>(persistentExclusions);
            replanExclusions.addAll(transientExclusions);
            PathfindingResult fresh;
            NavGoal freshGoal = replanGoal;
            boolean freshEvidenceUnrestricted = false;
            if (navigationRequest != null) {
                // Any local invalidation revokes the old suffix. A successful fresh proof installs
                // its own continuation; a deferred/failed search must never resume stale nodes.
                provenContinuation = null;
                NavigationRequest replanRequest = navigationRequest.withConstraint(
                        replanExclusions,
                        positionConstraint,
                        navigationRequest.constraintKey());
                NavigationPlanner.PlanningOutcome outcome = NavigationPlanner.plan(
                        pack.player(), replanRequest, true, currentHeading());
                fresh = outcome.result();
                freshGoal = outcome.plannedGoal();
                freshEvidenceUnrestricted = outcome.unrestrictedEvidenceScope();
                if (fresh.success()) {
                    provenContinuation = outcome.continuation();
                    failureEvidenceUnrestricted = freshEvidenceUnrestricted;
                }
            } else {
                boolean canPillar = allowPillarOnReplan && hasPlaceableBlock(pack.player());
                AStarPathfinder finder = new AStarPathfinder(
                        pack.player().serverLevel(),
                        pack.player().blockPosition(),
                        resolvedGoal,
                        10_000,
                        50L,
                        canPillar,
                        allowDigOnReplan,
                        traversalPolicy,
                        1.0D,
                        replanExclusions,
                        bounds,
                        positionConstraint);
                fresh = finder.findPath(forceFresh);
            }
            if (navigationRequest != null) {
                // A failed fresh search is still authoritative provenance. Publishing the prior
                // plan revision would make a real GOAL_UNREACHABLE look stale to outcome policy.
                plannedWorldVersion = fresh.worldVersion();
            }
            if (fresh.success()) {
                replanCount++;
                if (!dynamicRefresh) {
                    failureReplanCount++;
                }
                BotLog.path(pack.player(), "path_replan", "at_node", reason, "new_path_size", fresh.path().size());
                sunkPathCost += completedCurrentRouteCost();
                path = fresh.path();
                resolvedGoal = fresh.resolvedGoal() == null
                        ? resolvedGoal : fresh.resolvedGoal().immutable();
                replanGoal = freshGoal;
                plannedWorldVersion = fresh.worldVersion();
                plannedGoalFingerprint = fresh.goalFingerprint();
                if (navigationRequest != null) {
                    plannedSemanticGoalFingerprint = navigationRequest.goal().fingerprint(
                            pack.player().serverLevel());
                }
                index = 1;
                subWalker = null;
                subMiner = null;
                digWalking = false;
                progressTracker.reset();
                forceSingleStep = false;
                replanTried = false;
                failureReason = FailureReason.NONE;
                return ActionResult.IN_PROGRESS;
            }
            if (fresh.reason() == FailureReason.SEARCH_BUDGET) {
                cleanup(pack);
                replanTried = false;
                lastReplanTick = now - cooldown;
                lastReplanReason = reason + "; search_budget_deferred";
                planningDeferred = true;
                return ActionResult.IN_PROGRESS;
            }
            if (navigationRequest != null) {
                // The terminal proof inherits the actual replan scope, including transient entity
                // exclusions. Never upgrade that narrower search to the original request scope.
                Set<BlockPos> evidenceExclusions = new LinkedHashSet<>(persistentExclusions);
                evidenceExclusions.addAll(transientExclusions);
                NavigationRequest evidenceRequest = navigationRequest.withConstraint(
                        evidenceExclusions,
                        positionConstraint,
                        navigationRequest.constraintKey());
                failureEvidenceUnrestricted = freshEvidenceUnrestricted
                        && evidenceRequest.unrestrictedEvidenceScope();
            }
            failureReason = fresh.reason();
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

    private double completedCurrentRouteCost() {
        if (path.isEmpty() || index <= 0) {
            return 0.0D;
        }
        int completedIndex = Math.min(index - 1, path.size() - 1);
        return Math.max(0.0D, path.get(completedIndex).gCost());
    }

    private void rememberEntityObstacles(ActionPack pack) {
        for (LivingEntity entity : pack.player().serverLevel().getEntitiesOfClass(
                LivingEntity.class,
                pack.player().getBoundingBox().inflate(8.0D),
                entity -> entity != pack.player() && entity.isAlive())) {
            AABB box = entity.getBoundingBox();
            int minX = Mth.floor(box.minX + 1.0E-4D);
            int maxX = Mth.floor(box.maxX - 1.0E-4D);
            int minZ = Mth.floor(box.minZ + 1.0E-4D);
            int maxZ = Mth.floor(box.maxZ - 1.0E-4D);
            int feetY = Mth.floor(box.minY + 1.0E-4D);
            for (int x = minX; x <= maxX; x++) {
                for (int z = minZ; z <= maxZ; z++) {
                    rememberExclusion(new BlockPos(x, feetY, z));
                }
            }
        }
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
