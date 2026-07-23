package io.github.greytaiwolf.fakeaiplayer.mission;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Declarative, versioned capability call. {@code id} names the reusable capability, while
 * {@code invocationId} uniquely addresses this call inside one MissionPlan. It does not contain
 * executable code or world objects.
 */
public record SkillSpec(
        String invocationId,
        String id,
        int version,
        Map<String, String> parameters,
        List<String> preconditions,
        List<String> successPredicates,
        RetryPolicy retryPolicy,
        MissionPolicy.MutationScope mutationScope,
        MissionPolicy.RiskLevel requiredRisk
) {
    /**
     * Backwards-compatible constructor for contracts written before per-Skill risk was explicit.
     * Existing capabilities default to the safest execution class.
     */
    public SkillSpec(String invocationId,
                     String id,
                     int version,
                     Map<String, String> parameters,
                     List<String> preconditions,
                     List<String> successPredicates,
                     RetryPolicy retryPolicy,
                     MissionPolicy.MutationScope mutationScope) {
        this(invocationId, id, version, parameters, preconditions, successPredicates,
                retryPolicy, mutationScope, MissionPolicy.RiskLevel.CONSERVATIVE);
    }

    /** Backwards-compatible constructor for callers that model a single invocation per capability. */
    public SkillSpec(String id,
                     int version,
                     Map<String, String> parameters,
                     List<String> preconditions,
                     List<String> successPredicates,
                     RetryPolicy retryPolicy,
                     MissionPolicy.MutationScope mutationScope) {
        this(id, id, version, parameters, preconditions, successPredicates, retryPolicy,
                mutationScope, MissionPolicy.RiskLevel.CONSERVATIVE);
    }

    public SkillSpec {
        if (invocationId == null || !invocationId.matches("[a-z0-9_.-]+")) {
            throw new IllegalArgumentException("invalid_skill_invocation_id");
        }
        if (id == null || !id.matches("[a-z0-9_.-]+")) {
            throw new IllegalArgumentException("invalid_skill_id");
        }
        if (version < 1) {
            throw new IllegalArgumentException("invalid_skill_version");
        }
        parameters = parameters == null ? Map.of() : Map.copyOf(parameters);
        preconditions = preconditions == null ? List.of() : List.copyOf(preconditions);
        successPredicates = successPredicates == null ? List.of() : List.copyOf(successPredicates);
        if (successPredicates.isEmpty()) {
            throw new IllegalArgumentException("skill_requires_success_predicate");
        }
        retryPolicy = retryPolicy == null ? RetryPolicy.standard() : retryPolicy;
        mutationScope = mutationScope == null ? MissionPolicy.MutationScope.SURVIVAL : mutationScope;
        requiredRisk = requiredRisk == null
                ? MissionPolicy.RiskLevel.CONSERVATIVE : requiredRisk;
    }

    public record RetryPolicy(
            int maxAttempts,
            Set<SkillOutcome.FailureKind> replanOn
    ) {
        public RetryPolicy {
            if (maxAttempts < 1) {
                throw new IllegalArgumentException("skill_max_attempts_must_be_positive");
            }
            replanOn = replanOn == null ? Set.of() : Set.copyOf(replanOn);
            if (replanOn.contains(SkillOutcome.FailureKind.NONE)
                    || replanOn.contains(SkillOutcome.FailureKind.CANCELLED)
                    || replanOn.contains(SkillOutcome.FailureKind.INTERNAL)) {
                throw new IllegalArgumentException("non_retryable_failure_in_retry_policy");
            }
        }

        public static RetryPolicy standard() {
            return new RetryPolicy(3, Set.of(
                    SkillOutcome.FailureKind.PRECONDITION,
                    SkillOutcome.FailureKind.PATH_UNREACHABLE,
                    SkillOutcome.FailureKind.WORLD_CHANGED,
                    SkillOutcome.FailureKind.SAFETY,
                    SkillOutcome.FailureKind.TIMEOUT,
                    SkillOutcome.FailureKind.UNKNOWN));
        }

        public boolean mayReplan(SkillOutcome outcome, int attemptsSoFar) {
            return outcome != null
                    && attemptsSoFar < maxAttempts
                    && outcome.retryable()
                    && replanOn.contains(outcome.failureKind());
        }
    }
}
