package io.github.greytaiwolf.fakeaiplayer.mission;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Fail-closed execution boundary for declarative Skills.
 *
 * <p>The planner may describe a capability, but only a registered runtime adapter may execute it.
 * Every declared precondition is checked before installation, and every success predicate is
 * checked again against authoritative state after the adapter reports completion.</p>
 */
public final class SkillRuntimeGate {
    private SkillRuntimeGate() {
    }

    public static GateResult beforeStart(GoalSpec goal, SkillSpec skill, PredicateEvaluator evaluator) {
        if (goal == null || skill == null || evaluator == null) {
            return rejected(fatal("skill_runtime_gate_incomplete"));
        }
        if (!scopeAllowed(goal.policy().mutationScope(), skill.mutationScope())) {
            return rejected(new SkillOutcome(
                    SkillOutcome.Status.FATAL_FAILURE,
                    SkillOutcome.FailureKind.INTERNAL,
                    "skill_mutation_scope_exceeds_mission_policy",
                    0,
                    Map.of("mission_scope", goal.policy().mutationScope().name(),
                            "skill_scope", skill.mutationScope().name())));
        }
        if (!riskAllowed(goal.policy().riskLevel(), skill.requiredRisk())) {
            return rejected(new SkillOutcome(
                    SkillOutcome.Status.FATAL_FAILURE,
                    SkillOutcome.FailureKind.INTERNAL,
                    "skill_risk_exceeds_mission_policy",
                    0,
                    Map.of("mission_risk", goal.policy().riskLevel().name(),
                            "skill_risk", skill.requiredRisk().name())));
        }
        SupportResult support = safeSupports(evaluator, skill);
        if (!support.supported()) {
            return rejected(new SkillOutcome(
                    SkillOutcome.Status.FATAL_FAILURE,
                    SkillOutcome.FailureKind.INTERNAL,
                    support.reason().isBlank()
                            ? "unsupported_skill_contract:" + skill.id() + "@" + skill.version()
                            : support.reason(),
                    0,
                    Map.of("skill", skill.invocationId())));
        }

        Map<String, String> evidence = new LinkedHashMap<>();
        for (String predicate : skill.preconditions()) {
            PredicateResult result = safeEvaluate(evaluator, skill, predicate, Phase.PRECONDITION);
            if (!mergeEvidence(evidence, result.evidence(), "predicate:" + predicate + ':')) {
                return rejected(fatal("predicate_evidence_invalid:" + predicate));
            }
            evidence.put("precondition:" + predicate, result.state().name());
            if (result.state() == PredicateState.SATISFIED) {
                continue;
            }
            if (result.state() == PredicateState.UNKNOWN) {
                return rejected(new SkillOutcome(
                        SkillOutcome.Status.FATAL_FAILURE,
                        SkillOutcome.FailureKind.INTERNAL,
                        result.reason().isBlank()
                                ? "unsupported_precondition:" + predicate : result.reason(),
                        0,
                        evidence));
            }
            SkillOutcome.FailureKind kind = result.failureKind() == SkillOutcome.FailureKind.NONE
                    ? SkillOutcome.FailureKind.PRECONDITION : result.failureKind();
            return rejected(new SkillOutcome(
                    statusForUnsatisfied(kind),
                    kind,
                    result.reason().isBlank()
                            ? "precondition_unsatisfied:" + predicate : result.reason(),
                    0,
                    evidence));
        }
        return new GateResult(true, null, Map.copyOf(evidence));
    }

