package io.github.greytaiwolf.fakeaiplayer.pathfinding;

import java.util.List;
import net.minecraft.core.BlockPos;

public record PathfindingResult(
        List<Node> path,
        boolean success,
        FailureReason reason,
        int nodesExplored,
        long elapsedMs,
        BlockPos resolvedStart,
        BlockPos resolvedGoal,
        double pathCost,
        long worldVersion,
        String goalFingerprint,
        boolean cacheHit
) {
    public PathfindingResult {
        path = path == null ? List.of() : List.copyOf(path);
        reason = reason == null ? FailureReason.NONE : reason;
        resolvedStart = resolvedStart == null ? null : resolvedStart.immutable();
        resolvedGoal = resolvedGoal == null ? null : resolvedGoal.immutable();
        pathCost = Math.max(0.0D, pathCost);
        goalFingerprint = goalFingerprint == null ? "" : goalFingerprint;
    }

    public static PathfindingResult success(List<Node> path, int nodesExplored, long elapsedMs) {
        return success(path, nodesExplored, elapsedMs, 0L, "", false);
    }

    public static PathfindingResult success(List<Node> path,
                                            int nodesExplored,
                                            long elapsedMs,
                                            long worldVersion,
                                            String goalFingerprint,
                                            boolean cacheHit) {
        List<Node> copy = List.copyOf(path);
        BlockPos resolvedStart = copy.isEmpty() ? null : copy.get(0).pos();
        BlockPos resolvedGoal = copy.isEmpty() ? null : copy.get(copy.size() - 1).pos();
        double pathCost = copy.isEmpty() ? 0.0D : copy.get(copy.size() - 1).gCost();
        return new PathfindingResult(
                copy, true, FailureReason.NONE, nodesExplored, elapsedMs,
                resolvedStart, resolvedGoal, pathCost, worldVersion, goalFingerprint, cacheHit);
    }

    public static PathfindingResult failure(FailureReason reason, int nodesExplored, long elapsedMs) {
        return failure(reason, nodesExplored, elapsedMs, 0L, "");
    }

    public static PathfindingResult failure(FailureReason reason,
                                            int nodesExplored,
                                            long elapsedMs,
                                            long worldVersion,
                                            String goalFingerprint) {
        return new PathfindingResult(
                List.of(), false, reason, nodesExplored, elapsedMs, null, null,
                0.0D, worldVersion, goalFingerprint, false);
    }
}
