package io.github.greytaiwolf.fakeaiplayer.brain;

import com.google.gson.JsonObject;
import io.github.greytaiwolf.fakeaiplayer.coordination.IdleCoordinator;
import io.github.greytaiwolf.fakeaiplayer.coordination.Job;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolRegistryBuildingBoundaryTest {
    private static final String EXPECTED =
            "building_requires_player_confirmation: use draft_building";

    @Test
    void everyLlmBuildingBypassRequiresThePreviewGate() {
        ToolRegistry registry = new ToolRegistry();

        assertRejected(registry.get("build_house").orElseThrow(), new JsonObject());

        JsonObject assignArgs = new JsonObject();
        assignArgs.addProperty("task_type", "build");
        assignArgs.add("params", new JsonObject());
        assertRejected(registry.get("assign_task").orElseThrow(), assignArgs);

        JsonObject jobArgs = new JsonObject();
        jobArgs.addProperty("kind", " BUILD ");
        jobArgs.add("params", new JsonObject());
        assertRejected(registry.get("post_job").orElseThrow(), jobArgs);

        Job persistedLegacyBuild = new Job(
                UUID.randomUUID(), "build", Map.of("blueprint", "small_hut"), "",
                Job.Scope.OWNER, UUID.randomUUID(), Job.Status.OPEN,
                null, null, null, "");
        assertTrue(IdleCoordinator.jobToTask(null, persistedLegacyBuild).isEmpty(),
                "a queued legacy AI build must not bypass confirmation after restart");
    }

    private static void assertRejected(ToolDefinition definition, JsonObject args) {
        // The guard deliberately runs before any Bot access, which also proves a rejected call
        // cannot submit a Goal, assign a Task, or publish a coordination job as a side effect.
        ToolDefinition.ToolResult result = definition.handler().invoke(null, args);
        assertFalse(result.ok());
        assertEquals(EXPECTED, result.message());
    }
}
