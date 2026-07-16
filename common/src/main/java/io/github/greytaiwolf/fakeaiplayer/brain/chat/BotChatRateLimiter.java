package io.github.greytaiwolf.fakeaiplayer.brain.chat;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Per-bot, per-message-kind rate limit for owner chat delivery. */
public final class BotChatRateLimiter {
    private final Map<Key, Long> lastDeliveryTick = new ConcurrentHashMap<>();

    public boolean tryAcquire(UUID botId,
                              BotChatPolicy.MessageKind kind,
                              long currentTick,
                              int cooldownTicks) {
        Objects.requireNonNull(botId, "botId");
        Objects.requireNonNull(kind, "kind");
        if (cooldownTicks <= 0) {
            return true;
        }
        Key key = new Key(botId, kind);
        boolean[] acquired = {false};
        lastDeliveryTick.compute(key, (ignored, previous) -> {
            if (previous == null
                    || currentTick < previous
                    || currentTick - previous >= cooldownTicks) {
                acquired[0] = true;
                return currentTick;
            }
            return previous;
        });
        return acquired[0];
    }

    public void clear(UUID botId) {
        if (botId != null) {
            lastDeliveryTick.keySet().removeIf(key -> key.botId().equals(botId));
        }
    }

    public void clearAll() {
        lastDeliveryTick.clear();
    }

    private record Key(UUID botId, BotChatPolicy.MessageKind kind) {
    }
}
