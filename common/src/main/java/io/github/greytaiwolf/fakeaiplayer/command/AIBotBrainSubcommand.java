package io.github.greytaiwolf.fakeaiplayer.command;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import io.github.greytaiwolf.fakeaiplayer.auth.BotAuthorizationGate;
import io.github.greytaiwolf.fakeaiplayer.auth.BotAuthorizationPolicy;
import io.github.greytaiwolf.fakeaiplayer.brain.BrainValidation;
import io.github.greytaiwolf.fakeaiplayer.brain.BrainCoordinator;
import io.github.greytaiwolf.fakeaiplayer.runtime.IntentController;
import io.github.greytaiwolf.fakeaiplayer.runtime.RuntimeLifecycleCoordinator;
import io.github.greytaiwolf.fakeaiplayer.entity.AIPlayerEntity;
import io.github.greytaiwolf.fakeaiplayer.log.BotLog;
import io.github.greytaiwolf.fakeaiplayer.log.LogCategory;
import io.github.greytaiwolf.fakeaiplayer.manager.AIPlayerManager;
import io.github.greytaiwolf.fakeaiplayer.task.TaskManager;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.arguments.MessageArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;

public final class AIBotBrainSubcommand {
    private AIBotBrainSubcommand() {
    }

    public static LiteralArgumentBuilder<CommandSourceStack> build() {
        return literal("brain")
                .then(literal("status")
                        .then(botName()
                                .executes(context -> status(context.getSource(), StringArgumentType.getString(context, "name")))))
                .then(literal("reset")
                        .then(botName()
                                .executes(context -> reset(context.getSource(), StringArgumentType.getString(context, "name")))))
                .then(literal("manual")
                        .then(botName()
                                .then(literal("on")
                                        .executes(context -> manual(context.getSource(), StringArgumentType.getString(context, "name"), true)))
                                .then(literal("off")
                                        .executes(context -> manual(context.getSource(), StringArgumentType.getString(context, "name"), false)))))
                .then(literal("say")
                        .then(botName()
                                .then(argument("text", MessageArgument.message())
                                        .executes(context -> say(
                                                context.getSource(),
                                                StringArgumentType.getString(context, "name"),
                                                MessageArgument.getMessage(context, "text").getString())))))
                .then(literal("validate")
                        .then(botName()
                                .then(literal("api-failure")
                                        .executes(context -> validateApiFailure(context.getSource(), StringArgumentType.getString(context, "name"))))
                                .then(literal("bad-tool-args")
                                        .executes(context -> validateBadToolArgs(context.getSource(), StringArgumentType.getString(context, "name"))))
                                .then(literal("bad-response")
                                        .executes(context -> validateBadResponse(context.getSource(), StringArgumentType.getString(context, "name"))))
                                .then(literal("tps")
                                        .then(argument("seconds", IntegerArgumentType.integer(3, 60))
                                                .then(argument("text", MessageArgument.message())
                                                        .executes(context -> validateTps(
                                                                context.getSource(),
                                                                StringArgumentType.getString(context, "name"),
                                                                IntegerArgumentType.getInteger(context, "seconds"),
                                                                MessageArgument.getMessage(context, "text").getString()))))))
                        .then(literal("api-failure")
                                .then(botName()
                                        .executes(context -> validateApiFailure(context.getSource(), StringArgumentType.getString(context, "name")))))
                        .then(literal("bad-tool-args")
                                .then(botName()
                                        .executes(context -> validateBadToolArgs(context.getSource(), StringArgumentType.getString(context, "name")))))
                        .then(literal("bad-response")
                                .then(botName()
                                        .executes(context -> validateBadResponse(context.getSource(), StringArgumentType.getString(context, "name")))))
                        .then(literal("tps")
                                .then(botName()
                                        .then(argument("seconds", IntegerArgumentType.integer(3, 60))
                                                .then(argument("text", MessageArgument.message())
                                                        .executes(context -> validateTps(
                                                                context.getSource(),
                                                                StringArgumentType.getString(context, "name"),
                                                                IntegerArgumentType.getInteger(context, "seconds"),
                                                                MessageArgument.getMessage(context, "text").getString())))))));
    }

    private static com.mojang.brigadier.builder.RequiredArgumentBuilder<CommandSourceStack, String> botName() {
        return argument("name", StringArgumentType.word());
    }

    private static int status(CommandSourceStack source, String name) {
        Optional<AIPlayerEntity> bot = getBot(source, name, BotAuthorizationPolicy.Operation.VIEW, "command:brain_status");
        if (bot.isEmpty()) {
            return 0;
        }
        BrainCoordinator.BrainStatus status = BrainCoordinator.INSTANCE.status(bot.get());
        source.sendSuccess(() -> Component.literal("[FakeAiPlayer] brain status " + name
                + ": busy=" + status.busy()
                + ", history=" + status.historySize()
                + ", prompt_tokens=" + status.promptTokens()
                + ", completion_tokens=" + status.completionTokens()
                + ", cache_hit_tokens=" + status.cacheHitTokens()), false);
        return 1;
    }

    private static int reset(CommandSourceStack source, String name) {
        Optional<AIPlayerEntity> bot = getBot(source, name, BotAuthorizationPolicy.Operation.COMMAND, "command:brain_reset");
        if (bot.isEmpty()) {
            return 0;
        }
        if (rejectRecoveryControl(source, bot.get(), "重置大脑")) {
            return 0;
        }
        RuntimeLifecycleCoordinator.INSTANCE.resetBot(
                bot.get(), IntentController.ControlOrigin.PLAYER_COMMAND, "command_brain_reset");
        source.sendSuccess(() -> Component.literal("[FakeAiPlayer] brain reset " + name), false);
        return 1;
    }

