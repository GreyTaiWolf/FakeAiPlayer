package io.github.greytaiwolf.fakeaiplayer.mission;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkillRuntimeGateTest {
    @Test
    void compatibilityConstructorsDefaultToConservativeRisk() {
        SkillSpec singleInvocation = new SkillSpec(
                "test.skill",
                1,
                Map.of(),
                List.of(),
                List.of("success"),
                SkillSpec.RetryPolicy.standard(),
                MissionPolicy.MutationScope.NONE);
        SkillSpec namedInvocation = new SkillSpec(
                "call.1",
                "test.skill",
                1,
                Map.of(),
                List.of(),
                List.of("success"),
                SkillSpec.RetryPolicy.standard(),
                MissionPolicy.MutationScope.NONE);

        assertEquals(MissionPolicy.RiskLevel.CONSERVATIVE, singleInvocation.requiredRisk());
        assertEquals(MissionPolicy.RiskLevel.CONSERVATIVE, namedInvocation.requiredRisk());
    }

    @Test
    void missionRiskIsAHardUpperBoundCheckedBeforePredicates() {
        AtomicBoolean evaluated = new AtomicBoolean();
        SkillRuntimeGate.GateResult rejected = SkillRuntimeGate.beforeStart(
                goal(MissionPolicy.RiskLevel.CONSERVATIVE, MissionPolicy.MutationScope.SURVIVAL),
                skill(MissionPolicy.RiskLevel.BALANCED, MissionPolicy.MutationScope.SURVIVAL,
                        List.of("world:bound_dimension")),
                (skill, predicate, phase) -> {
                    evaluated.set(true);
                    return SkillRuntimeGate.PredicateResult.satisfied(Map.of());
                });

        assertFalse(rejected.allowed());
        assertFalse(evaluated.get());
        assertEquals(SkillOutcome.Status.FATAL_FAILURE, rejected.rejection().status());
        assertEquals("skill_risk_exceeds_mission_policy", rejected.rejection().reason());
        assertEquals("CONSERVATIVE", rejected.rejection().evidence().get("mission_risk"));
        assertEquals("BALANCED", rejected.rejection().evidence().get("skill_risk"));

        SkillRuntimeGate.GateResult allowed = SkillRuntimeGate.beforeStart(
                goal(MissionPolicy.RiskLevel.BALANCED, MissionPolicy.MutationScope.SURVIVAL),
                skill(MissionPolicy.RiskLevel.BALANCED, MissionPolicy.MutationScope.SURVIVAL,
                        List.of("world:bound_dimension")),
                (skill, predicate, phase) ->
                        SkillRuntimeGate.PredicateResult.satisfied(Map.of()));
        assertTrue(allowed.allowed());
    }

    @Test
    void mutationScopeCannotExceedMissionScope() {
        SkillRuntimeGate.GateResult result = SkillRuntimeGate.beforeStart(
                goal(MissionPolicy.RiskLevel.BALANCED, MissionPolicy.MutationScope.NONE),
                skill(MissionPolicy.RiskLevel.CONSERVATIVE,
                        MissionPolicy.MutationScope.SURVIVAL, List.of()),
                (skill, predicate, phase) ->
                        SkillRuntimeGate.PredicateResult.satisfied(Map.of()));

        assertFalse(result.allowed());
        assertEquals(SkillOutcome.Status.FATAL_FAILURE, result.rejection().status());
        assertEquals("skill_mutation_scope_exceeds_mission_policy", result.rejection().reason());
    }

    @Test
    void unknownPreconditionFailsClosed() {
        SkillRuntimeGate.GateResult result = SkillRuntimeGate.beforeStart(
                goal(MissionPolicy.RiskLevel.BALANCED, MissionPolicy.MutationScope.SURVIVAL),
                skill(MissionPolicy.RiskLevel.CONSERVATIVE,
                        MissionPolicy.MutationScope.SURVIVAL, List.of("unknown:condition")),
                (skill, predicate, phase) -> SkillRuntimeGate.PredicateResult.unknown(""));

        assertFalse(result.allowed());
        assertEquals(SkillOutcome.Status.FATAL_FAILURE, result.rejection().status());
        assertEquals(SkillOutcome.FailureKind.INTERNAL, result.rejection().failureKind());
        assertEquals("unsupported_precondition:unknown:condition", result.rejection().reason());
    }

    @Test
    void falseSuccessPredicateCanNeverReportSuccess() {
        SkillOutcome outcome = SkillRuntimeGate.verifySuccess(
                skill(MissionPolicy.RiskLevel.CONSERVATIVE,
                        MissionPolicy.MutationScope.NONE, List.of()),
                (skill, predicate, phase) -> SkillRuntimeGate.PredicateResult.unsatisfied(
                        SkillOutcome.FailureKind.NONE, "", Map.of("observed", "false")),
                7,
                Map.of("adapter", "finished"));

        assertEquals(SkillOutcome.Status.RETRYABLE_FAILURE, outcome.status());
        assertEquals(SkillOutcome.FailureKind.WORLD_CHANGED, outcome.failureKind());
        assertEquals("success_predicate_unsatisfied:success", outcome.reason());
        assertEquals(7, outcome.progress());
        assertEquals("false", outcome.evidence().get("observed"));
    }

    @Test
    void unknownSuccessPredicateFailsClosed() {
        SkillOutcome outcome = SkillRuntimeGate.verifySuccess(
                skill(MissionPolicy.RiskLevel.CONSERVATIVE,
                        MissionPolicy.MutationScope.NONE, List.of()),
                (skill, predicate, phase) -> SkillRuntimeGate.PredicateResult.unknown(""),
                2,
                Map.of());

        assertEquals(SkillOutcome.Status.FATAL_FAILURE, outcome.status());
        assertEquals(SkillOutcome.FailureKind.INTERNAL, outcome.failureKind());
        assertEquals("unsupported_success_predicate:success", outcome.reason());
    }

    @Test
    void supportCheckExceptionsFailClosedInBothPhases() {
        SkillRuntimeGate.PredicateEvaluator throwing = new SkillRuntimeGate.PredicateEvaluator() {
            @Override
            public SkillRuntimeGate.PredicateResult evaluate(
                    SkillSpec skill, String predicate, SkillRuntimeGate.Phase phase) {
                return SkillRuntimeGate.PredicateResult.satisfied(Map.of());
            }

            @Override
            public boolean supports(SkillSpec skill) {
                throw new IllegalStateException("broken_registry");
            }
        };
        SkillSpec skill = skill(MissionPolicy.RiskLevel.CONSERVATIVE,
                MissionPolicy.MutationScope.NONE, List.of());

        SkillRuntimeGate.GateResult before = SkillRuntimeGate.beforeStart(
                goal(MissionPolicy.RiskLevel.CONSERVATIVE, MissionPolicy.MutationScope.NONE),
                skill,
                throwing);
        SkillOutcome after = SkillRuntimeGate.verifySuccess(skill, throwing, 0, Map.of());

        assertFalse(before.allowed());
        assertEquals(SkillOutcome.Status.FATAL_FAILURE, before.rejection().status());
        assertEquals("skill_support_check_failed:IllegalStateException",
                before.rejection().reason());
        assertEquals(SkillOutcome.Status.FATAL_FAILURE, after.status());
        assertEquals("skill_support_check_failed:IllegalStateException", after.reason());
    }

    @Test
    void contradictoryUnsatisfiedKindsAreMappedToTypedTerminalOutcomes() {
        SkillSpec skill = skill(MissionPolicy.RiskLevel.CONSERVATIVE,
                MissionPolicy.MutationScope.NONE, List.of("precondition"));
        GoalSpec goal = goal(MissionPolicy.RiskLevel.CONSERVATIVE,
                MissionPolicy.MutationScope.NONE);

        SkillRuntimeGate.GateResult blocked = SkillRuntimeGate.beforeStart(
                goal, skill,
                (ignored, predicate, phase) -> SkillRuntimeGate.PredicateResult.unsatisfied(
                        SkillOutcome.FailureKind.RESOURCE_UNAVAILABLE,
                        "resource_missing", Map.of()));
        SkillRuntimeGate.GateResult fatal = SkillRuntimeGate.beforeStart(
                goal, skill,
                (ignored, predicate, phase) -> SkillRuntimeGate.PredicateResult.unsatisfied(
                        SkillOutcome.FailureKind.INTERNAL,
                        "adapter_broken", Map.of()));

        assertEquals(SkillOutcome.Status.BLOCKED, blocked.rejection().status());
        assertEquals(SkillOutcome.Status.FATAL_FAILURE, fatal.rejection().status());
    }

    @Test
    void evaluatorEvidenceCannotOverwriteGateVerdicts() {
        SkillSpec skill = skill(MissionPolicy.RiskLevel.CONSERVATIVE,
                MissionPolicy.MutationScope.NONE, List.of("ready"));
        SkillRuntimeGate.GateResult result = SkillRuntimeGate.beforeStart(
                goal(MissionPolicy.RiskLevel.CONSERVATIVE, MissionPolicy.MutationScope.NONE),
                skill,
                (ignored, predicate, phase) -> SkillRuntimeGate.PredicateResult.satisfied(
                        Map.of("precondition:ready", "FORGED")));

        assertTrue(result.allowed());
        assertEquals("SATISFIED", result.evidence().get("precondition:ready"));
        assertEquals("FORGED",
                result.evidence().get("predicate:ready:precondition:ready"));
    }

    @Test
    void predicateStatesRejectContradictoryFailureKinds() {
        assertThrows(IllegalArgumentException.class, () -> new SkillRuntimeGate.PredicateResult(
                SkillRuntimeGate.PredicateState.SATISFIED,
                SkillOutcome.FailureKind.PRECONDITION,
                "contradiction",
                Map.of()));
        assertThrows(IllegalArgumentException.class, () -> new SkillRuntimeGate.PredicateResult(
                SkillRuntimeGate.PredicateState.UNKNOWN,
                SkillOutcome.FailureKind.NONE,
                "contradiction",
                Map.of()));
    }

    @Test
    void declaredPredicateOrderIsObservableAndPartOfTheRecoveryIdentity() {
        SkillSpec resourceFirst = skill(MissionPolicy.RiskLevel.CONSERVATIVE,
                MissionPolicy.MutationScope.NONE, List.of("resource", "adapter"));
        SkillSpec adapterFirst = skill(MissionPolicy.RiskLevel.CONSERVATIVE,
                MissionPolicy.MutationScope.NONE, List.of("adapter", "resource"));
        SkillRuntimeGate.PredicateEvaluator evaluator = (ignored, predicate, phase) ->
                predicate.equals("resource")
                        ? SkillRuntimeGate.PredicateResult.unsatisfied(
                                SkillOutcome.FailureKind.RESOURCE_UNAVAILABLE,
                                "resource_missing", Map.of())
                        : SkillRuntimeGate.PredicateResult.unsatisfied(
                                SkillOutcome.FailureKind.INTERNAL,
                                "adapter_broken", Map.of());

        SkillRuntimeGate.GateResult blocked = SkillRuntimeGate.beforeStart(
                goal(MissionPolicy.RiskLevel.CONSERVATIVE, MissionPolicy.MutationScope.NONE),
                resourceFirst, evaluator);
        SkillRuntimeGate.GateResult fatal = SkillRuntimeGate.beforeStart(
                goal(MissionPolicy.RiskLevel.CONSERVATIVE, MissionPolicy.MutationScope.NONE),
                adapterFirst, evaluator);

        assertEquals(SkillOutcome.Status.BLOCKED, blocked.rejection().status());
        assertEquals(SkillOutcome.Status.FATAL_FAILURE, fatal.rejection().status());
        assertNotEquals(RecoveryLedger.fingerprint(resourceFirst),
                RecoveryLedger.fingerprint(adapterFirst));
    }

    private static GoalSpec goal(MissionPolicy.RiskLevel risk,
                                 MissionPolicy.MutationScope scope) {
        return new GoalSpec(
                "test_goal",
                GoalSpec.Source.PLAYER_COMMAND,
                90,
                "goal_success",
                "minecraft:overworld",
                new MissionPolicy(
                        risk,
                        scope,
                        1_000,
                        3,
                        MissionPolicy.InterruptionPolicy.RESUME_AFTER_SAFETY),
                Map.of());
    }

    private static SkillSpec skill(MissionPolicy.RiskLevel requiredRisk,
                                   MissionPolicy.MutationScope scope,
                                   List<String> preconditions) {
        return new SkillSpec(
                "call.1",
                "test.skill",
                1,
                Map.of(),
                preconditions,
                List.of("success"),
                SkillSpec.RetryPolicy.standard(),
                scope,
                requiredRisk);
    }
}
