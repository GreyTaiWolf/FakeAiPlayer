package io.github.greytaiwolf.fakeaiplayer.task;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ThreatResponsePolicyTest {
    @Test
    void routeScoreRewardsSeparationAndMultipleExits() {
        double exposedDeadEnd = ThreatResponsePolicy.escapeScore(
                3.0D, 9.0D, 3.0D, 1, 18, 0.8D, 5);
        double safeOpenRoute = ThreatResponsePolicy.escapeScore(
                3.0D, 13.0D, 6.0D, 3, 20, 0.8D, 1);

        assertTrue(safeOpenRoute > exposedDeadEnd);
    }

    @Test
    void routeThatEndsFarAwayButCutsThroughDangerIsRejected() {
        assertFalse(ThreatResponsePolicy.materiallySafer(
                4.0D, 13.0D, 1.5D, 8, 16));
    }

    @Test
    void routeWithSustainedDistanceGainIsAccepted() {
        assertTrue(ThreatResponsePolicy.materiallySafer(
                4.0D, 11.0D, 4.0D, 2, 16));
    }

    @Test
    void onlyHostileThreatSessionsMayCounterattack() {
        assertTrue(ThreatResponsePolicy.canCounterattack(Threat.Type.HOSTILE));
        assertTrue(ThreatResponsePolicy.canCounterattack(Threat.Type.LOW_HP));
        assertFalse(ThreatResponsePolicy.canCounterattack(Threat.Type.DROWNING));
        assertFalse(ThreatResponsePolicy.canCounterattack(Threat.Type.LAVA));
        assertFalse(ThreatResponsePolicy.canCounterattack(Threat.Type.FALLING));
    }

    @Test
    void terminalRouteIsRevalidatedAgainstCurrentHostiles() {
        assertFalse(ThreatResponsePolicy.shouldCompleteRoute(true, true, false));
        assertTrue(ThreatResponsePolicy.shouldCompleteRoute(true, true, true));
        assertTrue(ThreatResponsePolicy.shouldCompleteRoute(true, false, false));
        assertFalse(ThreatResponsePolicy.shouldCompleteRoute(false, false, false));
    }

    @Test
    void failedEscapeEscalatesFromBreakContactToLastStand() {
        assertEquals(ThreatResponsePolicy.Fallback.BREAK_CONTACT,
                ThreatResponsePolicy.fallback(true, 14.0F, 1));
        assertEquals(ThreatResponsePolicy.Fallback.LAST_STAND,
                ThreatResponsePolicy.fallback(true, 14.0F, 2));
        assertEquals(ThreatResponsePolicy.Fallback.LAST_STAND,
                ThreatResponsePolicy.fallback(true, 5.0F, 1));
    }

    @Test
    void environmentalFailureDoesNotInventAnAttackTarget() {
        assertEquals(ThreatResponsePolicy.Fallback.NONE,
                ThreatResponsePolicy.fallback(false, 5.0F, 3));
    }
}
