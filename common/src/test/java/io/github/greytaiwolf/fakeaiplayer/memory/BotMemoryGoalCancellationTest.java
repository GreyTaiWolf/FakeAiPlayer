package io.github.greytaiwolf.fakeaiplayer.memory;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class BotMemoryGoalCancellationTest {
    @Test
    void clearingLongTermGoalPreventsAutomaticWakeAndIsIdempotent() {
        BotMemory memory = new BotMemory();
        memory.setGoal("build", List.of("gather", "build"));
        assertTrue(memory.hasActiveGoal());

        assertTrue(memory.clearGoal());
        assertFalse(memory.hasActiveGoal());
        assertFalse(memory.clearGoal());
    }

    @Test
    void persistedMemoryRejectsMalformedOrLossyShapes() {
        BotMemoryStore store = BotMemoryStore.INSTANCE;
        UUID botId = UUID.randomUUID();
        String valid = new BotMemory().toNbt().toString();
        try {
            assertTrue(store.persistedPayloadValid(""));
            assertTrue(store.persistedPayloadValid(valid));
            assertTrue(store.loadString(botId, valid));

            assertFalse(store.persistedPayloadValid("not-snbt"));
            assertFalse(store.persistedPayloadValid("{}"));
            assertFalse(store.persistedPayloadValid(
                    "{facts:{},places:{},goalTitle:\"\",goalCursor:0,goalSteps:{}}"));
            assertFalse(store.loadString(botId, "{}"));
        } finally {
            store.remove(botId);
        }
    }
}
