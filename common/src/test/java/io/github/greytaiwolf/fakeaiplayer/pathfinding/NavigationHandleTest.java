package io.github.greytaiwolf.fakeaiplayer.pathfinding;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

final class NavigationHandleTest {
    private final NavigationHandle.Authority authority = new NavigationHandle.Authority();

    @Test
    void planningFollowingArrivedRetainsTerminalReceipt() {
        NavigationHandle handle = handle(1L);
        assertEquals(NavigationState.PLANNING, handle.state());
        assertTrue(handle.publishFollowing(
                authority, new BlockPos(5, 0, 0), 5.0D, 2L, false));
        assertTrue(handle.finish(
                authority,
                NavigationState.ARRIVED, FailureReason.NONE, "",
                new BlockPos(5, 0, 0), 5.0D, 2L));
        Optional<NavigationResult> result = handle.result();
        assertTrue(result.isPresent());
        assertTrue(result.orElseThrow().succeeded());
        assertEquals(5.0D, result.orElseThrow().pathCost());
    }

    @Test
    void terminalStateIsImmutableAndFinishIsIdempotent() {
        NavigationHandle handle = handle(2L);
        assertTrue(handle.finish(
                authority,
                NavigationState.CANCELLED, FailureReason.NONE, "cancelled",
                null, 0.0D, 1L));
        assertFalse(handle.finish(
                authority,
                NavigationState.ARRIVED, FailureReason.NONE, "",
                BlockPos.ZERO, 0.0D, 1L));
        assertEquals(NavigationState.CANCELLED, handle.state());
        assertEquals("cancelled", handle.result().orElseThrow().reason());
    }

    @Test
    void replanKeepsRequestIdAndIncrementsRouteRevision() {
        NavigationHandle handle = handle(3L);
        handle.publishFollowing(authority, new BlockPos(5, 0, 0), 5.0D, 1L, false);
        handle.publishPlanning(authority, "dynamic_wall", 2L);
        handle.publishFollowing(authority, new BlockPos(5, 0, 2), 7.0D, 2L, true);
        assertEquals(3L, handle.requestId());
        assertEquals(1, handle.routeRevision());
        assertEquals(new BlockPos(5, 0, 2), handle.resolvedGoal());
        assertEquals(2L, handle.worldVersion());
    }

    @Test
    void followingCanReturnToPlanningButTerminalCannot() {
        NavigationHandle handle = handle(4L);
        handle.publishFollowing(authority, new BlockPos(2, 0, 0), 2.0D, 1L, false);
        assertTrue(handle.publishPlanning(authority, "segment", 1L));
        handle.finish(
                authority,
                NavigationState.BLOCKED, FailureReason.GOAL_UNREACHABLE,
                "blocked", null, 0.0D, 1L);
        assertFalse(handle.publishPlanning(authority, "illegal", 2L));
        assertFalse(handle.publishFollowing(
                authority, BlockPos.ZERO, 0.0D, 2L, false));
    }

    @Test
    void illegalNonTerminalFinishIsRejected() {
        NavigationHandle handle = handle(5L);
        assertThrows(IllegalArgumentException.class, () -> handle.finish(
                authority,
                NavigationState.FOLLOWING, FailureReason.NONE, "",
                null, 0.0D, 0L));
    }

    @Test
    void eachHandleRetainsItsOwnResultAfterSuccessorExists() {
        NavigationHandle first = handle(6L);
        first.finish(
                authority,
                NavigationState.PREEMPTED, FailureReason.NONE, "replaced",
                new BlockPos(2, 0, 0), 2.0D, 1L);
        NavigationHandle second = handle(7L);
        second.finish(
                authority,
                NavigationState.ARRIVED, FailureReason.NONE, "",
                new BlockPos(5, 0, 0), 5.0D, 1L);
        assertEquals(NavigationState.PREEMPTED, first.result().orElseThrow().state());
        assertEquals(NavigationState.ARRIVED, second.result().orElseThrow().state());
    }

    @Test
    void searchMetricsAreCapturedInTerminalReceipt() {
        NavigationSearchMetrics metrics = new NavigationSearchMetrics();
        NavigationRequest request = new NavigationRequest(
                NavGoal.exact(new BlockPos(1, 0, 0)),
                TraversalPolicy.TASK_WALK_DRY,
                TraversalBounds.unbounded(),
                false, false, 100, 10L, 1.0D,
                java.util.Set.of(), null, "none", 16, 1, "test", metrics);
        NavigationHandle handle = new NavigationHandle(
                8L, request, BlockPos.ZERO, 1L, authority);
        metrics.frontierStarted();
        metrics.searchCompleted(PathfindingResult.failure(
                FailureReason.SEARCH_LIMIT, 7, 2L, 1L, "goal"), 3L);
        handle.finish(
                authority,
                NavigationState.FAILED, FailureReason.SEARCH_LIMIT, "limit",
                null, 0.0D, 1L);
        NavigationSearchMetrics.Snapshot receipt =
                handle.result().orElseThrow().searchMetrics();
        assertEquals(1, receipt.frontiersStarted());
        assertEquals(7, receipt.nodesExplored());
        metrics.frontierStarted();
        assertEquals(1, receipt.frontiersStarted(), "terminal receipt mutated with live metrics");
    }

    @Test
    void observerCannotMutateHandleWithAnotherAuthority() {
        NavigationHandle handle = handle(9L);
        NavigationHandle.Authority stranger = new NavigationHandle.Authority();
        assertThrows(SecurityException.class, () -> handle.publishFollowing(
                stranger, BlockPos.ZERO, 0.0D, 1L, false));
        assertEquals(NavigationState.PLANNING, handle.state());
    }

    @Test
    void terminalReceiptCanNarrowEvidenceToActualReplanScope() {
        NavigationHandle handle = handle(10L);
        assertTrue(handle.finish(
                authority,
                NavigationState.BLOCKED,
                FailureReason.GOAL_UNREACHABLE,
                "transient_exclusion",
                null,
                0.0D,
                1L,
                false));
        assertFalse(handle.result().orElseThrow().unrestrictedEvidenceScope());
    }

    private NavigationHandle handle(long requestId) {
        return new NavigationHandle(
                requestId,
                NavigationRequest.walk(
                        NavGoal.exact(new BlockPos(5, 0, 0)), "unit_test"),
                BlockPos.ZERO,
                1L,
                authority);
    }
}
