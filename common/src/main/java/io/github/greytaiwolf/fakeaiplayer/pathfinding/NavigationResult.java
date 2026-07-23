package io.github.greytaiwolf.fakeaiplayer.pathfinding;

import net.minecraft.core.BlockPos;

/** Permanent terminal receipt for a single logical navigation request. */
public record NavigationResult(
        long requestId,
        NavigationState state,
        NavGoal requestedGoal,
        BlockPos start,
        BlockPos resolvedGoal,
        TraversalPolicy traversalPolicy,
        double pathCost,
        int routeRevision,
        long worldVersion,
        FailureReason failureReason,
        String reason,
        boolean unrestrictedEvidenceScope,
        NavigationSearchMetrics.Snapshot searchMetrics
) {
    public NavigationResult {
        if (requestId <= 0L || state == null || !state.terminal() || requestedGoal == null) {
            throw new IllegalArgumentException("terminal navigation result is incomplete");
        }
        start = start == null ? null : start.immutable();
        resolvedGoal = resolvedGoal == null ? null : resolvedGoal.immutable();
        failureReason = failureReason == null ? FailureReason.NONE : failureReason;
        reason = reason == null ? "" : reason;
        pathCost = Math.max(0.0D, pathCost);
        routeRevision = Math.max(0, routeRevision);
        searchMetrics = searchMetrics == null
                ? new NavigationSearchMetrics().snapshot() : searchMetrics;
    }

    public boolean succeeded() {
        return state == NavigationState.ARRIVED;
    }

    /** Whether this outcome is inconclusive and therefore cannot justify no-path memory. */
    public boolean excludesUnreachableMemory() {
        return !NavigationOutcomePolicy.mayRecordUnreachable(this);
    }

    /** Whether the task scheduler may retry without an explicit caller/user decision. */
    public boolean mayRetryAutomatically() {
        return state == NavigationState.PREEMPTED
                || state == NavigationState.STALE_WORLD
                || failureReason == FailureReason.SEARCH_LIMIT
                || failureReason == FailureReason.SEARCH_BUDGET
                || failureReason == FailureReason.TIMEOUT;
    }

    /** Compatibility name retained for callers that only classify memory evidence. */
    public boolean retryableWithoutUnreachableMemory() {
        return excludesUnreachableMemory();
    }
}
