package io.github.greytaiwolf.fakeaiplayer.persist;

import io.github.greytaiwolf.fakeaiplayer.goal.Goal;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MissionSpecTest {
    private static final String BLUEPRINT_DIGEST = "ab".repeat(32);

    @Test
    void registryIndependentGoalKindsRoundTripWithoutTaskState() {
        List<Goal> goals = List.of(
                new Goal.HavePickaxeTier(3),
                new Goal.Armor(),
                new Goal.Workstation(),
                new Goal.Food(5));

        for (Goal goal : goals) {
            MissionSpec spec = MissionSpec.fromGoal(goal);
            assertEquals(goal, spec.toGoal().orElseThrow());
            assertTrue(spec.params().keySet().stream().noneMatch(key -> key.contains("task") || key.contains("phase")));
        }
    }

    @Test
    void invalidNumericOrFutureTypeIsIsolated() {
        assertTrue(new MissionSpec("food", java.util.Map.of("count", "not-a-number"), List.of()).toGoal().isEmpty());
        assertTrue(new MissionSpec("future_goal", java.util.Map.of(), List.of()).toGoal().isEmpty());
    }

    @Test
    void confirmedBuildBindingRoundTripsWhileLegacyBuildsRemainReadableButUntrusted() {
        Goal.Build confirmed = new Goal.Build(
                "generated_owner_hash",
                new BlockPos(-12, 70, 345),
                "minecraft:overworld",
                BLUEPRINT_DIGEST);

        MissionSpec persisted = MissionSpec.fromGoal(confirmed);

        assertEquals(confirmed, persisted.toGoal().orElseThrow());
        assertTrue(confirmed.hasCompleteConfirmedBinding());
        assertEquals(Map.of(
                "blueprint", "generated_owner_hash",
                "anchor_x", "-12",
                "anchor_y", "70",
                "anchor_z", "345",
                "dimension", "minecraft:overworld",
                "blueprint_digest", BLUEPRINT_DIGEST), persisted.params());
        Goal.Build oldPreset = (Goal.Build) new MissionSpec(
                "build", Map.of("blueprint", "small_hut"), List.of()).toGoal().orElseThrow();
        assertEquals(new Goal.Build("small_hut"), oldPreset);
        assertFalse(oldPreset.isGeneratedReference());
        assertFalse(oldPreset.hasCompleteConfirmedBinding());
        // Decoder compatibility is intentionally broader than executor trust: old records are
        // readable so unrelated snapshot data is not corrupted, but GoalExecutor rejects every
        // Build lacking the full confirmation-time binding.
        Goal.Build oldGenerated = (Goal.Build) new MissionSpec("build", Map.of(
                        "blueprint", "generated_old_record",
                        "anchor_x", "1",
                        "anchor_y", "2",
                        "anchor_z", "3",
                        "dimension", "minecraft:overworld"), List.of()).toGoal().orElseThrow();
        assertEquals(new Goal.Build(
                "generated_old_record", new BlockPos(1, 2, 3), "minecraft:overworld"), oldGenerated);
        assertFalse(oldGenerated.hasCompleteConfirmedBinding());
    }

    @Test
    void partialOrMalformedBuildAnchorIsIsolated() {
        assertTrue(new MissionSpec("build", Map.of(
                "blueprint", "generated_test",
                "anchor_x", "1",
                "anchor_y", "2"), List.of()).toGoal().isEmpty());
        assertTrue(new MissionSpec("build", Map.of(
                "blueprint", "generated_test",
                "anchor_x", "1",
                "anchor_y", "not-a-number",
                "anchor_z", "3"), List.of()).toGoal().isEmpty());
        assertTrue(new MissionSpec("build", Map.of(
                "blueprint", "generated_test",
                "dimension", "not a resource location"), List.of()).toGoal().isEmpty());
        assertTrue(new MissionSpec("build", Map.of(
                "blueprint", "generated_test",
                "blueprint_digest", "not-a-sha256"), List.of()).toGoal().isEmpty());
    }
}
