package io.github.greytaiwolf.fakeaiplayer.brain;

import io.github.greytaiwolf.fakeaiplayer.AIBotConfig;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class BotApiCredentialRegistryTest {
    private static final String SERVER_KEY = "server-secret-must-not-leak";
    private static final String BOT_KEY = "bot-secret-must-not-leak";

    @Test
    void routesBotKeyThenFallsBackWithoutExposingSecrets() {
        BotApiCredentialRegistry registry = new BotApiCredentialRegistry();
        registry.configure(config(SERVER_KEY));
        UUID bot = UUID.randomUUID();

        BotApiCredentialRegistry.CredentialState initial = registry.status(bot);
        assertEquals(BotApiCredentialRegistry.CredentialStatus.SERVER_FALLBACK, initial.status());
        assertEquals(0L, initial.generation());
        DeepSeekApiClient fallbackClient = registry.clientFor(bot);

        BotApiCredentialRegistry.CredentialUpdate bound = registry.bind(bot, BOT_KEY);
        assertTrue(bound.changed());
        assertEquals(1L, bound.generation());
        assertEquals(BotApiCredentialRegistry.CredentialStatus.BOT_KEY, bound.status());
        DeepSeekApiClient botClient = registry.clientFor(bot);
        assertNotSame(fallbackClient, botClient);

        BotApiCredentialRegistry.CredentialUpdate unchanged = registry.bind(bot, "  " + BOT_KEY + "  ");
        assertFalse(unchanged.changed());
        assertEquals(1L, unchanged.generation());
        assertSame(botClient, registry.clientFor(bot));

        BotApiCredentialRegistry.CredentialUpdate revoked = registry.revoke(bot);
        assertTrue(revoked.changed());
        assertEquals(2L, revoked.generation());
        assertEquals(BotApiCredentialRegistry.CredentialStatus.SERVER_FALLBACK, revoked.status());
        assertNotSame(botClient, registry.clientFor(bot));
        assertEquals(2L, registry.status(bot).generation());

        String safeOutput = initial + " " + bound + " " + revoked;
        assertFalse(safeOutput.contains(SERVER_KEY));
        assertFalse(safeOutput.contains(BOT_KEY));
    }

    @Test
    void generationAdvancesAcrossReplacementAndRevocation() {
        BotApiCredentialRegistry registry = new BotApiCredentialRegistry();
        registry.configure(config(""));
        UUID bot = UUID.randomUUID();

        assertEquals(BotApiCredentialRegistry.CredentialStatus.MISSING, registry.status(bot).status());
        assertFalse(registry.status(bot).usable());
        assertEquals(1L, registry.bind(bot, "first").generation());
        assertEquals(2L, registry.bind(bot, "second").generation());
        assertEquals(3L, registry.revoke(bot).generation());
        assertEquals(BotApiCredentialRegistry.CredentialStatus.MISSING, registry.status(bot).status());
        assertEquals(4L, registry.bind(bot, "third").generation());
    }

    @Test
    void stagedKeyIsInvisibleToBrainUntilMatchingCommit() {
        BotApiCredentialRegistry registry = new BotApiCredentialRegistry();
        registry.configure(config(SERVER_KEY));
        UUID bot = UUID.randomUUID();
        DeepSeekApiClient fallbackClient = registry.clientFor(bot);

        BotApiCredentialRegistry.CredentialUpdate staged = registry.stage(bot, BOT_KEY);
        assertEquals(1L, staged.generation());
        assertEquals(BotApiCredentialRegistry.CredentialStatus.SERVER_FALLBACK,
                registry.status(bot).status());
        assertSame(fallbackClient, registry.clientFor(bot));
        assertNotSame(fallbackClient, registry.clientForProbe(bot, staged.generation()));
        assertThrows(IllegalStateException.class,
                () -> registry.clientForProbe(bot, staged.generation() + 1));

        BotApiCredentialRegistry.CredentialUpdate committed =
                registry.commit(bot, staged.generation());
        assertTrue(committed.changed());
        assertEquals(BotApiCredentialRegistry.CredentialStatus.BOT_KEY,
                registry.status(bot).status());
        assertNotSame(fallbackClient, registry.clientFor(bot));
        assertThrows(IllegalStateException.class,
                () -> registry.clientForProbe(bot, staged.generation()));
    }

    @Test
    void failedRotationPreservesPreviouslyActiveKey() {
        BotApiCredentialRegistry registry = new BotApiCredentialRegistry();
        registry.configure(config(""));
        UUID bot = UUID.randomUUID();
        registry.bind(bot, "known-good-key");
        DeepSeekApiClient activeClient = registry.clientFor(bot);

        BotApiCredentialRegistry.CredentialUpdate staged =
                registry.stage(bot, "unverified-rotation-key");
        assertSame(activeClient, registry.clientFor(bot));

        BotApiCredentialRegistry.CredentialUpdate rejected =
                registry.reject(bot, staged.generation());
        assertTrue(rejected.changed());
        assertEquals(BotApiCredentialRegistry.CredentialStatus.BOT_KEY, rejected.status());
        assertSame(activeClient, registry.clientFor(bot));
        assertEquals(staged.generation(), registry.status(bot).generation());

        assertFalse(registry.commit(bot, staged.generation()).changed());
        assertSame(activeClient, registry.clientFor(bot));
    }

    @Test
    void newerStageInvalidatesOlderProbeGeneration() {
        BotApiCredentialRegistry registry = new BotApiCredentialRegistry();
        registry.configure(config(""));
        UUID bot = UUID.randomUUID();

        long first = registry.stage(bot, "first-pending-key").generation();
        long second = registry.stage(bot, "second-pending-key").generation();
        assertEquals(first + 1L, second);
        assertThrows(IllegalStateException.class, () -> registry.clientForProbe(bot, first));
        registry.clientForProbe(bot, second);
        assertFalse(registry.reject(bot, first).changed());
        assertTrue(registry.commit(bot, second).changed());
    }

    @Test
    void rejectsMissingOrOversizedKeysAndClearsSessionState() {
        BotApiCredentialRegistry registry = new BotApiCredentialRegistry();
        registry.configure(config(""));
        UUID bot = UUID.randomUUID();

        assertThrows(IllegalArgumentException.class, () -> registry.bind(bot, "  "));
        assertThrows(IllegalArgumentException.class, () -> registry.bind(bot, "x".repeat(513)));

        registry.bind(bot, BOT_KEY);
        registry.clearSession();
        assertEquals(BotApiCredentialRegistry.CredentialStatus.MISSING, registry.status(bot).status());
        assertEquals(0L, registry.status(bot).generation());
    }

    private static AIBotConfig.DeepSeek config(String key) {
        return new AIBotConfig.DeepSeek(
                key,
                "https://api.deepseek.com",
                "deepseek-chat",
                512,
                0.2D,
                30,
                0,
                10);
    }
}
