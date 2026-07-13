package io.github.greytaiwolf.fakeaiplayer.auth;

import io.github.greytaiwolf.fakeaiplayer.auth.BotAuthorizationPolicy.Actor;
import io.github.greytaiwolf.fakeaiplayer.auth.BotAuthorizationPolicy.BotTarget;
import io.github.greytaiwolf.fakeaiplayer.auth.BotAuthorizationPolicy.Decision;
import io.github.greytaiwolf.fakeaiplayer.auth.BotAuthorizationPolicy.GlobalTarget;
import io.github.greytaiwolf.fakeaiplayer.auth.BotAuthorizationPolicy.Operation;
import io.github.greytaiwolf.fakeaiplayer.entity.AIPlayerEntity;
import io.github.greytaiwolf.fakeaiplayer.log.BotLog;
import io.github.greytaiwolf.fakeaiplayer.manager.AIPlayerManager;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/** Minecraft-facing resolver, denial response, and audit adapter for {@link BotAuthorizationPolicy}. */
public final class BotAuthorizationGate {
    public static final BotAuthorizationGate INSTANCE = new BotAuthorizationGate();

    private static final int OPERATOR_LEVEL = 2;
    private static final int TRUSTED_CONSOLE_LEVEL = 4;
    private static final String GENERIC_NOT_FOUND = "[FakeAiPlayer] 找不到该 Bot 或无权限。";
    private final BotAuthorizationPolicy policy = new BotAuthorizationPolicy();

    private BotAuthorizationGate() {
    }

    public Optional<AIPlayerEntity> resolveAuthorized(CommandSourceStack source,
                                                      String botName,
                                                      Operation operation,
                                                      String channel) {
        Optional<AIPlayerEntity> bot = resolveForSource(source, botName);
        if (bot.isEmpty()) {
            auditMissing(actor(source), source.getTextName(), botName, operation, channel);
            source.sendFailure(Component.literal(GENERIC_NOT_FOUND));
            return Optional.empty();
        }
        if (!authorize(source, bot.get(), operation, channel)) {
            source.sendFailure(Component.literal(GENERIC_NOT_FOUND));
            return Optional.empty();
        }
        return bot;
    }

    public Optional<AIPlayerEntity> resolveAuthorized(ServerPlayer player,
                                                      String botName,
                                                      Operation operation,
                                                      String channel) {
        Optional<AIPlayerEntity> bot = resolveForPlayer(player, botName);
        if (bot.isEmpty()) {
            auditMissing(Actor.player(player.getUUID(), player.hasPermissions(OPERATOR_LEVEL)),
                    player.getGameProfile().getName(), botName, operation, channel);
            return Optional.empty();
        }
        if (!authorize(player, bot.get(), operation, channel)) {
            return Optional.empty();
        }
        return bot;
    }

    public boolean authorize(CommandSourceStack source,
                             AIPlayerEntity bot,
                             Operation operation,
                             String channel) {
        Actor actor = actor(source);
        Decision decision = policy.evaluate(actor, target(bot), operation);
        if (!decision.allowed()) {
            auditDenied(actor, source.getTextName(), bot, operation, channel, decision);
        }
        return decision.allowed();
    }

    public boolean authorize(ServerPlayer player,
                             AIPlayerEntity bot,
                             Operation operation,
                             String channel) {
        Actor actor = Actor.player(player.getUUID(), player.hasPermissions(OPERATOR_LEVEL));
        Decision decision = policy.evaluate(actor, target(bot), operation);
        if (!decision.allowed()) {
            auditDenied(actor, player.getGameProfile().getName(), bot, operation, channel, decision);
        }
        return decision.allowed();
    }

