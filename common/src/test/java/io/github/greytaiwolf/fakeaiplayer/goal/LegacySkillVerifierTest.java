package io.github.greytaiwolf.fakeaiplayer.goal;

import io.github.greytaiwolf.fakeaiplayer.mission.GoalSpec;
import io.github.greytaiwolf.fakeaiplayer.mission.MissionPolicy;
import io.github.greytaiwolf.fakeaiplayer.mission.SkillSpec;
import io.github.greytaiwolf.fakeaiplayer.task.BlueprintLoader;
import io.github.greytaiwolf.fakeaiplayer.task.BlueprintSchema;
import io.github.greytaiwolf.fakeaiplayer.task.HuntTask;
import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LegacySkillVerifierTest {
    private static final String OVERWORLD = "minecraft:overworld";

    @Test
    void canonicalDiscoverySkillPassesAdmissionContractWithoutNearbyResourceFacts() {
        Goal goal = new Goal.HaveItem(Items.OAK_LOG, 3);
        LegacyMissionCompiler.CompiledMission compiled = compile(
                goal, GoalSpec.Source.PLAYER_COMMAND, List.of(GoalStep.gather(Items.OAK_LOG, 3)));

        LegacySkillVerifier.ContractCheck check = LegacySkillVerifier.validateContract(
                compiled.plan().goal(),
                goal,
                compiled.skills().getFirst(),
                OVERWORLD,
                GoalSnapshotCollector.Context.at(BlockPos.ZERO));

        assertTrue(check.valid(), check.reason());
        assertEquals(List.of("world:bound_dimension", "inventory:pickup_space"),
                compiled.skills().getFirst().spec().preconditions());
    }

    @Test
    void exactCompilerContractIncludesInvocationRetryScopeAndRisk() {
        Goal goal = new Goal.MineOre(Set.of(Blocks.IRON_ORE), 2);
        LegacyMissionCompiler.CompiledMission compiled = compile(
                goal,
                GoalSpec.Source.AI_PROPOSAL,
                List.of(GoalStep.craft(Items.STONE_PICKAXE, 1),
                        GoalStep.mineOre(Set.of(Blocks.IRON_ORE), 2)));
        LegacyMissionCompiler.ExecutableSkill original = compiled.skills().get(1);
        SkillSpec spec = original.spec();
        SkillSpec tampered = new SkillSpec(
                spec.invocationId(),
                spec.id(),
                spec.version(),
                spec.parameters(),
                spec.preconditions(),
                spec.successPredicates(),
                spec.retryPolicy(),
                spec.mutationScope(),
                MissionPolicy.RiskLevel.BOLD);

        LegacySkillVerifier.ContractCheck check = LegacySkillVerifier.validateContract(
                compiled.plan().goal(),
                goal,
                new LegacyMissionCompiler.ExecutableSkill(tampered, original.step()),
                OVERWORLD,
                GoalSnapshotCollector.Context.at(BlockPos.ZERO));

        assertFalse(check.valid());
        assertEquals("legacy_skill_spec_not_canonical", check.reason());
    }

    @Test
    void boundDimensionMismatchFailsClosedBeforeTaskStart() {
        Goal goal = new Goal.HaveItem(Items.STICK, 4);
        LegacyMissionCompiler.CompiledMission compiled = compile(
                goal, GoalSpec.Source.LEGACY, List.of(GoalStep.craft(Items.STICK, 4)));

        LegacySkillVerifier.ContractCheck check = LegacySkillVerifier.validateContract(
                compiled.plan().goal(),
                goal,
                compiled.skills().getFirst(),
                "minecraft:the_nether",
                GoalSnapshotCollector.Context.at(BlockPos.ZERO));

        assertFalse(check.valid());
        assertEquals("legacy_skill_bound_dimension_mismatch", check.reason());
    }

    @Test
    void confirmedBuildRequiresExactBlueprintAnchorDimensionAndDigest() throws IOException {
        BlockPos anchor = new BlockPos(12, 70, -9);
        BlueprintSchema blueprint = new BlueprintSchema(
                "generated_test_house",
                1,
                1,
                1,
                List.of(new BlueprintSchema.BlockPlacement(
                        0, 0, 0, "minecraft:oak_planks")),
                List.of());
        String digest = BlueprintLoader.canonicalDigest(blueprint);
        Goal.Build goal = new Goal.Build(
                blueprint.name(), anchor, OVERWORLD, digest);
        LegacyMissionCompiler.CompiledMission compiled = compile(
                goal,
                GoalSpec.Source.PLAYER_CONFIRMED,
                List.of(GoalStep.build(blueprint.name())));

        LegacySkillVerifier.ContractCheck accepted = LegacySkillVerifier.validateContract(
                compiled.plan().goal(),
                goal,
                compiled.skills().getFirst(),
                OVERWORLD,
                new GoalSnapshotCollector.Context(
                        anchor, Set.of(), blueprint, anchor, 0, 0));
        LegacySkillVerifier.ContractCheck wrongAnchor = LegacySkillVerifier.validateContract(
                compiled.plan().goal(),
                goal,
                compiled.skills().getFirst(),
                OVERWORLD,
                new GoalSnapshotCollector.Context(
                        anchor, Set.of(), blueprint, anchor.east(), 0, 0));

        assertTrue(accepted.valid(), accepted.reason());
        assertFalse(wrongAnchor.valid());
        assertEquals("legacy_build_anchor_mismatch", wrongAnchor.reason());
    }

    @Test
    void verifierUsesTheSameGatherMineOreHuntAndCookFamiliesAsTasks() {
        assertTrue(LegacySkillVerifier.relevantItemsFor(
                GoalStep.gather(Items.OAK_LOG, 1)).contains(Items.SPRUCE_LOG));
        assertEquals(Set.of(Items.OAK_LOG), LegacySkillVerifier.relevantItemsFor(
                GoalStep.gatherExact(Items.OAK_LOG, 1)));
        assertEquals(Set.of(Items.COBBLESTONE), LegacySkillVerifier.relevantItemsFor(
                GoalStep.mineExact(Blocks.STONE, 1)));
        assertTrue(LegacySkillVerifier.relevantItemsFor(
                GoalStep.mine(Blocks.STONE, 1)).contains(Items.COBBLED_DEEPSLATE));
        assertEquals(Set.of(Items.RAW_IRON), LegacySkillVerifier.relevantItemsFor(
                GoalStep.mineOre(Set.of(Blocks.IRON_ORE), 1)));
        assertEquals(HuntTask.rawMeatDrops(), LegacySkillVerifier.relevantItemsFor(
                GoalStep.hunt(1)));
        assertTrue(LegacySkillVerifier.relevantItemsFor(
                GoalStep.cookFood(1)).contains(Items.COOKED_BEEF));
    }

    private static LegacyMissionCompiler.CompiledMission compile(Goal goal,
                                                                 GoalSpec.Source source,
                                                                 List<GoalStep> steps) {
        return LegacyMissionCompiler.compile(
                UUID.fromString("00000000-0000-0000-0000-000000000777"),
                0,
                goal,
                source,
                OVERWORLD,
                steps);
    }
}
