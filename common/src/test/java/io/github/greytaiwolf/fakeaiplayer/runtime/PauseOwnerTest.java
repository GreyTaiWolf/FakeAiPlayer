package io.github.greytaiwolf.fakeaiplayer.runtime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class PauseOwnerTest {
    @Test
    void onlyTemporaryCoordinatorsMayResumeThemselvesAutomatically() {
        assertTrue(PauseOwner.SAFETY.automaticResumeAllowed());
        assertTrue(PauseOwner.SYSTEM.automaticResumeAllowed());
        assertFalse(PauseOwner.USER.automaticResumeAllowed());
        assertFalse(PauseOwner.INVENTORY.automaticResumeAllowed());
    }

    @Test
    void userAndInventoryAreIndependentPersistentLocks() {
        assertTrue(PauseOwner.USER.persistentLock());
        assertTrue(PauseOwner.INVENTORY.persistentLock());
        assertFalse(PauseOwner.SAFETY.persistentLock());
        assertFalse(PauseOwner.SYSTEM.persistentLock());
    }
}