    public boolean authorizeBot(AIPlayerEntity actorBot,
                                AIPlayerEntity targetBot,
                                Operation operation,
                                String channel) {
        Actor actor = Actor.bot(actorBot.getUUID(), AIPlayerManager.INSTANCE.ownerOf(actorBot).orElse(null));
        Decision decision = policy.evaluate(actor, target(targetBot), operation);
        if (!decision.allowed()) {
            auditDenied(actor, actorBot.getGameProfile().getName(), targetBot, operation, channel, decision);
        }
        return decision.allowed();
    }

    public boolean requireGlobalAdmin(CommandSourceStack source, String channel) {
        Actor actor = actor(source);
        Decision decision = policy.evaluate(actor, GlobalTarget.INSTANCE, Operation.ADMIN);
        if (decision.allowed()) {
            return true;
        }
        BotLog.security("authorization_denied",
                "actor_kind", actor.kind(),
                "actor_uuid", safe(actor.actorUuid()),
                "actor_name", source.getTextName(),
                "bot_uuid", "-",
                "bot_name", "-",
                "operation", Operation.ADMIN,
                "channel", cleanChannel(channel),
                "reason", decision.reason());
        source.sendFailure(Component.literal("[FakeAiPlayer] 该操作需要服务器管理员权限。"));
        return false;
    }

    public boolean canProvisionPersonalBot(CommandSourceStack source, String channel) {
        if (source.getPlayer() != null) {
            return true;
        }
        return requireGlobalAdmin(source, channel);
    }

    public boolean canView(CommandSourceStack source, AIPlayerEntity bot) {
        return policy.evaluate(actor(source), target(bot), Operation.VIEW).allowed();
    }

    private Optional<AIPlayerEntity> resolveForSource(CommandSourceStack source, String botName) {
        ServerPlayer player = source.getPlayer();
        return BotTargetSelector.resolve(player == null ? null : player.getUUID(), botName,
                AIPlayerManager.INSTANCE::botOf, AIPlayerManager.INSTANCE::getByName);
    }

    private Optional<AIPlayerEntity> resolveForPlayer(ServerPlayer player, String botName) {
        return BotTargetSelector.resolve(player.getUUID(), botName,
                AIPlayerManager.INSTANCE::botOf, AIPlayerManager.INSTANCE::getByName);
    }

    private Actor actor(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        if (player != null) {
            return Actor.player(player.getUUID(), source.hasPermission(OPERATOR_LEVEL));
        }
        return source.hasPermission(TRUSTED_CONSOLE_LEVEL) ? Actor.console() : Actor.unknown();
    }

    private BotTarget target(AIPlayerEntity bot) {
        return new BotTarget(bot.getUUID(), AIPlayerManager.INSTANCE.ownerOf(bot).orElse(null));
    }

    private static void auditDenied(Actor actor,
                                    String actorName,
                                    AIPlayerEntity bot,
                                    Operation operation,
                                    String channel,
                                    Decision decision) {
        BotLog.security("authorization_denied",
                "actor_kind", actor.kind(),
                "actor_uuid", safe(actor.actorUuid()),
                "actor_name", actorName == null ? "-" : actorName,
                "bot_uuid", bot.getUUID(),
                "bot_name", bot.getGameProfile().getName(),
                "operation", operation,
                "channel", cleanChannel(channel),
                "reason", decision.reason());
    }

    private static void auditMissing(Actor actor,
                                     String actorName,
                                     String requestedName,
                                     Operation operation,
                                     String channel) {
        BotLog.security("authorization_target_unresolved",
                "actor_kind", actor.kind(),
                "actor_uuid", safe(actor.actorUuid()),
                "actor_name", actorName == null ? "-" : actorName,
                "bot_uuid", "-",
                "bot_name", requestedName == null || requestedName.isBlank() ? "<owned>" : requestedName,
                "operation", operation,
                "channel", cleanChannel(channel),
                "reason", "target_not_found");
    }

    private static String safe(UUID value) {
        return value == null ? "-" : value.toString();
    }

    private static String cleanChannel(String channel) {
        return channel == null || channel.isBlank() ? "unknown" : channel;
    }
}
