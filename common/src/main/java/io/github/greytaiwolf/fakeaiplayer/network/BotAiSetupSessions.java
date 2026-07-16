package io.github.greytaiwolf.fakeaiplayer.network;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Short-lived, one-use authorization nonce issued before opening the client credential screen. */
public final class BotAiSetupSessions {
    public static final BotAiSetupSessions INSTANCE = new BotAiSetupSessions();
    static final long SESSION_TTL_TICKS = 2_400L;

    private final Map<UUID, SetupSession> sessions = new HashMap<>();

    BotAiSetupSessions() {
    }

    public synchronized UUID create(UUID playerId, UUID botId, String botName, long currentTick) {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(botId, "botId");
        Objects.requireNonNull(botName, "botName");
        UUID nonce = UUID.randomUUID();
        sessions.put(playerId, new SetupSession(botId, botName, nonce, currentTick + SESSION_TTL_TICKS));
        return nonce;
    }

    public synchronized boolean consume(UUID playerId,
                                        UUID botId,
                                        String botName,
                                        UUID nonce,
                                        long currentTick) {
        Objects.requireNonNull(playerId, "playerId");
        SetupSession session = sessions.remove(playerId);
        if (session == null || currentTick > session.expiresAtTick()) {
            return false;
        }
        return session.botId().equals(botId)
                && session.botName().equals(botName)
                && session.nonce().equals(nonce);
    }

    public synchronized void clear(UUID playerId) {
        if (playerId != null) {
            sessions.remove(playerId);
        }
    }

    public synchronized void clearAll() {
        sessions.clear();
    }

    record SetupSession(UUID botId, String botName, UUID nonce, long expiresAtTick) {
    }
}
