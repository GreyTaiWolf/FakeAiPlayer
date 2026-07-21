package io.github.greytaiwolf.fakeaiplayer.pathfinding;

import io.github.greytaiwolf.fakeaiplayer.entity.AIPlayerEntity;
import java.util.List;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

/** Bounded formal planning entrypoint shared by initial requests, segments, and local replans. */
public final class NavigationPlanner {
    private NavigationPlanner() {
    }

    public static PlanningOutcome plan(AIPlayerEntity bot,
                                       NavigationRequest request,
                                       boolean bypassCache) {
        return plan(bot, request, bypassCache, null);
    }

    public static PlanningOutcome plan(AIPlayerEntity bot,
                                       NavigationRequest request,
                                       boolean bypassCache,
                                       Direction initialHeading) {
        BlockPos start = bot.blockPosition();
        NavGoal goal = request.goal();
        if (!goal.resolvable(bot.serverLevel())) {
            return new PlanningOutcome(
                    PathfindingResult.failure(
                            FailureReason.STALE_WORLD, 0, 0L,
                            AStarPathfinder.worldVersion(), goal.fingerprint(bot.serverLevel())),
                    goal, null, false);
        }
        if (!legalStart(bot, request, start)) {
            return new PlanningOutcome(
                    PathfindingResult.failure(
                            FailureReason.NO_START, 0, 0L,
                            AStarPathfinder.worldVersion(), goal.fingerprint(bot.serverLevel())),
                    goal, null, false);
        }
        if (isSatisfiedAt(bot, request, start)) {
            Node startNode = new Node(
                    start, 0.0D, 0.0D, MoveType.WALK, null, initialHeading);
            return new PlanningOutcome(
                    PathfindingResult.success(
                            List.of(startNode), 0, 0L,
                            AStarPathfinder.worldVersion(), goal.fingerprint(bot.serverLevel()),
                            false),
                    goal, null, request.unrestrictedEvidenceScope());
        }
        NavigationSearchBudget.Permit permit = NavigationSearchBudget.acquire(
                bot.getServer(), bot.getUUID(), request.maxNodes(), request.maxMillis());
        if (!permit.granted()) {
            return new PlanningOutcome(
                    PathfindingResult.failure(
                            permit.denialReason(), 0, 0L,
                            AStarPathfinder.worldVersion(), goal.fingerprint(bot.serverLevel())),
                    goal, null, false);
        }
        boolean canPillar = request.allowPillar() && PathExecutor.hasPlaceableBlock(bot);
        boolean unrestrictedEvidenceScope = request.unrestrictedEvidenceScope()
                && (!request.allowPillar() || canPillar);
        Predicate<BlockPos> constraint = request.cacheableConstraint()
                ? null : request.positionConstraint();
        MultiGoalAStarPathfinder finder = new MultiGoalAStarPathfinder(
                bot.serverLevel(),
                start,
                goal,
                permit.maxNodes(),
                permit.maxMillis(),
                canPillar,
                request.allowDig(),
                request.traversalPolicy(),
                request.heuristicWeight(),
                request.excludedPositions(),
                request.bounds(),
                constraint,
                request.constraintKey(),
                request.searchMetrics(),
                initialHeading);
        PathfindingResult complete = finder.findPath(bypassCache);
        permit.complete(complete.nodesExplored(), complete.elapsedMs());
        FailureReason classified = permit.classifyExhaustion(complete.reason());
        if (!complete.success() && classified != complete.reason()) {
            complete = PathfindingResult.failure(
                    classified, complete.nodesExplored(), complete.elapsedMs(),
                    complete.worldVersion(), complete.goalFingerprint());
        }
        if (!complete.success()) {
            return new PlanningOutcome(
                    complete, goal, null, unrestrictedEvidenceScope);
        }
        ProvenRoute.Slice split = ProvenRoute.splitInitial(
                complete, request.segmentLength());
        return new PlanningOutcome(
                split.result(), goal, split.continuation(), unrestrictedEvidenceScope);
    }

    /** Authoritative no-op/arrival predicate shared by planning and ActionPack publication. */
    public static boolean isSatisfiedAt(AIPlayerEntity bot,
                                        NavigationRequest request,
                                        BlockPos position) {
        return bot != null && request != null && position != null
                && request.goal().resolvable(bot.serverLevel())
                && legalStart(bot, request, position)
                && request.goal().accepts(bot.serverLevel(), position)
                && (!request.traversalPolicy().requiresOpenGoal()
                || LocalOpenness.isOpen(
                bot.serverLevel(), position, request.traversalPolicy()));
    }

    static boolean legalStart(AIPlayerEntity bot,
                              NavigationRequest request,
                              BlockPos position) {
        return request.bounds().contains(position)
                && !request.excludedPositions().contains(position)
                && request.positionConstraint().test(position)
                && Standability.isStandableFresh(
                bot.serverLevel(), position, request.traversalPolicy());
    }

    public static final class PlanningOutcome {
        private final PathfindingResult result;
        private final NavGoal plannedGoal;
        private final ProvenRoute continuation;
        private final boolean unrestrictedEvidenceScope;

        private PlanningOutcome(PathfindingResult result,
                                NavGoal plannedGoal,
                                ProvenRoute continuation,
                                boolean unrestrictedEvidenceScope) {
            if (result == null || plannedGoal == null) {
                throw new IllegalArgumentException("planning outcome is incomplete");
            }
            this.result = result;
            this.plannedGoal = plannedGoal;
            this.continuation = continuation;
            this.unrestrictedEvidenceScope = unrestrictedEvidenceScope;
        }

        public PathfindingResult result() {
            return result;
        }

        public NavGoal plannedGoal() {
            return plannedGoal;
        }

        public boolean segmented() {
            return continuation != null;
        }

        ProvenRoute continuation() {
            return continuation;
        }

        public boolean unrestrictedEvidenceScope() {
            return unrestrictedEvidenceScope;
        }

        public List<Node> path() {
            return result.path();
        }
    }
}
