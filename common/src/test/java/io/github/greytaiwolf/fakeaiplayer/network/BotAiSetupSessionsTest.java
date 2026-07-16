package io.github.greytaiwolf.fakeaiplayer.network;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class BotAiSetupSessionsTest {
    @Test
    void nonceIsOneUseAndBoundToPlayerBotAndName() {
        BotAiSetupSessions sessions = new BotAiSetupSessions();
        UUID player = UUID.randomUUID();
        UUID bot = UUID.randomUUID();
        UUID nonce = sessions.create(player, bot, "Helper", 100);

        assertFalse(sessions.consume(UUID.randomUUID(), bot, "Helper", nonce, 101));
        assertTrue(sessions.consume(player, bot, "Helper", nonce, 101));
        assertFalse(sessions.consume(player, bot, "Helper", nonce, 101));

        UUID wrongBotNonce = sessions.create(player, bot, "Helper", 110);
        assertFalse(sessions.consume(player, UUID.randomUUID(), "Helper", wrongBotNonce, 111));
        assertFalse(sessions.consume(player, bot, "Helper", wrongBotNonce, 111));

        UUID wrongNameNonce = sessions.create(player, bot, "Helper", 120);
        assertFalse(sessions.consume(player, bot, "Other", wrongNameNonce, 121));
        assertFalse(sessions.consume(player, bot, "Helper", wrongNameNonce, 121));
    }

    @Test
    void newerSetupReplacesOlderAndExpiredNonceFailsClosed() {
        BotAiSetupSessions sessions = new BotAiSetupSessions();
        UUID player = UUID.randomUUID();
        UUID bot = UUID.randomUUID();
        UUID first = sessions.create(player, bot, "Helper", 10);
        UUID second = sessions.create(player, bot, "Helper", 11);

        assertNotEquals(first, second);
        assertFalse(sessions.consume(player, bot, "Helper", first, 12));
        assertFalse(sessions.consume(player, bot, "Helper", second, 12));

        UUID expired = sessions.create(player, bot, "Helper", 20);
        assertFalse(sessions.consume(player, bot, "Helper", expired,
                20 + BotAiSetupSessions.SESSION_TTL_TICKS + 1));
    }
}
