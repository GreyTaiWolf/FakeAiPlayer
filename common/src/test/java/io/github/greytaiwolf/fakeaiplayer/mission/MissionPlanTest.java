package io.github.greytaiwolf.fakeaiplayer.mission;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MissionPlanTest {
    @Test
    void nestedSequencesLinearizeInDeclaredDependencyOrder() {
        SkillSpec gatherWood = skill("gather_wood");
        SkillSpec craftPickaxe = skill("craft_wooden_pickaxe");
        SkillSpec mineIron = skill("mine_iron");
        PlanNode root = new PlanNode.Sequence(List.of(
                new PlanNode.Skill(gatherWood),
                new PlanNode.Sequence(List.of(
                        new PlanNode.Skill(craftPickaxe),
                        new PlanNode.Skill(mineIron)))));
        MissionPlan plan = plan(root);

        assertEquals(List.of(gatherWood, craftPickaxe, mineIron), plan.requireLinearSkills());
        assertEquals(List.of("gather_wood", "craft_wooden_pickaxe", "mine_iron"),
                plan.requireLinearSkills().stream().map(SkillSpec::id).toList());
    }

    @Test
    void sequenceCopiesItsDependencyList() {
        List<PlanNode> source = new ArrayList<>();
        source.add(new PlanNode.Skill(skill("first")));
        PlanNode.Sequence sequence = new PlanNode.Sequence(source);

        source.add(new PlanNode.Skill(skill("late_mutation")));

        assertEquals(List.of("first"), sequence.linearSkills().orElseThrow().stream()
                .map(SkillSpec::id).toList());
        assertThrows(UnsupportedOperationException.class,
                () -> sequence.children().add(new PlanNode.Skill(skill("forbidden"))));
    }

    @Test
    void branchingDependenciesAreRejectedByTheLegacyLinearRuntime() {
        PlanNode allOf = new PlanNode.AllOf(List.of(
                new PlanNode.Skill(skill("gather_wood")),
                new PlanNode.Skill(skill("gather_stone"))));
        PlanNode anyOf = new PlanNode.AnyOf(List.of(
                new PlanNode.Skill(skill("inventory_provider")),
                new PlanNode.Skill(skill("world_provider"))));

        assertTrue(allOf.linearSkills().isEmpty());
        assertTrue(anyOf.linearSkills().isEmpty());
        assertThrows(IllegalStateException.class, () -> plan(allOf).requireLinearSkills());
        assertThrows(IllegalStateException.class, () -> plan(anyOf).requireLinearSkills());
    }

    @Test
    void sequenceRejectsMissingDependencies() {
        assertThrows(IllegalArgumentException.class, () -> new PlanNode.Sequence(List.of()));
        assertThrows(IllegalArgumentException.class, () -> new PlanNode.Sequence(null));
        assertThrows(IllegalArgumentException.class,
                () -> new PlanNode.Sequence(java.util.Arrays.<PlanNode>asList(
                        new PlanNode.Skill(skill("valid")), null)));
    }

    @Test
    void planRejectsDuplicateInvocationIdentityEvenWhenNodeIdsDiffer() {
        SkillSpec first = skill("call.1", "craft");
        SkillSpec duplicateInvocation = skill("call.1", "craft");
        PlanNode root = new PlanNode.Sequence(List.of(
                new PlanNode.Skill("node.1", first),
                new PlanNode.Skill("node.2", duplicateInvocation)));

        assertEquals("duplicate_skill_invocation_id:call.1",
                assertThrows(IllegalArgumentException.class, () -> plan(root)).getMessage());
    }

    @Test
    void planRejectsDuplicateNodeIdentityEvenWhenInvocationsDiffer() {
        PlanNode root = new PlanNode.Sequence(List.of(
                new PlanNode.Skill("node.1", skill("call.1", "craft")),
                new PlanNode.Skill("node.1", skill("call.2", "craft"))));

        assertEquals("duplicate_plan_node_id:node.1",
                assertThrows(IllegalArgumentException.class, () -> plan(root)).getMessage());
    }

    @Test
    void identityValidationTraversesEveryCompositeWrapper() {
        PlanNode root = new PlanNode.AnyOf(List.of(
                new PlanNode.Checkpoint(
                        "first_attempt",
                        new PlanNode.Skill("node.1", skill("call.same", "inventory"))),
                new PlanNode.Timeout(
                        new PlanNode.Retry(
                                new PlanNode.Skill("node.2", skill("call.same", "explore")),
                                2),
                        20)));

        assertEquals("duplicate_skill_invocation_id:call.same",
                assertThrows(IllegalArgumentException.class, () -> plan(root)).getMessage());
    }

    @Test
    void intentBindingSurvivesWorldReplanButChangesWithAuthorityOrPolicy() {
        MissionPlan original = plan(new PlanNode.Skill(skill("gather_wood")));
        MissionPlan replanned = new MissionPlan(
                original.missionId(),
                original.revision() + 1,
                original.goal(),
                new PlanNode.Skill(skill("mine_iron")),
                "test-planner-v2");
        GoalSpec elevated = new GoalSpec(
                original.goal().type(),
                GoalSpec.Source.PLAYER_CONFIRMED,
                100,
                original.goal().successPredicate(),
                original.goal().dimension(),
                new MissionPolicy(
                        MissionPolicy.RiskLevel.BOLD,
                        MissionPolicy.MutationScope.CONFIRMED_AREA,
                        Integer.MAX_VALUE,
                        Integer.MAX_VALUE,
                        MissionPolicy.InterruptionPolicy.RESUME_AFTER_SAFETY),
                original.goal().attributes());

        assertEquals(original.intentFingerprint(), replanned.intentFingerprint());
        assertNotEquals(original.fingerprint(), replanned.fingerprint());
        assertNotEquals(original.intentFingerprint(), MissionPlan.intentFingerprint(elevated));
    }

    private static MissionPlan plan(PlanNode root) {
        GoalSpec goal = new GoalSpec(
                "obtain_iron",
                GoalSpec.Source.PLAYER_COMMAND,
                90,
                "inventory:minecraft:iron_ingot>=1",
                "minecraft:overworld",
                MissionPolicy.standard(),
                Map.of());
        return new MissionPlan(UUID.fromString("00000000-0000-0000-0000-000000000001"),
                1, goal, root, "test-planner-v1");
    }

    private static SkillSpec skill(String id) {
        return new SkillSpec(
                id,
                1,
                Map.of(),
                List.of(),
                List.of("done:" + id),
                SkillSpec.RetryPolicy.standard(),
                MissionPolicy.MutationScope.SURVIVAL);
    }

    private static SkillSpec skill(String invocationId, String id) {
        return new SkillSpec(
                invocationId,
                id,
                1,
                Map.of(),
                List.of(),
                List.of("done:" + id),
                SkillSpec.RetryPolicy.standard(),
                MissionPolicy.MutationScope.SURVIVAL);
    }
}
