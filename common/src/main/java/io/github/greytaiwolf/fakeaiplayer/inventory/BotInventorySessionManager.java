package io.github.greytaiwolf.fakeaiplayer.inventory;

import io.github.greytaiwolf.fakeaiplayer.auth.BotAuthorizationGate;
import io.github.greytaiwolf.fakeaiplayer.auth.BotAuthorizationPolicy;
import io.github.greytaiwolf.fakeaiplayer.brain.BrainCoordinator;
import io.github.greytaiwolf.fakeaiplayer.entity.AIPlayerEntity;
import io.github.greytaiwolf.fakeaiplayer.log.BotLog;
import io.github.greytaiwolf.fakeaiplayer.manager.AIPlayerManager;
import io.github.greytaiwolf.fakeaiplayer.runtime.PauseOwner;
import io.github.greytaiwolf.fakeaiplayer.task.TaskManager;
import java.util.ArrayList;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Player;

/** Owns the one-viewer lease and the matching task/action suspension for a bot inventory. */
public final class BotInventorySessionManager {
    public static final BotInventorySessionManager INSTANCE = new BotInventorySessionManager();
    public static final double MAX_DISTANCE_SQUARED = 8.0D * 8.0D;

    private final Map<UUID, Session> sessionsByBot = new ConcurrentHashMap<>();

    private BotInventorySessionManager() {
    }

    public boolean tryOpen(ServerPlayer viewer, AIPlayerEntity bot, String channel) {
        if (!BotAuthorizationGate.INSTANCE.authorize(
                viewer, bot, BotAuthorizationPolicy.Operation.INVENTORY, channel)) {
            deny(viewer, "You are not this bot's owner and do not have OP permission.");
            return false;
        }
        if (!baseValidity(viewer, bot)) {
            deny(viewer, "Move within 8 blocks of the bot to open its inventory.");
            return false;
        }
        if (TaskManager.INSTANCE.activeOrigin(bot).map(origin -> origin.safety()).orElse(false)) {
            deny(viewer, "The bot is handling an immediate danger; try again when it is safe.");
            return false;
        }

        Session existing = sessionsByBot.get(bot.getUUID());
        if (existing != null && !existing.viewerUuid().equals(viewer.getUUID())) {
            deny(viewer, "This bot's inventory is already being edited.");
            return false;
        }
        if (existing != null && viewer.containerMenu instanceof BotInventoryMenu menu && menu.isFor(bot)) {
            return true;
        }
        if (existing != null) {
            // The same viewer no longer has the matching server menu (for example after a client
            // close packet). Release the stale lease before attempting a new open.
            release(existing, viewer, bot, false);
        }

        // Opening a second bot menu first closes and releases the viewer's previous session.
        if (viewer.containerMenu instanceof BotInventoryMenu previous && previous.serverBot() != null) {
            viewer.closeContainer();
        }

        Session session = new Session(bot.getUUID(), viewer.getUUID());
        if (sessionsByBot.putIfAbsent(bot.getUUID(), session) != null) {
            deny(viewer, "This bot's inventory is already being edited.");
            return false;
        }

        boolean suspended = false;
        try {
            // Invalidate the decision epoch before installing the pause. A response already in
            // flight may still arrive, but DecisionSession will reject it as stale before tools run.
            BrainCoordinator.INSTANCE.invalidateDecision(bot, "inventory_open");
            BrainCoordinator.INSTANCE.clearIntentWakeSources(bot);
            bot.getActionPack().suspend(PauseOwner.INVENTORY);
            suspended = true;
            TaskManager.INSTANCE.pauseFor(bot, PauseOwner.INVENTORY, "inventory_open");
            OptionalInt opened = viewer.openMenu(new SimpleMenuProvider(
                    (containerId, viewerInventory, player) ->
                            new BotInventoryMenu(containerId, viewerInventory, bot),
                    Component.translatable("screen.fakeaiplayer.inventory_title", bot.getGameProfile().getName())));
            if (opened.isEmpty()) {
                release(session, viewer, bot, false);
                return false;
            }
            BotLog.security("inventory_session_opened",
                    "actor_uuid", viewer.getUUID(),
                    "bot_uuid", bot.getUUID(),
                    "bot_name", bot.getGameProfile().getName(),
                    "channel", channel == null ? "unknown" : channel);
            return true;
        } catch (RuntimeException exception) {
            sessionsByBot.remove(bot.getUUID(), session);
            if (suspended) {
                bot.getActionPack().resume(PauseOwner.INVENTORY);
                TaskManager.INSTANCE.resumeOwnedPause(bot, PauseOwner.INVENTORY);
            }
            throw exception;
        }
    }

    public boolean tryOpenByName(ServerPlayer viewer, String botName, String channel) {
        Optional<AIPlayerEntity> bot = BotAuthorizationGate.INSTANCE.resolveAuthorized(
                viewer, botName, BotAuthorizationPolicy.Operation.INVENTORY, channel);
        if (bot.isEmpty()) {
            deny(viewer, "The bot was not found or you do not have permission.");
            return false;
        }
        return tryOpen(viewer, bot.get(), channel);
    }

