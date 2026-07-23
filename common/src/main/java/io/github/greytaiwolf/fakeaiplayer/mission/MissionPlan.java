package io.github.greytaiwolf.fakeaiplayer.mission;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Immutable plan revision compiled from one GoalSpec. */
public record MissionPlan(
        UUID missionId,
        int revision,
        GoalSpec goal,
        PlanNode root,
        String plannerVersion
) {
    public MissionPlan {
        if (missionId == null) {
            throw new IllegalArgumentException("mission_id_missing");
        }
        if (revision < 0) {
            throw new IllegalArgumentException("mission_revision_negative");
        }
        if (goal == null || root == null) {
            throw new IllegalArgumentException("mission_plan_incomplete");
        }
        if (plannerVersion == null || plannerVersion.isBlank()) {
            throw new IllegalArgumentException("planner_version_missing");
        }
        plannerVersion = plannerVersion.trim();
        validateUniqueSkillIdentities(root, new HashSet<>(), new HashSet<>());
    }

    public List<SkillSpec> requireLinearSkills() {
        return root.linearSkills().orElseThrow(() ->
                new IllegalStateException("legacy_runtime_requires_linear_plan"));
    }

    private static void validateUniqueSkillIdentities(PlanNode node,
                                                      Set<String> nodeIds,
                                                      Set<String> invocationIds) {
        if (node instanceof PlanNode.Skill skill) {
            if (!nodeIds.add(skill.nodeId())) {
                throw new IllegalArgumentException("duplicate_plan_node_id:" + skill.nodeId());
            }
            if (!invocationIds.add(skill.spec().invocationId())) {
                throw new IllegalArgumentException(
                        "duplicate_skill_invocation_id:" + skill.spec().invocationId());
            }
            return;
        }
        List<PlanNode> children = switch (node) {
            case PlanNode.Sequence sequence -> sequence.children();
            case PlanNode.AllOf allOf -> allOf.children();
            case PlanNode.AnyOf anyOf -> anyOf.children();
            case PlanNode.Skill ignored -> throw new IllegalStateException("unreachable_skill_node");
        };
        for (PlanNode child : children) {
            validateUniqueSkillIdentities(child, nodeIds, invocationIds);
        }
    }
}
