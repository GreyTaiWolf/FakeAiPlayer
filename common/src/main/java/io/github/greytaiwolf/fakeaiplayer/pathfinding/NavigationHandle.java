package io.github.greytaiwolf.fakeaiplayer.pathfinding;

import java.util.Optional;
import net.minecraft.core.BlockPos;

/**
 * Stable request-scoped handle. Later ActionPack requests cannot overwrite its terminal result.
 * Cancellation is intentionally owned by ActionPack so a stale handle cannot cancel a successor.
 */
public final class NavigationHandle {
    private final long requestId;
    private final NavigationRequest request;
    private final BlockPos start;
    private final Authority authority;
    private NavigationState state = NavigationState.PLANNING;
    private BlockPos resolvedGoal;
    private double pathCost;
    private int routeRevision;
    private long worldVersion;
    private FailureReason failureReason = FailureReason.NONE;
    private String reason = "";
    private NavigationResult terminalResult;

    public NavigationHandle(long requestId,
                            NavigationRequest request,
                            BlockPos start,
                            long worldVersion,
                            Authority authority) {
        if (requestId <= 0L || request == null || start == null || authority == null) {
            throw new IllegalArgumentException("invalid navigation handle");
        }
        this.requestId = requestId;
        this.request = request;
        this.start = start.immutable();
        this.worldVersion = worldVersion;
        this.authority = authority;
    }

    public long requestId() {
        return requestId;
    }

    public NavigationRequest request() {
        return request;
    }

    public NavGoal requestedGoal() {
        return request.goal();
    }

    public BlockPos start() {
        return start;
    }

    public synchronized NavigationState state() {
        return state;
    }

    public synchronized BlockPos resolvedGoal() {
        return resolvedGoal;
    }

    public synchronized int routeRevision() {
        return routeRevision;
    }

    public synchronized long worldVersion() {
        return worldVersion;
    }

    public synchronized FailureReason failureReason() {
        return failureReason;
    }

    public synchronized String reason() {
        return reason;
    }

    public synchronized Optional<NavigationResult> result() {
        return Optional.ofNullable(terminalResult);
    }

    public synchronized boolean terminal() {
        return state.terminal();
    }

    public synchronized boolean publishPlanning(Authority caller,
                                                String transitionReason,
                                                long revision) {
        requireAuthority(caller);
        if (state.terminal()) {
            return false;
        }
        if (state != NavigationState.PLANNING && state != NavigationState.FOLLOWING) {
            throw new IllegalStateException("illegal navigation planning transition from " + state);
        }
        state = NavigationState.PLANNING;
        reason = transitionReason == null ? "" : transitionReason;
        worldVersion = revision;
        return true;
    }

    public synchronized boolean publishFollowing(Authority caller,
                                                 BlockPos endpoint,
                                                 double cost,
                                                 long revision,
                                                 boolean replan) {
        requireAuthority(caller);
        if (state.terminal()) {
            return false;
        }
        if (state != NavigationState.PLANNING && state != NavigationState.FOLLOWING) {
            throw new IllegalStateException("illegal navigation following transition from " + state);
        }
        state = NavigationState.FOLLOWING;
        resolvedGoal = endpoint == null ? null : endpoint.immutable();
        pathCost = Math.max(0.0D, cost);
        worldVersion = revision;
        reason = "";
        failureReason = FailureReason.NONE;
        if (replan) {
            routeRevision++;
        }
        return true;
    }

    public synchronized boolean finish(Authority caller,
                                       NavigationState terminalState,
                                       FailureReason failure,
                                       String terminalReason,
                                       BlockPos endpoint,
                                       double cost,
                                       long revision) {
        return finish(
                caller, terminalState, failure, terminalReason, endpoint, cost, revision,
                request.unrestrictedEvidenceScope());
    }

    public synchronized boolean finish(Authority caller,
                                       NavigationState terminalState,
                                       FailureReason failure,
                                       String terminalReason,
                                       BlockPos endpoint,
                                       double cost,
                                       long revision,
                                       boolean unrestrictedEvidenceScope) {
        requireAuthority(caller);
        if (!terminalState.terminal()) {
            throw new IllegalArgumentException("navigation finish requires a terminal state");
        }
        if (state.terminal()) {
            return false;
        }
        state = terminalState;
        failureReason = failure == null ? FailureReason.NONE : failure;
        reason = terminalReason == null ? "" : terminalReason;
        resolvedGoal = endpoint == null ? resolvedGoal : endpoint.immutable();
        pathCost = Math.max(0.0D, cost);
        worldVersion = revision;
        terminalResult = new NavigationResult(
                requestId, state, request.goal(), start, resolvedGoal,
                request.traversalPolicy(), pathCost, routeRevision, worldVersion,
                failureReason, reason, unrestrictedEvidenceScope,
                request.searchMetrics().snapshot());
        return true;
    }

    private void requireAuthority(Authority caller) {
        if (caller != authority) {
            throw new SecurityException("navigation handle mutation is controller-owned");
        }
    }

    /**
     * Unforgeable-by-identity controller token. ActionPack never exposes its instance, so callers
     * holding a handle can observe it but cannot manufacture terminal outcomes or cancel successors.
     */
    public static final class Authority {
    }
}
