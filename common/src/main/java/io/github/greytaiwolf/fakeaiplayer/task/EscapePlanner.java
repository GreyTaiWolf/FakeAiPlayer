package io.github.greytaiwolf.fakeaiplayer.task;

import io.github.greytaiwolf.fakeaiplayer.entity.AIPlayerEntity;
import io.github.greytaiwolf.fakeaiplayer.mode.ObservableWorldQuery;
import io.github.greytaiwolf.fakeaiplayer.observe.TpsGuard;
import io.github.greytaiwolf.fakeaiplayer.pathfinding.AStarPathfinder;
import io.github.greytaiwolf.fakeaiplayer.pathfinding.LocalOpenness;
import io.github.greytaiwolf.fakeaiplayer.pathfinding.Node;
import io.github.greytaiwolf.fakeaiplayer.pathfinding.PathfindingResult;
import io.github.greytaiwolf.fakeaiplayer.pathfinding.Standability;
import io.github.greytaiwolf.fakeaiplayer.pathfinding.TraversalPolicy;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.monster.Skeleton;
import net.minecraft.world.phys.Vec3;

/**
 * Finds a reachable escape route without granting any world-edit capability.
 *
 * <p>The planner evaluates several directions against every currently reachable hostile,
 * verifies each candidate with walk-only A*, and prefers routes that gain separation without
 * briefly running closer to another threat. Search work is bounded by both per-candidate and
 * global deadlines because this runs on the server thread.</p>
 */
final class EscapePlanner {
    private static final double SEARCH_RANGE = 22.0D;
    private static final double[] ANGLES_DEGREES = {0.0D, 35.0D, -35.0D, 70.0D, -70.0D, 110.0D, -110.0D, 180.0D};
    private static final double[] DISTANCES = {20.0D, 19.0D, 19.0D, 17.0D, 17.0D, 15.0D, 15.0D, 12.0D};
    private static final int CANDIDATE_RADIUS = 4;
    private static final int NORMAL_MAX_SEARCH_NODES = 1_800;
    private static final int DEGRADED_MAX_SEARCH_NODES = 700;
    private static final long NORMAL_PER_CANDIDATE_MILLIS = 4L;
    private static final long DEGRADED_PER_CANDIDATE_MILLIS = 2L;
    private static final long NORMAL_GLOBAL_BUDGET_NANOS = 16_000_000L;
    private static final long DEGRADED_GLOBAL_BUDGET_NANOS = 6_000_000L;

    private EscapePlanner() {
    }

