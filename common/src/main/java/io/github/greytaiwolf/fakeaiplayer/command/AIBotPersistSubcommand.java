package io.github.greytaiwolf.fakeaiplayer.command;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import io.github.greytaiwolf.fakeaiplayer.auth.BotAuthorizationGate;
import io.github.greytaiwolf.fakeaiplayer.persist.BotPersistence;
import io.github.greytaiwolf.fakeaiplayer.task.tree.PlayerPlacedLogLedger;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;

import static net.minecraft.commands.Commands.literal;

public final class AIBotPersistSubcommand {
    private AIBotPersistSubcommand() {
    }

    public static LiteralArgumentBuilder<CommandSourceStack> build() {
        return literal("persist")
                .then(literal("save")
                        .executes(context -> save(context.getSource())))
                .then(literal("reload")
                        .executes(context -> reload(context.getSource())))
                .then(literal("trust-empty-log-baseline")
                        .then(literal("confirm")
                                .executes(context -> trustEmptyLogBaseline(
                                        context.getSource()))));
    }

    private static int save(CommandSourceStack source) {
        if (!BotAuthorizationGate.INSTANCE.requireGlobalAdmin(source, "command:persist_save")) {
            return 0;
        }
        int count = BotPersistence.INSTANCE.saveAll(source.getServer());
        if (!BotPersistence.INSTANCE.lastSaveSucceeded()) {
            source.sendFailure(Component.literal("[FakeAiPlayer] persistence failed; existing runtime file was preserved"));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("[FakeAiPlayer] persisted " + count + " bot(s)"), false);
        return count;
    }

    private static int reload(CommandSourceStack source) {
        if (!BotAuthorizationGate.INSTANCE.requireGlobalAdmin(source, "command:persist_reload")) {
            return 0;
        }
        int count = BotPersistence.INSTANCE.reloadIfIdle(source.getServer());
        if (count < 0) {
            source.sendFailure(Component.literal("[FakeAiPlayer] reload rejected: despawn all bots and clear jobs first"));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("[FakeAiPlayer] restored " + count + " bot(s)"), false);
        return count;
    }

    private static int trustEmptyLogBaseline(CommandSourceStack source) {
        if (!BotAuthorizationGate.INSTANCE.requireGlobalAdmin(
                source, "command:persist_trust_empty_log_baseline")) {
            return 0;
        }
        PlayerPlacedLogLedger.BaselineInstallResult result =
                PlayerPlacedLogLedger.INSTANCE.installAuditedEmptyBaseline(
                        source.getServer());
        return switch (result) {
            case INSTALLED -> {
                source.sendSuccess(() -> Component.literal(
                        "[FakeAiPlayer] trusted empty log baseline installed; only placements "
                                + "observed from now on have positive ownership evidence"), true);
                yield 1;
            }
            case ALREADY_TRUSTED -> {
                source.sendSuccess(() -> Component.literal(
                        "[FakeAiPlayer] player-placed log ledger is already trusted"), false);
                yield 1;
            }
            case EXISTING_STATE_REQUIRES_OFFLINE_REVIEW -> {
                source.sendFailure(Component.literal(
                        "[FakeAiPlayer] baseline refused: an existing ledger or unclean-session "
                                + "marker must be backed up and reviewed offline"));
                yield 0;
            }
            case WRITE_FAILED -> {
                source.sendFailure(Component.literal(
                        "[FakeAiPlayer] baseline write failed; automatic whole-tree felling "
                                + "remains disabled"));
                yield 0;
            }
        };
    }
}
