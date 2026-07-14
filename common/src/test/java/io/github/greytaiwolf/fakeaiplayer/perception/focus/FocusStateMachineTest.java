package io.github.greytaiwolf.fakeaiplayer.perception.focus;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FocusStateMachineTest {
    @Test
    void stableCandidateBecomesTracked() {
        FocusStateMachine machine = new FocusStateMachine(2, 2);

        FocusStateMachine.Update first = machine.sample("block:a");
        assertEquals(FocusState.ACQUIRING, first.state());
        assertEquals(FocusEvent.NONE, first.event());

        FocusStateMachine.Update second = machine.sample("block:a");
        assertEquals(FocusState.TRACKING, second.state());
        assertEquals(FocusEvent.ACQUIRED, second.event());
        assertEquals("block:a", second.targetKey());
    }

    @Test
    void changingTargetMustBeConfirmedAgain() {
        FocusStateMachine machine = trackedMachine("block:a");

        FocusStateMachine.Update firstB = machine.sample("entity:2");
        assertEquals(FocusState.ACQUIRING, firstB.state());
        assertEquals(FocusEvent.NONE, firstB.event());

        FocusStateMachine.Update confirmedB = machine.sample("entity:2");
        assertEquals(FocusState.TRACKING, confirmedB.state());
        assertEquals(FocusEvent.CHANGED, confirmedB.event());
        assertEquals("entity:2", confirmedB.targetKey());
    }

    @Test
    void shortMissUsesGraceAndCanRecover() {
        FocusStateMachine machine = trackedMachine("entity:3");

        FocusStateMachine.Update missed = machine.sample(null);
        assertEquals(FocusState.LOST_GRACE, missed.state());
        assertTrue(missed.stale());

        FocusStateMachine.Update recovered = machine.sample("entity:3");
        assertEquals(FocusState.TRACKING, recovered.state());
        assertEquals(FocusEvent.NONE, recovered.event());
        assertFalse(recovered.stale());
    }

    @Test
    void returningFromTransientCandidateImmediatelyRestoresActiveTarget() {
        FocusStateMachine machine = trackedMachine("block:a");

        assertEquals(FocusState.ACQUIRING, machine.sample("entity:b").state());
        FocusStateMachine.Update recovered = machine.sample("block:a");

        assertEquals(FocusState.TRACKING, recovered.state());
        assertEquals(FocusEvent.NONE, recovered.event());
        assertEquals("block:a", recovered.targetKey());
    }

    @Test
    void graceExpiryEmitsLostAndClearsTarget() {
        FocusStateMachine machine = trackedMachine("entity:4");

        assertEquals(FocusState.LOST_GRACE, machine.sample(null).state());
        assertEquals(FocusState.LOST_GRACE, machine.sample(null).state());
        FocusStateMachine.Update lost = machine.sample(null);

        assertEquals(FocusState.NO_TARGET, lost.state());
        assertEquals(FocusEvent.LOST, lost.event());
        assertEquals("entity:4", lost.targetKey());
    }

    @Test
    void disabledStateClearsPreviousTrackingIdentity() {
        FocusStateMachine machine = trackedMachine("block:a");
        assertEquals(FocusState.DISABLED, machine.disable().state());

        FocusStateMachine.Update afterEnableSample = machine.sample("block:a");
        assertEquals(FocusState.ACQUIRING, afterEnableSample.state());
        assertEquals(FocusEvent.NONE, afterEnableSample.event());
    }

    private static FocusStateMachine trackedMachine(String key) {
        FocusStateMachine machine = new FocusStateMachine(2, 2);
        machine.sample(key);
        machine.sample(key);
        return machine;
    }
}
