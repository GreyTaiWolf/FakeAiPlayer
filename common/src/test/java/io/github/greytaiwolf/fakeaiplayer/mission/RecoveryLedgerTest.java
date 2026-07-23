package io.github.greytaiwolf.fakeaiplayer.mission;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RecoveryLedgerTest {
    @Test
    void fingerprintIsStableAcrossInvocationIdentityAndParameterIterationOrder() {
        Map<String, String> forward = new LinkedHashMap<>();
        forward.put("item", "minecraft:iron_ingot");
        forward.put("quota", "3");
        Map<String, String> reverse = new LinkedHashMap<>();
        reverse.put("quota", "3");
        reverse.put("item", "minecraft:iron_ingot");

        SkillSpec first = skill("call.one", "legacy.craft", 1, forward, 3);
        SkillSpec recompiled = skill("step.7.legacy.craft", "legacy.craft", 1, reverse, 3);
        SkillSpec differentParameters = skill("call.two", "legacy.craft", 1,
                Map.of("item", "minecraft:iron_ingot", "quota", "4"), 3);
        SkillSpec differentVersion = skill("call.three", "legacy.craft", 2, reverse, 3);

        assertEquals(RecoveryLedger.fingerprint(first), RecoveryLedger.fingerprint(recompiled));
        assertNotEquals(RecoveryLedger.fingerprint(first),
                RecoveryLedger.fingerprint(differentParameters));
        assertNotEquals(RecoveryLedger.fingerprint(first),
                RecoveryLedger.fingerprint(differentVersion));
        assertTrue(RecoveryLedger.fingerprint(first).matches("[0-9a-f]{64}"));
    }

    @Test
    void fingerprintCoversTheCompleteSemanticContract() {
        SkillSpec baseline = new SkillSpec(
                "call.one",
                "legacy.mine_ore",
                1,
                Map.of("quota", "3"),
                List.of("world:bound_dimension", "inventory:pickaxe"),
                List.of("inventory_delta:3", "world:still_bound"),
                new SkillSpec.RetryPolicy(3, Set.of(
                        SkillOutcome.FailureKind.TIMEOUT,
                        SkillOutcome.FailureKind.WORLD_CHANGED)),
                MissionPolicy.MutationScope.SURVIVAL,
                MissionPolicy.RiskLevel.BALANCED);

        List<SkillSpec> semanticallyDifferent = List.of(
                new SkillSpec("call.two", baseline.id(), baseline.version(), baseline.parameters(),
                        List.of("inventory:pickaxe"), baseline.successPredicates(),
                        baseline.retryPolicy(), baseline.mutationScope(), baseline.requiredRisk()),
                new SkillSpec("call.two", baseline.id(), baseline.version(), baseline.parameters(),
                        baseline.preconditions(), List.of("inventory_delta:4"),
                        baseline.retryPolicy(), baseline.mutationScope(), baseline.requiredRisk()),
                new SkillSpec("call.two", baseline.id(), baseline.version(), baseline.parameters(),
                        baseline.preconditions(), baseline.successPredicates(),
                        new SkillSpec.RetryPolicy(2, baseline.retryPolicy().replanOn()),
                        baseline.mutationScope(), baseline.requiredRisk()),
                new SkillSpec("call.two", baseline.id(), baseline.version(), baseline.parameters(),
                        baseline.preconditions(), baseline.successPredicates(),
                        new SkillSpec.RetryPolicy(3, Set.of(SkillOutcome.FailureKind.TIMEOUT)),
                        baseline.mutationScope(), baseline.requiredRisk()),
                new SkillSpec("call.two", baseline.id(), baseline.version(), baseline.parameters(),
                        baseline.preconditions(), baseline.successPredicates(), baseline.retryPolicy(),
                        MissionPolicy.MutationScope.CONFIRMED_AREA, baseline.requiredRisk()),
                new SkillSpec("call.two", baseline.id(), baseline.version(), baseline.parameters(),
                        baseline.preconditions(), baseline.successPredicates(), baseline.retryPolicy(),
                        baseline.mutationScope(), MissionPolicy.RiskLevel.BOLD));

        String baselineFingerprint = RecoveryLedger.fingerprint(baseline);
        for (SkillSpec different : semanticallyDifferent) {
            assertNotEquals(baselineFingerprint, RecoveryLedger.fingerprint(different),
                    different.toString());
        }
        SkillSpec differentInvocationOnly = new SkillSpec(
                "recompiled.step.9", baseline.id(), baseline.version(), baseline.parameters(),
                baseline.preconditions(), baseline.successPredicates(), baseline.retryPolicy(),
                baseline.mutationScope(), baseline.requiredRisk());
        assertEquals(baselineFingerprint, RecoveryLedger.fingerprint(differentInvocationOnly));

        SkillSpec reorderedPredicates = new SkillSpec(
                "recompiled.step.10", baseline.id(), baseline.version(), baseline.parameters(),
                List.of("inventory:pickaxe", "world:bound_dimension"),
                List.of("world:still_bound", "inventory_delta:3"), baseline.retryPolicy(),
                baseline.mutationScope(), baseline.requiredRisk());
        assertNotEquals(baselineFingerprint, RecoveryLedger.fingerprint(reorderedPredicates));
    }

    @Test
    void initialExecutionIsAttemptOneAndDeclaredMaximumIsExact() {
        RecoveryLedger ledger = new RecoveryLedger(10);
        SkillSpec skill = skill("call.mine", "legacy.mine_ore", 1,
                Map.of("ores", "minecraft:iron_ore", "quota", "3"), 3);

        RecoveryLedger.AttemptDecision first = ledger.beginAttempt(skill);
        RecoveryLedger.AttemptDecision second = ledger.beginAttempt(skill);
        RecoveryLedger.AttemptDecision third = ledger.beginAttempt(skill);
        RecoveryLedger.AttemptDecision denied = ledger.beginAttempt(skill);

        assertTrue(first.allowed());
        assertEquals(1, first.attempt());
        assertTrue(second.allowed());
        assertEquals(2, second.attempt());
        assertTrue(third.allowed());
        assertEquals(3, third.attempt());
        assertFalse(denied.allowed());
        assertEquals(3, denied.attempt());
        assertEquals("skill_attempt_budget_exhausted", denied.reason());
        assertEquals(3, ledger.attemptsFor(skill));
    }

    @Test
    void semanticSkillsKeepIndependentAttemptBudgets() {
        RecoveryLedger ledger = new RecoveryLedger(10);
        SkillSpec gather = skill("step.0", "legacy.gather", 1,
                Map.of("item", "minecraft:oak_log"), 2);
        SkillSpec craft = skill("step.1", "legacy.craft", 1,
                Map.of("item", "minecraft:oak_planks"), 2);

        assertEquals(1, ledger.beginAttempt(gather).attempt());
        assertEquals(2, ledger.beginAttempt(gather).attempt());
        assertFalse(ledger.beginAttempt(gather).allowed());
        assertEquals(1, ledger.beginAttempt(craft).attempt());
        assertEquals(2, ledger.attemptsFor(gather));
        assertEquals(1, ledger.attemptsFor(craft));
    }

    @Test
    void deferredInstallationCanRollBackItsAttemptReservation() {
        RecoveryLedger ledger = new RecoveryLedger(2);
        SkillSpec skill = skill("step.waiting", "legacy.gather", 1,
                Map.of("item", "minecraft:oak_log"), 2);

        RecoveryLedger.AttemptDecision reservation = ledger.beginAttempt(skill);

        assertEquals(1, ledger.attemptsFor(skill));
        ledger.rollbackDeferredAttempt(reservation);
        assertEquals(0, ledger.attemptsFor(skill));
        assertEquals(1, ledger.beginAttempt(skill).attempt());
    }

    @Test
    void verifiedProgressOpensNewSkillWindowButNeverRefundsMissionBudget() {
        RecoveryLedger ledger = new RecoveryLedger(4);
        SkillSpec build = skill("step.build", "legacy.build", 1,
                Map.of("blueprint_digest", "a".repeat(64)), 2);

        assertTrue(ledger.beginAttempt(build).allowed());
        assertTrue(ledger.consumeRecovery(
                RecoveryLedger.RecoveryKind.SKILL_FAILURE, false).allowed());
        assertEquals(1, ledger.consecutiveNoProgressRecoveries());
        assertEquals(3, ledger.recoveriesRemaining());

        ledger.markVerifiedProgress(build);

        assertEquals(0, ledger.attemptsFor(build));
        assertEquals(0, ledger.consecutiveNoProgressRecoveries());
        assertEquals(1, ledger.recoveriesConsumed());
        assertEquals(3, ledger.recoveriesRemaining());
        assertEquals(1, ledger.beginAttempt(build).attempt());
    }

    @Test
    void missionBudgetAllowsExactlyTheDeclaredNumberOfRecoveries() {
        RecoveryLedger ledger = new RecoveryLedger(2);

        RecoveryLedger.RecoveryDecision first = ledger.consumeRecovery(
                RecoveryLedger.RecoveryKind.SKILL_FAILURE, false);
        RecoveryLedger.RecoveryDecision second = ledger.consumeRecovery(
                RecoveryLedger.RecoveryKind.POSTCONDITION, false);
        RecoveryLedger.RecoveryDecision denied = ledger.consumeRecovery(
                RecoveryLedger.RecoveryKind.INTERRUPT_REPLAN, true);

        assertTrue(first.allowed());
        assertEquals(1, first.consumed());
        assertTrue(second.allowed());
        assertEquals(2, second.consumed());
        assertEquals(1, second.postconditionConsumed());
        assertFalse(denied.allowed());
        assertEquals(2, denied.consumed());
        assertEquals("mission_recovery_budget_exhausted", denied.reason());
        assertEquals(0, ledger.recoveriesRemaining());
    }

    @Test
    void restoredSnapshotRetainsAttemptsAndAllRecoveryCounters() {
        SkillSpec skill = skill("old.invocation", "legacy.smelt", 1,
                Map.of("input", "minecraft:raw_iron", "output", "minecraft:iron_ingot"), 4);
        RecoveryLedger before = new RecoveryLedger(6);
        before.beginAttempt(skill);
        before.beginAttempt(skill);
        before.consumeRecovery(RecoveryLedger.RecoveryKind.SKILL_FAILURE, false);
        before.consumeRecovery(RecoveryLedger.RecoveryKind.POSTCONDITION, false);

        RecoveryLedger restored = new RecoveryLedger(6, before.snapshot());
        SkillSpec recompiled = skill("new.invocation", "legacy.smelt", 1,
                Map.of("output", "minecraft:iron_ingot", "input", "minecraft:raw_iron"), 4);

        assertEquals(2, restored.attemptsFor(recompiled));
        assertEquals(2, restored.recoveriesConsumed());
        assertEquals(2, restored.consecutiveNoProgressRecoveries());
        assertEquals(1, restored.postconditionRecoveriesConsumed());
        assertEquals(3, restored.beginAttempt(recompiled).attempt());
    }

    @Test
    void deferredInstallationRollsBackOnlyTheLatestReservation() {
        RecoveryLedger ledger = new RecoveryLedger(2);
        SkillSpec skill = skill("call.defer", "legacy.craft", 1,
                Map.of("item", "minecraft:stick"), 2);

        RecoveryLedger.AttemptDecision first = ledger.beginAttempt(skill);
        RecoveryLedger.AttemptDecision deferred = ledger.beginAttempt(skill);

        assertThrows(IllegalStateException.class,
                () -> ledger.rollbackDeferredAttempt(first));
        ledger.rollbackDeferredAttempt(deferred);

        assertEquals(1, ledger.attemptsFor(skill));
        ledger.rollbackDeferredAttempt(first);
        assertEquals(0, ledger.attemptsFor(skill));
    }

    @Test
    void restoredRecoveryCountCannotExceedCurrentMissionBudget() {
        RecoveryLedger.Snapshot snapshot = new RecoveryLedger.Snapshot(Map.of(), 3, 0, 0);

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> new RecoveryLedger(2, snapshot));

        assertEquals("recovery_snapshot_exceeds_budget", failure.getMessage());
    }

    @Test
    void trackingCapacityFailsClosedBeforeTheSnapshotCanBecomeUnsaveable() {
        RecoveryLedger ledger = new RecoveryLedger(2);
        for (int index = 0; index < RecoveryLedger.maxTrackedSkills(); index++) {
            RecoveryLedger.AttemptDecision accepted = ledger.beginAttempt(skill(
                    "call." + index,
                    "test.unique",
                    1,
                    Map.of("index", String.valueOf(index)),
                    2));
            assertTrue(accepted.allowed(), "entry " + index);
        }

        RecoveryLedger.AttemptDecision denied = ledger.beginAttempt(skill(
                "call.overflow",
                "test.unique",
                1,
                Map.of("index", "overflow"),
                2));

        assertFalse(denied.allowed());
        assertEquals(0, denied.attempt());
        assertEquals("skill_attempt_tracking_capacity_exhausted", denied.reason());
        assertEquals(RecoveryLedger.maxTrackedSkills(),
                ledger.snapshot().attemptsBySkill().size());
    }

    private static SkillSpec skill(String invocationId,
                                   String id,
                                   int version,
                                   Map<String, String> parameters,
                                   int maxAttempts) {
        return new SkillSpec(
                invocationId,
                id,
                version,
                parameters,
                List.of(),
                List.of("verified"),
                new SkillSpec.RetryPolicy(maxAttempts, Set.of(SkillOutcome.FailureKind.UNKNOWN)),
                MissionPolicy.MutationScope.SURVIVAL);
    }
}
