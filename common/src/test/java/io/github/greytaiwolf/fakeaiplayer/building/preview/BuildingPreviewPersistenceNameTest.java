package io.github.greytaiwolf.fakeaiplayer.building.preview;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.greytaiwolf.fakeaiplayer.building.plan.BuildingPlan;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class BuildingPreviewPersistenceNameTest {
    private static final UUID SESSION_ID = UUID.fromString("12345678-1234-5678-9abc-def012345678");

    @Test
    void embedsSanitizedBuildingCodeWhileKeepingSessionCollisionResistance() {
        assertEquals(
                "generated_b0042_12345678123456789abcdef012345678_r7",
                BuildingPreviewService.generatedBlueprintName(
                        plan(Map.of("building_code", "code-0042")), SESSION_ID, 7));
    }

    @Test
    void legacyPlansWithoutCodeKeepTheExistingNameShape() {
        assertEquals(
                "generated_12345678123456789abcdef012345678_r0",
                BuildingPreviewService.generatedBlueprintName(plan(Map.of()), SESSION_ID, 0));
    }

    private static BuildingPlan plan(Map<String, String> metadata) {
        return new BuildingPlan(
                BuildingPlan.CURRENT_SCHEMA_VERSION,
                "persistence-name-test",
                0,
                "Persistence name test",
                1,
                1,
                1,
                1L,
                "test",
                List.of(),
                metadata);
    }
}
