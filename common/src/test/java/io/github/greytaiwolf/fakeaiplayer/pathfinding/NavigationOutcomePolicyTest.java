package io.github.greytaiwolf.fakeaiplayer.pathfinding;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

final class NavigationOutcomePolicyTest {
    @Test
    void onlyCurrentRevisionUnreachableMayEnterMemory() {
        assertTrue(NavigationOutcomePolicy.mayRecordUnreachable(
                result(NavigationState.BLOCKED, FailureReason.GOAL_UNREACHABLE), 1L));
        assertFalse(NavigationOutcomePolicy.mayRecordUnreachable(
                result(NavigationState.BLOCKED, FailureReason.GOAL_UNREACHABLE), 2L));
    }

    @Test
    void callerConstrainedSearchIsNotGlobalUnreachableEvidence() {
        assertFalse(NavigationOutcomePolicy.mayRecordUnreachable(
                result(
                        NavigationState.BLOCKED,
                        FailureReason.GOAL_UNREACHABLE,
                        false),
                1L));
    }

    @Test
    void dynamicGoalFailureIsNotStableUnreachableEvidence() {
        NavigationResult dynamic = new NavigationResult(
                1L, NavigationState.BLOCKED,
                new NavGoal.FollowRing(
                        java.util.UUID.randomUUID(), BlockPos.ZERO, 2, 4),
                BlockPos.ZERO, null, TraversalPolicy.TASK_WALK_DRY,
                0.0D, 0, 1L, FailureReason.GOAL_UNREACHABLE,
                "dynamic_target", true, new NavigationSearchMetrics().snapshot());
        assertFalse(NavigationOutcomePolicy.mayRecordUnreachable(dynamic, 1L));
    }

    @Test
    void stalePreemptedAndCancelledNeverEnterUnreachableMemory() {
        assertFalse(NavigationOutcomePolicy.mayRecordUnreachable(
                result(NavigationState.STALE_WORLD, FailureReason.STALE_WORLD)));
        assertFalse(NavigationOutcomePolicy.mayRecordUnreachable(
                result(NavigationState.PREEMPTED, FailureReason.NONE)));
        assertFalse(NavigationOutcomePolicy.mayRecordUnreachable(
                result(NavigationState.CANCELLED, FailureReason.NONE)));
    }

    @Test
    void exhaustedSearchIsInconclusiveNotUnreachable() {
        assertFalse(NavigationOutcomePolicy.mayRecordUnreachable(
                result(NavigationState.FAILED, FailureReason.SEARCH_LIMIT)));
        assertFalse(NavigationOutcomePolicy.mayRecordUnreachable(
                result(NavigationState.FAILED, FailureReason.TIMEOUT)));
        assertFalse(NavigationOutcomePolicy.mayRecordUnreachable(
                result(NavigationState.FAILED, FailureReason.SEARCH_BUDGET)));
    }

    @Test
    void retryableClassificationIncludesEveryNonEvidenceOutcome() {
        assertTrue(result(NavigationState.STALE_WORLD, FailureReason.STALE_WORLD)
                .mayRetryAutomatically());
        assertTrue(result(NavigationState.FAILED, FailureReason.TIMEOUT)
                .mayRetryAutomatically());
        assertFalse(result(NavigationState.CANCELLED, FailureReason.NONE)
                .mayRetryAutomatically());
        assertTrue(result(NavigationState.CANCELLED, FailureReason.NONE)
                .excludesUnreachableMemory());
        assertFalse(result(NavigationState.BLOCKED, FailureReason.GOAL_UNREACHABLE)
                .mayRetryAutomatically());
        assertTrue(result(NavigationState.ARRIVED, FailureReason.NONE)
                .excludesUnreachableMemory());
        assertTrue(result(NavigationState.BLOCKED, FailureReason.PATH_BLOCKED)
                .excludesUnreachableMemory());
    }

    private static NavigationResult result(NavigationState state, FailureReason reason) {
        return result(state, reason, true);
    }

    private static NavigationResult result(NavigationState state,
                                           FailureReason reason,
                                           boolean unrestrictedEvidence) {
        return new NavigationResult(
                1L, state, NavGoal.exact(new BlockPos(1, 0, 0)), BlockPos.ZERO,
                null, TraversalPolicy.TASK_WALK_DRY, 0.0D, 0, 1L,
                reason, reason.name(), unrestrictedEvidence,
                new NavigationSearchMetrics().snapshot());
    }
}
