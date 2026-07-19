package io.github.greytaiwolf.fakeaiplayer.brain;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolRegistryBuildingCatalogTest {
    @Test
    void draftBuildingExposesCodeAndBoundedSiteInputsWithoutAnAiConfirmTool() {
        ToolRegistry registry = new ToolRegistry();
        JsonObject properties = registry.get("draft_building").orElseThrow()
                .parametersSchema().getAsJsonObject("properties");

        assertTrue(properties.has("building_code"));
        assertTrue(properties.has("random_code"));
        assertTrue(properties.has("search_radius"));
        assertTrue(properties.getAsJsonObject("building_code")
                .get("description").getAsString().contains("leading zeroes"));
        assertTrue(properties.getAsJsonObject("search_radius").has("maximum"));
        assertFalse(registry.get("confirm_building").isPresent());
        assertFalse(registry.get("confirm_building_preview").isPresent());
    }
}
