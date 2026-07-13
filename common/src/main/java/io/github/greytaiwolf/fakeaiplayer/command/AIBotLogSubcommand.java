package io.github.greytaiwolf.fakeaiplayer.command;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import io.github.greytaiwolf.fakeaiplayer.auth.BotAuthorizationGate;
import io.github.greytaiwolf.fakeaiplayer.log.BotLog;
import io.github.greytaiwolf.fakeaiplayer.log.BotLogWriter;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;

import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;

public final class AIBotLogSubcommand {
    private static final int DEFAULT_OVERFLOW_EVENTS = 6_000;

    private AIBotLogSubcommand() {
    }

    public static LiteralArgumentBuilder<CommandSourceStack> build() {
        return literal("log")
                .then(literal("status")
                        .executes(context -> status(context.getSource())))
                .then(literal("rotate")
                        .executes(context -> rotate(context.getSource())))
                .then(literal("overflow")
                        .executes(context -> overflow(context.getSource(), DEFAULT_OVERFLOW_EVENTS))
                        .then(argument("count", IntegerArgumentType.integer(1, 50_000))
                                .executes(context -> overflow(context.getSource(), IntegerArgumentType.getInteger(context, "count")))));
    }

    private static int status(CommandSourceStack source) {
        if (!BotAuthorizationGate.INSTANCE.requireGlobalAdmin(source, "command:log_status")) {
            return 0;
        }
        BotLogWriter writer = BotLogWriter.INSTANCE;
        source.sendSuccess(() -> Component.literal("[FakeAiPlayer] log started="
                + writer.isStarted()
                + " queue="
                + writer.queueSize()
                + " dropped="
                + writer.droppedCount()
                + " dir="
                + writer.baseDir()), false);
        return 1;
    }

    private static int rotate(CommandSourceStack source) {
        if (!BotAuthorizationGate.INSTANCE.requireGlobalAdmin(source, "command:log_rotate")) {
            return 0;
        }
        BotLog.config("log_rotate_requested", "source", "command");
        BotLogWriter.INSTANCE.forceRotateForTest();
        source.sendSuccess(() -> Component.literal("[FakeAiPlayer] log rotation triggered"), false);
        return 1;
    }

    private static int overflow(CommandSourceStack source, int count) {
        if (!BotAuthorizationGate.INSTANCE.requireGlobalAdmin(source, "command:log_overflow")) {
            return 0;
        }
        BotLogWriter.INSTANCE.forceOverflowForTest(count);
        source.sendSuccess(() -> Component.literal("[FakeAiPlayer] log overflow validation enqueued " + count + " events"), false);
        return 1;
    }
}
