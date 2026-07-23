package io.github.greytaiwolf.fakeaiplayer.mission;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** AND/OR-capable plan tree executed deterministically by {@link PlanCursor}. */
public sealed interface PlanNode permits PlanNode.Skill, PlanNode.Sequence, PlanNode.AllOf,
        PlanNode.AnyOf, PlanNode.Retry, PlanNode.Timeout, PlanNode.Checkpoint,
        PlanNode.WaitForEvent {
    /** A uniquely addressable capability invocation in the plan tree. */
    record Skill(String nodeId, SkillSpec spec) implements PlanNode {
        public Skill(SkillSpec spec) {
            this(spec == null ? null : spec.invocationId(), spec);
        }

        public Skill {
            if (spec == null) {
                throw new IllegalArgumentException("plan_skill_missing");
            }
            if (nodeId == null || !nodeId.matches("[a-z0-9_.-]+")) {
                throw new IllegalArgumentException("invalid_plan_node_id");
            }
        }
    }

    record Sequence(List<PlanNode> children) implements PlanNode {
        public Sequence {
            children = immutableChildren(children, "sequence");
        }
    }

    /** All children are required; a future DAG executor may schedule independent children. */
    record AllOf(List<PlanNode> children) implements PlanNode {
        public AllOf {
            children = immutableChildren(children, "all_of");
        }
    }

    /**
     * Candidate branches in planner preference order. The deterministic cursor tries each branch
     * after an ordinary blocked or retryable outcome until one succeeds. Fatal errors,
     * cancellation, and safety outcomes are control outcomes and propagate to the Mission
     * runtime instead of being hidden by a fallback branch. Planners may utility-score candidates
     * before constructing this node.
     */
    record AnyOf(List<PlanNode> children) implements PlanNode {
        public AnyOf {
            children = immutableChildren(children, "any_of");
        }
    }

    /**
     * Re-runs one child after an explicitly retryable, whitelisted local failure. Safety outcomes
     * belong to Mission arbitration and cannot be consumed by this immediate retry wrapper.
     */
    record Retry(
            PlanNode child,
            int maxAttempts,
            Set<SkillOutcome.FailureKind> retryOn
    ) implements PlanNode {
        public Retry(PlanNode child, int maxAttempts) {
            this(child, maxAttempts, SkillSpec.RetryPolicy.standard().replanOn().stream()
                    .filter(kind -> kind != SkillOutcome.FailureKind.SAFETY)
                    .collect(java.util.stream.Collectors.toUnmodifiableSet()));
        }

        public Retry {
            child = requireChild(child, "retry");
            if (maxAttempts < 1) {
                throw new IllegalArgumentException("retry_max_attempts_must_be_positive");
            }
            retryOn = retryOn == null ? Set.of() : Set.copyOf(retryOn);
            if (retryOn.contains(SkillOutcome.FailureKind.NONE)
                    || retryOn.contains(SkillOutcome.FailureKind.CANCELLED)
                    || retryOn.contains(SkillOutcome.FailureKind.INTERNAL)
                    || retryOn.contains(SkillOutcome.FailureKind.SAFETY)) {
                throw new IllegalArgumentException("non_retryable_failure_in_retry_node");
            }
        }
    }

    /** Fails the child with a typed TIMEOUT outcome once its active tick budget is exhausted. */
    record Timeout(PlanNode child, long timeoutTicks) implements PlanNode {
        public Timeout {
            child = requireChild(child, "timeout");
            if (timeoutTicks < 1) {
                throw new IllegalArgumentException("timeout_ticks_must_be_positive");
            }
        }
    }

    /** Publishes a durable marker only after its child succeeds. */
    record Checkpoint(String checkpointId, PlanNode child) implements PlanNode {
        public Checkpoint {
            checkpointId = requireToken(checkpointId, "checkpoint_id");
            child = requireChild(child, "checkpoint");
        }
    }

    /** Suspends deterministic plan progression until the exact event key is signalled. */
    record WaitForEvent(String eventKey) implements PlanNode {
        public WaitForEvent {
            eventKey = requireToken(eventKey, "event_key");
        }
    }

    default Optional<List<SkillSpec>> linearSkills() {
        List<SkillSpec> result = new ArrayList<>();
        return appendLinear(this, result) ? Optional.of(List.copyOf(result)) : Optional.empty();
    }

    private static boolean appendLinear(PlanNode node, List<SkillSpec> result) {
        if (node instanceof Skill skill) {
            result.add(skill.spec());
            return true;
        }
        if (node instanceof Sequence sequence) {
            for (PlanNode child : sequence.children()) {
                if (!appendLinear(child, result)) {
                    return false;
                }
            }
            return true;
        }
        return false;
    }

    private static List<PlanNode> immutableChildren(List<PlanNode> children, String type) {
        if (children == null || children.isEmpty() || children.stream().anyMatch(java.util.Objects::isNull)) {
            throw new IllegalArgumentException(type + "_requires_children");
        }
        return List.copyOf(children);
    }

    private static PlanNode requireChild(PlanNode child, String type) {
        if (child == null) {
            throw new IllegalArgumentException(type + "_requires_child");
        }
        return child;
    }

    private static String requireToken(String value, String field) {
        if (value == null || !value.matches("[a-z0-9_.:-]+")) {
            throw new IllegalArgumentException("invalid_" + field);
        }
        return value;
    }
}
