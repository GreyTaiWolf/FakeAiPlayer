package io.github.greytaiwolf.fakeaiplayer.persist;

import io.github.greytaiwolf.fakeaiplayer.goal.Goal;
import io.github.greytaiwolf.fakeaiplayer.mission.GoalSpec;
import io.github.greytaiwolf.fakeaiplayer.mission.MissionPolicy;
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
            assertTrue(spec.bindingValid());
            assertTrue(spec.params().keySet().stream().noneMatch(key -> key.contains("task") || key.contains("phase")));
        }
    }

    @Test
    void invalidNumericOrFutureTypeIsIsolated() {
        assertTrue(new MissionSpec("food", java.util.Map.of("count", "not-a-number"), List.of()).toGoal().isEmpty());
        assertTrue(new MissionSpec("future_goal", java.util.Map.of(), List.of()).toGoal().isEmpty());
    }

    @Test
    void sourceAndPriorityRoundTripWhileLegacyRecordsRestoreWithoutGainingAuthority() {
        Goal goal = new Goal.HaveItem(net.minecraft.world.item.Items.IRON_INGOT, 2);
        MissionSpec player = MissionSpec.fromGoal(
                goal, GoalSpec.Source.PLAYER_COMMAND, 93);

        assertEquals(GoalSpec.Source.PLAYER_COMMAND, player.sourceOrRestored());
        assertEquals(93, player.priorityOrDefault());
        assertEquals(goal, player.toGoal().orElseThrow());
        assertTrue(player.bindingValid());

        MissionSpec legacyShape = new MissionSpec(
                player.type(), player.params(), player.values());
        assertEquals(GoalSpec.Source.RESTORED, legacyShape.sourceOrRestored());
        assertEquals(GoalSpec.defaultPriority(GoalSpec.Source.RESTORED),
                legacyShape.priorityOrDefault());
        assertTrue(legacyShape.legacyUnboundShape());

        MissionSpec corruptAuthority = new MissionSpec(
                player.type(), player.params(), player.values(),
                GoalSpec.Source.PLAYER_COMMAND.name(), 10_000);
        assertEquals(GoalSpec.Source.RESTORED, corruptAuthority.sourceOrRestored());
        assertEquals(GoalSpec.defaultPriority(GoalSpec.Source.RESTORED),
                corruptAuthority.priorityOrDefault());
        assertEquals(goal, corruptAuthority.toGoal().orElseThrow());

        for (MissionSpec corruptSource : List.of(
                new MissionSpec(player.type(), player.params(), player.values(), "HACKED", 100),
                new MissionSpec(player.type(), player.params(), player.values(), "", 100))) {
            assertEquals(GoalSpec.Source.RESTORED, corruptSource.sourceOrRestored());
            assertEquals(GoalSpec.defaultPriority(GoalSpec.Source.RESTORED),
                    corruptSource.priorityOrDefault());
        }
    }

    @Test
    void currentBindingRejectsSingleFieldTamperingWithoutBreakingLegacyMigration() {
        MissionSpec current = MissionSpec.fromGoal(
                new Goal.Food(4), GoalSpec.Source.AI_PROPOSAL, 72);
        MissionSpec tamperedPriority = new MissionSpec(
                current.type(), current.params(), current.values(), current.source(), 100,
                current.policy(), current.binding());
        MissionSpec missingBinding = new MissionSpec(
                current.type(), current.params(), current.values(), current.source(),
                current.priority(), current.policy());
        MissionSpec legacy = new MissionSpec(
                current.type(), current.params(), current.values());

        assertTrue(current.bindingPresent());
        assertTrue(current.bindingValid());
        assertFalse(tamperedPriority.bindingValid());
        assertFalse(missingBinding.bindingPresent());
        assertFalse(missingBinding.legacyUnboundShape());
        assertTrue(legacy.legacyUnboundShape());
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
    void activeMissionPolicyRoundTripsExactlyWhileLegacyRecordsRemainUnbound() {
        MissionPolicy policy = new MissionPolicy(
                MissionPolicy.RiskLevel.BOLD,
                MissionPolicy.MutationScope.CONFIRMED_AREA,
                12_345,
                7,
                MissionPolicy.InterruptionPolicy.REPLAN_AFTER_SAFETY);
        MissionSpec current = MissionSpec.fromGoal(
                new Goal.Food(3), GoalSpec.Source.PLAYER_COMMAND, 91, policy);
        MissionSpec legacy = new MissionSpec(
                current.type(), current.params(), current.values(), current.source(), current.priority());
        MissionSpec corrupt = new MissionSpec(
                current.type(), current.params(), current.values(), current.source(), current.priority(),
                new MissionSpec.PolicySpec("IMPOSSIBLE", "SURVIVAL", 1, 0,
                        "RESUME_AFTER_SAFETY"));

        assertTrue(current.policyPresent());
        assertTrue(current.bindingValid());
        assertEquals(policy, current.persistedPolicy().orElseThrow());
        assertFalse(legacy.policyPresent());
        assertTrue(legacy.persistedPolicy().isEmpty());
        assertTrue(corrupt.policyPresent());
        assertTrue(corrupt.persistedPolicy().isEmpty());
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