    static Optional<Plan> plan(AIPlayerEntity bot, Threat primaryThreat, Set<BlockPos> rejectedGoals) {
        Standability.clearCache();
        List<Hazard> hazards = collectHazards(bot, primaryThreat);
        Vec3 preferredAway = preferredAway(bot, primaryThreat, hazards);
        BlockPos start = bot.blockPosition();
        double startDistance = hazards.isEmpty() ? 0.0D : nearestHazardDistance(start, hazards);
        boolean degraded = TpsGuard.INSTANCE.degraded(bot.getServer());
        int maxSearchNodes = degraded ? DEGRADED_MAX_SEARCH_NODES : NORMAL_MAX_SEARCH_NODES;
        long perCandidateMillis = degraded
                ? DEGRADED_PER_CANDIDATE_MILLIS : NORMAL_PER_CANDIDATE_MILLIS;
        long globalBudgetNanos = degraded
                ? DEGRADED_GLOBAL_BUDGET_NANOS : NORMAL_GLOBAL_BUDGET_NANOS;
        long deadline = System.nanoTime() + globalBudgetNanos;

        Plan bestSafe = null;
        Plan bestImproving = null;
        Plan bestClosedImproving = null;
        Set<BlockPos> seenGoals = new HashSet<>();
        for (int i = 0; i < ANGLES_DEGREES.length; i++) {
            if (System.nanoTime() >= deadline) {
                break;
            }
            Vec3 direction = rotate(preferredAway, ANGLES_DEGREES[i]);
            BlockPos requested = BlockPos.containing(bot.position().add(direction.scale(DISTANCES[i])));
            Optional<BlockPos> openStandable = Standability.findNearestStandable(
                    bot.serverLevel(), requested, CANDIDATE_RADIUS, 4, 4,
                    TraversalPolicy.ESCAPE_DRY_OPEN,
                    candidate -> LocalOpenness.isOpen(
                            bot.serverLevel(), candidate, TraversalPolicy.ESCAPE_DRY_OPEN));
            boolean openGoal = openStandable.isPresent();
            Optional<BlockPos> standable = openGoal ? openStandable : Standability.findNearestStandable(
                    bot.serverLevel(), requested, CANDIDATE_RADIUS, 4, 4,
                    TraversalPolicy.TASK_WALK_DRY);
            if (standable.isEmpty()) {
                continue;
            }
            BlockPos goal = standable.get().immutable();
            if (!seenGoals.add(goal) || start.closerThan(goal, 5.0D) || nearRejected(goal, rejectedGoals)) {
                continue;
            }

            long remainingNanos = deadline - System.nanoTime();
            if (remainingNanos <= 0L) {
                break;
            }
            long remainingMillis = Math.max(1L, (remainingNanos + 999_999L) / 1_000_000L);
            long searchMillis = Math.min(perCandidateMillis, remainingMillis);
            TraversalPolicy routePolicy = openGoal
                    ? TraversalPolicy.ESCAPE_DRY_OPEN : TraversalPolicy.TASK_WALK_DRY;
            PathfindingResult result = new AStarPathfinder(
                    bot.serverLevel(), start, goal,
                    maxSearchNodes, searchMillis,
                    routePolicy, 1.35D).findPath();
            if (!result.success() || result.path().size() < 2) {
                continue;
            }

            Metrics metrics = metrics(bot, result.path(), hazards, preferredAway);
            double score = ThreatResponsePolicy.escapeScore(
                    startDistance,
                    metrics.endDistance(),
                    metrics.midRouteDistance(),
                    metrics.exits(),
                    result.path().size(),
                    metrics.alignment(),
                    metrics.dangerSteps());
            boolean terminalSafe = openGoal && (hazards.isEmpty() || ThreatResponsePolicy.materiallySafer(
                    startDistance,
                    metrics.endDistance(),
                    metrics.midRouteDistance(),
                    metrics.dangerSteps(),
                    result.path().size()));
            BlockPos resolvedGoal = result.path().get(result.path().size() - 1).pos();
            Plan candidate = new Plan(resolvedGoal, result.path(), score, terminalSafe, openGoal,
                    startDistance, metrics.endDistance());
            if (terminalSafe && (bestSafe == null || candidate.score() > bestSafe.score())) {
                bestSafe = candidate;
            } else if (openGoal && !terminalSafe
                    && ThreatResponsePolicy.meaningfullyImproves(
                    startDistance, metrics.endDistance(), metrics.midRouteDistance())
                    && (bestImproving == null || candidate.score() > bestImproving.score())) {
                bestImproving = candidate;
            } else if (!openGoal
                    && ThreatResponsePolicy.meaningfullyImproves(
                    startDistance, metrics.endDistance(), metrics.midRouteDistance())
                    && (bestClosedImproving == null || candidate.score() > bestClosedImproving.score())) {
                bestClosedImproving = candidate;
            }
        }
        return Optional.ofNullable(bestSafe != null ? bestSafe
                : bestImproving != null ? bestImproving : bestClosedImproving);
    }

    private static List<Hazard> collectHazards(AIPlayerEntity bot, Threat primaryThreat) {
        List<Hazard> hazards = new ArrayList<>();
        Set<java.util.UUID> seen = new HashSet<>();
        List<LivingEntity> hostiles = bot.serverLevel().getEntitiesOfClass(
                LivingEntity.class,
                bot.getBoundingBox().inflate(SEARCH_RANGE),
                entity -> entity instanceof Monster && entity.isAlive() && entity != bot);
        for (LivingEntity hostile : hostiles) {
            if (!ObservableWorldQuery.canObserveEntity(bot, hostile)
                    || !CombatCore.hasLineOfSight(bot, hostile)) {
                continue;
            }
            seen.add(hostile.getUUID());
            hazards.add(new Hazard(hostile.position(), hazardWeight(hostile)));
        }
        LivingEntity primary = primaryThreat.entity();
        if (primary != null
                && primary.isAlive()
                && ObservableWorldQuery.canObserveEntity(bot, primary)
                && CombatCore.hasLineOfSight(bot, primary)
                && seen.add(primary.getUUID())) {
            hazards.add(new Hazard(primary.position(), hazardWeight(primary)));
        } else if (hazards.isEmpty() && primaryThreat.pos() != null) {
            // Keep a weak last-known-position hint without letting a mob behind a wall dominate
            // other currently reachable threats. Environmental hazards retain the stronger weight.
            double rememberedWeight = primary == null ? 1.25D : 0.65D;
            hazards.add(new Hazard(Vec3.atCenterOf(primaryThreat.pos()), rememberedWeight));
        }
        return hazards;
    }

