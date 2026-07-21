package io.github.greytaiwolf.fakeaiplayer.pathfinding;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

/**
 * Detects lack of progress toward a path node instead of treating any physical motion as useful.
 * This catches sidling loops and A-B oscillation while still allowing ordinary acceleration and
 * short jump arcs.
 */
final class PathProgressTracker {
    static final int DEFAULT_NO_PROGRESS_LIMIT = 40;
    static final int DEFAULT_OSCILLATION_LIMIT = 8;
    private static final double IMPROVEMENT_EPSILON = 0.04D;

    enum Stall {
        NONE,
        NO_GOAL_PROGRESS,
        OSCILLATION
    }

    private final int noProgressLimit;
    private final int oscillationLimit;
    private double bestDistance = Double.POSITIVE_INFINITY;
    private int noProgressTicks;
    private int oscillationHits;
    private BlockPos previousCell;
    private BlockPos twoCellsAgo;

    PathProgressTracker() {
        this(DEFAULT_NO_PROGRESS_LIMIT, DEFAULT_OSCILLATION_LIMIT);
    }

    PathProgressTracker(int noProgressLimit, int oscillationLimit) {
        this.noProgressLimit = Math.max(1, noProgressLimit);
        this.oscillationLimit = Math.max(1, oscillationLimit);
    }

    Stall sample(Vec3 current, Vec3 target) {
        double distance = current.distanceTo(target);
        boolean improved = distance + IMPROVEMENT_EPSILON < bestDistance;
        if (improved) {
            bestDistance = distance;
            noProgressTicks = 0;
            oscillationHits = 0;
        } else {
            noProgressTicks++;
        }

        BlockPos cell = BlockPos.containing(current);
        if (!improved && twoCellsAgo != null && cell.equals(twoCellsAgo)
                && previousCell != null && !cell.equals(previousCell)) {
            oscillationHits++;
        } else if (!improved && oscillationHits > 0) {
            oscillationHits--;
        }
        twoCellsAgo = previousCell;
        previousCell = cell.immutable();

        if (oscillationHits >= oscillationLimit) {
            return Stall.OSCILLATION;
        }
        if (noProgressTicks >= noProgressLimit) {
            return Stall.NO_GOAL_PROGRESS;
        }
        return Stall.NONE;
    }

    double bestDistance() {
        return bestDistance;
    }

    int noProgressTicks() {
        return noProgressTicks;
    }

    void reset() {
        bestDistance = Double.POSITIVE_INFINITY;
        noProgressTicks = 0;
        oscillationHits = 0;
        previousCell = null;
        twoCellsAgo = null;
    }
}
