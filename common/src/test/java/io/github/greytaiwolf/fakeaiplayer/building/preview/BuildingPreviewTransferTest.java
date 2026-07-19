package io.github.greytaiwolf.fakeaiplayer.building.preview;

import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BuildingPreviewTransferTest {
    @Test
    void advancesExactlyOnceThroughEveryChunk() {
        UUID session = UUID.randomUUID();
        BuildingPreviewTransfer transfer = new BuildingPreviewTransfer(session, "hash", 3, 4);

        assertTrue(transfer.matches(session, "hash", 3));
        assertFalse(transfer.matches(session, "other", 3));
        assertEquals(4, transfer.remainingChunks());
        assertEquals(0, transfer.nextChunk());
        assertEquals(1, transfer.nextChunk());
        assertEquals(2, transfer.remainingChunks());
        assertEquals(2, transfer.nextChunk());
        assertEquals(3, transfer.nextChunk());
        assertTrue(transfer.complete());
        assertThrows(IllegalStateException.class, transfer::nextChunk);
    }

    @Test
    void rejectsEmptyOrInvalidTransferIdentity() {
        UUID session = UUID.randomUUID();
        assertThrows(IllegalArgumentException.class,
                () -> new BuildingPreviewTransfer(session, "", 0, 1));
        assertThrows(IllegalArgumentException.class,
                () -> new BuildingPreviewTransfer(session, "hash", -1, 1));
        assertThrows(IllegalArgumentException.class,
                () -> new BuildingPreviewTransfer(session, "hash", 0, 0));
    }
}
