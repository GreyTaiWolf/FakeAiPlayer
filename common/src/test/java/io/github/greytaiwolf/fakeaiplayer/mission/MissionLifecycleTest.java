package io.github.greytaiwolf.fakeaiplayer.mission;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MissionLifecycleTest {
    @Test
    void acceptsACompletePlanRunRecoveryAndVerificationPath() {
        List<MissionState> path = List.of(
                MissionState.PROPOSED,
                MissionState.VALIDATED,
                MissionState.PLANNED,
                MissionState.RUNNING,
                MissionState.SUSPENDED,
                MissionState.RECOVERING,
                MissionState.VERIFYING,
                MissionState.SUCCEEDED);

        for (int index = 0; index < path.size() - 1; index++) {
            MissionState from = path.get(index);
            MissionState to = path.get(index + 1);
            assertTrue(MissionLifecycle.canTransition(from, to), from + " -> " + to);
            assertEquals(to, MissionLifecycle.transition(from, to));
        }
    }

    @Test
    void rejectsSkippingValidationPlanningOrVerification() {
        assertInvalid(MissionState.PROPOSED, MissionState.RUNNING);
        assertInvalid(MissionState.VALIDATED, MissionState.SUCCEEDED);
        assertInvalid(MissionState.RUNNING, MissionState.SUCCEEDED);
        assertInvalid(MissionState.VERIFYING, MissionState.RUNNING);
    }

    @Test
    void suspendedMissionMayEnterReadOnlyVerification() {
        assertTrue(MissionLifecycle.canTransition(MissionState.SUSPENDED, MissionState.VERIFYING));
        assertEquals(MissionState.VERIFYING,
                MissionLifecycle.transition(MissionState.SUSPENDED, MissionState.VERIFYING));
    }

    @Test
    void plannedMissionMayRecoverFromAnAdmissionGateBeforeAChildStarts() {
        assertTrue(MissionLifecycle.canTransition(
                MissionState.PLANNED, MissionState.RECOVERING));
        assertEquals(MissionState.RECOVERING,
                MissionLifecycle.transition(
                        MissionState.PLANNED, MissionState.RECOVERING));
    }

    @Test
    void restoredPlannedMissionMayEnterRecoveryBeforeStartingNewWork() {
        assertTrue(MissionLifecycle.canTransition(
                MissionState.PLANNED, MissionState.RECOVERING));
        assertEquals(MissionState.RECOVERING,
                MissionLifecycle.transition(MissionState.PLANNED, MissionState.RECOVERING));
    }

    @Test
    void terminalStatesCannotBeResurrectedButIdempotentTransitionIsAllowed() {
        for (MissionState terminal : List.of(
                MissionState.SUCCEEDED,
                MissionState.BLOCKED,
                MissionState.FAILED,
                MissionState.CANCELLED)) {
            assertTrue(terminal.terminal());
            assertTrue(MissionLifecycle.canTransition(terminal, terminal));
            assertEquals(terminal, MissionLifecycle.transition(terminal, terminal));
            assertInvalid(terminal, MissionState.RUNNING);
        }
    }

    @Test
    void nullEndpointsFailClosed() {
        assertFalse(MissionLifecycle.canTransition(null, MissionState.PROPOSED));
        assertFalse(MissionLifecycle.canTransition(MissionState.PROPOSED, null));
        assertThrows(IllegalStateException.class,
                () -> MissionLifecycle.transition(null, MissionState.PROPOSED));
    }

    private static void assertInvalid(MissionState from, MissionState to) {
        assertFalse(MissionLifecycle.canTransition(from, to), from + " -> " + to);
        assertThrows(IllegalStateException.class, () -> MissionLifecycle.transition(from, to));
    }
}
