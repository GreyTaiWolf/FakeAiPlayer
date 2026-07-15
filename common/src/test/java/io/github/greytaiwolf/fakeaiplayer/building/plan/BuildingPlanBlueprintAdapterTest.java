package io.github.greytaiwolf.fakeaiplayer.building.plan;

import io.github.greytaiwolf.fakeaiplayer.task.BlueprintSchema;
import java.util.List;
import java.util.Map;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BuildingPlanBlueprintAdapterTest {
    @BeforeAll
    static void bootstrapRegistries() {
        Bootstrap.bootStrap();
    }

    @Test
    void appliesMirrorAndRotationToCoordinatesBoundsAndBlockState() {
        PlanPlacement stair = placement(
                "stair", 2, 0, 1,
                new BlockStateSpec("minecraft:oak_stairs", Map.of(
                        "facing", "north",
                        "half", "bottom",
                        "shape", "straight",
                        "waterlogged", "false")),
                CellOperation.PLACE,
                ReplacePolicy.REQUIRE_EMPTY,
                BuildPhase.FOUNDATION,
                List.of(),
                "");
        BuildingPlan plan = plan(3, 1, 2, List.of(stair));

        BlueprintSchema blueprint = BuildingPlanBlueprintAdapter.adapt(
                plan,
                new PlanTransform(Mirror.FRONT_BACK, Rotation.CLOCKWISE_90));

        assertEquals(2, blueprint.width());
        assertEquals(3, blueprint.depth());
        BlueprintSchema.BlockPlacement transformed = blueprint.placements().get(0);
        assertEquals(0, transformed.dx());
        assertEquals(0, transformed.dz());
        assertEquals("east", transformed.properties().get("facing"));
    }

    @Test
    void preservesExecutionSemanticsInStableDependencyOrder() {
        PlanPlacement upperDoor = placement(
                "door_upper", 1, 1, 1,
                door("upper"),
                CellOperation.PLACE,
                ReplacePolicy.REQUIRE_EMPTY,
                BuildPhase.WALLS_AND_OPENINGS,
                List.of("door_lower"),
                "door:front");
        PlanPlacement preserved = placement(
                "survey_marker", 1, 0, 0,
                new BlockStateSpec("minecraft:stone"),
                CellOperation.PRESERVE,
                ReplacePolicy.PRESERVE_EXISTING,
                BuildPhase.FRAME,
                List.of("base"),
                "");
        PlanPlacement base = placement(
                "base", 0, 0, 0,
                new BlockStateSpec("minecraft:cobblestone"),
                CellOperation.PLACE,
                ReplacePolicy.REPLACE_NATURAL,
                BuildPhase.FOUNDATION,
                List.of(),
                "");
        PlanPlacement cleared = placement(
                "clear", 2, 0, 0,
                new BlockStateSpec("minecraft:air"),
                CellOperation.CLEAR,
                ReplacePolicy.CLEAR_AUTHORIZED,
                BuildPhase.SITE_PREPARATION,
                List.of(),
                "");
        PlanPlacement lowerDoor = placement(
                "door_lower", 1, 0, 1,
                door("lower"),
                CellOperation.PLACE,
                ReplacePolicy.REQUIRE_EMPTY,
                BuildPhase.WALLS_AND_OPENINGS,
                List.of("base"),
                "door:front");
        BuildingPlan plan = plan(
                3, 2, 2,
                List.of(upperDoor, preserved, base, cleared, lowerDoor));

        BlueprintSchema blueprint = BuildingPlanBlueprintAdapter.adapt(plan, PlanTransform.IDENTITY);

        assertEquals(
                List.of("minecraft:air", "minecraft:cobblestone", "minecraft:stone",
                        "minecraft:oak_door", "minecraft:oak_door"),
                blueprint.placements().stream().map(BlueprintSchema.BlockPlacement::blockId).toList());
        assertEquals(
                List.of(CellOperation.CLEAR, CellOperation.PLACE, CellOperation.PRESERVE,
                        CellOperation.PLACE, CellOperation.PLACE),
                blueprint.placements().stream().map(BlueprintSchema.BlockPlacement::operation).toList());
        assertEquals(
                List.of(ReplacePolicy.CLEAR_AUTHORIZED, ReplacePolicy.REPLACE_NATURAL,
                        ReplacePolicy.PRESERVE_EXISTING, ReplacePolicy.REQUIRE_EMPTY,
                        ReplacePolicy.REQUIRE_EMPTY),
                blueprint.placements().stream().map(BlueprintSchema.BlockPlacement::replacePolicy).toList());
        assertEquals(List.of(0, 1, 2, 3, 4),
                blueprint.placements().stream().map(BlueprintSchema.BlockPlacement::sequence).toList());
        assertEquals(
                List.of(List.of(), List.of(), List.of(1), List.of(1), List.of(3)),
                blueprint.placements().stream()
                        .map(BlueprintSchema.BlockPlacement::prerequisites)
                        .toList());
        assertEquals("door:front", blueprint.placements().get(3).atomicGroup());
        assertEquals("door:front", blueprint.placements().get(4).atomicGroup());
        assertEquals("lower", blueprint.placements().get(3).properties().get("half"));
        assertEquals("upper", blueprint.placements().get(4).properties().get("half"));
    }

    @Test
    void rejectsTemporaryCellsUntilCleanupExecutionExists() {
        PlanPlacement temporary = placement(
                "scaffold", 0, 0, 0,
                new BlockStateSpec("minecraft:dirt"),
                CellOperation.TEMPORARY,
                ReplacePolicy.REQUIRE_EMPTY,
                BuildPhase.FOUNDATION,
                List.of(),
                "");

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> BuildingPlanBlueprintAdapter.adapt(
                        plan(1, 1, 1, List.of(temporary)), PlanTransform.IDENTITY));

        assertTrue(exception.getMessage().contains("temporary_cells_require_cleanup_executor"));
    }

    private static BlockStateSpec door(String half) {
        return new BlockStateSpec("minecraft:oak_door", Map.of(
                "facing", "south",
                "half", half,
                "hinge", "left",
                "open", "false",
                "powered", "false"));
    }

    private static PlanPlacement placement(
            String id,
            int x,
            int y,
            int z,
            BlockStateSpec state,
            CellOperation operation,
            ReplacePolicy policy,
            BuildPhase phase,
            List<String> dependencies,
            String atomicGroup
    ) {
        return new PlanPlacement(
                id, x, y, z, state, operation, policy, MaterialRole.GENERIC,
                phase, "test:" + id, dependencies, atomicGroup);
    }

    private static BuildingPlan plan(
            int width,
            int height,
            int depth,
            List<PlanPlacement> placements
    ) {
        return new BuildingPlan(
                BuildingPlan.CURRENT_SCHEMA_VERSION,
                "test:adapter",
                1,
                "Adapter test",
                width,
                height,
                depth,
                5L,
                "test",
                placements,
                Map.of());
    }
}
