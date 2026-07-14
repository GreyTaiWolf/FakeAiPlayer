package io.github.greytaiwolf.fakeaiplayer.brain;

import io.github.greytaiwolf.fakeaiplayer.AIBotConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolRegistryFocusTest {
    @Test
    void inspectFocusIsAlwaysExposedAsCoreTool() {
        ToolRegistry registry = new ToolRegistry();
        ToolDefinition definition = registry.get("inspect_focus").orElseThrow();

        assertEquals(ToolDefinition.Group.CORE, definition.group());
        assertTrue(definition.parametersSchema().getAsJsonObject("properties")
                .has("expected_target_token"));
        assertTrue(registry.tools(AIBotConfig.defaults().brain()).stream()
                .anyMatch(tool -> tool.name().equals("inspect_focus")));
    }

    @Test
    void inspectionRemainsAllowedWhileMissionIsPaused() {
        assertTrue(ActionDispatcher.isAllowedWhileUserPaused("inspect_focus"));
    }
}