    private static double hazardWeight(LivingEntity entity) {
        if (entity instanceof Creeper) {
            return 1.55D;
        }
        if (entity instanceof Skeleton) {
            return 1.25D;
        }
        return 1.0D;
    }

    private static Vec3 preferredAway(AIPlayerEntity bot, Threat threat, List<Hazard> hazards) {
        Vec3 away = Vec3.ZERO;
        for (Hazard hazard : hazards) {
            Vec3 delta = bot.position().subtract(hazard.position());
            double distanceSquared = Math.max(1.0D, delta.lengthSqr());
            if (delta.lengthSqr() > 0.0001D) {
                away = away.add(delta.normalize().scale(hazard.weight() / distanceSquared));
            }
        }
        if (away.lengthSqr() < 0.0001D && threat.pos() != null) {
            away = bot.position().subtract(Vec3.atCenterOf(threat.pos()));
        }
        if (away.lengthSqr() < 0.0001D) {
            away = new Vec3(1.0D, 0.0D, 0.0D);
        }
        return new Vec3(away.x, 0.0D, away.z).normalize();
    }

    private static Metrics metrics(AIPlayerEntity bot, List<Node> path, List<Hazard> hazards, Vec3 preferredAway) {
        if (hazards.isEmpty()) {
            BlockPos start = path.get(0).pos();
            BlockPos end = path.get(path.size() - 1).pos();
            return new Metrics(0.0D, 0.0D,
                    countExits(bot, end), alignment(start, end, preferredAway), 0);
        }
        int midStart = Math.max(1, path.size() / 3);
        double midRouteDistance = Double.POSITIVE_INFINITY;
        int dangerSteps = 0;
        for (int i = 1; i < path.size(); i++) {
            double distance = nearestHazardDistance(path.get(i).pos(), hazards);
            if (i >= midStart) {
                midRouteDistance = Math.min(midRouteDistance, distance);
            }
            if (distance < 4.5D) {
                dangerSteps++;
            }
        }
        BlockPos start = path.get(0).pos();
        BlockPos end = path.get(path.size() - 1).pos();
        return new Metrics(
                nearestHazardDistance(end, hazards),
                midRouteDistance,
                countExits(bot, end),
                alignment(start, end, preferredAway),
                dangerSteps);
    }

    private static int countExits(AIPlayerEntity bot, BlockPos goal) {
        return LocalOpenness.analyze(
                bot.serverLevel(), goal, TraversalPolicy.ESCAPE_DRY_OPEN,
                LocalOpenness.DEFAULT_RADIUS).exitDirections().size();
    }

    private static double nearestHazardDistance(BlockPos pos, List<Hazard> hazards) {
        if (hazards.isEmpty()) {
            return Double.POSITIVE_INFINITY;
        }
        double best = Double.POSITIVE_INFINITY;
        Vec3 center = Vec3.atCenterOf(pos);
        for (Hazard hazard : hazards) {
            best = Math.min(best, center.distanceTo(hazard.position()) / hazard.weight());
        }
        return best;
    }

    private static double alignment(BlockPos start, BlockPos end, Vec3 preferredAway) {
        Vec3 travel = Vec3.atCenterOf(end).subtract(Vec3.atCenterOf(start));
        if (travel.lengthSqr() < 0.0001D) {
            return -1.0D;
        }
        return travel.normalize().dot(preferredAway);
    }

    private static boolean nearRejected(BlockPos goal, Set<BlockPos> rejectedGoals) {
        if (rejectedGoals == null || rejectedGoals.isEmpty()) {
            return false;
        }
        for (BlockPos rejected : rejectedGoals) {
            if (rejected.closerThan(goal, 3.0D)) {
                return true;
            }
        }
        return false;
    }

    private static Vec3 rotate(Vec3 vector, double degrees) {
        double radians = Math.toRadians(degrees);
        double cos = Math.cos(radians);
        double sin = Math.sin(radians);
        return new Vec3(
                vector.x * cos - vector.z * sin,
                0.0D,
                vector.x * sin + vector.z * cos).normalize();
    }

    record Plan(BlockPos goal,
                List<Node> path,
                double score,
                boolean terminalSafe,
                boolean openGoal,
                double startDistance,
                double endDistance) {
        Plan {
            goal = goal.immutable();
            path = List.copyOf(path);
        }
    }

    private record Hazard(Vec3 position, double weight) {
    }

    private record Metrics(double endDistance,
                           double midRouteDistance,
                           int exits,
                           double alignment,
                           int dangerSteps) {
    }
}
