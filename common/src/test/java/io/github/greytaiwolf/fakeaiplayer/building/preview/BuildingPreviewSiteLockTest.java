package io.github.greytaiwolf.fakeaiplayer.building.preview;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.greytaiwolf.fakeaiplayer.building.plan.BuildingPlan;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class BuildingPreviewSiteLockTest {
    @Test
    void onlyExplicitTrueLocksTransforms() {
        assertTrue(BuildingPreviewService.isSiteLocked(plan(Map.of("site_locked", "true"))));
        assertFalse(BuildingPreviewService.isSiteLocked(plan(Map.of("site_locked", "false"))));
        assertFalse(BuildingPreviewService.isSiteLocked(plan(Map.of())));
    }

    private static BuildingPlan plan(Map<String, String> metadata) {
        return new BuildingPlan(
                BuildingPlan.CURRENT_SCHEMA_VERSION,
                "site-lock-test",
                0,
                "Site lock test",
                1,
                1,
                1,
                1L,
                "test",
                List.of(),
                metadata);
    }
}
