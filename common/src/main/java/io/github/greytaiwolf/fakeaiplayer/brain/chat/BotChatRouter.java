package io.github.greytaiwolf.fakeaiplayer.brain.chat;

import io.github.greytaiwolf.fakeaiplayer.entity.AIPlayerEntity;
import io.github.greytaiwolf.fakeaiplayer.manager.AIPlayerManager;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.Objects;
import java.util.UUID;

/**
 * Unified server-side output route for the existing panel and the owning player's chat stream.
 *
 * <p>The default audience is deliberately owner-only. This adapter never broadcasts to nearby or
 * global players, and player/user role messages are never echoed into normal Minecraft chat.
 */
public final class BotChatRouter {
    public static final BotChatRouter INSTANCE = new BotChatRouter();

    private final BotChatRateLimiter rateLimiter;

    public BotChatRouter() {
        this(new BotChatRateLimiter());
    }

    BotChatRouter(BotChatRateLimiter rateLimiter) {
        this.rateLimiter = Objects.requireNonNull(rateLimiter, "rateLimiter");
    }

    public RouteResult route(AIPlayerEntity bot,
                             String role,
                             String text,
                             PanelSender panelSender) {
        Objects.requireNonNull(bot, "bot");
        BotChatPolicy.PreparedMessage message = BotChatPolicy.prepare(role, text);
        if (message.empty()) {
            return new RouteResult(false, false, OwnerDelivery.EMPTY);
        }

        boolean panelForwarded = false;
        if (panelSender != null) {
            panelSender.send(bot, message.panelRole(), message.panelText());
            panelForwarded = true;
        }
        if (!message.ownerVisible()) {
            return new RouteResult(true, panelForwarded, OwnerDelivery.PANEL_ONLY_ROLE);
        }

        UUID ownerId = AIPlayerManager.INSTANCE.ownerOf(bot).orElse(null);
        if (ownerId == null) {
            return new RouteResult(true, panelForwarded, OwnerDelivery.NO_OWNER);
        }
        ServerPlayer owner = bot.getServer().getPlayerList().getPlayer(ownerId);
        if (owner == null) {
            return new RouteResult(true, panelForwarded, OwnerDelivery.OWNER_OFFLINE);
        }
        BotChatPolicy.MessageKind kind = BotChatPolicy.MessageKind.fromRole(message.panelRole());
        if (!rateLimiter.tryAcquire(
                bot.getUUID(),
                kind,
                bot.getServer().getTickCount(),
                message.ownerCooldownTicks())) {
            return new RouteResult(true, panelForwarded, OwnerDelivery.COOLDOWN);
        }

        String botName = BotChatPolicy.sanitizeBotName(bot.getGameProfile().getName());
        owner.sendSystemMessage(Component.literal("[AI] <" + botName + "> " + message.ownerText()));
        return new RouteResult(true, panelForwarded, OwnerDelivery.SENT);
    }

    public void clear(UUID botId) {
        rateLimiter.clear(botId);
    }

    public void clearAll() {
        rateLimiter.clearAll();
    }

    @FunctionalInterface
    public interface PanelSender {
        void send(AIPlayerEntity bot, String role, String text);
    }

    public enum OwnerDelivery {
        SENT,
        EMPTY,
        PANEL_ONLY_ROLE,
        NO_OWNER,
        OWNER_OFFLINE,
        COOLDOWN
    }

    public record RouteResult(boolean accepted, boolean panelForwarded, OwnerDelivery ownerDelivery) {
    }
}