    public static SkillOutcome verifySuccess(SkillSpec skill,
                                             PredicateEvaluator evaluator,
                                             int progress,
                                             Map<String, String> adapterEvidence) {
        if (skill == null || evaluator == null) {
            return fatal("unsupported_skill_contract_at_verification");
        }
        SupportResult support = safeSupports(evaluator, skill);
        if (!support.supported()) {
            return fatal(support.reason().isBlank()
                    ? "unsupported_skill_contract_at_verification" : support.reason());
        }
        Map<String, String> evidence = new LinkedHashMap<>();
        if (!mergeEvidence(evidence, adapterEvidence, "adapter:")) {
            return fatal("adapter_evidence_invalid");
        }
        for (String predicate : skill.successPredicates()) {
            PredicateResult result = safeEvaluate(evaluator, skill, predicate, Phase.SUCCESS);
            if (!mergeEvidence(evidence, result.evidence(), "predicate:" + predicate + ':')) {
                return fatal("predicate_evidence_invalid:" + predicate);
            }
            evidence.put("success:" + predicate, result.state().name());
            if (result.state() == PredicateState.SATISFIED) {
                continue;
            }
            if (result.state() == PredicateState.UNKNOWN) {
                return new SkillOutcome(
                        SkillOutcome.Status.FATAL_FAILURE,
                        SkillOutcome.FailureKind.INTERNAL,
                        result.reason().isBlank()
                                ? "unsupported_success_predicate:" + predicate : result.reason(),
                        progress,
                        evidence);
            }
            SkillOutcome.FailureKind kind = result.failureKind() == SkillOutcome.FailureKind.NONE
                    ? SkillOutcome.FailureKind.WORLD_CHANGED : result.failureKind();
            return new SkillOutcome(
                    statusForUnsatisfied(kind),
                    kind,
                    result.reason().isBlank()
                            ? "success_predicate_unsatisfied:" + predicate : result.reason(),
                    progress,
                    evidence);
        }
        return SkillOutcome.succeeded(progress, evidence);
    }

    private static PredicateResult safeEvaluate(PredicateEvaluator evaluator,
                                                SkillSpec skill,
                                                String predicate,
                                                Phase phase) {
        if (predicate == null || predicate.isBlank()) {
            return PredicateResult.unknown("blank_skill_predicate");
        }
        try {
            PredicateResult result = evaluator.evaluate(skill, predicate, phase);
            return result == null ? PredicateResult.unknown("predicate_evaluator_returned_null") : result;
        } catch (RuntimeException exception) {
            return PredicateResult.unknown(
                    "predicate_evaluator_failed:" + exception.getClass().getSimpleName());
        }
    }

    private static SupportResult safeSupports(PredicateEvaluator evaluator, SkillSpec skill) {
        try {
            return evaluator.supports(skill)
                    ? new SupportResult(true, "")
                    : new SupportResult(false, "");
        } catch (RuntimeException exception) {
            return new SupportResult(false,
                    "skill_support_check_failed:" + exception.getClass().getSimpleName());
        }
    }

    private static SkillOutcome.Status statusForUnsatisfied(SkillOutcome.FailureKind kind) {
        return switch (kind) {
            case INTERNAL -> SkillOutcome.Status.FATAL_FAILURE;
            case CANCELLED -> SkillOutcome.Status.CANCELLED;
            case RESOURCE_UNAVAILABLE -> SkillOutcome.Status.BLOCKED;
            case NONE -> throw new IllegalArgumentException("unsatisfied_predicate_requires_kind");
            default -> SkillOutcome.Status.RETRYABLE_FAILURE;
        };
    }

    /** Reject null evidence and keep adapter-controlled keys from replacing gate verdicts. */
    private static boolean mergeEvidence(Map<String, String> target,
                                         Map<String, String> source,
                                         String reservedPrefix) {
        if (source == null) {
            return true;
        }
        try {
            for (Map.Entry<String, String> entry : source.entrySet()) {
                if (entry.getKey() == null || entry.getValue() == null) {
                    return false;
                }
                String key = entry.getKey();
                if (key.startsWith("precondition:") || key.startsWith("success:")) {
                    key = reservedPrefix + key;
                }
                target.put(key, entry.getValue());
            }
            return true;
        } catch (RuntimeException exception) {
            return false;
        }
    }

