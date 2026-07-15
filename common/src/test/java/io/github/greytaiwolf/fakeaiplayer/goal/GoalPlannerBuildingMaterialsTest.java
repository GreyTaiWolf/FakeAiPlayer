package io.github.greytaiwolf.fakeaiplayer.goal;

import io.github.greytaiwolf.fakeaiplayer.building.plan.CellOperation;
import io.github.greytaiwolf.fakeaiplayer.building.plan.ReplacePolicy;
import io.github.greytaiwolf.fakeaiplayer.craft.AcquisitionHints;
import io.github.greytaiwolf.fakeaiplayer.craft.RecipeRegistry;
import io.github.greytaiwolf.fakeaiplayer.craft.SmeltChain;
import io.github.greytaiwolf.fakeaiplayer.task.BlueprintSchema;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GoalPlannerBuildingMaterialsTest {
    @Test
    void skipsClearAndPreserveAndCountsAtomicGroupOnce() {
        BlueprintSchema.BlockPlacement lowerDoor = placement(
                0, "minecraft:oak_door", CellOperation.PLACE, "door:front", 0);
        BlueprintSchema.BlockPlacement upperDoor = placement(
                1, "minecraft:oak_door", CellOperation.PLACE, "door:front", 1);
        BlueprintSchema.BlockPlacement clear = placement(
                2, "minecraft:air", CellOperation.CLEAR, "", 2);
        BlueprintSchema.BlockPlacement preserve = placement(
                3, "minecraft:stone", CellOperation.PRESERVE, "", 3);
        BlueprintSchema.BlockPlacement floor = placement(
                4, "minecraft:oak_planks", CellOperation.PLACE, "", 4);
        BlueprintSchema.BlockPlacement alreadyBuilt = placement(
                5, "minecraft:glass", CellOperation.PLACE, "", 5);
        BlueprintSchema schema = new BlueprintSchema(
                "materials", 6, 1, 1,
                List.of(lowerDoor, upperDoor, clear, preserve, floor, alreadyBuilt),
                List.of());

        List<BlueprintSchema.BlockPlacement> requirements =
                GoalPlanner.materialPlacements(schema, placement -> placement == alreadyBuilt);

        assertEquals(List.of(lowerDoor, floor), requirements);
    }

    @Test
    void unresolvedAtomicMemberStillRequiresOneItemWhenItsPartnerAlreadyMatches() {
        BlueprintSchema.BlockPlacement lowerDoor = placement(
                0, "minecraft:oak_door", CellOperation.PLACE, "door:front", 0);
        BlueprintSchema.BlockPlacement upperDoor = placement(
                1, "minecraft:oak_door", CellOperation.PLACE, "door:front", 1);
        BlueprintSchema schema = new BlueprintSchema(
                "partial", 2, 1, 1, List.of(lowerDoor, upperDoor), List.of());

        assertEquals(List.of(upperDoor), GoalPlanner.materialPlacements(
                schema, placement -> placement == lowerDoor));
    }

    @Test
    void builtInStyleMaterialsHaveDeterministicAcquisitionAndCraftChains() {
        assertTrue(GoalPlanner.isDirectGatherItem(Items.OAK_LOG));
        assertTrue(GoalPlanner.isDirectGatherItem(Items.SPRUCE_LOG));
        assertTrue(GoalPlanner.isDirectGatherItem(Items.SAND));
        assertEquals("mine", AcquisitionHints.source(Items.SAND));
        assertEquals(Items.SAND, SmeltChain.rawFor(Items.GLASS));

        assertWoodRecipe(Items.OAK_DOOR, Items.OAK_PLANKS, 3);
        assertWoodRecipe(Items.OAK_STAIRS, Items.OAK_PLANKS, 4);
        assertWoodRecipe(Items.SPRUCE_DOOR, Items.SPRUCE_PLANKS, 3);
        assertWoodRecipe(Items.SPRUCE_STAIRS, Items.SPRUCE_PLANKS, 4);
    }

    @Test
    void generatedBuildsKeepExactLogGatherSeparateFromLegacyFamilyGather() {
        assertTrue(GoalPlanner.usesExactBuildingMaterials("generated_owner_session_r2"));
        assertEquals(GoalStep.Kind.GATHER_EXACT,
                GoalPlanner.directGatherStep(Items.SPRUCE_LOG, 3, true).kind());
        assertEquals(GoalStep.Kind.GATHER,
                GoalPlanner.directGatherStep(Items.SPRUCE_LOG, 3, false).kind());
        assertEquals(GoalStep.Kind.GATHER,
                GoalPlanner.directGatherStep(Items.SAND, 3, true).kind());

        List<GoalStep> merged = GoalPlanner.mergeGathers(List.of(
                GoalStep.gatherExact(Items.OAK_LOG, 2),
                GoalStep.gather(Items.OAK_LOG, 3),
                GoalStep.gatherExact(Items.OAK_LOG, 4),
                GoalStep.craft(Items.OAK_DOOR, 3)));

        assertEquals(List.of(
                GoalStep.gatherExact(Items.OAK_LOG, 6),
                GoalStep.gather(Items.OAK_LOG, 3),
                GoalStep.craft(Items.OAK_DOOR, 3)), merged);
        assertFalse(GoalPlanner.usesExactBuildingMaterials("small_hut"));
        assertFalse(GoalPlanner.usesExactBuildingMaterials("custom:9x9x5:planks"));
    }

    @Test
    void confirmedBuildStagingIsOutsideTheFootprintAndSurvivesGatherMerging() {
        BlueprintSchema schema = new BlueprintSchema("house", 11, 12, 14, List.of(), List.of());
        BlockPos anchor = new BlockPos(100, 64, 200);
        BlockPos staging = GoalPlanner.buildStagingPoint(schema, anchor);

        assertEquals(new BlockPos(105, 64, 198), staging);
        assertTrue(staging.getZ() < anchor.getZ());
        assertTrue(staging.getX() >= anchor.getX());
        assertTrue(staging.getX() < anchor.getX() + schema.width());
        assertTrue(staging.distSqr(anchor.offset((schema.width() - 1) / 2, 0, 0)) < 16.0D * 16.0D);

        List<GoalStep> merged = GoalPlanner.mergeGathers(List.of(
                GoalStep.craft(Items.OAK_PLANKS, 64),
                GoalStep.gatherExact(Items.OAK_LOG, 16),
                GoalStep.move(staging),
                GoalStep.build("generated_house")));
        assertEquals(GoalStep.Kind.MOVE, merged.get(2).kind());
        assertEquals(staging, merged.get(2).pos());
        assertEquals(GoalStep.Kind.BUILD, merged.get(3).kind());
    }

    @Test
    void reservationsProtectFinalPlanksFromLaterRecipesAndCombineWithPaletteNeeds() {
        Map<Item, Integer> counts = new HashMap<>();
        counts.put(Items.OAK_PLANKS, 70);
        counts.put(Items.BIRCH_PLANKS, 40);
        GoalPlanner.MaterialReservations reservations =
                new GoalPlanner.MaterialReservations(counts);

        assertTrue(reservations.reserve(Items.OAK_PLANKS, 10));
        assertTrue(reservations.reserve(RecipeRegistry.PLANKS, 100));
        assertEquals(110, reservations.reserved(RecipeRegistry.PLANKS));
        assertEquals(0, reservations.available(RecipeRegistry.PLANKS));

        // A later door/table recipe must acquire new planks. It may consume only those new
        // planks, never the 110 final blocks already promised to the building.
        counts.merge(Items.OAK_PLANKS, 8, Integer::sum);
        assertEquals(8, reservations.available(Items.OAK_PLANKS));
        assertEquals(6, reservations.takeAvailable(Items.OAK_PLANKS, 6));
        assertEquals(2, reservations.available(Items.OAK_PLANKS));
        assertEquals(110, reservations.reserved(RecipeRegistry.PLANKS));
        assertEquals(112, counts.get(Items.OAK_PLANKS) + counts.get(Items.BIRCH_PLANKS));
    }

    private static void assertWoodRecipe(Item output, Item planks, int outputCount) {
        RecipeRegistry.Recipe recipe = RecipeRegistry.find(output).orElseThrow();
        assertEquals(outputCount, recipe.outputCount());
        assertEquals(1, recipe.ingredients().size());
        assertEquals(List.of(planks), recipe.ingredients().get(0).anyOf());
        assertEquals(6, recipe.ingredients().get(0).count());
        assertTrue(recipe.needsCraftingTable());
    }

    private static BlueprintSchema.BlockPlacement placement(
            int x,
            String block,
            CellOperation operation,
            String atomicGroup,
            int sequence) {
        ReplacePolicy policy = switch (operation) {
            case CLEAR -> ReplacePolicy.CLEAR_AUTHORIZED;
            case PRESERVE -> ReplacePolicy.PRESERVE_EXISTING;
            case PLACE, TEMPORARY -> ReplacePolicy.REPLACE_REPLACEABLE;
        };
        return new BlueprintSchema.BlockPlacement(
                x, 0, 0, block, null, Map.of(), operation, policy, atomicGroup, sequence);
    }
}