    private static int manual(CommandSourceStack source, String name, boolean enabled) {
        Optional<AIPlayerEntity> bot = getBot(source, name, BotAuthorizationPolicy.Operation.ADMIN, "command:brain_manual");
        if (bot.isEmpty()) {
            return 0;
        }
        if (rejectRecoveryControl(source, bot.get(), "修改手动模式")) {
            return 0;
        }
        BrainCoordinator.INSTANCE.setManualMode(bot.get(), enabled);
        source.sendSuccess(() -> Component.literal("[FakeAiPlayer] manual low-level tools " + (enabled ? "on" : "off") + " for " + name), false);
        return 1;
    }

    private static int say(CommandSourceStack source, String name, String text) {
        Optional<AIPlayerEntity> bot = getBot(source, name, BotAuthorizationPolicy.Operation.COMMAND, "command:brain_say");
        if (bot.isEmpty()) {
            return 0;
        }
        if (rejectRecoveryControl(source, bot.get(), "提交大脑请求")) {
            return 0;
        }
        if (IntentController.INSTANCE.routePlayerControlPhrase(
                bot.get(), IntentController.ControlOrigin.PLAYER_COMMAND, text)) {
            return 1;
        }
        boolean queued = BrainCoordinator.INSTANCE.handleMessage(bot.get(), source.getTextName(), text);
        if (queued) {
            source.sendSuccess(() -> Component.literal("[FakeAiPlayer] brain request queued for " + name), false);
            return 1;
        }
        return 0;
    }

    private static boolean rejectRecoveryControl(CommandSourceStack source,
                                                 AIPlayerEntity bot,
                                                 String operation) {
        if (!TaskManager.INSTANCE.hasRuntimeRecoveryLock(bot)) {
            return false;
        }
        source.sendFailure(Component.literal(
                "[FakeAiPlayer] 运行时存档处于只读恢复保护中，无法" + operation + "；修复 runtime.json 后请重启服务器"));
        return true;
    }

    private static int validateApiFailure(CommandSourceStack source, String name) {
        return reportValidation(source, getBot(source, name, BotAuthorizationPolicy.Operation.ADMIN, "command:brain_validate"), BrainValidation::apiFailure);
    }

    private static int validateBadToolArgs(CommandSourceStack source, String name) {
        return reportValidation(source, getBot(source, name, BotAuthorizationPolicy.Operation.ADMIN, "command:brain_validate"), BrainValidation::badToolArgs);
    }

    private static int validateBadResponse(CommandSourceStack source, String name) {
        return reportValidation(source, getBot(source, name, BotAuthorizationPolicy.Operation.ADMIN, "command:brain_validate"), BrainValidation::badResponse);
    }

    private static int validateTps(CommandSourceStack source, String name, int seconds, String text) {
        Optional<AIPlayerEntity> bot = getBot(source, name, BotAuthorizationPolicy.Operation.ADMIN, "command:brain_validate_tps");
        if (bot.isEmpty()) {
            return 0;
        }

        MinecraftServer server = source.getServer();
        long startTicks = server.getTickCount();
        long startNanos = System.nanoTime();
        boolean queued = BrainCoordinator.INSTANCE.handleMessage(bot.get(), source.getTextName(), text);
        CompletableFuture.delayedExecutor(seconds, TimeUnit.SECONDS).execute(() ->
                server.execute(() -> {
                    long elapsedTicks = server.getTickCount() - startTicks;
                    double elapsedSeconds = Math.max(0.001D, (System.nanoTime() - startNanos) / 1_000_000_000.0D);
                    double tps = elapsedTicks / elapsedSeconds;
                    boolean ok = tps >= 19.0D;
                    BotLog.raw(LogCategory.API, ok ? org.slf4j.event.Level.INFO : org.slf4j.event.Level.WARN, bot.get(),
                            "tps_validation",
                            null,
                            "queued", queued,
                            "seconds", seconds,
                            "ticks", elapsedTicks,
                            "tps", String.format(java.util.Locale.ROOT, "%.2f", tps));
                    source.sendSuccess(() -> Component.literal("[FakeAiPlayer] TPS validation "
                            + (ok ? "ok" : "failed")
                            + ": queued=" + queued
                            + ", ticks=" + elapsedTicks
                            + ", tps=" + String.format(java.util.Locale.ROOT, "%.2f", tps)), false);
                }));
        source.sendSuccess(() -> Component.literal("[FakeAiPlayer] TPS validation started for " + name + " over " + seconds + "s"), false);
        return queued ? 1 : 0;
    }

    private static int reportValidation(CommandSourceStack source,
                                        Optional<AIPlayerEntity> bot,
                                        java.util.function.Function<AIPlayerEntity, BrainValidation.ValidationResult> validator) {
        if (bot.isEmpty()) {
            return 0;
        }
        BrainValidation.ValidationResult result = validator.apply(bot.get());
        if (result.ok()) {
            source.sendSuccess(() -> Component.literal("[FakeAiPlayer] validation ok: " + result.scenario() + " -> " + result.message()), false);
            return 1;
        }
        source.sendFailure(Component.literal("[FakeAiPlayer] validation failed: " + result.scenario() + " -> " + result.message()));
        return 0;
    }

    private static Optional<AIPlayerEntity> getBot(CommandSourceStack source,
                                                   String name,
                                                   BotAuthorizationPolicy.Operation operation,
                                                   String channel) {
        return BotAuthorizationGate.INSTANCE.resolveAuthorized(source, name, operation, channel);
    }
}
