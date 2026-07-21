package io.github.greytaiwolf.fakeaiplayer.pathfinding;

import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;

/** Immutable permission, budget, constraint, and segmentation snapshot for one logical request. */
public record NavigationRequest(
        NavGoal goal,
        TraversalPolicy traversalPolicy,
        TraversalBounds bounds,
        boolean allowDig,
        boolean allowPillar,
        int maxNodes,
        long maxMillis,
        double heuristicWeight,
        Set<BlockPos> excludedPositions,
        Predicate<BlockPos> positionConstraint,
        String constraintKey,
        int segmentLength,
        int maxReplans,
        String source,
        NavigationSearchMetrics searchMetrics
) {
    private static final Predicate<BlockPos> ALLOW_ALL = ignored -> true;

    public NavigationRequest {
        if (goal == null || traversalPolicy == null) {
            throw new IllegalArgumentException("goal and traversal policy are required");
        }
        bounds = bounds == null ? TraversalBounds.unbounded() : bounds;
        if ((traversalPolicy == TraversalPolicy.AMBIENT_DRY_OPEN
                || traversalPolicy == TraversalPolicy.ESCAPE_DRY_OPEN)
                && !bounds.isBounded()) {
            throw new IllegalArgumentException(
                    traversalPolicy + " navigation requires finite caller bounds");
        }
        if (traversalPolicy == TraversalPolicy.ESCAPE_DRY_OPEN
                && !(goal instanceof NavGoal.Flee)) {
            throw new IllegalArgumentException("escape navigation requires a flee goal");
        }
        if (containsFlee(goal) && traversalPolicy != TraversalPolicy.ESCAPE_DRY_OPEN) {
            throw new IllegalArgumentException("flee goals require the escape traversal policy");
        }
        if (allowDig && !traversalPolicy.allowsDigging()) {
            throw new IllegalArgumentException("policy does not grant digging");
        }
        if (allowPillar && !traversalPolicy.allowsPillaring()) {
            throw new IllegalArgumentException("policy does not grant pillaring");
        }
        if (maxNodes <= 0 || maxMillis <= 0L || heuristicWeight < 1.0D
                || !Double.isFinite(heuristicWeight)) {
            throw new IllegalArgumentException("invalid navigation request budget");
        }
        excludedPositions = immutablePositions(excludedPositions);
        positionConstraint = positionConstraint == null ? ALLOW_ALL : positionConstraint;
        constraintKey = constraintKey == null || constraintKey.isBlank()
                ? (positionConstraint == ALLOW_ALL ? "none" : "opaque") : constraintKey;
        segmentLength = Math.max(8, segmentLength);
        maxReplans = Math.max(0, maxReplans);
        source = source == null || source.isBlank() ? "unspecified" : source;
        searchMetrics = searchMetrics == null ? new NavigationSearchMetrics() : searchMetrics;
    }

    public static NavigationRequest walk(NavGoal goal, String source) {
        return new NavigationRequest(
                goal, TraversalPolicy.TASK_WALK_DRY, TraversalBounds.unbounded(),
                false, false, 10_000, 50L, 1.0D, Set.of(), ALLOW_ALL, "none",
                40, 3, source, new NavigationSearchMetrics());
    }

    public static NavigationRequest ambient(NavGoal goal,
                                            TraversalBounds bounds,
                                            String source) {
        if (bounds == null || !bounds.isBounded()) {
            throw new IllegalArgumentException("ambient navigation requires finite caller bounds");
        }
        return new NavigationRequest(
                goal, TraversalPolicy.AMBIENT_DRY_OPEN, bounds,
                false, false, 2_000, 8L, 1.0D, Set.of(), ALLOW_ALL, "none",
                24, 1, source, new NavigationSearchMetrics());
    }

    public static NavigationRequest water(NavGoal goal, String source) {
        return new NavigationRequest(
                goal, TraversalPolicy.WATER_CAPABLE, TraversalBounds.unbounded(),
                false, false, 10_000, 50L, 1.0D, Set.of(), ALLOW_ALL, "none",
                40, 3, source, new NavigationSearchMetrics());
    }

    /** Emergency escape is always dry, open-goal, bounded, and forbidden from editing terrain. */
    public static NavigationRequest escape(NavGoal.Flee goal,
                                           TraversalBounds bounds,
                                           String source) {
        if (bounds == null || !bounds.isBounded()) {
            throw new IllegalArgumentException("escape navigation requires finite caller bounds");
        }
        return new NavigationRequest(
                goal, TraversalPolicy.ESCAPE_DRY_OPEN, bounds,
                false, false, 4_000, 20L, 1.0D, Set.of(), ALLOW_ALL, "none",
                24, 2, source, new NavigationSearchMetrics());
    }

    public static NavigationRequest mutating(NavGoal goal,
                                             boolean allowPillar,
                                             String source) {
        return new NavigationRequest(
                goal, TraversalPolicy.TASK_MUTATING_DRY, TraversalBounds.unbounded(),
                true, allowPillar, 24_000, 50L, 1.0D, Set.of(), ALLOW_ALL, "none",
                32, 3, source, new NavigationSearchMetrics());
    }

    public NavigationRequest withBounds(TraversalBounds newBounds) {
        return copy(goal, traversalPolicy, newBounds, allowDig, allowPillar, maxNodes, maxMillis,
                heuristicWeight, excludedPositions, positionConstraint, constraintKey,
                segmentLength, maxReplans, source, searchMetrics);
    }

    public NavigationRequest withGoal(NavGoal newGoal) {
        return copy(newGoal, traversalPolicy, bounds, allowDig, allowPillar, maxNodes, maxMillis,
                heuristicWeight, excludedPositions, positionConstraint, constraintKey,
                segmentLength, maxReplans, source, searchMetrics);
    }

    public NavigationRequest withBudget(int nodes, long millis) {
        return copy(goal, traversalPolicy, bounds, allowDig, allowPillar, nodes, millis,
                heuristicWeight, excludedPositions, positionConstraint, constraintKey,
                segmentLength, maxReplans, source, searchMetrics);
    }

    public NavigationRequest withSegmentation(int length) {
        return copy(goal, traversalPolicy, bounds, allowDig, allowPillar, maxNodes, maxMillis,
                heuristicWeight, excludedPositions, positionConstraint, constraintKey,
                length, maxReplans, source, searchMetrics);
    }

    public NavigationRequest withMaxReplans(int replans) {
        return copy(goal, traversalPolicy, bounds, allowDig, allowPillar, maxNodes, maxMillis,
                heuristicWeight, excludedPositions, positionConstraint, constraintKey,
                segmentLength, replans, source, searchMetrics);
    }

    public NavigationRequest withConstraint(Set<BlockPos> exclusions,
                                            Predicate<BlockPos> constraint,
                                            String stableConstraintKey) {
        return copy(goal, traversalPolicy, bounds, allowDig, allowPillar, maxNodes, maxMillis,
                heuristicWeight, exclusions, constraint, stableConstraintKey,
                segmentLength, maxReplans, source, searchMetrics);
    }

    public String permissionFingerprint() {
        return traversalPolicy.name() + ":dig=" + allowDig + ":pillar=" + allowPillar;
    }

    public boolean cacheableConstraint() {
        return positionConstraint == ALLOW_ALL && !"opaque".equals(constraintKey);
    }

    /** True only when GOAL_UNREACHABLE was not narrowed by caller-owned spatial restrictions. */
    public boolean unrestrictedEvidenceScope() {
        return !bounds.isBounded()
                && excludedPositions.isEmpty()
                && positionConstraint == ALLOW_ALL
                && "none".equals(constraintKey);
    }

    private static NavigationRequest copy(NavGoal goal,
                                          TraversalPolicy policy,
                                          TraversalBounds bounds,
                                          boolean allowDig,
                                          boolean allowPillar,
                                          int nodes,
                                          long millis,
                                          double weight,
                                          Set<BlockPos> exclusions,
                                          Predicate<BlockPos> constraint,
                                          String constraintKey,
                                          int segmentLength,
                                          int maxReplans,
                                          String source,
                                          NavigationSearchMetrics metrics) {
        return new NavigationRequest(
                goal, policy, bounds, allowDig, allowPillar, nodes, millis, weight,
                exclusions, constraint, constraintKey, segmentLength, maxReplans, source, metrics);
    }

    private static Set<BlockPos> immutablePositions(Set<BlockPos> positions) {
        if (positions == null || positions.isEmpty()) {
            return Set.of();
        }
        LinkedHashSet<BlockPos> copy = new LinkedHashSet<>();
        positions.stream().map(BlockPos::immutable)
                .sorted(Comparator.comparingLong(BlockPos::asLong)).forEach(copy::add);
        return Set.copyOf(copy);
    }

    private static boolean containsFlee(NavGoal goal) {
        if (goal instanceof NavGoal.Flee) {
            return true;
        }
        return goal instanceof NavGoal.Composite composite
                && composite.goals().stream().anyMatch(NavigationRequest::containsFlee);
    }
}
