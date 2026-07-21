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
     * Plans while retaining only poses whose complete reviewed path satisfies a caller-specific
     * invariant. Tree felling uses this to continue past a cheap canopy-supported route and still
     * consider a slightly longer ground-supported alternative.
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
        BlockPos immutableTarget = target.immutable();
        BlockPos current = bot.blockPosition();
        int targetSearchLimit = Math.max(1, maxSearchesForTarget);
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
                && canInteractFrom(bot, current, immutableTarget, reach)) {
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

        List<InteractionPose> candidates = new ArrayList<>();
        int searchesForTarget = 0;
        for (BlockPos stand : candidateStands(immutableTarget, current)) {
            if (excludedStands.contains(stand)
                    || pathExclusions.contains(stand)
                    || !pathPositionConstraint.test(stand)
                    || !Standability.isStandable(
                    bot.serverLevel(), stand, TraversalPolicy.TASK_WALK_DRY)
                    || !canInteractFrom(bot, stand, immutableTarget, reach)) {
                continue;
            }
            if (searchesForTarget >= targetSearchLimit) {
                budget.markInconclusive();
                break;
            }
            if (!budget.tryAcquireSearch()) {
                break;
            }
            searchesForTarget++;
            PathfindingResult path = new AStarPathfinder(
                    bot.serverLevel(),
                    current,
                    stand,
                    POSE_MAX_NODES,
                    Math.min(POSE_MAX_MILLIS, budget.remainingMillis()),
                    false,
                    false,
                    TraversalPolicy.TASK_WALK_DRY,
                    1.0D,
                    pathExclusions,
                    TraversalBounds.unbounded(),
                    pathPositionConstraint).findPath();
            if (!path.success() || path.path().isEmpty()) {
                if (path.reason() == FailureReason.TIMEOUT
                        || path.reason() == FailureReason.SEARCH_LIMIT) {
                    budget.markInconclusive();
                }
                continue;
            }
            if (!path.path().get(0).pos().equals(current)
                    || !path.path().get(path.path().size() - 1).pos().equals(stand)) {
                // A work pose is an exact reviewed stance. Endpoint snapping is appropriate for
                // generic movement but cannot be relabeled as this candidate pose.
                budget.markInconclusive();
                continue;
            }
            double cost = path.path().get(path.path().size() - 1).gCost();
            InteractionPose candidate = new InteractionPose(
                    immutableTarget,
                    stand,
                    faceFrom(stand, immutableTarget),
                    false,
                    path.path(),
                    cost,
                    path.nodesExplored());
            if (poseConstraint.test(candidate)) {
                candidates.add(candidate);
            }
        }
        return candidates.stream()
                .min(Comparator.comparingDouble(InteractionPose::pathCost)
                        .thenComparingDouble(pose -> pose.stand().distSqr(current))
                        .thenComparingLong(pose -> pose.stand().asLong()));
    }

    private static List<BlockPos> candidateStands(BlockPos target, BlockPos current) {
        Set<BlockPos> positions = new LinkedHashSet<>();
        int[] verticalOffsets = {0, -1, 1, -2, 2};
        for (int dy : verticalOffsets) {
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
                for (int dy : new int[]{0, -1, 1}) {
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
    }

    public static boolean canInteractFromCurrent(AIPlayerEntity bot, BlockPos target) {
        double reach = bot.getAttributeValue(Attributes.BLOCK_INTERACTION_RANGE) + 0.5D;
        return Standability.isStandableFresh(
                bot.serverLevel(), bot.blockPosition(), TraversalPolicy.TASK_WALK_DRY)
                && canInteract(bot, bot.getEyePosition(), target, reach);
    }

    public static boolean canInteractFrom(
            AIPlayerEntity bot, BlockPos stand, BlockPos target, double reach) {
        Vec3 eye = new Vec3(
                stand.getX() + 0.5D,
                stand.getY() + bot.getEyeHeight(),
                stand.getZ() + 0.5D);
        return canInteract(bot, eye, target, reach);
    }

    private static boolean canInteract(
            AIPlayerEntity bot, Vec3 eye, BlockPos target, double reach) {
        Vec3 center = target.getCenter();
        if (eye.distanceTo(center) > reach) {
            return false;
        }
        BlockHitResult hit = bot.serverLevel().clip(new ClipContext(
                eye,
                center,
                ClipContext.Block.OUTLINE,
                ClipContext.Fluid.NONE,
                bot));
        if (hit.getType() == HitResult.Type.MISS) {
            // Plants and a few modded resource blocks have no outline collision. A MISS means no
            // nearer outline occluded the exact target center, so the interaction is still valid.
            return true;
        }
        return hit.getBlockPos().equals(target);
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
