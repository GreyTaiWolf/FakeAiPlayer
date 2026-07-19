package io.github.greytaiwolf.fakeaiplayer.building.site;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.greytaiwolf.fakeaiplayer.building.plan.BlockStateSpec;
import io.github.greytaiwolf.fakeaiplayer.building.plan.BuildPhase;
import io.github.greytaiwolf.fakeaiplayer.building.plan.BuildingPlan;
import io.github.greytaiwolf.fakeaiplayer.building.plan.BuildingPlanBlueprintAdapter;
import io.github.greytaiwolf.fakeaiplayer.building.plan.BuildingPlanValidator;
import io.github.greytaiwolf.fakeaiplayer.building.plan.CellOperation;
import io.github.greytaiwolf.fakeaiplayer.building.plan.MaterialRole;
import io.github.greytaiwolf.fakeaiplayer.building.plan.PlanPlacement;
import io.github.greytaiwolf.fakeaiplayer.building.plan.PlanTransform;
import io.github.greytaiwolf.fakeaiplayer.building.plan.ReplacePolicy;
import io.github.greytaiwolf.fakeaiplayer.task.BlueprintSchema;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

class BuildingTerrainAdapterTest {
    @Test
    void compilesSurveyedHeightDifferenceIntoPermanentPiers() {
        BuildingPlan source = foundationPlan(3, 2);
        BuildingSiteSurvey survey = new BuildingSiteSurvey(
                "minecraft:overworld",
                new BlockPos(100, 65, -40), 3, 2,
                List.of(64, 65, 64, 65, 64, 65),
                64, 65, 0, 0, 0, 0.25D, "");

        BuildingTerrainAdapter.AdaptedPlan result = BuildingTerrainAdapter.adapt(
                source, PlanTransform.IDENTITY, survey);

        assertEquals(new BlockPos(100, 64, -40), result.anchor());
        assertEquals(2, result.plan().height());
        assertEquals("true", result.plan().metadata().get("site_locked"));
        assertEquals("STILTS", result.plan().metadata().get("site_strategy"));
        assertEquals("minecraft:overworld", result.plan().metadata().get("site_dimension"));
        assertEquals(3, result.pierCount());
        assertTrue(BuildingPlanValidator.validate(result.plan()).valid());

        long piers = result.plan().placements().stream()
                .filter(placement -> placement.componentId().equals("site:pier"))
                .count();
        assertEquals(3, piers);
        PlanPlacement shiftedLowColumn = at(result.plan(), 0, 1, 0);
        assertTrue(shiftedLowColumn.dependencies().contains("site:pier@0,0,0"));
        PlanPlacement shiftedHighColumn = at(result.plan(), 1, 1, 0);
        assertFalse(shiftedHighColumn.dependencies().stream()
                .anyMatch(dependency -> dependency.startsWith("site:pier@")));

        BlueprintSchema execution = BuildingPlanBlueprintAdapter.adapt(
                result.plan(), PlanTransform.IDENTITY);
        assertTrue(execution.placements().stream()
                .anyMatch(placement -> placement.dx() == 1
                        && placement.dy() == 1
                        && placement.dz() == 0
                        && placement.requiresExternalSupport()));
        assertTrue(execution.placements().stream()
                .anyMatch(placement -> placement.dx() == 0
                        && placement.dy() == 0
                        && placement.dz() == 0
                        && placement.requiresExternalSupport()));
        assertFalse(execution.placements().stream()
                .filter(placement -> placement.dx() == 0
                        && placement.dy() == 1
                        && placement.dz() == 0)
                .findFirst()
                .orElseThrow()
                .requiresExternalSupport());
    }

    @Test
    void rejectsWaterIncompleteAndTooSteepSurveys() {
        BuildingPlan plan = foundationPlan(1, 1);
        assertThrows(IllegalArgumentException.class, () -> BuildingTerrainAdapter.adapt(
                plan, PlanTransform.IDENTITY,
                new BuildingSiteSurvey("minecraft:overworld", new BlockPos(0, 64, 0), 1, 1,
                        List.of(64), 64, 64, 1, 0, 0, 0, "")));

        BuildingPlan wide = foundationPlan(2, 1);
        assertThrows(IllegalArgumentException.class, () -> BuildingTerrainAdapter.adapt(
                wide, PlanTransform.IDENTITY,
                new BuildingSiteSurvey("minecraft:overworld", new BlockPos(0, 64, 0), 2, 1,
                        List.of(64, 70), 64, 70, 0, 0, 0, 9, "")));
    }

    @Test
    void surveySignatureBindsCoordinatesAndEveryHeight() {
        BuildingSiteSurvey survey = new BuildingSiteSurvey(
                "minecraft:overworld",
                new BlockPos(4, 70, 8), 2, 2,
                List.of(70, 70, 71, 70), 70, 71,
                0, 0, 0, 0.1875D, "");
        assertEquals(64, survey.signature().length());
        assertThrows(IllegalArgumentException.class, () -> new BuildingSiteSurvey(
                "minecraft:overworld",
                new BlockPos(4, 70, 8), 2, 2,
                List.of(70, 70, 71, 71), 70, 71,
                0, 0, 0, 0.25D, survey.signature()));
    }

    @Test
    void surveyRejectsForgedDerivedStatisticsAndBindsDimension() {
        List<Integer> heights = List.of(70, 72);
        assertThrows(IllegalArgumentException.class, () -> new BuildingSiteSurvey(
                "minecraft:overworld", new BlockPos(4, 70, 8), 2, 1,
                heights, 70, 70, 0, 0, 0, 1.0D, ""));
        assertThrows(IllegalArgumentException.class, () -> new BuildingSiteSurvey(
                "minecraft:overworld", new BlockPos(4, 70, 8), 2, 1,
                heights, 70, 72, 0, 0, 0, 0.5D, ""));

        BuildingSiteSurvey overworld = new BuildingSiteSurvey(
                "minecraft:overworld", new BlockPos(4, 70, 8), 2, 1,
                heights, 70, 72, 0, 0, 0, 1.0D, "");
        BuildingSiteSurvey nether = new BuildingSiteSurvey(
                "minecraft:the_nether", new BlockPos(4, 70, 8), 2, 1,
                heights, 70, 72, 0, 0, 0, 1.0D, "");
        assertNotEquals(overworld.signature(), nether.signature());
    }

    private static BuildingPlan foundationPlan(int width, int depth) {
        List<PlanPlacement> placements = new ArrayList<>();
        for (int z = 0; z < depth; z++) {
            for (int x = 0; x < width; x++) {
                String id = "foundation@" + x + ",0," + z;
                placements.add(new PlanPlacement(
                        id, x, 0, z, new BlockStateSpec("minecraft:cobblestone"),
                        CellOperation.PLACE, ReplacePolicy.REPLACE_NATURAL,
                        MaterialRole.FOUNDATION, BuildPhase.FOUNDATION,
                        "foundation", List.of(), ""));
            }
        }
        return new BuildingPlan(
                BuildingPlan.CURRENT_SCHEMA_VERSION, "test:site", 0,
                "site test", width, 1, depth, 42L, "test-1",
                placements, Map.of());
    }

    private static PlanPlacement at(BuildingPlan plan, int x, int y, int z) {
        return plan.placements().stream()
                .filter(placement -> placement.dx() == x
                        && placement.dy() == y && placement.dz() == z)
                .findFirst()
                .orElseThrow();
    }
}
