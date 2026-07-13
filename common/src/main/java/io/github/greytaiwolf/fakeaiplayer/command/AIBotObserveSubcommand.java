package io.github.greytaiwolf.fakeaiplayer.command;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import io.github.greytaiwolf.fakeaiplayer.auth.BotAuthorizationGate;
import io.github.greytaiwolf.fakeaiplayer.auth.BotAuthorizationPolicy;
import io.github.greytaiwolf.fakeaiplayer.entity.AIPlayerEntity;
import io.github.greytaiwolf.fakeaiplayer.manager.AIPlayerManager;
import io.github.greytaiwolf.fakeaiplayer.observe.BotProfiler;
import io.github.greytaiwolf.fakeaiplayer.observe.ReplayRecorder;
import io.github.greytaiwolf.fakeaiplayer.observe.TpsGuard;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;

import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;

public final class AIBotObserveSubcommand {
    private AIBotObserveSubcommand() {
    }

    public static LiteralArgumentBuilder<CommandSourceStack> profile() {
        return literal("profile")
                .then(botName().executes(context -> profile(
                        context.getSource(),
                        StringArgumentType.getString(context, "name"))));
    }

    public static LiteralArgumentBuilder<CommandSourceStack> replay() {
        return literal("replay")
                .then(botName()
                        .executes(context -> replay(
                                context.getSource(),
                                StringArgumentType.getString(context, "name"),
                                10))
                        .then(argument("count", IntegerArgumentType.integer(1, 50))
                                .executes(context -> replay(
                                        context.getSource(),
                                        StringArgumentType.getString(context, "name"),
                                        IntegerArgumentType.getInteger(context, "count")))));
    }

    public static LiteralArgumentBuilder<CommandSourceStack> tps() {
        return literal("tps")
                .executes(context -> tps(context.getSource()));
    }

    private static com.mojang.brigadier.builder.RequiredArgumentBuilder<CommandSourceStack, String> botName() {
        return argument("name", StringArgumentType.word());
    }

    private static int profile(CommandSourceStack source, String name) {
        Optional<AIPlayerEntity> bot = getBot(source, name);
        if (bot.isEmpty()) {
            return 0;
        }
        Map<String, BotProfiler.Stat> stats = BotProfiler.INSTANCE.snapshot(bot.get().getUUID());
        if (stats.isEmpty()) {
            source.sendSuccess(() -> Component.literal("[FakeAiPlayer] profile " + name + ": <empty>"), false);
            return 1;
        }
        StringBuilder builder = new StringBuilder("[FakeAiPlayer] profile ").append(name).append(":");
        for (Map.Entry<String, BotProfiler.Stat> entry : stats.entrySet()) {
            BotProfiler.Stat stat = entry.getValue();
            builder.append("\n- ").append(entry.getKey())
                    .append(" count=").append(stat.count())
                    .append(" avg=").append(format(stat.avgMs())).append("ms")
                    .append(" p95=").append(format(stat.p95Ms())).append("ms")
                    .append(" max=").append(format(stat.maxMs())).append("ms");
        }
        source.sendSuccess(() -> Component.literal(builder.toString()), false);
        return 1;
    }

    private static int replay(CommandSourceStack source, String name, int count) {
        Optional<AIPlayerEntity> bot = getBot(source, name);
        if (bot.isEmpty()) {
            return 0;
        }
        List<ReplayRecorder.ReplayEvent> events = ReplayRecorder.INSTANCE.tail(bot.get().getUUID(), count);
        if (events.isEmpty()) {
            source.sendSuccess(() -> Component.literal("[FakeAiPlayer] replay " + name + ": <empty>"), false);
            return 1;
        }
        StringBuilder builder = new StringBuilder("[FakeAiPlayer] replay ").append(name).append(" last ").append(events.size()).append(":");
        for (ReplayRecorder.ReplayEvent event : events) {
            builder.append("\n- ").append(event.summary());
        }
        source.sendSuccess(() -> Component.literal(builder.toString()), false);
        return 1;
    }

    private static int tps(CommandSourceStack source) {
        if (!BotAuthorizationGate.INSTANCE.requireGlobalAdmin(source, "command:tps")) {
            return 0;
        }
        TpsGuard.Snapshot snapshot = TpsGuard.INSTANCE.snapshot(source.getServer());
        source.sendSuccess(() -> Component.literal("[FakeAiPlayer] tps estimated="
                + format(snapshot.estimatedTps())
                + " avg_tick_ms=" + format(snapshot.averageTickMs())
                + " degraded=" + snapshot.degraded()
                + " continuation_delay_s=" + snapshot.continuationDelaySeconds()
                + " scan_interval=" + snapshot.scanInterval()), false);
        return 1;
    }

    private static Optional<AIPlayerEntity> getBot(CommandSourceStack source, String name) {
        return BotAuthorizationGate.INSTANCE.resolveAuthorized(
                source, name, BotAuthorizationPolicy.Operation.VIEW, "command:observe");
    }

    private static String format(double value) {
        return String.format(java.util.Locale.ROOT, "%.2f", value);
    }
}
