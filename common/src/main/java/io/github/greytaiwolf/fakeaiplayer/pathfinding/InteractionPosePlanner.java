package io.github.greytaiwolf.fakeaiplayer.pathfinding;

import io.github.greytaiwolf.fakeaiplayer.entity.AIPlayerEntity;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Selects a concrete feet position from which a block can really be interacted with.
 *
 * <p>Every horizontal pose is evaluated with reach, line of sight, standability and actual dry
 * walking cost. This replaces the old fixed-direction "first adjacent air block" rule.</p>
 */
public final class InteractionPosePlanner {
    private static final int POSE_MAX_NODES = 3_000;
    private static final long POSE_MAX_MILLIS = 15L;
    private static final int MAX_SEARCHES_PER_TARGET = 8;
    private static final int MAX_VERTICAL_POSE_OFFSET = 16;
    private static final double TARGET_FACE_INSET = 1.0E-4D;
    private static final double RAY_ADVANCE_EPSILON = 1.0E-5D;

    private InteractionPosePlanner() {
    }

    public static Optional<InteractionPose> plan(AIPlayerEntity bot, BlockPos target) {
        return plan(bot, target, Set.of(), PlanningBudget.bounded(8, 30L));
    }

    public static Optional<InteractionPose> plan(
            AIPlayerEntity bot, BlockPos target, Set<BlockPos> excludedStands) {
        return plan(bot, target, excludedStands, PlanningBudget.bounded(8, 30L));
    }

    public static Optional<InteractionPose> plan(
            AIPlayerEntity bot,
            BlockPos target,
            Set<BlockPos> excludedStands,
            PlanningBudget budget) {
        return plan(bot, target, excludedStands, budget, MAX_SEARCHES_PER_TARGET);
    }

    public static Optional<InteractionPose> plan(
            AIPlayerEntity bot,
            BlockPos target,
            Set<BlockPos> excludedStands,
            PlanningBudget budget,
            int maxSearchesForTarget) {
        return plan(
                bot, target, excludedStands, budget, maxSearchesForTarget,
                ignored -> true);
    }

    /**
     * Plans while retaining only poses that satisfy a caller-specific endpoint invariant. Any
     * invariant that applies to the complete route must also be supplied through the
     * {@code pathPositionConstraint} overload so it is enforced during every graph expansion.
     */
    public static Optional<InteractionPose> plan(
            AIPlayerEntity bot,
            BlockPos target,
            Set<BlockPos> excludedStands,
            PlanningBudget budget,
            int maxSearchesForTarget,
            Predicate<InteractionPose> poseConstraint) {
        return plan(
                bot, target, excludedStands, budget, maxSearchesForTarget,
                Set.of(), poseConstraint);
    }

    /** Variant that also removes forbidden feet cells from the A* graph itself. */
    public static Optional<InteractionPose> plan(
            AIPlayerEntity bot,
            BlockPos target,
            Set<BlockPos> excludedStands,
            PlanningBudget budget,
            int maxSearchesForTarget,
            Set<BlockPos> pathExclusions,
            Predicate<InteractionPose> poseConstraint) {
        return plan(
                bot, target, excludedStands, budget, maxSearchesForTarget,
                pathExclusions, ignored -> true, poseConstraint);
    }

    /** Variant whose skill-owned feet invariant is enforced inside every A* expansion. */
    public static Optional<InteractionPose> plan(
            AIPlayerEntity bot,
            BlockPos target,
            Set<BlockPos> excludedStands,
            PlanningBudget budget,
            int maxSearchesForTarget,
            Set<BlockPos> pathExclusions,
            Predicate<BlockPos> pathPositionConstraint,
            Predicate<InteractionPose> poseConstraint) {
        return planInternal(
                bot, target, excludedStands, budget, maxSearchesForTarget,
                pathExclusions, pathPositionConstraint, poseConstraint, Set.of(), false);
    }

