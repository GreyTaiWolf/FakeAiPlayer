package io.github.greytaiwolf.fakeaiplayer.building.plan;

import io.github.greytaiwolf.fakeaiplayer.task.BlueprintSchema;
import java.util.List;
import java.util.Map;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LegacyBlueprintAdapterTest {
    @BeforeAll
    static void bootstrapRegistries() {
        Bootstrap.bootStrap();
    }

    @Test
    void preservesLegacyDimensionsCellsAndOptionalStateProperties() {
        BlueprintSchema legacy = new BlueprintSchema(
                "stateful_test",
                2,
                2,
                1,
                List.of(
                        new BlueprintSchema.BlockPlacement(
                                0, 0, 0, "minecraft:oak_log", "logs", Map.of("axis", "x")),
                        new BlueprintSchema.BlockPlacement(1, 1, 0, "minecraft:air")),
                List.of());

        BuildingPlan plan = LegacyBlueprintAdapter.adapt(legacy);

        assertEquals(2, plan.placements().size());
        assertEquals(MaterialRole.FRAME, plan.placements().get(0).materialRole());
        assertEquals(Map.of("axis", "x"), plan.placements().get(0).state().properties());
        assertEquals(CellOperation.CLEAR, plan.placements().get(1).operation());
        assertTrue(BuildingPlanValidator.validate(plan).valid());
    }

    @Test
    void expandsLegacyOpsInsteadOfDroppingTheStructure() {
        BuildingPlan plan = LegacyBlueprintAdapter.adapt(BlueprintSchema.smallHutOps());

        assertTrue(plan.placements().size() > 2);
        assertTrue(plan.placements().stream()
                .anyMatch(placement -> placement.state().blockId().equals("minecraft:oak_planks")));
    }
}