    /** CONFIRMED_AREA Missions may contain ordinary supply Skills before the bounded build Skill. */
    public static boolean scopeAllowed(MissionPolicy.MutationScope mission,
                                       MissionPolicy.MutationScope skill) {
        if (mission == null || skill == null) {
            return false;
        }
        return switch (mission) {
            case NONE -> skill == MissionPolicy.MutationScope.NONE;
            case SURVIVAL -> skill != MissionPolicy.MutationScope.CONFIRMED_AREA;
            case CONFIRMED_AREA -> true;
        };
    }

    /** A Mission risk level is the maximum execution risk that its Skills may require. */
    public static boolean riskAllowed(MissionPolicy.RiskLevel mission,
                                      MissionPolicy.RiskLevel required) {
        if (mission == null || required == null) {
            return false;
        }
        return switch (mission) {
            case CONSERVATIVE -> required == MissionPolicy.RiskLevel.CONSERVATIVE;
            case BALANCED -> required != MissionPolicy.RiskLevel.BOLD;
            case BOLD -> true;
        };
    }

    private static GateResult rejected(SkillOutcome rejection) {
        return new GateResult(false, rejection,
                rejection == null ? Map.of() : rejection.evidence());
    }

    private static SkillOutcome fatal(String reason) {
        return new SkillOutcome(SkillOutcome.Status.FATAL_FAILURE,
                SkillOutcome.FailureKind.INTERNAL, reason, 0, Map.of());
    }

    @FunctionalInterface
    public interface PredicateEvaluator {
        PredicateResult evaluate(SkillSpec skill, String predicate, Phase phase);

        default boolean supports(SkillSpec skill) {
            return true;
        }
    }

    public record GateResult(boolean allowed, SkillOutcome rejection, Map<String, String> evidence) {
        public GateResult {
            evidence = evidence == null ? Map.of() : Map.copyOf(evidence);
            if (allowed == (rejection != null)) {
                throw new IllegalArgumentException("skill_gate_result_inconsistent");
            }
        }
    }

    public record PredicateResult(
            PredicateState state,
            SkillOutcome.FailureKind failureKind,
            String reason,
            Map<String, String> evidence
    ) {
        public PredicateResult {
            state = state == null ? PredicateState.UNKNOWN : state;
            failureKind = failureKind == null ? SkillOutcome.FailureKind.INTERNAL : failureKind;
            reason = reason == null ? "" : reason;
            evidence = evidence == null ? Map.of() : Map.copyOf(evidence);
            if (state == PredicateState.SATISFIED
                    && failureKind != SkillOutcome.FailureKind.NONE) {
                throw new IllegalArgumentException("satisfied_predicate_cannot_have_failure_kind");
            }
            if (state == PredicateState.UNKNOWN
                    && failureKind != SkillOutcome.FailureKind.INTERNAL) {
                throw new IllegalArgumentException("unknown_predicate_requires_internal_kind");
            }
        }

        public static PredicateResult satisfied(Map<String, String> evidence) {
            return new PredicateResult(PredicateState.SATISFIED, SkillOutcome.FailureKind.NONE,
                    "", evidence);
        }

        public static PredicateResult unsatisfied(SkillOutcome.FailureKind kind,
                                                  String reason,
                                                  Map<String, String> evidence) {
            return new PredicateResult(PredicateState.UNSATISFIED, kind, reason, evidence);
        }

        public static PredicateResult unknown(String reason) {
            return new PredicateResult(PredicateState.UNKNOWN, SkillOutcome.FailureKind.INTERNAL,
                    reason, Map.of());
        }
    }

    public enum PredicateState {
        SATISFIED,
        UNSATISFIED,
        UNKNOWN
    }

    public enum Phase {
        PRECONDITION,
        SUCCESS
    }

    private record SupportResult(boolean supported, String reason) {
    }
}