    /**
     * Preflight variant for an ordered destructive skill. Only the supplied committed blocks may
     * be treated as transient line-of-sight occluders; every other block remains authoritative.
     * The caller must pass only blocks guaranteed to be removed before {@code target}.
     */
    public static Optional<InteractionPose> planWithRemovableOccluders(
            AIPlayerEntity bot,
            BlockPos target,
            Set<BlockPos> excludedStands,
            PlanningBudget budget,
            int maxSearchesForTarget,
            Set<BlockPos> pathExclusions,
            Predicate<BlockPos> pathPositionConstraint,
            Predicate<InteractionPose> poseConstraint,
            Set<BlockPos> removableOccluders) {
        return planInternal(
                bot, target, excludedStands, budget, maxSearchesForTarget,
                pathExclusions, pathPositionConstraint, poseConstraint,
                Set.copyOf(removableOccluders), true);
    }

    private static Optional<InteractionPose> planInternal(
            AIPlayerEntity bot,
            BlockPos target,
            Set<BlockPos> excludedStands,
            PlanningBudget budget,
            int maxSearchesForTarget,
            Set<BlockPos> pathExclusions,
            Predicate<BlockPos> pathPositionConstraint,
            Predicate<InteractionPose> poseConstraint,
            Set<BlockPos> removableOccluders,
            boolean requireTargetHit) {
        BlockPos immutableTarget = target.immutable();
        BlockPos current = bot.blockPosition();
        double reach = bot.getAttributeValue(Attributes.BLOCK_INTERACTION_RANGE) + 0.5D;
        Standability.clearCache();
        if (!Standability.isStandable(
                bot.serverLevel(), current, TraversalPolicy.TASK_WALK_DRY)) {
            // AStarPathfinder can resolve an invalid start to a nearby standable cell for ordinary
            // navigation. A pose plan cannot use that computational snap: its reviewed path must
            // begin where the bot really stands so startPlannedNonMutatingPath can consume it.
            // NavSafety/ActionPack owns physical recovery from an invalid current column.
            budget.markInconclusive();
            return Optional.empty();
        }
        if (pathExclusions.contains(current) || !pathPositionConstraint.test(current)) {
            // Do not let A* computationally snap away from a start that this skill explicitly
            // forbids (for example, feet supported by a log that the same session will remove).
            budget.markInconclusive();
            return Optional.empty();
        }
        if (!excludedStands.contains(current)
                && canInteractFrom(
                bot, current, immutableTarget, reach, removableOccluders,
                requireTargetHit)) {
            InteractionPose currentPose = new InteractionPose(
                    immutableTarget,
                    current,
                    faceFrom(current, immutableTarget),
                    true,
                    List.of(),
                    0.0D,
                    0);
            if (poseConstraint.test(currentPose)) {
                return Optional.of(currentPose);
            }
        }

        List<BlockPos> legalStands = new ArrayList<>();
        for (BlockPos stand : candidateStands(immutableTarget, current, reach)) {
            if (excludedStands.contains(stand)
                    || pathExclusions.contains(stand)
                    || !pathPositionConstraint.test(stand)
                    || !Standability.isStandable(
                    bot.serverLevel(), stand, TraversalPolicy.TASK_WALK_DRY)
                    || !canInteractFrom(
                    bot, stand, immutableTarget, reach, removableOccluders,
                    requireTargetHit)) {
                continue;
            }
            InteractionPose standOnly = new InteractionPose(
                    immutableTarget, stand, faceFrom(stand, immutableTarget),
                    false, List.of(), 0.0D, 0);
            if (!poseConstraint.test(standOnly)) {
                continue;
            }
            legalStands.add(stand.immutable());
        }
        if (legalStands.isEmpty()) {
            return Optional.empty();
        }
        // P2: one semantic interaction goal creates exactly one A* frontier. The historical
        // maxSearchesForTarget parameter remains source-compatible, but now only validates that
        // this caller allocated at least one search instead of truncating the candidate set.
        if (maxSearchesForTarget <= 0 || !budget.tryAcquireSearch()) {
            budget.markInconclusive();
            return Optional.empty();
        }
        NavigationSearchBudget.Permit serverPermit = NavigationSearchBudget.acquire(
                bot.getServer(), bot.getUUID(), POSE_MAX_NODES,
                Math.min(POSE_MAX_MILLIS, budget.remainingMillis()));
        if (!serverPermit.granted()) {
            budget.markInconclusive();
            return Optional.empty();
        }
        NavGoal.Interaction interactionGoal = new NavGoal.Interaction(
                immutableTarget, Set.copyOf(legalStands),
                bot.serverLevel().getBlockState(immutableTarget).toString());
        PathfindingResult path = new MultiGoalAStarPathfinder(
                bot.serverLevel(),
                current,
                interactionGoal,
                serverPermit.maxNodes(),
                serverPermit.maxMillis(),
                false,
                false,
                TraversalPolicy.TASK_WALK_DRY,
                1.0D,
                pathExclusions,
                TraversalBounds.unbounded(),
                pathPositionConstraint,
                "opaque",
                budget.searchMetrics).findPath();
        serverPermit.complete(path.nodesExplored(), path.elapsedMs());
        FailureReason classified = serverPermit.classifyExhaustion(path.reason());
        if (!path.success() && classified != path.reason()) {
            path = PathfindingResult.failure(
                    classified, path.nodesExplored(), path.elapsedMs(),
                    path.worldVersion(), path.goalFingerprint());
        }
        if (!path.success() || path.path().isEmpty() || path.resolvedGoal() == null) {
            if (path.reason() == FailureReason.TIMEOUT
                    || path.reason() == FailureReason.SEARCH_LIMIT
                    || path.reason() == FailureReason.SEARCH_BUDGET) {
                budget.markInconclusive();
            }
            return Optional.empty();
        }
        if (!path.path().get(0).pos().equals(current)
                || !interactionGoal.accepts(bot.serverLevel(), path.resolvedGoal())) {
            budget.markInconclusive();
            return Optional.empty();
        }
        BlockPos stand = path.resolvedGoal();
        InteractionPose selected = new InteractionPose(
                immutableTarget,
                stand,
                faceFrom(stand, immutableTarget),
                false,
                path.path(),
                path.pathCost(),
                path.nodesExplored());
        return poseConstraint.test(selected) ? Optional.of(selected) : Optional.empty();
    }

