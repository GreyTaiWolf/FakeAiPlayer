package io.github.greytaiwolf.fakeaiplayer.mission;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

/** Immutable plan revision compiled from one GoalSpec. */
public record MissionPlan(
        UUID missionId,
        int revision,
        GoalSpec goal,
        PlanNode root,
        String plannerVersion
) {
    /** Admission limits for plans that may eventually originate from an AI proposal. */
    public static final int MAX_PLAN_NODES = 512;
    public static final int MAX_PLAN_DEPTH = 64;

    private static final HexFormat HEX = HexFormat.of();

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
        validatePlan(root, 1, new int[]{0}, new HashSet<>(), new HashSet<>(), new HashSet<>());
    }

    public List<SkillSpec> requireLinearSkills() {
        return root.linearSkills().orElseThrow(() ->
                new IllegalStateException("legacy_runtime_requires_linear_plan"));
    }

    public PlanCursor cursor(long startTick) {
        return new PlanCursor(this, startTick);
    }

    /**
     * Restores a complete, plan-bound cursor checkpoint under a fresh runtime epoch. Mismatches
     * fail closed, and callbacks leased before the restore cannot complete the restored cursor.
     */
    public PlanCursor cursor(CursorCheckpoint checkpoint) {
        return new PlanCursor(this, checkpoint);
    }

    /**
     * Stable SHA-256 identity of the complete executable contract, excluding only mission id and
     * revision because those are bound as separate checkpoint fields.
     */
    public String fingerprint() {
        StringBuilder canonical = new StringBuilder();
        appendField(canonical, "planner_version", plannerVersion);
        appendGoal(canonical, goal);
        appendNode(canonical, root);
        return sha256(canonical);
    }

    /**
     * Stable identity of the non-replannable Mission intent. World-state replanning may replace
     * the executable node tree, but it must never change the requested outcome, admission
     * authority, dimension, or execution policy carried by the GoalSpec.
     */
    public String intentFingerprint() {
        return intentFingerprint(goal);
    }

    public static String intentFingerprint(GoalSpec goal) {
        if (goal == null) {
            throw new IllegalArgumentException("mission_goal_missing");
        }
        StringBuilder canonical = new StringBuilder();
        appendGoal(canonical, goal);
        return sha256(canonical);
    }

    private static String sha256(StringBuilder canonical) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
            return HEX.formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("sha256_unavailable", exception);
        }
    }

    private static void validatePlan(PlanNode node,
                                     int depth,
                                     int[] nodeCount,
                                     Set<String> nodeIds,
                                     Set<String> invocationIds,
                                     Set<String> checkpointIds) {
        if (depth > MAX_PLAN_DEPTH) {
            throw new IllegalArgumentException("mission_plan_depth_exceeded");
        }
        nodeCount[0]++;
        if (nodeCount[0] > MAX_PLAN_NODES) {
            throw new IllegalArgumentException("mission_plan_node_limit_exceeded");
        }
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
        if (node instanceof PlanNode.Checkpoint checkpoint
                && !checkpointIds.add(checkpoint.checkpointId())) {
            throw new IllegalArgumentException(
                    "duplicate_checkpoint_id:" + checkpoint.checkpointId());
        }
        List<PlanNode> children = switch (node) {
            case PlanNode.Sequence sequence -> sequence.children();
            case PlanNode.AllOf allOf -> allOf.children();
            case PlanNode.AnyOf anyOf -> anyOf.children();
            case PlanNode.Retry retry -> List.of(retry.child());
            case PlanNode.Timeout timeout -> List.of(timeout.child());
            case PlanNode.Checkpoint checkpoint -> List.of(checkpoint.child());
            case PlanNode.WaitForEvent ignored -> List.of();
            case PlanNode.Skill ignored -> throw new IllegalStateException("unreachable_skill_node");
        };
        for (PlanNode child : children) {
            validatePlan(child, depth + 1, nodeCount, nodeIds, invocationIds, checkpointIds);
        }
    }

    private static void appendGoal(StringBuilder target, GoalSpec value) {
        appendField(target, "goal.type", value.type());
        appendField(target, "goal.source", value.source().name());
        appendField(target, "goal.priority", Integer.toString(value.priority()));
        appendField(target, "goal.success", value.successPredicate());
        appendField(target, "goal.dimension", value.dimension());
        MissionPolicy policy = value.policy();
        appendField(target, "policy.risk", policy.riskLevel().name());
        appendField(target, "policy.mutation", policy.mutationScope().name());
        appendField(target, "policy.time", Integer.toString(policy.timeBudgetTicks()));
        appendField(target, "policy.recovery", Integer.toString(policy.recoveryBudget()));
        appendField(target, "policy.interruption", policy.interruptionPolicy().name());
        appendMap(target, "goal.attributes", value.attributes());
    }

    private static void appendNode(StringBuilder target, PlanNode node) {
        switch (node) {
            case PlanNode.Skill skill -> {
                appendField(target, "node", "skill");
                appendField(target, "node_id", skill.nodeId());
                appendSkill(target, skill.spec());
            }
            case PlanNode.Sequence sequence -> {
                appendField(target, "node", "sequence");
                appendChildren(target, sequence.children());
            }
            case PlanNode.AllOf allOf -> {
                appendField(target, "node", "all_of");
                appendChildren(target, allOf.children());
            }
            case PlanNode.AnyOf anyOf -> {
                appendField(target, "node", "any_of");
                appendChildren(target, anyOf.children());
            }
            case PlanNode.Retry retry -> {
                appendField(target, "node", "retry");
                appendField(target, "retry.max_attempts", Integer.toString(retry.maxAttempts()));
                appendSortedSet(target, "retry.on", retry.retryOn().stream()
                        .map(Enum::name).collect(java.util.stream.Collectors.toSet()));
                appendNode(target, retry.child());
            }
            case PlanNode.Timeout timeout -> {
                appendField(target, "node", "timeout");
                appendField(target, "timeout.ticks", Long.toString(timeout.timeoutTicks()));
                appendNode(target, timeout.child());
            }
            case PlanNode.Checkpoint checkpoint -> {
                appendField(target, "node", "checkpoint");
                appendField(target, "checkpoint.id", checkpoint.checkpointId());
                appendNode(target, checkpoint.child());
            }
            case PlanNode.WaitForEvent wait -> {
                appendField(target, "node", "wait_for_event");
                appendField(target, "event.key", wait.eventKey());
            }
        }
    }

    private static void appendChildren(StringBuilder target, List<PlanNode> children) {
        appendField(target, "children", Integer.toString(children.size()));
        for (PlanNode child : children) {
            appendNode(target, child);
        }
    }

    private static void appendSkill(StringBuilder target, SkillSpec skill) {
        appendField(target, "skill.invocation", skill.invocationId());
        appendField(target, "skill.id", skill.id());
        appendField(target, "skill.version", Integer.toString(skill.version()));
        appendMap(target, "skill.parameters", skill.parameters());
        appendList(target, "skill.preconditions", skill.preconditions());
        appendList(target, "skill.success", skill.successPredicates());
        appendField(target, "skill.max_attempts",
                Integer.toString(skill.retryPolicy().maxAttempts()));
        appendSortedSet(target, "skill.replan_on", skill.retryPolicy().replanOn().stream()
                .map(Enum::name).collect(java.util.stream.Collectors.toSet()));
        appendField(target, "skill.mutation", skill.mutationScope().name());
        appendField(target, "skill.risk", skill.requiredRisk().name());
    }

    private static void appendMap(StringBuilder target, String name, Map<String, String> values) {
        appendField(target, name + ".size", Integer.toString(values.size()));
        new TreeMap<>(values).forEach((key, value) -> {
            appendField(target, name + ".key", key);
            appendField(target, name + ".value", value);
        });
    }

    private static void appendList(StringBuilder target, String name, List<String> values) {
        appendField(target, name + ".size", Integer.toString(values.size()));
        for (String value : values) {
            appendField(target, name + ".item", value);
        }
    }

    private static void appendSortedSet(StringBuilder target, String name, Set<String> values) {
        appendField(target, name + ".size", Integer.toString(values.size()));
        values.stream().sorted().forEach(value -> appendField(target, name + ".item", value));
    }

    private static void appendField(StringBuilder target, String name, String value) {
        appendLengthPrefixed(target, name);
        appendLengthPrefixed(target, value);
    }

    private static void appendLengthPrefixed(StringBuilder target, String value) {
        target.append(value.length()).append(':').append(value).append(';');
    }
}
