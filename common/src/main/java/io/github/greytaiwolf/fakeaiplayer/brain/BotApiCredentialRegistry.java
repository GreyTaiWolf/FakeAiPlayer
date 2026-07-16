package io.github.greytaiwolf.fakeaiplayer.brain;

import io.github.greytaiwolf.fakeaiplayer.AIBotConfig;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Session-only server registry for per-bot API credentials.
 *
 * <p>The registry intentionally exposes only redacted state. Raw credentials can only flow into
 * a {@link DeepSeekApiClient}; they are never returned to commands, payload handlers, logs, or
 * status objects. A bot without an explicit credential uses the server-configured key when one is
 * available.</p>
 */
public final class BotApiCredentialRegistry {
    public static final BotApiCredentialRegistry INSTANCE = new BotApiCredentialRegistry();
    private static final int MAX_API_KEY_LENGTH = 512;

    private final Map<UUID, BotCredential> credentials = new HashMap<>();
    private final Map<UUID, CachedClient> clients = new HashMap<>();
    private AIBotConfig.DeepSeek serverConfig = AIBotConfig.defaults().deepseek();
    private long serverConfigGeneration;

    BotApiCredentialRegistry() {
    }

    /** Updates endpoint/model defaults and the optional global fallback without persisting them. */
    public synchronized void configure(AIBotConfig.DeepSeek config) {
        serverConfig = Objects.requireNonNull(config, "config");
        serverConfigGeneration = nextGeneration(serverConfigGeneration);
        clients.clear();
    }

    /**
     * Compatibility path that activates a key immediately. New network flows must use
     * {@link #stage(UUID, String)}, probe it, and then call {@link #commit(UUID, long)}.
     */
    public synchronized CredentialUpdate bind(UUID botId, String apiKey) {
        Objects.requireNonNull(botId, "botId");
        String normalized = normalize(apiKey);
        BotCredential current = credentials.get(botId);
        if (current != null
                && normalized.equals(current.activeApiKey)
                && current.pendingApiKey == null) {
            return new CredentialUpdate(false, current.generation, CredentialStatus.BOT_KEY);
        }

        long generation = nextGeneration(current == null ? 0L : current.generation);
        credentials.put(botId, new BotCredential(
                normalized,
                generation,
                null,
                0L,
                generation));
        clients.remove(botId);
        return new CredentialUpdate(true, generation, CredentialStatus.BOT_KEY);
    }

    /**
     * Stores an unverified key in a pending slot without changing the credential used by the
     * normal brain. A newer stage always supersedes any earlier in-flight probe generation.
     */
    public synchronized CredentialUpdate stage(UUID botId, String apiKey) {
        Objects.requireNonNull(botId, "botId");
        String normalized = normalize(apiKey);
        BotCredential current = credentials.get(botId);
        long generation = nextGeneration(current == null ? 0L : current.generation);
        credentials.put(botId, new BotCredential(
                current == null ? null : current.activeApiKey,
                current == null ? 0L : current.activeGeneration,
                normalized,
                generation,
                generation));
        return new CredentialUpdate(true, generation, activeStatus(current));
    }

    /** Returns a client for exactly one still-current pending generation. */
    public synchronized DeepSeekApiClient clientForProbe(UUID botId, long generation) {
        Objects.requireNonNull(botId, "botId");
        BotCredential credential = credentials.get(botId);
        if (credential == null
                || credential.pendingApiKey == null
                || credential.pendingGeneration != generation) {
            throw new IllegalStateException("credential_probe_stale");
        }
        return new DeepSeekApiClient(serverConfig, credential.pendingApiKey);
    }

    /** Activates a successfully probed generation. Stale commits fail closed. */
    public synchronized CredentialUpdate commit(UUID botId, long generation) {
        Objects.requireNonNull(botId, "botId");
        BotCredential current = credentials.get(botId);
        if (current == null
                || current.pendingApiKey == null
                || current.pendingGeneration != generation) {
            return unchanged(current);
        }
        credentials.put(botId, new BotCredential(
                current.pendingApiKey,
                generation,
                null,
                0L,
                current.generation));
        clients.remove(botId);
        return new CredentialUpdate(true, generation, CredentialStatus.BOT_KEY);
    }

    /** Discards a failed pending generation while preserving the previous active key/fallback. */
    public synchronized CredentialUpdate reject(UUID botId, long generation) {
        Objects.requireNonNull(botId, "botId");
        BotCredential current = credentials.get(botId);
        if (current == null
                || current.pendingApiKey == null
                || current.pendingGeneration != generation) {
            return unchanged(current);
        }
        BotCredential rejected = new BotCredential(
                current.activeApiKey,
                current.activeGeneration,
                null,
                0L,
                current.generation);
        credentials.put(botId, rejected);
        return new CredentialUpdate(true, generation, activeStatus(rejected));
    }

