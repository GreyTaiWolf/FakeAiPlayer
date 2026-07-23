package io.github.greytaiwolf.fakeaiplayer.task;

import io.github.greytaiwolf.fakeaiplayer.mission.GoalSpec;
import io.github.greytaiwolf.fakeaiplayer.mission.MissionArbiter;
import io.github.greytaiwolf.fakeaiplayer.mission.MissionPolicy;
import io.github.greytaiwolf.fakeaiplayer.runtime.TaskOrigin;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TaskManagerWorkClaimTest {
    @Test
    void legacyMissionFactoryKeepsPreMetadataArbitrationCompatibility() {
        MissionArbiter.WorkClaim claim = TaskManager.workClaim(
                TaskOrigin.mission(UUID.randomUUID(), "legacy_runtime"));

        assertEquals(MissionArbiter.WorkKind.MISSION, claim.kind());
        assertEquals(700, claim.priority());
        assertTrue(claim.resumable());
    }

    @Test
    void goalPriorityStaysInsideTheMissionBand() {
        MissionArbiter.WorkClaim autonomous = TaskManager.workClaim(TaskOrigin.mission(
                UUID.randomUUID(), "autonomous", GoalSpec.Source.AUTONOMOUS, 0));
        MissionArbiter.WorkClaim ai = TaskManager.workClaim(TaskOrigin.mission(
                UUID.randomUUID(), "ai", GoalSpec.Source.AI_PROPOSAL, 70));
        MissionArbiter.WorkClaim maximum = TaskManager.workClaim(TaskOrigin.mission(
                UUID.randomUUID(), "maximum", GoalSpec.Source.LEGACY, 100));

        assertEquals(600, autonomous.priority());
        assertEquals(670, ai.priority());
        assertEquals(700, maximum.priority());
        assertEquals(MissionArbiter.WorkKind.MISSION, autonomous.kind());
        assertEquals(MissionArbiter.WorkKind.MISSION, ai.kind());
    }

    @Test
    void goalSpecFactoryCarriesTypedSourceAndPriorityIntoTheWorkClaim() {
        GoalSpec goal = new GoalSpec(
                "obtain_iron",
                GoalSpec.Source.AI_PROPOSAL,
                73,
                "inventory:minecraft:iron_ingot>=1",
                "minecraft:overworld",
                MissionPolicy.standard(),
                Map.of());

        TaskOrigin origin = TaskOrigin.mission(UUID.randomUUID(), "step.0.mine", goal);
        MissionArbiter.WorkClaim claim = TaskManager.workClaim(origin);

        assertEquals(GoalSpec.Source.AI_PROPOSAL, origin.missionSource());
        assertEquals(73, origin.goalPriority());
        assertEquals("minecraft:overworld", origin.missionDimension());
        assertEquals(MissionArbiter.WorkKind.MISSION, claim.kind());
        assertEquals(673, claim.priority());
        assertEquals(GoalSpec.Source.AI_PROPOSAL, claim.missionSource());
    }

    @Test
    void boundMissionDimensionRejectsTheTickBeforeTaskExecution() {
        TaskOrigin bound = TaskOrigin.mission(UUID.randomUUID(), "step.0.mine", new GoalSpec(
                "obtain_iron",
                GoalSpec.Source.AI_PROPOSAL,
                73,
                "inventory:minecraft:iron_ingot>=1",
                "minecraft:overworld",
                MissionPolicy.standard(),
                Map.of()));

        assertEquals(null, TaskManager.missionDimensionFailure(bound, "minecraft:overworld"));
        assertEquals(
                "mission_bound_dimension_changed: expected=minecraft:overworld actual=minecraft:the_nether",
                TaskManager.missionDimensionFailure(bound, "minecraft:the_nether"));
        assertEquals(null, TaskManager.missionDimensionFailure(
                TaskOrigin.mission(UUID.randomUUID(), "legacy"), "minecraft:the_nether"));
        assertEquals(null, TaskManager.missionDimensionFailure(
                TaskOrigin.safety("lava_escape"), "minecraft:the_nether"));
    }

    @Test
    void playerGoalSourcesHaveExplicitPlayerMissionSemantics() {
        for (GoalSpec.Source source : java.util.List.of(
                GoalSpec.Source.PLAYER_COMMAND,
                GoalSpec.Source.PLAYER_CONFIRMED)) {
            MissionArbiter.WorkClaim player = TaskManager.workClaim(TaskOrigin.mission(
                    UUID.randomUUID(), source.name(), source, 90));
            MissionArbiter.WorkClaim ai = TaskManager.workClaim(TaskOrigin.mission(
                    UUID.randomUUID(), "ai", GoalSpec.Source.AI_PROPOSAL, 100));

            assertEquals(MissionArbiter.WorkKind.PLAYER_MISSION, player.kind());
            assertEquals(690, player.priority());
            assertEquals(source, player.missionSource());
            assertEquals(MissionArbiter.Action.REPLACE,
                    MissionArbiter.decide(ai, player, false).action());
        }
    }

    @Test
    void reflexVerifyAndSafetyStillPreemptPlayerMission() {
        MissionArbiter.WorkClaim player = TaskManager.workClaim(TaskOrigin.mission(
                UUID.randomUUID(), "confirmed", GoalSpec.Source.PLAYER_CONFIRMED, 100));

        for (TaskOrigin interrupt : java.util.List.of(
                TaskOrigin.of(TaskOrigin.Kind.REFLEX, "resupply"),
                TaskOrigin.of(TaskOrigin.Kind.VERIFY, "authoritative_check"),
                TaskOrigin.safety("lava_escape"))) {
            MissionArbiter.Decision decision = MissionArbiter.decide(
                    player, TaskManager.workClaim(interrupt), false);
            assertEquals(MissionArbiter.Action.PREEMPT, decision.action(), interrupt.toString());
        }
    }

    @Test
    void playerMissionCannotReplaceActiveReflexVerifyOrSafety() {
        MissionArbiter.WorkClaim player = TaskManager.workClaim(TaskOrigin.mission(
                UUID.randomUUID(), "confirmed", GoalSpec.Source.PLAYER_CONFIRMED, 100));

        for (TaskOrigin interrupt : java.util.List.of(
                TaskOrigin.of(TaskOrigin.Kind.REFLEX, "resupply"),
                TaskOrigin.of(TaskOrigin.Kind.VERIFY, "authoritative_check"),
                TaskOrigin.safety("lava_escape"))) {
            MissionArbiter.Decision decision = MissionArbiter.decide(
                    TaskManager.workClaim(interrupt), player, false);
            assertEquals(MissionArbiter.Action.DEFER, decision.action(), interrupt.toString());
        }
    }

    @Test
    void goalMetadataIsValidatedAndRestrictedToMissionOrigins() {
        assertEquals("goal_priority_out_of_range", assertThrows(IllegalArgumentException.class,
                () -> TaskOrigin.mission(
                        UUID.randomUUID(), "negative", GoalSpec.Source.AI_PROPOSAL, -1))
                .getMessage());
        assertEquals("goal_priority_out_of_range", assertThrows(IllegalArgumentException.class,
                () -> TaskOrigin.mission(
                        UUID.randomUUID(), "too_high", GoalSpec.Source.AI_PROPOSAL, 101))
                .getMessage());
        assertEquals("goal_metadata_requires_mission_origin", assertThrows(IllegalArgumentException.class,
                () -> new TaskOrigin(
                        TaskOrigin.Kind.JOB,
                        null,
                        UUID.randomUUID(),
                        "invalid",
                        GoalSpec.Source.AUTONOMOUS,
                        40))
                .getMessage());
    }
}
