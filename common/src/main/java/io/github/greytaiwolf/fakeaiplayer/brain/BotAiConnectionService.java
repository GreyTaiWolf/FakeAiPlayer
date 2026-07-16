package io.github.greytaiwolf.fakeaiplayer.brain;

import io.github.greytaiwolf.fakeaiplayer.brain.social.BotSocialCoordinator;
import io.github.greytaiwolf.fakeaiplayer.entity.AIPlayerEntity;
import io.github.greytaiwolf.fakeaiplayer.log.BotLog;
import io.github.greytaiwolf.fakeaiplayer.log.LogCategory;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Performs a bounded, no-tool API probe for a bot-specific credential.
 *
 * <p>The probe deliberately has no access to {@link ActionDispatcher} or the normal tool catalog.
 * A successful first connection may therefore produce a short greeting, but it can never start a
 * task or mutate the world. Callbacks are marshalled back onto the Minecraft server thread and are
 * discarded when the credential generation changes while HTTP is in flight.</p>
 */
public final class BotAiConnectionService {
    public static final BotAiConnectionService INSTANCE = new BotAiConnectionService();

    private static final int PARALLEL_PROBES = 2;
    private static final int QUEUED_BOTS = 16;

    private volatile LatestPerBotExecutor executor = newExecutor();

    private BotAiConnectionService() {
    }

    /** Interrupts session probes and starts with a fresh bounded queue after server shutdown. */
    public synchronized void reset() {
        LatestPerBotExecutor previous = executor;
        executor = newExecutor();
        previous.shutdownNow();
    }

    /** Drops a probe that has not started yet for a removed/disconnected bot binding. */
    public boolean discardPending(UUID botId) {
        return executor.discardPending(Objects.requireNonNull(botId, "botId"));
    }

    /** Verifies a newly supplied key and lets the model optionally greet the owner. */
    public boolean verifyAndGreet(AIPlayerEntity bot,
                                  long credentialGeneration,
                                  Consumer<ProbeResult> callback) {
        Objects.requireNonNull(bot, "bot");
        Objects.requireNonNull(callback, "callback");
        boolean[] socialAttempted = {false};
        boolean socialAccepted = BotSocialCoordinator.INSTANCE.onConnectionSucceeded(
                bot.getUUID(),
                bot.getServer().getTickCount(),
                request -> {
                    socialAttempted[0] = true;
                    return submit(bot, credentialGeneration, request.prompt(), true, true, callback);
                });
        if (socialAttempted[0]) {
            return socialAccepted;
        }
        return submit(bot, credentialGeneration, verificationPrompt(), false, true, callback);
    }

    /** Re-tests an existing binding without emitting another greeting. */
    public boolean verify(AIPlayerEntity bot,
                          long credentialGeneration,
                          Consumer<ProbeResult> callback) {
        return submit(bot, credentialGeneration, verificationPrompt(), false, false, callback);
    }

    private boolean submit(AIPlayerEntity bot,
                           long credentialGeneration,
                           String prompt,
                           boolean publishResponseAsGreeting,
                           boolean stagedCredential,
                           Consumer<ProbeResult> callback) {
        UUID botId = bot.getUUID();
        LatestPerBotExecutor.Submission submission = executor.submit(botId, () -> {
            ProbeResult result;
            try {
                DeepSeekApiClient client = stagedCredential
                        ? BotApiCredentialRegistry.INSTANCE.clientForProbe(botId, credentialGeneration)
                        : BotApiCredentialRegistry.INSTANCE.clientFor(botId);
                ChatResponse response = client.chat(List.of(
                        ChatMessage.system("You are testing the API connection for a Minecraft AI companion. "
                                + "This turn has no tools and must never start or change a task."),
                        ChatMessage.user(prompt)), List.of());
                String greeting = publishResponseAsGreeting && response.content() != null
                        ? response.content()
                        : "";
                result = new ProbeResult(true, "connected", greeting, credentialGeneration);
            } catch (Exception exception) {
                String code = safeStatusCode(exception);
                BotLog.warn(LogCategory.API, bot, "bot_api_probe_failed", "reason", code);
                result = new ProbeResult(false, code, "", credentialGeneration);
            }

            ProbeResult completed = result;
            bot.getServer().execute(() -> {
                BotApiCredentialRegistry.CredentialState state =
                        BotApiCredentialRegistry.INSTANCE.status(botId);
                if (state.generation() != credentialGeneration || bot.isRemoved()) {
                    BotLog.commSystem("stale_bot_api_probe_dropped",
                            "bot_uuid", botId,
                            "probe_generation", credentialGeneration,
                            "current_generation", state.generation());
                    return;
                }
                if (!completed.connected()) {
                    BotSocialCoordinator.INSTANCE.clear(botId);
                }
                callback.accept(completed);
            });
        });
        if (submission == LatestPerBotExecutor.Submission.REJECTED) {
            bot.getServer().execute(() -> {
                BotApiCredentialRegistry.CredentialState state =
                        BotApiCredentialRegistry.INSTANCE.status(botId);
                if (state.generation() == credentialGeneration && !bot.isRemoved()) {
                    callback.accept(new ProbeResult(false, "busy", "", credentialGeneration));
                }
            });
            return false;
        }
        return true;
    }

    static String safeStatusCode(Throwable throwable) {
        String message = throwable == null || throwable.getMessage() == null
                ? "unknown_error"
                : throwable.getMessage().toLowerCase(java.util.Locale.ROOT);
        if (message.startsWith("auth_error") || message.contains("api_key_missing")) {
            return "auth_error";
        }
        if (message.startsWith("rate_limited")) {
            return "rate_limited";
        }
        if (message.startsWith("api_timeout")) {
            return "timeout";
        }
        if (message.startsWith("server_error")) {
            return "provider_unavailable";
        }
        if (message.startsWith("bad_response") || message.startsWith("empty_response")) {
            return "bad_response";
        }
        if (message.startsWith("decision_executor_busy")) {
            return "busy";
        }
        if (message.startsWith("io_error")) {
            return "network_error";
        }
        return "connection_failed";
    }

    private static String verificationPrompt() {
        return "Reply with exactly OK in plain text to confirm this connection. Do not call tools.";
    }

    private static LatestPerBotExecutor newExecutor() {
        return new LatestPerBotExecutor(PARALLEL_PROBES, QUEUED_BOTS);
    }

    public record ProbeResult(boolean connected,
                              String statusCode,
                              String greeting,
                              long credentialGeneration) {
        public ProbeResult {
            Objects.requireNonNull(statusCode, "statusCode");
            greeting = greeting == null ? "" : greeting;
        }
    }
}
