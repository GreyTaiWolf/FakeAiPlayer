package io.github.greytaiwolf.fakeaiplayer.mission;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlanCursorTest {
    @Test
    void sequenceAdvancesOneSkillAtATimeInDeclaredOrder() {
        PlanCursor cursor = plan(new PlanNode.Sequence(List.of(
                node("first"),
                node("second")))).cursor(10);

        assertReady(cursor.snapshot(), "first", 1);
        assertReady(completeReady(cursor, "first", succeeded(), 11), "second", 1);

        PlanCursor.Snapshot complete = completeReady(cursor, "second", succeeded(), 12);
        assertEquals(PlanCursor.State.SUCCEEDED, complete.state());
        assertTrue(complete.terminal());
        assertTrue(complete.readySkills().isEmpty());
    }

    @Test
    void allOfUsesDeterministicDeclaredOrderAndFailsFast() {
        PlanCursor cursor = plan(new PlanNode.AllOf(List.of(
                node("wood"),
                node("stone"),
                node("iron")))).cursor(0);

        assertReady(cursor.snapshot(), "wood", 1);
        assertReady(completeReady(cursor, "wood", succeeded(), 1), "stone", 1);

        PlanCursor.Snapshot failed = completeReady(cursor,
                "stone",
                failure(SkillOutcome.Status.BLOCKED,
                        SkillOutcome.FailureKind.RESOURCE_UNAVAILABLE),
                2);

        assertEquals(PlanCursor.State.FAILED, failed.state());
        assertEquals(SkillOutcome.FailureKind.RESOURCE_UNAVAILABLE,
                failed.failure().orElseThrow().outcome().failureKind());
        assertEquals("root/1", failed.failure().orElseThrow().nodePath());
        assertTrue(failed.readySkills().isEmpty());

        PlanCursor successful = plan(new PlanNode.AllOf(List.of(
                node("left"), node("right")))).cursor(0);
        completeReady(successful, "left", succeeded(), 1);
        assertEquals(PlanCursor.State.SUCCEEDED,
                completeReady(successful, "right", succeeded(), 2).state());
    }

    @Test
    void anyOfFallsBackInDeclaredOrderUntilOneBranchSucceeds() {
        PlanCursor cursor = plan(new PlanNode.AnyOf(List.of(
                node("inventory"),
                node("known_world_site"),
                node("explore")))).cursor(0);

        assertReady(cursor.snapshot(), "inventory", 1);
        assertReady(completeReady(cursor,
                "inventory",
                failure(SkillOutcome.Status.BLOCKED,
                        SkillOutcome.FailureKind.RESOURCE_UNAVAILABLE),
                1), "known_world_site", 1);
        assertReady(completeReady(cursor,
                "known_world_site",
                retryable(SkillOutcome.FailureKind.WORLD_CHANGED),
                2), "explore", 1);

        assertEquals(PlanCursor.State.SUCCEEDED,
                completeReady(cursor, "explore", succeeded(), 3).state());
    }

    @Test
    void anyOfNeverHidesFatalCancellationOrSafetyPreemption() {
        List<SkillOutcome> controlOutcomes = List.of(
                failure(SkillOutcome.Status.FATAL_FAILURE,
                        SkillOutcome.FailureKind.INTERNAL),
                SkillOutcome.cancelled("player_cancelled", 0),
                SkillOutcome.preempted("safety_reflex", 0),
                retryable(SkillOutcome.FailureKind.SAFETY));

        for (SkillOutcome control : controlOutcomes) {
            PlanCursor cursor = plan(new PlanNode.AnyOf(List.of(
                    node("first"), node("must_not_start")))).cursor(0);

            PlanCursor.Snapshot result = completeReady(cursor, "first", control, 1);

            assertEquals(PlanCursor.State.FAILED, result.state());
            assertEquals(control.status(), result.failure().orElseThrow().outcome().status());
            assertTrue(result.readySkills().isEmpty());
        }
    }

    @Test
    void anyOfReportsTheLastFailureAfterAllCandidatesFail() {
        PlanCursor cursor = plan(new PlanNode.AnyOf(List.of(
                node("first"),
                node("last")))).cursor(0);

        completeReady(cursor, "first", retryable(SkillOutcome.FailureKind.PATH_UNREACHABLE), 1);
        PlanCursor.Snapshot failed = completeReady(cursor,
                "last",
                failure(SkillOutcome.Status.BLOCKED,
                        SkillOutcome.FailureKind.RESOURCE_UNAVAILABLE),
                2);

        assertEquals(PlanCursor.State.FAILED, failed.state());
        assertEquals(SkillOutcome.FailureKind.RESOURCE_UNAVAILABLE,
                failed.failure().orElseThrow().outcome().failureKind());
        assertEquals("root/1", failed.failure().orElseThrow().nodePath());
    }

    @Test
    void retryReactivatesAWhitelistedFailureAndCanSucceed() {
        PlanCursor cursor = plan(new PlanNode.Retry(
                node("mine"),
                3,
                Set.of(SkillOutcome.FailureKind.PATH_UNREACHABLE))).cursor(0);

        assertReady(cursor.snapshot(), "mine", 1);
        assertReady(completeReady(cursor,
                "mine",
                retryable(SkillOutcome.FailureKind.PATH_UNREACHABLE),
                1), "mine", 2);

        assertEquals(PlanCursor.State.SUCCEEDED,
                completeReady(cursor, "mine", succeeded(), 2).state());
    }

    @Test
    void retryRejectsATypeOutsideItsWhitelist() {
        PlanCursor cursor = plan(new PlanNode.Retry(
                node("mine"),
                3,
                Set.of(SkillOutcome.FailureKind.PATH_UNREACHABLE))).cursor(0);

        PlanCursor.Snapshot failed = completeReady(cursor,
                "mine",
                retryable(SkillOutcome.FailureKind.WORLD_CHANGED),
                1);
        assertEquals(PlanCursor.State.FAILED, failed.state());
        assertEquals(SkillOutcome.FailureKind.WORLD_CHANGED,
                failed.failure().orElseThrow().outcome().failureKind());
    }

    @Test
    void retryStopsAtItsAttemptBudget() {
        PlanCursor cursor = plan(new PlanNode.Retry(
                node("mine"),
                2,
                Set.of(SkillOutcome.FailureKind.PATH_UNREACHABLE))).cursor(0);

        assertReady(completeReady(cursor,
                "mine", retryable(SkillOutcome.FailureKind.PATH_UNREACHABLE), 1),
                "mine", 2);
        PlanCursor.Snapshot failed = completeReady(cursor,
                "mine", retryable(SkillOutcome.FailureKind.PATH_UNREACHABLE), 2);

        assertEquals(PlanCursor.State.FAILED, failed.state());
        assertFalse(failed.failure().isEmpty());
    }

    @Test
    void retryNeverRestartsImmediatelyAfterSafetyOutcome() {
        List<SkillOutcome> safetyOutcomes = List.of(
                SkillOutcome.preempted("safety_reflex", 0),
                retryable(SkillOutcome.FailureKind.SAFETY));

        for (SkillOutcome safety : safetyOutcomes) {
            PlanCursor cursor = plan(new PlanNode.Retry(
                    node("mine"),
                    3,
                    Set.of(SkillOutcome.FailureKind.PATH_UNREACHABLE))).cursor(0);

            PlanCursor.Snapshot interrupted = completeReady(cursor, "mine", safety, 1);

            assertEquals(PlanCursor.State.FAILED, interrupted.state());
            assertEquals(safety.status(),
                    interrupted.failure().orElseThrow().outcome().status());
            assertEquals(SkillOutcome.FailureKind.SAFETY,
                    interrupted.failure().orElseThrow().outcome().failureKind());
            assertTrue(interrupted.readySkills().isEmpty());
        }
    }

    @Test
    void timeoutWinsAtTheExactDeadlineAndRejectsLateCompletion() {
        PlanCursor cursor = plan(new PlanNode.Timeout(node("travel"), 5)).cursor(10);
        PlanCursor.ActivationLease lease = ready(cursor, "travel").lease();

        assertReady(cursor.advanceTo(14), "travel", 1);
        PlanCursor.Snapshot expired = cursor.advanceTo(15);

        assertEquals(PlanCursor.State.FAILED, expired.state());
        assertEquals(SkillOutcome.FailureKind.TIMEOUT,
                expired.failure().orElseThrow().outcome().failureKind());
        assertEquals("root", expired.failure().orElseThrow().nodePath());
        assertTrue(expired.readySkills().isEmpty());
        assertEquals(expired, cursor.completeSkill(lease, succeeded(), 15));
    }

    @Test
    void checkedCompletionDistinguishesConsumedOutcomeFromTimeoutBranchSwitch() {
        PlanCursor cursor = plan(new PlanNode.AnyOf(List.of(
                new PlanNode.Timeout(node("primary"), 5),
                node("fallback")))).cursor(0);
        PlanCursor.ActivationLease expired = ready(cursor, "primary").lease();

        PlanCursor.Completion rejected = cursor.tryCompleteSkill(expired, succeeded(), 5);

        assertFalse(rejected.accepted());
        assertReady(rejected.snapshot(), "fallback", 1);
        PlanCursor.Completion accepted = cursor.tryCompleteSkill(
                ready(cursor, "fallback").lease(), succeeded(), 6);
        assertTrue(accepted.accepted());
        assertEquals(PlanCursor.State.SUCCEEDED, accepted.snapshot().state());
    }

    @Test
    void completionAtTheExactDeadlineReturnsTypedTimeoutInsteadOfThrowing() {
        PlanCursor cursor = plan(new PlanNode.Timeout(node("travel"), 5)).cursor(10);

        PlanCursor.Snapshot expired = completeReady(cursor, "travel", succeeded(), 15);

        assertEquals(PlanCursor.State.FAILED, expired.state());
        assertEquals(SkillOutcome.Status.RETRYABLE_FAILURE,
                expired.failure().orElseThrow().outcome().status());
        assertEquals(SkillOutcome.FailureKind.TIMEOUT,
                expired.failure().orElseThrow().outcome().failureKind());
        assertEquals("root", expired.failure().orElseThrow().nodePath());
    }

    @Test
    void eventAtTheExactDeadlineReturnsTypedTimeoutInsteadOfRevivingWaiter() {
        PlanCursor cursor = plan(new PlanNode.Timeout(
                new PlanNode.WaitForEvent("door.open"), 5)).cursor(20);

        PlanCursor.Snapshot expired = cursor.signalEvent("door.open", 25);

        assertEquals(PlanCursor.State.FAILED, expired.state());
        assertEquals(SkillOutcome.FailureKind.TIMEOUT,
                expired.failure().orElseThrow().outcome().failureKind());
        assertTrue(expired.waitingEvents().isEmpty());
    }

    @Test
    void staleDeadlineCallbackCannotCompleteAFallbackOrFreshRetryAttempt() {
        PlanCursor fallback = plan(new PlanNode.AnyOf(List.of(
                new PlanNode.Timeout(node("primary"), 5),
                node("fallback")))).cursor(0);

        PlanCursor.Snapshot switched = completeReady(fallback, "primary", succeeded(), 5);
        assertReady(switched, "fallback", 1);

        PlanCursor retry = plan(new PlanNode.Retry(
                new PlanNode.Timeout(node("retried"), 5),
                2,
                Set.of(SkillOutcome.FailureKind.TIMEOUT))).cursor(0);
        PlanCursor.Snapshot freshAttempt = completeReady(retry, "retried", succeeded(), 5);
        assertReady(freshAttempt, "retried", 2);
        assertEquals(PlanCursor.State.SUCCEEDED,
                completeReady(retry, "retried", succeeded(), 6).state());
    }

    @Test
    void staleRetryLeaseCannotCompleteAFreshActivationAfterClockAdvance() {
        PlanCursor cursor = plan(new PlanNode.Retry(
                new PlanNode.Timeout(node("retried"), 5),
                2,
                Set.of(SkillOutcome.FailureKind.TIMEOUT))).cursor(0);
        PlanCursor.ActivationLease staleLease = ready(cursor, "retried").lease();

        assertReady(cursor.advanceTo(5), "retried", 2);
        PlanCursor.Snapshot beforeStaleCallback = cursor.snapshot();
        PlanCursor.Snapshot ignored = cursor.completeSkill(staleLease, succeeded(), 1_000);

        assertEquals(beforeStaleCallback, ignored);
        assertEquals(5, ignored.tick());
        assertReady(ignored, "retried", 2);
        assertEquals(PlanCursor.State.SUCCEEDED,
                cursor.completeSkill(ready(cursor, "retried").lease(), succeeded(), 6).state());
    }

    @Test
    void completionLeaseRejectsEveryRuntimeBindingWithoutAdvancingClock() {
        MissionPlan plan = plan(new PlanNode.Timeout(node("work"), 5));
        PlanCursor cursor = plan.cursor(10);
        PlanCursor.ActivationLease valid = ready(cursor, "work").lease();
        PlanCursor.ActivationLease otherEpoch = ready(plan.cursor(10), "work").lease();
        String otherFingerprint = "0".repeat(64);
        if (otherFingerprint.equals(valid.planFingerprint())) {
            otherFingerprint = "1".repeat(64);
        }

        List<PlanCursor.ActivationLease> staleBindings = List.of(
                otherEpoch,
                new PlanCursor.ActivationLease(
                        UUID.fromString("00000000-0000-0000-0000-000000000002"),
                        valid.planRevision(), valid.planFingerprint(), valid.runtimeEpoch(),
                        valid.invocationId(), valid.activationAttempt()),
                new PlanCursor.ActivationLease(
                        valid.missionId(), valid.planRevision() + 1,
                        valid.planFingerprint(), valid.runtimeEpoch(),
                        valid.invocationId(), valid.activationAttempt()),
                new PlanCursor.ActivationLease(
                        valid.missionId(), valid.planRevision(), otherFingerprint,
                        valid.runtimeEpoch(), valid.invocationId(), valid.activationAttempt()));

        PlanCursor.Snapshot before = cursor.snapshot();
        for (PlanCursor.ActivationLease stale : staleBindings) {
            assertEquals(before, cursor.completeSkill(stale, succeeded(), 1_000));
        }
        assertEquals(10, cursor.snapshot().tick());
        assertEquals(PlanCursor.State.SUCCEEDED,
                cursor.completeSkill(valid, succeeded(), 11).state());
    }

    @Test
    void restoredCursorInvalidatesOldEpochAndTerminalDuplicateIsIdempotent() {
        MissionPlan plan = plan(node("work"));
        PlanCursor original = plan.cursor(3);
        PlanCursor.ActivationLease oldEpoch = ready(original, "work").lease();
        PlanCursor restored = plan.cursor(original.checkpoint());
        PlanCursor.ActivationLease restoredLease = ready(restored, "work").lease();

        assertFalse(oldEpoch.runtimeEpoch().equals(restoredLease.runtimeEpoch()));
        PlanCursor.Snapshot before = restored.snapshot();
        assertEquals(before, restored.completeSkill(oldEpoch, succeeded(), 999));
        assertEquals(3, restored.snapshot().tick());

        PlanCursor.Snapshot completed = restored.completeSkill(restoredLease, succeeded(), 4);
        assertEquals(PlanCursor.State.SUCCEEDED, completed.state());
        assertEquals(completed, restored.completeSkill(restoredLease, succeeded(), 1_000));
        assertEquals(4, restored.snapshot().tick());
    }

    @Test
    void completionLeaseRejectsInvalidOrUnknownIdentityAndIgnoresInactiveKnownBranch() {
        PlanCursor cursor = plan(new PlanNode.AnyOf(List.of(
                node("primary"), node("fallback")))).cursor(0);
        PlanCursor.ActivationLease primaryLease = ready(cursor, "primary").lease();

        assertThrows(IllegalArgumentException.class,
                () -> new PlanCursor.ActivationLease(
                        primaryLease.missionId(), primaryLease.planRevision(),
                        primaryLease.planFingerprint(), primaryLease.runtimeEpoch(),
                        primaryLease.invocationId(), 0));
        PlanCursor.ActivationLease unknownLease = new PlanCursor.ActivationLease(
                primaryLease.missionId(), primaryLease.planRevision(),
                primaryLease.planFingerprint(), primaryLease.runtimeEpoch(), "typo", 1);
        assertThrows(IllegalArgumentException.class,
                () -> cursor.completeSkill(unknownLease, succeeded(), 1));

        PlanCursor.Snapshot switched = cursor.completeSkill(primaryLease,
                retryable(SkillOutcome.FailureKind.PATH_UNREACHABLE), 1);
        assertReady(switched, "fallback", 1);
        assertReady(cursor.completeSkill(primaryLease, succeeded(), 2), "fallback", 1);
    }

    @Test
    void deadlineEventDoesNotLeakIntoAWaiterActivatedByTimeoutFallback() {
        PlanCursor cursor = plan(new PlanNode.AnyOf(List.of(
                new PlanNode.Timeout(new PlanNode.WaitForEvent("door.open"), 5),
                new PlanNode.WaitForEvent("door.open")))).cursor(0);

        PlanCursor.Snapshot switched = cursor.signalEvent("door.open", 5);

        assertEquals(PlanCursor.State.WAITING, switched.state());
        assertEquals(Set.of("door.open"), switched.waitingEvents());
        assertEquals(PlanCursor.State.SUCCEEDED,
                cursor.signalEvent("door.open", 6).state());
    }

    @Test
    void checkpointPublishesAfterSuccessAndCanSkipCompletedSubtreeOnResume() {
        MissionPlan plan = plan(new PlanNode.Sequence(List.of(
                new PlanNode.Checkpoint("wood_ready", node("gather_wood")),
                node("craft_pickaxe"))));
        PlanCursor cursor = plan.cursor(0);

        assertReady(cursor.snapshot(), "gather_wood", 1);
        PlanCursor.Snapshot afterCheckpoint = completeReady(cursor,
                "gather_wood", succeeded(), 1);
        assertEquals(Set.of("wood_ready"), afterCheckpoint.reachedCheckpoints());
        assertReady(afterCheckpoint, "craft_pickaxe", 1);

        CursorCheckpoint checkpoint = cursor.checkpoint();
        PlanCursor restored = plan.cursor(checkpoint);
        assertReady(restored.snapshot(), "craft_pickaxe", 1);

        Set<String> unknownReached = new java.util.LinkedHashSet<>(checkpoint.reachedCheckpoints());
        unknownReached.add("not_in_plan");
        CursorCheckpoint corrupted = new CursorCheckpoint(
                checkpoint.schemaVersion(), checkpoint.missionId(), checkpoint.planRevision(),
                checkpoint.planFingerprint(), checkpoint.tick(), checkpoint.nodeStates(),
                checkpoint.activationCounts(), unknownReached, checkpoint.waitingEvents());
        assertEquals("cursor_checkpoint_unknown_reached_checkpoint",
                assertThrows(IllegalArgumentException.class,
                        () -> plan.cursor(corrupted)).getMessage());
    }

    @Test
    void cursorCheckpointRejectsForgedFutureCheckpointMarkers() {
        MissionPlan plan = plan(new PlanNode.Sequence(List.of(
                node("active"),
                new PlanNode.Checkpoint("future_ready", node("future")))));
        CursorCheckpoint checkpoint = plan.cursor(0).checkpoint();

        CursorCheckpoint setOnly = copyCheckpoint(
                checkpoint,
                checkpoint.nodeStates(),
                Set.of("future_ready"));
        assertEquals("cursor_checkpoint_reached_marker_mismatch",
                assertThrows(IllegalArgumentException.class,
                        () -> plan.cursor(setOnly)).getMessage());

        java.util.LinkedHashMap<String, CursorCheckpoint.NodeState> markerStates =
                new java.util.LinkedHashMap<>(checkpoint.nodeStates());
        markerStates.put("root/1", withCheckpointMarker(markerStates.get("root/1"), true));
        CursorCheckpoint markerOnly = copyCheckpoint(checkpoint, markerStates, Set.of());
        assertEquals("cursor_checkpoint_reached_marker_mismatch",
                assertThrows(IllegalArgumentException.class,
                        () -> plan.cursor(markerOnly)).getMessage());

        CursorCheckpoint markerAndSet = copyCheckpoint(
                checkpoint, markerStates, Set.of("future_ready"));
        assertEquals("cursor_checkpoint_structure_invalid:root/1",
                assertThrows(IllegalArgumentException.class,
                        () -> plan.cursor(markerAndSet)).getMessage());

        java.util.LinkedHashMap<String, CursorCheckpoint.NodeState> nonCheckpointStates =
                new java.util.LinkedHashMap<>(checkpoint.nodeStates());
        nonCheckpointStates.put(
                "root/0", withCheckpointMarker(nonCheckpointStates.get("root/0"), true));
        CursorCheckpoint markerOnSkill = copyCheckpoint(
                checkpoint, nonCheckpointStates, checkpoint.reachedCheckpoints());
        assertEquals("cursor_checkpoint_marker_on_non_checkpoint:root/0",
                assertThrows(IllegalArgumentException.class,
                        () -> plan.cursor(markerOnSkill)).getMessage());
    }

    @Test
    void cursorCheckpointRequiresSucceededCheckpointToCarryItsDurableMarker() {
        MissionPlan plan = plan(new PlanNode.Checkpoint("done", node("work")));
        PlanCursor cursor = plan.cursor(0);
        completeReady(cursor, "work", succeeded(), 1);
        CursorCheckpoint checkpoint = cursor.checkpoint();

        java.util.LinkedHashMap<String, CursorCheckpoint.NodeState> states =
                new java.util.LinkedHashMap<>(checkpoint.nodeStates());
        states.put("root", withCheckpointMarker(states.get("root"), false));
        CursorCheckpoint corrupted = copyCheckpoint(checkpoint, states, Set.of());

        assertEquals("cursor_checkpoint_structure_invalid:root",
                assertThrows(IllegalArgumentException.class,
                        () -> plan.cursor(corrupted)).getMessage());
    }

    @Test
    void checkpointSurvivesOuterRetryAndSkipsAlreadyCompletedWork() {
        PlanNode attempt = new PlanNode.Sequence(List.of(
                new PlanNode.Checkpoint("supplies_ready", node("gather")),
                node("deliver")));
        PlanCursor cursor = plan(new PlanNode.Retry(
                attempt,
                2,
                Set.of(SkillOutcome.FailureKind.PATH_UNREACHABLE))).cursor(0);

        assertReady(completeReady(cursor, "gather", succeeded(), 1), "deliver", 1);
        PlanCursor.Snapshot retried = completeReady(cursor,
                "deliver", retryable(SkillOutcome.FailureKind.PATH_UNREACHABLE), 2);

        assertEquals(Set.of("supplies_ready"), retried.reachedCheckpoints());
        assertReady(retried, "deliver", 2);
    }

    @Test
    void retryCannotForgeAnUnreachedCheckpointAfterItsChildFailed() {
        PlanNode attempt = new PlanNode.Sequence(List.of(
                node("prepare"),
                new PlanNode.Checkpoint("verified", node("verify")),
                node("finish")));
        MissionPlan plan = plan(new PlanNode.Retry(
                attempt, 2, Set.of(SkillOutcome.FailureKind.PATH_UNREACHABLE)));
        PlanCursor cursor = plan.cursor(0);
        assertReady(completeReady(cursor, "prepare", succeeded(), 1), "verify", 1);
        assertReady(completeReady(cursor, "verify",
                retryable(SkillOutcome.FailureKind.PATH_UNREACHABLE), 2), "prepare", 2);

        CursorCheckpoint checkpoint = cursor.checkpoint();
        java.util.LinkedHashMap<String, CursorCheckpoint.NodeState> forgedStates =
                new java.util.LinkedHashMap<>(checkpoint.nodeStates());
        String checkpointPath = "root/child/1";
        forgedStates.put(checkpointPath,
                withCheckpointMarker(forgedStates.get(checkpointPath), true));
        CursorCheckpoint forged = copyCheckpoint(
                checkpoint, forgedStates, Set.of("verified"));

        assertEquals("cursor_checkpoint_structure_invalid:" + checkpointPath,
                assertThrows(IllegalArgumentException.class,
                        () -> plan.cursor(forged)).getMessage());
    }

    @Test
    void completeCursorCheckpointRestoresCurrentAnyOfBranchWithoutRetryingFailedBranch() {
        PlanNode primary = new PlanNode.Sequence(List.of(
                new PlanNode.Checkpoint("supplies_ready", node("gather")),
                node("primary_delivery")));
        MissionPlan plan = plan(new PlanNode.AnyOf(List.of(primary, node("fallback_delivery"))));
        PlanCursor cursor = plan.cursor(0);

        assertReady(completeReady(cursor, "gather", succeeded(), 1), "primary_delivery", 1);
        assertReady(completeReady(cursor,
                "primary_delivery",
                retryable(SkillOutcome.FailureKind.PATH_UNREACHABLE),
                2), "fallback_delivery", 1);

        CursorCheckpoint checkpoint = cursor.checkpoint();
        PlanCursor restored = plan.cursor(checkpoint);

        assertEquals(checkpoint, restored.checkpoint());
        assertEquals(Set.of("supplies_ready"), checkpoint.reachedCheckpoints());
        assertEquals(1, checkpoint.activationCounts().get("primary_delivery"));
        assertReady(restored.snapshot(), "fallback_delivery", 1);
        assertEquals(PlanCursor.State.SUCCEEDED,
                completeReady(restored, "fallback_delivery", succeeded(), 3).state());
    }

    @Test
    void completeCursorCheckpointRestoresRetryAttemptsAndTimeoutElapsedTicks() {
        MissionPlan retryPlan = plan(new PlanNode.Retry(
                node("mine"), 3, Set.of(SkillOutcome.FailureKind.PATH_UNREACHABLE)));
        PlanCursor retrying = retryPlan.cursor(0);
        assertReady(completeReady(retrying,
                "mine", retryable(SkillOutcome.FailureKind.PATH_UNREACHABLE), 1),
                "mine", 2);

        PlanCursor restoredRetry = retryPlan.cursor(retrying.checkpoint());
        assertReady(restoredRetry.snapshot(), "mine", 2);
        assertReady(completeReady(restoredRetry,
                "mine", retryable(SkillOutcome.FailureKind.PATH_UNREACHABLE), 2),
                "mine", 3);

        MissionPlan timeoutPlan = plan(new PlanNode.Timeout(node("travel"), 5));
        PlanCursor timed = timeoutPlan.cursor(10);
        timed.advanceTo(13);
        CursorCheckpoint timeoutCheckpoint = timed.checkpoint();
        CursorCheckpoint.NodeState timeoutState = timeoutCheckpoint.nodeStates().get("root");
        assertEquals(10, timeoutState.timeoutStartedTick());
        assertEquals(3, timeoutState.timeoutElapsedTicks());

        PlanCursor restoredTimeout = timeoutPlan.cursor(timeoutCheckpoint);
        assertReady(restoredTimeout.advanceTo(14), "travel", 1);
        PlanCursor.Snapshot expired = completeReady(restoredTimeout, "travel", succeeded(), 15);
        assertEquals(PlanCursor.State.FAILED, expired.state());
        assertEquals(SkillOutcome.FailureKind.TIMEOUT,
                expired.failure().orElseThrow().outcome().failureKind());
    }

    @Test
    void completeCursorCheckpointRestoresWaitingEventAndReachedFutureCheckpoint() {
        MissionPlan waitingPlan = plan(new PlanNode.Sequence(List.of(
                node("prepare"),
                new PlanNode.WaitForEvent("door.open"),
                node("enter"))));
        PlanCursor waiting = waitingPlan.cursor(0);
        assertEquals(PlanCursor.State.WAITING,
                completeReady(waiting, "prepare", succeeded(), 1).state());

        CursorCheckpoint waitingCheckpoint = waiting.checkpoint();
        PlanCursor restoredWaiting = waitingPlan.cursor(waitingCheckpoint);
        assertEquals(Set.of("door.open"), restoredWaiting.snapshot().waitingEvents());
        assertReady(restoredWaiting.signalEvent("door.open", 2), "enter", 1);

        PlanNode retriedWork = new PlanNode.Sequence(List.of(
                node("refresh"),
                new PlanNode.Checkpoint("supplies_ready", node("gather")),
                node("deliver")));
        MissionPlan retryPlan = plan(new PlanNode.Retry(
                retriedWork, 2, Set.of(SkillOutcome.FailureKind.PATH_UNREACHABLE)));
        PlanCursor retrying = retryPlan.cursor(0);
        completeReady(retrying, "refresh", succeeded(), 1);
        completeReady(retrying, "gather", succeeded(), 2);
        PlanCursor.Snapshot secondAttempt = completeReady(retrying,
                "deliver", retryable(SkillOutcome.FailureKind.PATH_UNREACHABLE), 3);
        assertReady(secondAttempt, "refresh", 2);

        CursorCheckpoint pendingReachedCheckpoint = retrying.checkpoint();
        assertTrue(pendingReachedCheckpoint.nodeStates()
                .get("root/child/1").checkpointReached());
        PlanCursor restoredRetry = retryPlan.cursor(pendingReachedCheckpoint);
        assertReady(restoredRetry.snapshot(), "refresh", 2);
        assertReady(completeReady(restoredRetry, "refresh", succeeded(), 4), "deliver", 2);
    }

    @Test
    void cursorCheckpointRestoresTerminalFailureAndRejectsEveryPlanBindingMismatch() {
        MissionPlan original = plan(node("mine"));
        PlanCursor failedCursor = original.cursor(4);
        PlanCursor.Snapshot failed = completeReady(failedCursor,
                "mine", failure(SkillOutcome.Status.FATAL_FAILURE,
                        SkillOutcome.FailureKind.INTERNAL), 5);
        CursorCheckpoint checkpoint = failedCursor.checkpoint();

        PlanCursor restored = original.cursor(checkpoint);
        assertEquals(failed, restored.snapshot());
        assertEquals(checkpoint, restored.checkpoint());

        MissionPlan wrongMission = new MissionPlan(
                UUID.fromString("00000000-0000-0000-0000-000000000002"),
                original.revision(), original.goal(), original.root(), original.plannerVersion());
        MissionPlan wrongRevision = new MissionPlan(
                original.missionId(), original.revision() + 1,
                original.goal(), original.root(), original.plannerVersion());
        MissionPlan wrongPlan = new MissionPlan(
                original.missionId(), original.revision(), original.goal(),
                node("different"), original.plannerVersion());
        CursorCheckpoint future = new CursorCheckpoint(
                CursorCheckpoint.CURRENT_VERSION + 1,
                checkpoint.missionId(), checkpoint.planRevision(), checkpoint.planFingerprint(),
                checkpoint.tick(), checkpoint.nodeStates(), checkpoint.activationCounts(),
                checkpoint.reachedCheckpoints(), checkpoint.waitingEvents());

        assertEquals("cursor_checkpoint_mission_mismatch",
                assertThrows(IllegalArgumentException.class,
                        () -> wrongMission.cursor(checkpoint)).getMessage());
        assertEquals("cursor_checkpoint_revision_mismatch",
                assertThrows(IllegalArgumentException.class,
                        () -> wrongRevision.cursor(checkpoint)).getMessage());
        assertEquals("cursor_checkpoint_plan_mismatch",
                assertThrows(IllegalArgumentException.class,
                        () -> wrongPlan.cursor(checkpoint)).getMessage());
        assertEquals("cursor_checkpoint_version_unsupported",
                assertThrows(IllegalArgumentException.class,
                        () -> original.cursor(future)).getMessage());
    }

    @Test
    void cursorCheckpointRejectsActivationHistoryInjectedIntoAPendingSkill() {
        MissionPlan plan = plan(new PlanNode.Sequence(List.of(node("first"), node("pending"))));
        CursorCheckpoint checkpoint = plan.cursor(0).checkpoint();
        java.util.LinkedHashMap<String, Integer> corruptedCounts =
                new java.util.LinkedHashMap<>(checkpoint.activationCounts());
        corruptedCounts.put("pending", Integer.MAX_VALUE);
        CursorCheckpoint corrupted = new CursorCheckpoint(
                checkpoint.schemaVersion(), checkpoint.missionId(), checkpoint.planRevision(),
                checkpoint.planFingerprint(), checkpoint.tick(), checkpoint.nodeStates(),
                corruptedCounts, checkpoint.reachedCheckpoints(), checkpoint.waitingEvents());

        assertEquals("cursor_checkpoint_activation_state_mismatch",
                assertThrows(IllegalArgumentException.class,
                        () -> plan.cursor(corrupted)).getMessage());
    }

    @Test
    void cursorCheckpointAcceptsBoundedHistoryFromNestedRetries() {
        PlanNode innerAttempt = new PlanNode.Sequence(List.of(node("prepare"), node("deliver")));
        MissionPlan plan = plan(new PlanNode.Retry(
                new PlanNode.Retry(
                        innerAttempt, 3,
                        Set.of(SkillOutcome.FailureKind.PATH_UNREACHABLE)),
                2,
                Set.of(SkillOutcome.FailureKind.PATH_UNREACHABLE)));
        PlanCursor cursor = plan.cursor(0);

        for (int attempt = 1; attempt <= 3; attempt++) {
            assertReady(completeReady(cursor, "prepare", succeeded(), attempt * 2L - 1L),
                    "deliver", attempt);
            PlanCursor.Snapshot afterFailure = completeReady(cursor,
                    "deliver", retryable(SkillOutcome.FailureKind.PATH_UNREACHABLE),
                    attempt * 2L);
            if (attempt < 3) {
                assertReady(afterFailure, "prepare", attempt + 1);
            }
        }
        assertReady(cursor.snapshot(), "prepare", 4);

        CursorCheckpoint checkpoint = cursor.checkpoint();
        PlanCursor restored = plan.cursor(checkpoint);

        assertEquals(checkpoint, restored.checkpoint());
        assertReady(restored.snapshot(), "prepare", 4);
        assertEquals(3, checkpoint.activationCounts().get("deliver"));
    }

    @Test
    void planFingerprintIsDeterministicAndAdmissionBoundsNodeCountAndDepth() {
        java.util.LinkedHashMap<String, String> forward = new java.util.LinkedHashMap<>();
        forward.put("item", "minecraft:iron_ingot");
        forward.put("quota", "3");
        java.util.LinkedHashMap<String, String> reverse = new java.util.LinkedHashMap<>();
        reverse.put("quota", "3");
        reverse.put("item", "minecraft:iron_ingot");
        SkillSpec firstSkill = new SkillSpec(
                "call.1", "craft", 1, forward, List.of("has_inputs"), List.of("crafted"),
                SkillSpec.RetryPolicy.standard(), MissionPolicy.MutationScope.SURVIVAL);
        SkillSpec reorderedSkill = new SkillSpec(
                "call.1", "craft", 1, reverse, List.of("has_inputs"), List.of("crafted"),
                SkillSpec.RetryPolicy.standard(), MissionPolicy.MutationScope.SURVIVAL);
        MissionPlan first = plan(new PlanNode.Timeout(new PlanNode.Skill(firstSkill), 20));
        MissionPlan reordered = plan(new PlanNode.Timeout(new PlanNode.Skill(reorderedSkill), 20));
        MissionPlan changed = plan(new PlanNode.Timeout(new PlanNode.Skill(reorderedSkill), 21));

        assertTrue(first.fingerprint().matches("[0-9a-f]{64}"));
        assertEquals(first.fingerprint(), reordered.fingerprint());
        assertFalse(first.fingerprint().equals(changed.fingerprint()));

        List<PlanNode> tooMany = new java.util.ArrayList<>();
        for (int index = 0; index < MissionPlan.MAX_PLAN_NODES; index++) {
            tooMany.add(node("node_" + index));
        }
        assertEquals("mission_plan_node_limit_exceeded",
                assertThrows(IllegalArgumentException.class,
                        () -> plan(new PlanNode.Sequence(tooMany))).getMessage());

        PlanNode tooDeep = node("deep_leaf");
        for (int depth = 0; depth < MissionPlan.MAX_PLAN_DEPTH; depth++) {
            tooDeep = new PlanNode.Timeout(tooDeep, 10);
        }
        PlanNode finalTooDeep = tooDeep;
        assertEquals("mission_plan_depth_exceeded",
                assertThrows(IllegalArgumentException.class,
                        () -> plan(finalTooDeep)).getMessage());
    }

    @Test
    void waitForEventConsumesOnlyAnEventObservedWhileItIsActive() {
        PlanCursor cursor = plan(new PlanNode.Sequence(List.of(
                node("prepare"),
                new PlanNode.WaitForEvent("door.open"),
                node("enter")))).cursor(0);

        assertReady(cursor.signalEvent("door.open", 1), "prepare", 1);
        PlanCursor.Snapshot waiting = completeReady(cursor, "prepare", succeeded(), 2);
        assertEquals(PlanCursor.State.WAITING, waiting.state());
        assertEquals(Set.of("door.open"), waiting.waitingEvents());

        PlanCursor.Snapshot unrelated = cursor.signalEvent("sunrise", 3);
        assertEquals(PlanCursor.State.WAITING, unrelated.state());
        assertReady(cursor.signalEvent("door.open", 4), "enter", 1);
    }

    @Test
    void newCompositeNodesRemainClosedToTheLegacyLinearRuntime() {
        MissionPlan legacy = plan(new PlanNode.Sequence(List.of(node("one"), node("two"))));
        MissionPlan composite = plan(new PlanNode.Retry(node("retry"), 2));

        assertEquals(List.of("one", "two"), legacy.requireLinearSkills().stream()
                .map(SkillSpec::invocationId)
                .toList());
        assertThrows(IllegalStateException.class, composite::requireLinearSkills);
    }

    @Test
    void admissionRebindPreservesProgressAndInvalidatesOldActivationLease() {
        MissionPlan original = plan(new PlanNode.Sequence(List.of(
                node("first"), node("second"))));
        PlanCursor cursor = original.cursor(0);
        completeReady(cursor, "first", succeeded(), 1);
        PlanCursor.ActivationLease oldLease = ready(cursor, "second").lease();
        GoalSpec before = original.goal();
        GoalSpec stronger = new GoalSpec(
                before.type(),
                GoalSpec.Source.PLAYER_CONFIRMED,
                100,
                before.successPredicate(),
                before.dimension(),
                before.policy(),
                before.attributes());
        MissionPlan upgraded = new MissionPlan(
                original.missionId(), original.revision() + 1, stronger,
                original.root(), original.plannerVersion());

        PlanCursor rebound = cursor.rebindAdmission(upgraded);
        PlanCursor.ReadySkill reboundReady = ready(rebound, "second");

        assertEquals(2, rebound.checkpoint().planRevision());
        assertEquals(upgraded.fingerprint(), rebound.checkpoint().planFingerprint());
        assertEquals(oldLease.activationAttempt(), reboundReady.activationAttempt());
        assertFalse(oldLease.equals(reboundReady.lease()));
        assertReady(rebound.completeSkill(oldLease, succeeded(), 1), "second", 1);
        assertEquals(PlanCursor.State.SUCCEEDED,
                rebound.completeSkill(reboundReady.lease(), succeeded(), 2).state());
    }

    @Test
    void admissionRebindRejectsExecutableOrPolicyChanges() {
        MissionPlan original = plan(node("work"));
        PlanCursor cursor = original.cursor(0);
        GoalSpec before = original.goal();
        GoalSpec changedPolicy = new GoalSpec(
                before.type(), before.source(), before.priority(),
                before.successPredicate(), before.dimension(),
                new MissionPolicy(
                        MissionPolicy.RiskLevel.BOLD,
                        before.policy().mutationScope(),
                        before.policy().timeBudgetTicks(),
                        before.policy().recoveryBudget(),
                        before.policy().interruptionPolicy()),
                before.attributes());

        assertEquals("plan_admission_rebind_invalid", assertThrows(
                IllegalArgumentException.class,
                () -> cursor.rebindAdmission(new MissionPlan(
                        original.missionId(), original.revision() + 1,
                        changedPolicy, original.root(), original.plannerVersion())))
                .getMessage());
        assertEquals("plan_admission_rebind_invalid", assertThrows(
                IllegalArgumentException.class,
                () -> cursor.rebindAdmission(new MissionPlan(
                        original.missionId(), original.revision() + 1,
                        before, node("different"), original.plannerVersion())))
                .getMessage());
    }

    @Test
    void contractsRejectAmbiguousIdentityBudgetAndTime() {
        assertThrows(IllegalArgumentException.class,
                () -> new PlanNode.Retry(null, 2));
        assertThrows(IllegalArgumentException.class,
                () -> new PlanNode.Retry(node("retry"), 0));
        assertThrows(IllegalArgumentException.class,
                () -> new PlanNode.Retry(node("retry"), 2,
                        Set.of(SkillOutcome.FailureKind.NONE)));
        assertThrows(IllegalArgumentException.class,
                () -> new PlanNode.Retry(node("retry"), 2,
                        Set.of(SkillOutcome.FailureKind.SAFETY)));
        assertThrows(IllegalArgumentException.class,
                () -> new PlanNode.Timeout(node("timeout"), 0));
        assertThrows(IllegalArgumentException.class,
                () -> new PlanNode.Timeout(null, 10));
        assertThrows(IllegalArgumentException.class,
                () -> new PlanNode.Checkpoint("BAD ID", node("checkpoint")));
        assertThrows(IllegalArgumentException.class,
                () -> new PlanNode.Checkpoint("valid", null));
        assertThrows(IllegalArgumentException.class,
                () -> new PlanNode.WaitForEvent(""));

        PlanNode duplicateCheckpoints = new PlanNode.Sequence(List.of(
                new PlanNode.Checkpoint("same", node("one")),
                new PlanNode.Checkpoint("same", node("two"))));
        assertEquals("duplicate_checkpoint_id:same",
                assertThrows(IllegalArgumentException.class,
                        () -> plan(duplicateCheckpoints)).getMessage());

        PlanCursor cursor = plan(node("clock")).cursor(5);
        assertThrows(IllegalArgumentException.class, () -> cursor.advanceTo(4));
        assertThrows(IllegalArgumentException.class, () -> plan(node("negative")).cursor(-1));
    }

    private static void assertReady(PlanCursor.Snapshot snapshot,
                                    String invocationId,
                                    int attempt) {
        assertEquals(PlanCursor.State.READY, snapshot.state());
        assertEquals(1, snapshot.readySkills().size());
        PlanCursor.ReadySkill ready = snapshot.readySkills().get(0);
        assertEquals(invocationId, ready.spec().invocationId());
        assertEquals(attempt, ready.activationAttempt());
        assertTrue(snapshot.waitingEvents().isEmpty());
        assertTrue(snapshot.failure().isEmpty());
    }

    private static CursorCheckpoint copyCheckpoint(
            CursorCheckpoint source,
            Map<String, CursorCheckpoint.NodeState> nodeStates,
            Set<String> reachedCheckpoints) {
        return new CursorCheckpoint(
                source.schemaVersion(),
                source.missionId(),
                source.planRevision(),
                source.planFingerprint(),
                source.tick(),
                nodeStates,
                source.activationCounts(),
                reachedCheckpoints,
                source.waitingEvents());
    }

    private static CursorCheckpoint.NodeState withCheckpointMarker(
            CursorCheckpoint.NodeState source,
            boolean checkpointReached) {
        return new CursorCheckpoint.NodeState(
                source.phase(),
                source.childIndex(),
                source.retryAttempt(),
                source.timeoutStartedTick(),
                source.timeoutElapsedTicks(),
                checkpointReached,
                source.failurePath(),
                source.failureOutcome());
    }

    private static PlanCursor.Snapshot completeReady(PlanCursor cursor,
                                                     String invocationId,
                                                     SkillOutcome outcome,
                                                     long tick) {
        PlanCursor.ReadySkill ready = ready(cursor, invocationId);
        return cursor.completeSkill(ready.lease(), outcome, tick);
    }

    private static PlanCursor.ReadySkill ready(PlanCursor cursor, String invocationId) {
        return cursor.snapshot().readySkills().stream()
                .filter(candidate -> candidate.spec().invocationId().equals(invocationId))
                .findFirst()
                .orElseThrow(() -> new AssertionError("skill_not_ready:" + invocationId));
    }

    private static PlanNode.Skill node(String invocationId) {
        return new PlanNode.Skill(skill(invocationId));
    }

    private static SkillSpec skill(String invocationId) {
        return new SkillSpec(
                invocationId,
                "test_skill",
                1,
                Map.of(),
                List.of(),
                List.of("done:" + invocationId),
                SkillSpec.RetryPolicy.standard(),
                MissionPolicy.MutationScope.SURVIVAL);
    }

    private static SkillOutcome succeeded() {
        return SkillOutcome.succeeded(1, Map.of("verified", "true"));
    }

    private static SkillOutcome retryable(SkillOutcome.FailureKind kind) {
        return failure(SkillOutcome.Status.RETRYABLE_FAILURE, kind);
    }

    private static SkillOutcome failure(SkillOutcome.Status status,
                                        SkillOutcome.FailureKind kind) {
        return new SkillOutcome(status, kind, kind.name().toLowerCase(), 0, Map.of());
    }

    private static MissionPlan plan(PlanNode root) {
        GoalSpec goal = new GoalSpec(
                "test_goal",
                GoalSpec.Source.PLAYER_COMMAND,
                90,
                "test:complete",
                "minecraft:overworld",
                MissionPolicy.standard(),
                Map.of());
        return new MissionPlan(
                UUID.fromString("00000000-0000-0000-0000-000000000001"),
                1,
                goal,
                root,
                "plan-cursor-test-v1");
    }
}
