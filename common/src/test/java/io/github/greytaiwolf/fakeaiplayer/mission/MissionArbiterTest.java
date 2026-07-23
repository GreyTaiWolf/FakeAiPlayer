package io.github.greytaiwolf.fakeaiplayer.mission;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MissionArbiterTest {
    @Test
    void safetyPreemptsOrdinaryWorkRegardlessOfNumericPriority() {
        MissionArbiter.Decision decision = MissionArbiter.decide(
                claim(MissionArbiter.WorkKind.MISSION, "mission-1", 1_000),
                claim(MissionArbiter.WorkKind.SAFETY, "lava", 0),
                false);

        assertEquals(MissionArbiter.Action.PREEMPT, decision.action());
        assertEquals("safety_preempts_ordinary_work", decision.reason());
        assertTrue(decision.startsIncoming());
    }

    @Test
    void ordinaryWorkCannotReplaceActiveSafetyWork() {
        MissionArbiter.Decision decision = MissionArbiter.decide(
                claim(MissionArbiter.WorkKind.SAFETY, "combat", 1),
                claim(MissionArbiter.WorkKind.PLAYER, "owner-command", 1_000),
                false);

        assertEquals(MissionArbiter.Action.DEFER, decision.action());
        assertEquals("safety_work_active", decision.reason());
        assertFalse(decision.startsIncoming());
    }

    @Test
    void explicitPlayerWorkReplacesOrdinaryWorkRegardlessOfNumericPriority() {
        MissionArbiter.Decision decision = MissionArbiter.decide(
                claim(MissionArbiter.WorkKind.MISSION, "autonomous-mission", 1_000),
                claim(MissionArbiter.WorkKind.PLAYER, "owner-command", 0),
                false);

        assertEquals(MissionArbiter.Action.REPLACE, decision.action());
        assertEquals("explicit_player_replacement", decision.reason());
        assertTrue(decision.startsIncoming());
    }

    @Test
    void lowerPriorityOrdinaryWorkIsDeferred() {
        MissionArbiter.Decision decision = MissionArbiter.decide(
                claim(MissionArbiter.WorkKind.JOB, "claimed-job", 500),
                claim(MissionArbiter.WorkKind.BACKGROUND, "idle", 100),
                false);

        assertEquals(MissionArbiter.Action.DEFER, decision.action());
        assertEquals("higher_or_equal_priority_work_active", decision.reason());
        assertFalse(decision.startsIncoming());
    }

    @Test
    void routineSurvivalReflexPausesResumableMissionInsteadOfDestroyingIt() {
        MissionArbiter.Decision decision = MissionArbiter.decide(
                claim(MissionArbiter.WorkKind.MISSION, "mission-1", 700),
                claim(MissionArbiter.WorkKind.REFLEX, "resupply", 750),
                false);

        assertEquals(MissionArbiter.Action.PREEMPT, decision.action());
        assertEquals("higher_priority_resumable_interrupt", decision.reason());
        assertTrue(decision.startsIncoming());
    }

    @Test
    void goalSourceAndPriorityEnterTheSharedAdmissionScale() {
        GoalSpec player = goal(GoalSpec.Source.PLAYER_COMMAND, 94);
        GoalSpec ai = goal(GoalSpec.Source.AI_PROPOSAL, 70);
        GoalSpec autonomous = goal(GoalSpec.Source.AUTONOMOUS, 40);

        MissionArbiter.WorkClaim playerClaim = MissionArbiter.goalClaim(player, "player-goal");
        MissionArbiter.WorkClaim aiClaim = MissionArbiter.goalClaim(ai, "ai-goal");
        MissionArbiter.WorkClaim autonomousClaim = MissionArbiter.goalClaim(
                autonomous, "autonomous-goal");

        assertEquals(MissionArbiter.WorkKind.PLAYER_MISSION, playerClaim.kind());
        assertEquals(694, playerClaim.priority());
        assertEquals(MissionArbiter.WorkKind.MISSION, aiClaim.kind());
        assertEquals(670, aiClaim.priority());
        assertEquals(640, autonomousClaim.priority());
        assertEquals(MissionArbiter.Action.REPLACE,
                MissionArbiter.decide(aiClaim, playerClaim, false).action());
        assertEquals(MissionArbiter.Action.DEFER,
                MissionArbiter.decide(playerClaim, autonomousClaim, false).action());
    }

    @Test
    void numericAiPriorityCannotOverrideAnExplicitPlayerMission() {
        MissionArbiter.WorkClaim player = MissionArbiter.goalClaim(
                goal(GoalSpec.Source.PLAYER_COMMAND, 1), "player-goal");
        MissionArbiter.WorkClaim ai = MissionArbiter.goalClaim(
                goal(GoalSpec.Source.AI_PROPOSAL, 100), "ai-goal");

        MissionArbiter.Decision decision = MissionArbiter.decide(player, ai, false);

        assertEquals(MissionArbiter.Action.DEFER, decision.action());
        assertEquals("mission_admission_not_stronger", decision.reason());
    }

    @Test
    void equalPlayerSourceAndPriorityQueuesInsteadOfReplacing() {
        MissionArbiter.WorkClaim current = MissionArbiter.goalClaim(
                goal(GoalSpec.Source.PLAYER_COMMAND, 90), "first-player-goal");
        MissionArbiter.WorkClaim incoming = MissionArbiter.goalClaim(
                goal(GoalSpec.Source.PLAYER_COMMAND, 90), "second-player-goal");

        MissionArbiter.Decision decision = MissionArbiter.decide(current, incoming, false);

        assertEquals(MissionArbiter.Action.DEFER, decision.action());
        assertEquals("mission_admission_not_stronger", decision.reason());
        assertFalse(decision.startsIncoming());
    }

    @Test
    void higherPriorityWithinTheSameMissionSourceMayReplace() {
        MissionArbiter.WorkClaim current = MissionArbiter.goalClaim(
                goal(GoalSpec.Source.PLAYER_COMMAND, 40), "lower-priority-player-goal");
        MissionArbiter.WorkClaim incoming = MissionArbiter.goalClaim(
                goal(GoalSpec.Source.PLAYER_COMMAND, 41), "higher-priority-player-goal");

        MissionArbiter.Decision decision = MissionArbiter.decide(current, incoming, false);

        assertEquals(MissionArbiter.Action.REPLACE, decision.action());
        assertEquals("stronger_mission_admission", decision.reason());
    }

    @Test
    void sourceAuthorityOutranksNumericPriorityAcrossMissionSources() {
        MissionArbiter.WorkClaim confirmed = MissionArbiter.goalClaim(
                goal(GoalSpec.Source.PLAYER_CONFIRMED, 1), "confirmed-goal");
        MissionArbiter.WorkClaim command = MissionArbiter.goalClaim(
                goal(GoalSpec.Source.PLAYER_COMMAND, 100), "command-goal");
        MissionArbiter.WorkClaim ai = MissionArbiter.goalClaim(
                goal(GoalSpec.Source.AI_PROPOSAL, 100), "ai-goal");

        assertEquals(MissionArbiter.Action.DEFER,
                MissionArbiter.decide(confirmed, command, false).action());
        assertEquals(MissionArbiter.Action.REPLACE,
                MissionArbiter.decide(ai, command, false).action());
        assertEquals(MissionArbiter.Action.REPLACE,
                MissionArbiter.decide(command, confirmed, false).action());
    }

    @Test
    void persistentPauseRejectsGoalReplacementUntilItsOwnerResumes() {
        MissionArbiter.WorkClaim current = MissionArbiter.goalClaim(
                goal(GoalSpec.Source.AUTONOMOUS, 1), "paused-goal");
        MissionArbiter.WorkClaim incoming = MissionArbiter.goalClaim(
                goal(GoalSpec.Source.PLAYER_COMMAND, 100), "new-player-goal");

        MissionArbiter.Decision decision = MissionArbiter.decide(current, incoming, true);

        assertEquals(MissionArbiter.Action.REJECT, decision.action());
        assertEquals("persistent_pause_owned", decision.reason());
        assertFalse(decision.startsIncoming());
    }

    private static GoalSpec goal(GoalSpec.Source source, int priority) {
        return new GoalSpec(
                "have_item",
                source,
                priority,
                "inventory(minecraft:iron_ingot)>=1",
                "minecraft:overworld",
                MissionPolicy.standard(),
                Map.of());
    }

    private static MissionArbiter.WorkClaim claim(MissionArbiter.WorkKind kind,
                                                   String owner,
                                                   int priority) {
        return new MissionArbiter.WorkClaim(kind, owner, priority, true);
    }
}
