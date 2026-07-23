package io.github.greytaiwolf.fakeaiplayer.mission;

import org.junit.jupiter.api.Test;

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

    private static MissionArbiter.WorkClaim claim(MissionArbiter.WorkKind kind,
                                                   String owner,
                                                   int priority) {
        return new MissionArbiter.WorkClaim(kind, owner, priority, true);
    }
}
