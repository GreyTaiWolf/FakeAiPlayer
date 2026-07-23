package io.github.greytaiwolf.fakeaiplayer.mission;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** AND/OR-capable plan tree. The legacy runtime currently executes only Skill/Sequence trees. */
public sealed interface PlanNode permits PlanNode.Skill, PlanNode.Sequence, PlanNode.AllOf, PlanNode.AnyOf {
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

    /** Exactly one feasible child is selected by utility/capability scoring. */
    record AnyOf(List<PlanNode> children) implements PlanNode {
        public AnyOf {
            children = immutableChildren(children, "any_of");
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
}
