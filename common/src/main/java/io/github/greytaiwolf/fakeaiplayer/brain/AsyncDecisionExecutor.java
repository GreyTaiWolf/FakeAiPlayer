package io.github.greytaiwolf.fakeaiplayer.brain;

import io.github.greytaiwolf.fakeaiplayer.entity.AIPlayerEntity;
import io.github.greytaiwolf.fakeaiplayer.observe.BotProfiler;

import java.util.List;
import java.util.UUID;
import java.util.function.BiConsumer;

public final class AsyncDecisionExecutor {
    private static final int PARALLEL_REQUESTS = 4;
    private static final int QUEUED_BOTS = 32;
    private final BotApiCredentialRegistry credentials;
    private final LatestPerBotExecutor executor;

    public AsyncDecisionExecutor(BotApiCredentialRegistry credentials) {
        this(credentials, new LatestPerBotExecutor(PARALLEL_REQUESTS, QUEUED_BOTS));
    }

    AsyncDecisionExecutor(BotApiCredentialRegistry credentials, LatestPerBotExecutor executor) {
        this.credentials = credentials;
        this.executor = executor;
    }

    public void submit(AIPlayerEntity bot,
                       DecisionLease lease,
                       List<ChatMessage> historySnapshot,
                       List<ToolDefinition> tools,
                       BiConsumer<DecisionLease, ChatResponse> onResponse,
                       BiConsumer<DecisionLease, Throwable> onError) {
        var server = bot.getServer();
        var botId = bot.getUUID();
        String botName = bot.getGameProfile().getName();
        DeepSeekApiClient apiClient = credentials.clientFor(botId);
        LatestPerBotExecutor.Submission submission = executor.submit(botId, () -> {
            long started = System.nanoTime();
            try {
                ChatResponse response = apiClient.chat(historySnapshot, tools);
                long elapsed = System.nanoTime() - started;
                server.execute(() -> onResponse.accept(lease, response));
                BotProfiler.INSTANCE.record(botId, botName, "brain_latency", elapsed);
            } catch (Exception exception) {
                long elapsed = System.nanoTime() - started;
                BotProfiler.INSTANCE.record(botId, botName, "brain_latency_error", elapsed);
                server.execute(() -> onError.accept(lease, exception));
            }
        });
        if (submission == LatestPerBotExecutor.Submission.REJECTED) {
            server.execute(() -> onError.accept(
                    lease,
                    new DeepSeekApiException("decision_executor_busy")));
        }
    }

    public void shutdown() {
        executor.shutdownNow();
    }

    /** Drops a not-yet-started request after its bot credential changes. */
    public boolean discardPending(UUID botId) {
        return executor.discardPending(botId);
    }
}
