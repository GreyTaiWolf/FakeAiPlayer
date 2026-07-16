package io.github.greytaiwolf.fakeaiplayer.brain.chat;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BotChatRateLimiterTest {
    @Test
    void limitsSameBotAndKindWithoutMixingOtherStreams() {
        BotChatRateLimiter limiter = new BotChatRateLimiter();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();

        assertTrue(limiter.tryAcquire(first, BotChatPolicy.MessageKind.BOT, 100, 20));
        assertFalse(limiter.tryAcquire(first, BotChatPolicy.MessageKind.BOT, 119, 20));
        assertTrue(limiter.tryAcquire(first, BotChatPolicy.MessageKind.SYSTEM, 119, 60));
        assertTrue(limiter.tryAcquire(second, BotChatPolicy.MessageKind.BOT, 119, 20));
        assertTrue(limiter.tryAcquire(first, BotChatPolicy.MessageKind.BOT, 120, 20));
    }

    @Test
    void acceptsAResetServerClockAndClear() {
        BotChatRateLimiter limiter = new BotChatRateLimiter();
        UUID botId = UUID.randomUUID();

        assertTrue(limiter.tryAcquire(botId, BotChatPolicy.MessageKind.BOT, 10_000, 20));
        assertTrue(limiter.tryAcquire(botId, BotChatPolicy.MessageKind.BOT, 4, 20));
        assertFalse(limiter.tryAcquire(botId, BotChatPolicy.MessageKind.BOT, 5, 20));
        limiter.clear(botId);
        assertTrue(limiter.tryAcquire(botId, BotChatPolicy.MessageKind.BOT, 5, 20));
    }
}