    public boolean isOpen(AIPlayerEntity bot) {
        return sessionsByBot.containsKey(bot.getUUID());
    }

    public boolean isValidViewer(Player viewer, AIPlayerEntity bot) {
        Session session = sessionsByBot.get(bot.getUUID());
        return session != null
                && session.viewerUuid().equals(viewer.getUUID())
                && viewer instanceof ServerPlayer serverViewer
                && baseValidity(serverViewer, bot)
                && BotAuthorizationGate.INSTANCE.authorize(serverViewer, bot,
                        BotAuthorizationPolicy.Operation.INVENTORY, "menu:still_valid");
    }

    public void onMenuClosed(Player viewer, AIPlayerEntity bot) {
        Session session = sessionsByBot.get(bot.getUUID());
        if (session == null || !session.viewerUuid().equals(viewer.getUUID())) {
            return;
        }
        release(session, viewer instanceof ServerPlayer serverPlayer ? serverPlayer : null, bot, false);
    }

    /** Safety coordinators call this before assigning an emergency task. */
    public void closeForSafety(AIPlayerEntity bot, String reason) {
        Session session = sessionsByBot.get(bot.getUUID());
        if (session == null) {
            return;
        }
        ServerPlayer viewer = bot.getServer().getPlayerList().getPlayer(session.viewerUuid());
        release(session, viewer, bot, true);
        BotLog.security("inventory_session_closed_for_safety",
                "bot_uuid", bot.getUUID(), "reason", reason == null ? "danger" : reason);
    }

    public void onViewerDisconnect(ServerPlayer viewer) {
        for (Session session : new ArrayList<>(sessionsByBot.values())) {
            if (!session.viewerUuid().equals(viewer.getUUID())) {
                continue;
            }
            AIPlayerManager.INSTANCE.getByUuid(session.botUuid())
                    .ifPresentOrElse(bot -> release(session, null, bot, false),
                            () -> sessionsByBot.remove(session.botUuid(), session));
        }
    }

    public void onBotRemoved(AIPlayerEntity bot) {
        Session session = sessionsByBot.get(bot.getUUID());
        if (session == null) {
            return;
        }
        ServerPlayer viewer = bot.getServer().getPlayerList().getPlayer(session.viewerUuid());
        release(session, viewer, bot, true);
    }

    public void tick(MinecraftServer server) {
        for (Session session : new ArrayList<>(sessionsByBot.values())) {
            AIPlayerEntity bot = AIPlayerManager.INSTANCE.getByUuid(session.botUuid()).orElse(null);
            ServerPlayer viewer = server.getPlayerList().getPlayer(session.viewerUuid());
            boolean menuMatches = viewer != null
                    && viewer.containerMenu instanceof BotInventoryMenu menu
                    && bot != null
                    && menu.isFor(bot);
            if (bot == null) {
                sessionsByBot.remove(session.botUuid(), session);
            } else if (!menuMatches || !baseValidity(viewer, bot)) {
                release(session, viewer, bot, menuMatches);
            }
        }
    }

    public void clear(MinecraftServer server) {
        for (Session session : new ArrayList<>(sessionsByBot.values())) {
            AIPlayerManager.INSTANCE.getByUuid(session.botUuid()).ifPresent(bot -> {
                ServerPlayer viewer = server.getPlayerList().getPlayer(session.viewerUuid());
                release(session, viewer, bot, true);
            });
        }
        sessionsByBot.clear();
    }

    private void release(Session session,
                         ServerPlayer viewer,
                         AIPlayerEntity bot,
                         boolean closeContainer) {
        if (!sessionsByBot.remove(session.botUuid(), session)) {
            return;
        }
        try {
            if (closeContainer && viewer != null
                    && viewer.containerMenu instanceof BotInventoryMenu menu
                    && menu.isFor(bot)) {
                viewer.closeContainer();
            }
        } finally {
            // Complete the vanilla cursor/slot close transaction before ordinary work can resume
            // and inspect or mutate the same inventory.
            bot.getActionPack().resume(PauseOwner.INVENTORY);
            TaskManager.INSTANCE.resumeOwnedPause(bot, PauseOwner.INVENTORY);
        }
        BotLog.security("inventory_session_closed",
                "actor_uuid", session.viewerUuid(), "bot_uuid", session.botUuid());
    }

    private static boolean baseValidity(ServerPlayer viewer, AIPlayerEntity bot) {
        return viewer != null
                && viewer.isAlive()
                && bot.isAlive()
                && !bot.isRemoved()
                && AIPlayerManager.INSTANCE.getByUuid(bot.getUUID()).orElse(null) == bot
                && viewer.serverLevel() == bot.serverLevel()
                && viewer.distanceToSqr(bot) <= MAX_DISTANCE_SQUARED;
    }

    private static void deny(ServerPlayer viewer, String message) {
        viewer.displayClientMessage(Component.literal("[FakeAiPlayer] " + message), false);
    }

    private record Session(UUID botUuid, UUID viewerUuid) {
    }
}
