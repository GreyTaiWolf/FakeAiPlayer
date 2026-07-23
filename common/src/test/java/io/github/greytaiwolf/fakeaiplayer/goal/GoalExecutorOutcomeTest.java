package io.github.greytaiwolf.fakeaiplayer.goal;

import io.github.greytaiwolf.fakeaiplayer.mission.SkillOutcome;
import io.github.greytaiwolf.fakeaiplayer.mission.RecoveryLedger;
import io.github.greytaiwolf.fakeaiplayer.mission.CursorCheckpoint;
import io.github.greytaiwolf.fakeaiplayer.mission.GoalSpec;
import io.github.greytaiwolf.fakeaiplayer.mission.MissionPolicy;
import io.github.greytaiwolf.fakeaiplayer.mission.SkillSpec;
import io.github.greytaiwolf.fakeaiplayer.persist.MissionCheckpointCodec;
import io.github.greytaiwolf.fakeaiplayer.persist.MissionRecord;
import io.github.greytaiwolf.fakeaiplayer.persist.MissionSpec;
import io.github.greytaiwolf.fakeaiplayer.task.TaskState;
import io.github.greytaiwolf.fakeaiplayer.task.TaskStatus;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GoalExecutorOutcomeTest {
    @Test
    void interruptedFailedChildKeepsItsTypedTerminalOutcome() {
        SkillOutcome outcome = GoalExecutor.interruptedTerminalOutcome(new TaskStatus(
                "ore_dig", "Mining iron", TaskState.FAILED, 0.5D,
                "no_ore_found", 40));

        assertEquals(SkillOutcome.Status.BLOCKED, outcome.status());
        assertEquals(SkillOutcome.FailureKind.RESOURCE_UNAVAILABLE, outcome.failureKind());
        assertEquals(500, outcome.progress());
        assertEquals("no_ore_found", outcome.evidence().get("legacy_reason"));
    }

    @Test
    void interruptedCancellationCannotBeReplannedAsAnOrdinarySafetyEvent() {
        SkillOutcome outcome = GoalExecutor.interruptedTerminalOutcome(new TaskStatus(
                "build", "Building", TaskState.CANCELLED, 0.25D,
                "cancelled_by_player", 20));

        assertEquals(SkillOutcome.Status.CANCELLED, outcome.status());
        assertEquals(SkillOutcome.FailureKind.CANCELLED, outcome.failureKind());
        assertEquals(250, outcome.progress());
    }

    @Test
    void lostNonterminalChildFailsClosedAndCompletedChildRequiresWorldVerification() {
        SkillOutcome lost = GoalExecutor.interruptedTerminalOutcome(new TaskStatus(
                "mine", "Mining", TaskState.RUNNING, 0.1D, "", 5));

        assertEquals(SkillOutcome.Status.FATAL_FAILURE, lost.status());
        assertEquals(SkillOutcome.FailureKind.INTERNAL, lost.failureKind());
        assertThrows(IllegalArgumentException.class,
                () -> GoalExecutor.interruptedTerminalOutcome(new TaskStatus(
                        "mine", "Mining", TaskState.COMPLETED, 1.0D, "", 10)));
    }

    @Test
    void bestEffortMaySkipOnlyRecoverableOrPreemptedOutcomes() {
        assertTrue(GoalExecutor.bestEffortSkippable(new SkillOutcome(
                SkillOutcome.Status.RETRYABLE_FAILURE,
                SkillOutcome.FailureKind.PATH_UNREACHABLE,
                "no_path",
                0,
                java.util.Map.of())));
        assertTrue(GoalExecutor.bestEffortSkippable(
                SkillOutcome.preempted("safety_interrupt", 0)));

        assertFalse(GoalExecutor.bestEffortSkippable(new SkillOutcome(
                SkillOutcome.Status.BLOCKED,
                SkillOutcome.FailureKind.RESOURCE_UNAVAILABLE,
                "no_resource",
                0,
                java.util.Map.of())));
        assertFalse(GoalExecutor.bestEffortSkippable(new SkillOutcome(
                SkillOutcome.Status.FATAL_FAILURE,
                SkillOutcome.FailureKind.INTERNAL,
                "adapter_contract_broken",
                0,
                java.util.Map.of())));
        assertFalse(GoalExecutor.bestEffortSkippable(
                SkillOutcome.cancelled("cancelled_by_player", 0)));
        assertFalse(GoalExecutor.bestEffortSkippable(null));
    }

    @Test
    void missionBudgetStopsAtTheExactConfiguredTick() {
        assertFalse(GoalExecutor.missionTimeBudgetExhausted(9, 10));
        assertTrue(GoalExecutor.missionTimeBudgetExhausted(10, 10));
        assertTrue(GoalExecutor.missionTimeBudgetExhausted(11, 10));
    }

    @Test
    void missionClockAndPlanRevisionNeverOverflow() {
        assertEquals(1, GoalExecutor.incrementMissionTicks(0));
        assertEquals(Integer.MAX_VALUE,
                GoalExecutor.incrementMissionTicks(Integer.MAX_VALUE));
        assertThrows(IllegalArgumentException.class,
                () -> GoalExecutor.incrementMissionTicks(-1));

        assertEquals(1, GoalExecutor.nextPlanRevision(0).orElseThrow());
        assertTrue(GoalExecutor.nextPlanRevision(Integer.MAX_VALUE).isEmpty());
        assertThrows(IllegalArgumentException.class,
                () -> GoalExecutor.nextPlanRevision(-1));
    }

    @Test
    void runningMissionClockCreatesPeriodicPersistenceCheckpoints() {
        assertFalse(GoalExecutor.shouldPersistRuntimeCheckpoint(0));
        assertFalse(GoalExecutor.shouldPersistRuntimeCheckpoint(199));
        assertTrue(GoalExecutor.shouldPersistRuntimeCheckpoint(200));
        assertFalse(GoalExecutor.shouldPersistRuntimeCheckpoint(201));
        assertTrue(GoalExecutor.shouldPersistRuntimeCheckpoint(400));
    }

    @Test
    void restoredProgressIsIncludedInTheReplannedTotalWithoutOverflow() {
        assertEquals(7, GoalExecutor.totalSteps(3, 4));
        assertThrows(IllegalArgumentException.class,
                () -> GoalExecutor.totalSteps(Integer.MAX_VALUE, 1));
        assertThrows(IllegalArgumentException.class,
                () -> GoalExecutor.totalSteps(-1, 1));
    }

    @Test
    void replannedLabelsRetainCompletedHistoryAndAlignWithGlobalIndices() {
        List<String> labels = GoalExecutor.alignedStepLabels(
                List.of("收集木材", "制作木镐", "旧的失败步骤"),
                2,
                List.of("采集铁矿", "熔炼铁锭"));

        assertEquals(List.of("收集木材", "制作木镐", "采集铁矿", "熔炼铁锭"), labels);
        assertEquals(4, labels.size());
        assertEquals(2, GoalExecutor.currentStepIndex(labels.size(), 1, true));
    }

    @Test
    void restoredLabelsUseCompletedPlaceholdersAndDeferredIndexStaysValid() {
        List<String> labels = GoalExecutor.alignedStepLabels(
                List.of(), 3, List.of("熔炼铁锭"));

        assertEquals(4, labels.size());
        assertEquals("已完成步骤 1", labels.get(0));
        assertEquals("熔炼铁锭", labels.get(3));
        assertEquals(3, GoalExecutor.currentStepIndex(labels.size(), 1, false));
        assertEquals(3, GoalExecutor.currentStepIndex(labels.size(), 0, true));
    }

    @Test
    void maxPlanRevisionRestoresOnlyWhenThePlanFingerprintIsUnchanged() {
        String original = "a".repeat(64);
        String changed = "b".repeat(64);

        assertEquals(Integer.MAX_VALUE, GoalExecutor.resolveRestoredPlanRevision(
                Integer.MAX_VALUE, original, original).orElseThrow());
        assertTrue(GoalExecutor.resolveRestoredPlanRevision(
                Integer.MAX_VALUE, original, changed).isEmpty());
        assertEquals(8, GoalExecutor.resolveRestoredPlanRevision(
                7, original, changed).orElseThrow());
    }

    @Test
    void attemptCapacityFailureIsFatalWhileAConsumedSkillBudgetIsBlocked() throws Exception {
        String fingerprint = "a".repeat(64);
        SkillOutcome capacity = GoalExecutor.attemptDeniedOutcome(
                new RecoveryLedger.AttemptDecision(false, fingerprint, 0, 3,
                        "skill_attempt_tracking_capacity_exhausted"));
        SkillOutcome consumed = GoalExecutor.attemptDeniedOutcome(
                new RecoveryLedger.AttemptDecision(false, fingerprint, 3, 3,
                        "skill_attempt_budget_exhausted"));

        assertEquals(SkillOutcome.Status.FATAL_FAILURE, capacity.status());
        assertEquals(SkillOutcome.FailureKind.INTERNAL, capacity.failureKind());
        assertEquals(SkillOutcome.Status.BLOCKED, consumed.status());
        assertEquals(SkillOutcome.FailureKind.PRECONDITION, consumed.failureKind());
        assertThrows(IllegalArgumentException.class,
                () -> GoalExecutor.attemptDeniedOutcome(null));

        SkillSpec torch = new SkillSpec(
                "step.0.legacy.craft",
                "legacy.craft",
                1,
                Map.of("item", "minecraft:torch", "quota", "4"),
                List.of("inventory:recipe_inputs"),
                List.of("inventory(minecraft:torch)>=4"),
                SkillSpec.RetryPolicy.standard(),
                MissionPolicy.MutationScope.SURVIVAL);
        String torchFingerprint = RecoveryLedger.fingerprint(torch);
        RecoveryLedger.Snapshot unique = new RecoveryLedger.Snapshot(
                Map.of(torchFingerprint, 1), 1, 0, 0);

        assertTrue(GoalExecutor.canReuseUniqueRecompiledReservation(
                unique, torch, false));
        assertFalse(GoalExecutor.canReuseUniqueRecompiledReservation(
                unique, torch, true));
        assertFalse(GoalExecutor.canReuseUniqueRecompiledReservation(
                new RecoveryLedger.Snapshot(
                        Map.of(torchFingerprint, 1, "a".repeat(64), 1), 1, 0, 0),
                torch,
                false));

        SkillSpec table = new SkillSpec(
                "step.0.legacy.craft",
                "legacy.craft",
                1,
                Map.of("item", "minecraft:crafting_table", "quota", "1"),
                List.of("inventory:recipe_inputs"),
                List.of("inventory(minecraft:crafting_table)>=1"),
                new SkillSpec.RetryPolicy(3, Set.of(SkillOutcome.FailureKind.PRECONDITION)),
                MissionPolicy.MutationScope.SURVIVAL);
        assertFalse(GoalExecutor.canReuseUniqueRecompiledReservation(
                unique, table, false));
        assertTrue(GoalExecutor.requiresDurableInterruptionAction(
                MissionPolicy.InterruptionPolicy.REPLAN_AFTER_SAFETY));
        assertTrue(GoalExecutor.requiresDurableInterruptionAction(
                MissionPolicy.InterruptionPolicy.CANCEL_ON_INTERRUPT));
        assertFalse(GoalExecutor.requiresDurableInterruptionAction(
                MissionPolicy.InterruptionPolicy.RESUME_AFTER_SAFETY));
        assertFalse(GoalExecutor.requiresDurableInterruptionAction(null));
        MissionPolicy cancelPolicy = new MissionPolicy(
                MissionPolicy.RiskLevel.BALANCED,
                MissionPolicy.MutationScope.SURVIVAL,
                1_000,
                2,
                MissionPolicy.InterruptionPolicy.CANCEL_ON_INTERRUPT);
        assertTrue(GoalExecutor.restoredCancellationRequired(true, cancelPolicy));
        assertFalse(GoalExecutor.restoredCancellationRequired(false, cancelPolicy));
        assertFalse(GoalExecutor.restoredCancellationRequired(true, MissionPolicy.standard()));
        assertFalse(GoalExecutor.restoredCancellationRequired(true, null));
        SkillOutcome resumeFailure = GoalExecutor.interruptionResumeFailureOutcome(
                new IllegalStateException("injected"));
        assertEquals(SkillOutcome.Status.FATAL_FAILURE, resumeFailure.status());
        assertEquals(SkillOutcome.FailureKind.INTERNAL, resumeFailure.failureKind());
        assertEquals("mission_resume_after_interrupt_failed:IllegalStateException",
                resumeFailure.reason());

        UUID missionId = UUID.randomUUID();
        String planFingerprint = "b".repeat(64);
        CursorCheckpoint cursor = new CursorCheckpoint(
                CursorCheckpoint.CURRENT_VERSION,
                missionId,
                0,
                planFingerprint,
                0,
                Map.of("root", new CursorCheckpoint.NodeState(
                        CursorCheckpoint.NodePhase.ACTIVE,
                        0,
                        0,
                        -1,
                        -1,
                        false,
                        null,
                        null)),
                Map.of("step.one", 1),
                Set.of(),
                Set.of());
        MissionCheckpointCodec.Checkpoint pendingCancel =
                new MissionCheckpointCodec.Checkpoint(
                        missionId,
                        0,
                        planFingerprint,
                        "c".repeat(64),
                        "d".repeat(64),
                        0,
                        0,
                        RecoveryLedger.Snapshot.empty(),
                        MissionCheckpointCodec.ProgressSnapshot.legacy(0),
                        true,
                        cursor);
        MissionSpec cancelSpec = MissionSpec.fromGoal(
                new Goal.Food(1),
                GoalSpec.Source.PLAYER_COMMAND,
                90,
                cancelPolicy);
        MissionRecord authenticatedCancel = new MissionRecord(
                missionId.toString(),
                cancelSpec,
                MissionCheckpointCodec.encode(Map.of(), pendingCancel));

        assertEquals(Optional.of(missionId),
                GoalExecutor.authenticatedRestoredCancellation(authenticatedCancel));
        assertThrows(java.io.IOException.class,
                () -> GoalExecutor.authenticatedRestoredCancellation(new MissionRecord(
                        UUID.randomUUID().toString(),
                        cancelSpec,
                        authenticatedCancel.checkpoint())));
        MissionSpec unboundCancel = new MissionSpec(
                cancelSpec.type(),
                cancelSpec.params(),
                cancelSpec.values(),
                cancelSpec.source(),
                cancelSpec.priority(),
                cancelSpec.policy(),
                "");
        assertThrows(java.io.IOException.class,
                () -> GoalExecutor.authenticatedRestoredCancellation(new MissionRecord(
                        missionId.toString(),
                        unboundCancel,
                        authenticatedCancel.checkpoint())));

        MissionSpec resumeSpec = MissionSpec.fromGoal(
                new Goal.Food(1),
                GoalSpec.Source.PLAYER_COMMAND,
                90,
                MissionPolicy.standard());
        assertTrue(GoalExecutor.authenticatedRestoredCancellation(new MissionRecord(
                missionId.toString(),
                resumeSpec,
                authenticatedCancel.checkpoint())).isEmpty());
    }
}
