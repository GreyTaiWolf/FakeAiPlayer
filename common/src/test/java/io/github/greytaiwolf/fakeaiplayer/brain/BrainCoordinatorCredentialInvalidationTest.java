package io.github.greytaiwolf.fakeaiplayer.brain;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class BrainCoordinatorCredentialInvalidationTest {
    @Test
    void credentialChangeMakesOldDecisionLeaseFailClosed() {
        DecisionSession decision = new DecisionSession(UUID.randomUUID());
        DecisionLease oldLease = decision.beginEpoch();

        assertTrue(BrainCoordinator.invalidateCredentialDecision(decision));
        assertFalse(decision.tryAcceptResponse(oldLease));
        assertFalse(decision.tryAcceptError(oldLease));
        assertFalse(decision.busy());
    }

    @Test
    void idleSessionNeedsNoInvalidation() {
        assertFalse(BrainCoordinator.invalidateCredentialDecision(
                new DecisionSession(UUID.randomUUID())));
        assertFalse(BrainCoordinator.invalidateCredentialDecision(null));
    }
}
