package io.github.greytaiwolf.fakeaiplayer.goal;

import io.github.greytaiwolf.fakeaiplayer.mission.GoalSpec;
import io.github.greytaiwolf.fakeaiplayer.mission.MissionPolicy;
import io.github.greytaiwolf.fakeaiplayer.mission.PlanNode;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LegacyMissionCompilerTest {
    @Test
    void goldenSurvivalChainKeepsVerifiedSkillOrderAndQuotaSemantics() {
        List<GoalStep> steps = List.of(
                GoalStep.gather(Items.OAK_LOG, 3),
                GoalStep.craft(Items.OAK_PLANKS, 12),
                GoalStep.craft(Items.STICK, 4),
                GoalStep.craft(Items.WOODEN_PICKAXE, 1),
                GoalStep.mine(Blocks.STONE, 3),
                GoalStep.craft(Items.STONE_PICKAXE, 1),
                GoalStep.mineOre(Set.of(Blocks.IRON_ORE), 3),
                GoalStep.smelt(Items.RAW_IRON, Items.IRON_INGOT, 3));

        LegacyMissionCompiler.CompiledMission compiled = LegacyMissionCompiler.compile(
                UUID.fromString("00000000-0000-0000-0000-000000000123"),
                2,
                new Goal.HaveItem(Items.IRON_INGOT, 3),
                GoalSpec.Source.AI_PROPOSAL,
                "minecraft:overworld",
                steps);

        assertEquals(List.of(
                        "legacy.gather",
                        "legacy.craft",
                        "legacy.craft",
                        "legacy.craft",
                        "legacy.mine",
                        "legacy.craft",
                        "legacy.mine_ore",
                        "legacy.smelt"),
                compiled.plan().requireLinearSkills().stream().map(skill -> skill.id()).toList());
        assertEquals(GoalSpec.Source.AI_PROPOSAL, compiled.plan().goal().source());
        assertEquals(2, compiled.plan().revision());
        assertEquals("delta_from_baseline",
                compiled.skills().get(0).spec().parameters().get("quota_mode"));
        assertEquals("family", compiled.skills().get(0).spec().parameters().get("item_match"));
        assertFalse(compiled.skills().stream()
                .anyMatch(skill -> skill.spec().successPredicates().isEmpty()));
    }

    @Test
    void armorPlanEndsWithNonMutatingAuthoritativeEquipVerification() {
        LegacyMissionCompiler.CompiledMission compiled = LegacyMissionCompiler.compile(
                UUID.fromString("00000000-0000-0000-0000-000000000124"),
                0,
                new Goal.Armor(),
                GoalSpec.Source.PLAYER_COMMAND,
                "minecraft:overworld",
                List.of(GoalStep.craft(Items.IRON_HELMET, 1), GoalStep.equipLoadout()));

        var equip = compiled.skills().get(1).spec();
        assertEquals("legacy.equip_loadout", equip.id());
        assertEquals(MissionPolicy.MutationScope.NONE, equip.mutationScope());
        assertEquals(List.of("authoritative_armor_slots_and_owned_weapon_ready"),
                equip.successPredicates());
    }

    @Test
    void repeatedCapabilitiesReceiveUniqueDeterministicInvocationAndNodeIds() {
        LegacyMissionCompiler.CompiledMission compiled = LegacyMissionCompiler.compile(
                UUID.fromString("00000000-0000-0000-0000-000000000125"),
                0,
                new Goal.HaveItem(Items.STICK, 4),
                GoalSpec.Source.PLAYER_COMMAND,
                "minecraft:overworld",
                List.of(GoalStep.craft(Items.OAK_PLANKS, 4), GoalStep.craft(Items.STICK, 4)));

        var first = compiled.skills().get(0).spec();
        var second = compiled.skills().get(1).spec();
        assertEquals("legacy.craft", first.id());
        assertEquals("legacy.craft", second.id());
        assertEquals("step.0.legacy.craft", first.invocationId());
        assertEquals("step.1.legacy.craft", second.invocationId());
        assertNotEquals(first.invocationId(), second.invocationId());

        PlanNode.Sequence root = (PlanNode.Sequence) compiled.plan().root();
        assertEquals(List.of("step.0.legacy.craft", "step.1.legacy.craft"), root.children().stream()
                .map(PlanNode.Skill.class::cast)
                .map(PlanNode.Skill::nodeId)
                .toList());
    }

    @Test
    void movementAndMiningContractsMatchTheirActualWorldMutationAndDropAccounting() {
        List<LegacyMissionCompiler.ExecutableSkill> skills = LegacyMissionCompiler.compileSkills(List.of(
                GoalStep.move(new BlockPos(2, 64, 3)),
                GoalStep.moveNonMutating(new BlockPos(2, 64, 3)),
                GoalStep.mine(Blocks.STONE, 3),
                GoalStep.mineExact(Blocks.STONE, 2)));

        assertEquals(MissionPolicy.MutationScope.SURVIVAL, skills.get(0).spec().mutationScope());
        assertEquals(MissionPolicy.MutationScope.NONE, skills.get(1).spec().mutationScope());
        assertEquals(List.of(
                        "inventory_drop_delta(minecraft:blackstone,minecraft:cobbled_deepslate,minecraft:cobblestone)>=3"),
                skills.get(2).spec().successPredicates());
        assertEquals(List.of("inventory_drop_delta(minecraft:cobblestone)>=2"),
                skills.get(3).spec().successPredicates());
    }

    @Test
    void confirmedBuildBindingIsCopiedIntoGoalAndSkillContracts() {
        String digest = "a".repeat(64);
        Goal.Build build = new Goal.Build(
                "small_hut", new BlockPos(10, 70, -4), "minecraft:overworld", digest);

        LegacyMissionCompiler.CompiledMission compiled = LegacyMissionCompiler.compile(
                UUID.fromString("00000000-0000-0000-0000-000000000126"),
                0,
                build,
                GoalSpec.Source.PLAYER_CONFIRMED,
                "minecraft:overworld",
                List.of(GoalStep.build("small_hut")));

        var attributes = compiled.plan().goal().attributes();
        assertEquals("10", attributes.get("anchor_x"));
        assertEquals("70", attributes.get("anchor_y"));
        assertEquals("-4", attributes.get("anchor_z"));
        assertEquals("minecraft:overworld", attributes.get("dimension"));
        assertEquals(digest, attributes.get("blueprint_digest"));
        assertEquals("minecraft:overworld", compiled.plan().goal().dimension());

        var parameters = compiled.skills().get(0).spec().parameters();
        assertEquals("10", parameters.get("anchor_x"));
        assertEquals("70", parameters.get("anchor_y"));
        assertEquals("-4", parameters.get("anchor_z"));
        assertEquals("minecraft:overworld", parameters.get("dimension"));
        assertEquals(digest, parameters.get("blueprint_digest"));
        assertEquals(MissionPolicy.MutationScope.CONFIRMED_AREA,
                compiled.skills().get(0).spec().mutationScope());

        assertEquals("build_goal_requires_confirmed_binding", assertThrows(IllegalArgumentException.class,
                () -> LegacyMissionCompiler.compile(
                        UUID.randomUUID(), 0, new Goal.Build("small_hut"),
                        GoalSpec.Source.PLAYER_COMMAND, "minecraft:overworld",
                        List.of(GoalStep.build("small_hut")))).getMessage());
    }

    @Test
    void malformedStepsAndGoalsFailWithNamedContractErrors() {
        assertEquals("goal_step_item_missing:gather", assertThrows(IllegalArgumentException.class,
                () -> GoalStep.gather(null, 1)).getMessage());
        assertEquals("goal_step_ores_missing:mine_ore", assertThrows(IllegalArgumentException.class,
                () -> GoalStep.mineOre(Set.of(), 1)).getMessage());
        assertEquals("goal_step_ores_invalid:mine_ore", assertThrows(IllegalArgumentException.class,
                () -> GoalStep.mineOre(Collections.singleton(null), 1)).getMessage());
        assertEquals("goal_step_position_missing:move", assertThrows(IllegalArgumentException.class,
                () -> GoalStep.move(null)).getMessage());
        assertEquals("goal_step_blueprint_missing:build", assertThrows(IllegalArgumentException.class,
                () -> GoalStep.build(" ")).getMessage());

        assertEquals("goal_item_missing:have_item", assertThrows(IllegalArgumentException.class,
                () -> new Goal.HaveItem(null, 1)).getMessage());
        assertEquals("goal_ores_missing:mine_ore", assertThrows(IllegalArgumentException.class,
                () -> new Goal.MineOre(Set.of(), 1)).getMessage());
        assertEquals("goal_ores_invalid:mine_ore", assertThrows(IllegalArgumentException.class,
                () -> new Goal.MineOre(Collections.singleton(null), 1)).getMessage());
        assertEquals("goal_crop_missing:harvest_crop", assertThrows(IllegalArgumentException.class,
                () -> new Goal.HarvestCrop(null, Items.WHEAT_SEEDS, Items.WHEAT, 1)).getMessage());
        assertEquals("goal_item_missing:stockpile", assertThrows(IllegalArgumentException.class,
                () -> new Goal.Stockpile(null, 1)).getMessage());
        assertEquals("goal_blueprint_missing:build", assertThrows(IllegalArgumentException.class,
                () -> new Goal.Build(" ")).getMessage());
    }
}