    private static List<BlockPos> candidateStands(
            BlockPos target, BlockPos current, double reach) {
        Set<BlockPos> positions = new LinkedHashSet<>();
        int verticalRadius = Math.min(
                MAX_VERTICAL_POSE_OFFSET,
                Math.max(2, (int) Math.ceil(reach + 2.0D)));
        for (int dy = -verticalRadius; dy <= verticalRadius; dy++) {
            for (Direction direction : Direction.Plane.HORIZONTAL) {
                positions.add(target.relative(direction).above(dy).immutable());
            }
        }
        // A block can be interacted with from two cells away. These poses matter on slopes and
        // when every adjacent column is occupied, but remain bounded and lower priority than the
        // ordinary four neighboring cells.
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                if (Math.max(Math.abs(dx), Math.abs(dz)) != 2) {
                    continue;
                }
                for (int dy = -verticalRadius; dy <= verticalRadius; dy++) {
                    positions.add(target.offset(dx, dy, dz).immutable());
                }
            }
        }
        return positions.stream()
                .sorted(Comparator
                        .comparingDouble((BlockPos stand) -> stand.distSqr(current))
                        .thenComparingInt(stand -> Math.abs(stand.getY() - target.getY()))
                        .thenComparingInt(stand -> stand.distManhattan(target))
                        .thenComparingLong(BlockPos::asLong))
                .toList();
    }

    /** Shared wall-clock/search allowance for one target-selection decision. */
    public static final class PlanningBudget {
        private final long deadlineNanos;
        private int remainingSearches;
        private boolean inconclusive;
        private final NavigationSearchMetrics searchMetrics = new NavigationSearchMetrics();

        private PlanningBudget(int maxSearches, long maxMillis) {
            remainingSearches = Math.max(0, maxSearches);
            deadlineNanos = System.nanoTime() + Math.max(1L, maxMillis) * 1_000_000L;
        }

        public static PlanningBudget bounded(int maxSearches, long maxMillis) {
            return new PlanningBudget(maxSearches, maxMillis);
        }

        private boolean tryAcquireSearch() {
            if (remainingSearches <= 0 || System.nanoTime() >= deadlineNanos) {
                inconclusive = true;
                return false;
            }
            remainingSearches--;
            return true;
        }

        private long remainingMillis() {
            long nanos = Math.max(1L, deadlineNanos - System.nanoTime());
            return Math.max(1L, nanos / 1_000_000L);
        }

        private void markInconclusive() {
            inconclusive = true;
        }

        /** True when a candidate was skipped or a bounded A* ended before proving reachability. */
        public boolean inconclusive() {
            return inconclusive;
        }

        /** Request-local proof that a pose decision did not hide per-candidate A* loops. */
        public NavigationSearchMetrics.Snapshot searchMetrics() {
            return searchMetrics.snapshot();
        }
    }

    public static boolean canInteractFromCurrent(AIPlayerEntity bot, BlockPos target) {
        double reach = bot.getAttributeValue(Attributes.BLOCK_INTERACTION_RANGE) + 0.5D;
        return Standability.isStandableFresh(
                bot.serverLevel(), bot.blockPosition(), TraversalPolicy.TASK_WALK_DRY)
                && canInteract(bot, bot.getEyePosition(), target, reach, Set.of(), false);
    }

    /** Ordered-skill preflight variant; see {@link #planWithRemovableOccluders}. */
    public static boolean canInteractFromCurrent(
            AIPlayerEntity bot, BlockPos target, Set<BlockPos> removableOccluders) {
        double reach = bot.getAttributeValue(Attributes.BLOCK_INTERACTION_RANGE) + 0.5D;
        return Standability.isStandableFresh(
                bot.serverLevel(), bot.blockPosition(), TraversalPolicy.TASK_WALK_DRY)
                && canInteract(
                bot, bot.getEyePosition(), target, reach, removableOccluders, true);
    }

    public static boolean canInteractFrom(
            AIPlayerEntity bot, BlockPos stand, BlockPos target, double reach) {
        Vec3 eye = new Vec3(
                stand.getX() + 0.5D,
                stand.getY() + bot.getEyeHeight(),
                stand.getZ() + 0.5D);
        return canInteract(bot, eye, target, reach, Set.of(), false);
    }

    /** Ordered-skill preflight variant; see {@link #planWithRemovableOccluders}. */
    public static boolean canInteractFrom(
            AIPlayerEntity bot,
            BlockPos stand,
            BlockPos target,
            double reach,
            Set<BlockPos> removableOccluders) {
        return canInteractFrom(bot, stand, target, reach, removableOccluders, true);
    }

    private static boolean canInteractFrom(
            AIPlayerEntity bot,
            BlockPos stand,
            BlockPos target,
            double reach,
            Set<BlockPos> removableOccluders,
            boolean requireTargetHit) {
        Vec3 eye = new Vec3(
                stand.getX() + 0.5D,
                stand.getY() + bot.getEyeHeight(),
                stand.getZ() + 0.5D);
        return canInteract(
                bot, eye, target, reach, removableOccluders, requireTargetHit);
    }

    private static boolean canInteract(
            AIPlayerEntity bot,
            Vec3 eye,
            BlockPos target,
            double reach,
            Set<BlockPos> removableOccluders,
            boolean requireTargetHit) {
        // Callers already add the half-block tolerance needed for a center-based block range.
        // Keep that authoritative gate so testing multiple visible faces cannot spend the same
        // tolerance twice and turn a five-cell approach into an in-place interaction.
        if (eye.distanceTo(target.getCenter()) > reach) {
            return false;
        }
        for (Vec3 sample : interactionSamples(target)) {
            if (eye.distanceTo(sample) <= reach
                    && hasReviewedLineOfSight(
                    bot, eye, sample, target, removableOccluders, requireTargetHit)) {
                return true;
            }
        }
        return false;
    }

    private static List<Vec3> interactionSamples(BlockPos target) {
        double minX = target.getX() + TARGET_FACE_INSET;
        double minY = target.getY() + TARGET_FACE_INSET;
        double minZ = target.getZ() + TARGET_FACE_INSET;
        double maxX = target.getX() + 1.0D - TARGET_FACE_INSET;
        double maxY = target.getY() + 1.0D - TARGET_FACE_INSET;
        double maxZ = target.getZ() + 1.0D - TARGET_FACE_INSET;
        double centerX = target.getX() + 0.5D;
        double centerY = target.getY() + 0.5D;
        double centerZ = target.getZ() + 0.5D;
        return List.of(
                new Vec3(centerX, centerY, centerZ),
                new Vec3(minX, centerY, centerZ),
                new Vec3(maxX, centerY, centerZ),
                new Vec3(centerX, minY, centerZ),
                new Vec3(centerX, maxY, centerZ),
                new Vec3(centerX, centerY, minZ),
                new Vec3(centerX, centerY, maxZ));
    }

    private static boolean hasReviewedLineOfSight(
            AIPlayerEntity bot,
            Vec3 eye,
            Vec3 sample,
            BlockPos target,
            Set<BlockPos> removableOccluders,
            boolean requireTargetHit) {
        Vec3 delta = sample.subtract(eye);
        Vec3 rayStart = eye;
        double rayFraction = 0.0D;
        int skipsRemaining = removableOccluders.size();
        while (true) {
            BlockHitResult hit = bot.serverLevel().clip(new ClipContext(
                    rayStart,
                    sample,
                    ClipContext.Block.OUTLINE,
                    ClipContext.Fluid.NONE,
                    bot));
            if (hit.getType() == HitResult.Type.MISS) {
                // Plants and a few modded resource blocks have no outline collision. A MISS means
                // no nearer outline occluded this reviewed target sample. Destructive preflight
                // is stricter: it must end in an authoritative hit on the committed target.
                return !requireTargetHit;
            }
            BlockPos hitPosition = hit.getBlockPos();
            if (hitPosition.equals(target)) {
                return true;
            }
            if (skipsRemaining-- <= 0 || !removableOccluders.contains(hitPosition)) {
                return false;
            }
            double exitFraction = voxelExitFraction(eye, delta, hitPosition);
            double nextFraction = exitFraction + RAY_ADVANCE_EPSILON;
            if (!Double.isFinite(nextFraction)
                    || nextFraction <= rayFraction
                    || nextFraction >= 1.0D) {
                return false;
            }
            rayFraction = nextFraction;
            rayStart = eye.add(delta.scale(rayFraction));
        }
    }

    private static double voxelExitFraction(
            Vec3 origin, Vec3 delta, BlockPos voxel) {
        double exitX = axisExitFraction(origin.x, delta.x, voxel.getX());
        double exitY = axisExitFraction(origin.y, delta.y, voxel.getY());
        double exitZ = axisExitFraction(origin.z, delta.z, voxel.getZ());
        return Math.min(exitX, Math.min(exitY, exitZ));
    }

    private static double axisExitFraction(
            double origin, double delta, int voxelMinimum) {
        if (delta > 0.0D) {
            return (voxelMinimum + 1.0D - origin) / delta;
        }
        if (delta < 0.0D) {
            return (voxelMinimum - origin) / delta;
        }
        return Double.POSITIVE_INFINITY;
    }

    private static Direction faceFrom(BlockPos stand, BlockPos target) {
        double dx = stand.getX() - target.getX();
        double dz = stand.getZ() - target.getZ();
        if (Math.abs(dx) >= Math.abs(dz)) {
            return dx >= 0.0D ? Direction.EAST : Direction.WEST;
        }
        return dz >= 0.0D ? Direction.SOUTH : Direction.NORTH;
    }

    public record InteractionPose(
            BlockPos target,
            BlockPos stand,
            Direction face,
            boolean currentPosition,
            List<Node> path,
            double pathCost,
            int nodesExplored
    ) {
        public InteractionPose {
            target = target.immutable();
            stand = stand.immutable();
            path = List.copyOf(path);
            pathCost = Math.max(0.0D, pathCost);
            nodesExplored = Math.max(0, nodesExplored);
        }
    }
}