    /** Removes a bot-specific key. The bot falls back to the server key when configured. */
    public synchronized CredentialUpdate revoke(UUID botId) {
        Objects.requireNonNull(botId, "botId");
        BotCredential current = credentials.get(botId);
        CredentialStatus fallbackStatus = fallbackStatus();
        if (current == null
                || (current.activeApiKey == null && current.pendingApiKey == null)) {
            long generation = current == null ? 0L : current.generation;
            return new CredentialUpdate(false, generation, fallbackStatus);
        }

        long generation = nextGeneration(current.generation);
        // Keep a tombstone so a revoke followed by a bind cannot reuse an earlier generation.
        credentials.put(botId, new BotCredential(null, 0L, null, 0L, generation));
        clients.remove(botId);
        return new CredentialUpdate(true, generation, fallbackStatus);
    }

    /** Returns redacted credential state suitable for command/UI status output. */
    public synchronized CredentialState status(UUID botId) {
        Objects.requireNonNull(botId, "botId");
        BotCredential credential = credentials.get(botId);
        if (credential != null && credential.activeApiKey != null) {
            return new CredentialState(CredentialStatus.BOT_KEY, credential.generation);
        }
        return new CredentialState(fallbackStatus(), credential == null ? 0L : credential.generation);
    }

    /** Resolves a client without exposing the selected raw key to the caller. */
    public synchronized DeepSeekApiClient clientFor(UUID botId) {
        Objects.requireNonNull(botId, "botId");
        BotCredential credential = credentials.get(botId);
        boolean usesBotKey = credential != null && credential.activeApiKey != null;
        long credentialGeneration = usesBotKey ? credential.activeGeneration : 0L;
        CredentialStatus credentialStatus = usesBotKey ? CredentialStatus.BOT_KEY : fallbackStatus();

        CachedClient cached = clients.get(botId);
        if (cached != null
                && cached.credentialGeneration == credentialGeneration
                && cached.serverConfigGeneration == serverConfigGeneration
                && cached.status == credentialStatus) {
            return cached.client;
        }

        String selectedKey = usesBotKey ? credential.activeApiKey : serverConfig.apiKey();
        DeepSeekApiClient client = new DeepSeekApiClient(serverConfig, selectedKey);
        clients.put(botId, new CachedClient(
                credentialGeneration,
                serverConfigGeneration,
                credentialStatus,
                client));
        return client;
    }

    /** Clears every player-supplied secret when the server session ends. */
    public synchronized void clearSession() {
        credentials.clear();
        clients.clear();
    }

    private CredentialStatus fallbackStatus() {
        String key = serverConfig.apiKey();
        return key == null || key.isBlank()
                ? CredentialStatus.MISSING
                : CredentialStatus.SERVER_FALLBACK;
    }

    private CredentialStatus activeStatus(BotCredential credential) {
        return credential != null && credential.activeApiKey != null
                ? CredentialStatus.BOT_KEY
                : fallbackStatus();
    }

    private CredentialUpdate unchanged(BotCredential credential) {
        return new CredentialUpdate(
                false,
                credential == null ? 0L : credential.generation,
                activeStatus(credential));
    }

    private static String normalize(String apiKey) {
        if (apiKey == null) {
            throw new IllegalArgumentException("api_key_missing");
        }
        String normalized = apiKey.strip();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("api_key_missing");
        }
        if (normalized.length() > MAX_API_KEY_LENGTH) {
            throw new IllegalArgumentException("api_key_too_long");
        }
        for (int index = 0; index < normalized.length(); index++) {
            char character = normalized.charAt(index);
            if (character < 0x21 || character > 0x7e) {
                throw new IllegalArgumentException("api_key_invalid_character");
            }
        }
        return normalized;
    }

    private static long nextGeneration(long current) {
        if (current == Long.MAX_VALUE) {
            throw new IllegalStateException("credential_generation_exhausted");
        }
        return current + 1L;
    }

    private static final class BotCredential {
        private final String activeApiKey;
        private final long activeGeneration;
        private final String pendingApiKey;
        private final long pendingGeneration;
        private final long generation;

        private BotCredential(String activeApiKey,
                              long activeGeneration,
                              String pendingApiKey,
                              long pendingGeneration,
                              long generation) {
            this.activeApiKey = activeApiKey;
            this.activeGeneration = activeGeneration;
            this.pendingApiKey = pendingApiKey;
            this.pendingGeneration = pendingGeneration;
            this.generation = generation;
        }
    }

    private static final class CachedClient {
        private final long credentialGeneration;
        private final long serverConfigGeneration;
        private final CredentialStatus status;
        private final DeepSeekApiClient client;

        private CachedClient(long credentialGeneration,
                             long serverConfigGeneration,
                             CredentialStatus status,
                             DeepSeekApiClient client) {
            this.credentialGeneration = credentialGeneration;
            this.serverConfigGeneration = serverConfigGeneration;
            this.status = status;
            this.client = client;
        }
    }

    public enum CredentialStatus {
        BOT_KEY,
        SERVER_FALLBACK,
        MISSING
    }

    public record CredentialState(CredentialStatus status, long generation) {
        public CredentialState {
            Objects.requireNonNull(status, "status");
        }

        public boolean usable() {
            return status != CredentialStatus.MISSING;
        }
    }

    public record CredentialUpdate(boolean changed, long generation, CredentialStatus status) {
        public CredentialUpdate {
            Objects.requireNonNull(status, "status");
        }
    }
}
