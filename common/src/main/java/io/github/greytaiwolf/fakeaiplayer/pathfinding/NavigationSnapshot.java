package io.github.greytaiwolf.fakeaiplayer.pathfinding;

import net.minecraft.core.BlockPos;

/** A bounded, immutable diagnostic snapshot for the latest navigation request. */
public record NavigationSnapshot(
        long requestId,
        NavigationState state,
        BlockPos start,
        BlockPos requestedGoal,
        BlockPos resolvedGoal,
        BlockPos currentNode,
        TraversalPolicy traversalPolicy,
        int pathLength,
        int remainingNodes,
        double pathCost,
        double remainingPathCost,
        int elapsedTicks,
        int replanCount,
        double bestNodeDistance,
        int noProgressEvents,
        int oscillationEvents,
        String lastReplanReason,
        String reason
) {
    public NavigationSnapshot {
        state = state == null ? NavigationState.IDLE : state;
        start = immutable(start);
        requestedGoal = immutable(requestedGoal);
        resolvedGoal = immutable(resolvedGoal);
        currentNode = immutable(currentNode);
        pathLength = Math.max(0, pathLength);
        remainingNodes = Math.max(0, remainingNodes);
        pathCost = Math.max(0.0D, pathCost);
        remainingPathCost = Math.max(0.0D, remainingPathCost);
        elapsedTicks = Math.max(0, elapsedTicks);
        replanCount = Math.max(0, replanCount);
        noProgressEvents = Math.max(0, noProgressEvents);
        oscillationEvents = Math.max(0, oscillationEvents);
        lastReplanReason = lastReplanReason == null ? "" : lastReplanReason;
        reason = reason == null ? "" : reason;
    }

    public static NavigationSnapshot idle() {
        return new NavigationSnapshot(
                0L, NavigationState.IDLE, null, null, null, null, null,
                0, 0, 0.0D, 0.0D, 0, 0, Double.POSITIVE_INFINITY,
                0, 0, "", "");
    }

    public boolean succeeded() {
        return state == NavigationState.ARRIVED;
    }

    public boolean retryableInterruption() {
        return state == NavigationState.PREEMPTED || state == NavigationState.CANCELLED;
    }

    private static BlockPos immutable(BlockPos position) {
        return position == null ? null : position.immutable();
    }
}
