package io.github.greytaiwolf.fakeaiplayer.network;

import io.github.greytaiwolf.fakeaiplayer.auth.BotAuthorizationGate;
import io.github.greytaiwolf.fakeaiplayer.auth.BotAuthorizationPolicy;
import io.github.greytaiwolf.fakeaiplayer.brain.BotAiConnectionService;
import io.github.greytaiwolf.fakeaiplayer.brain.BrainCoordinator;
import io.github.greytaiwolf.fakeaiplayer.brain.BotRuntimeOptions;
import io.github.greytaiwolf.fakeaiplayer.brain.chat.BotChatRouter;
import io.github.greytaiwolf.fakeaiplayer.brain.social.BotSocialCoordinator;
import io.github.greytaiwolf.fakeaiplayer.entity.AIPlayerEntity;
import io.github.greytaiwolf.fakeaiplayer.log.BotLog;
import io.github.greytaiwolf.fakeaiplayer.manager.AIPlayerManager;
import io.github.greytaiwolf.fakeaiplayer.goal.GoalExecutor;
import io.github.greytaiwolf.fakeaiplayer.memory.BotMemory;
import io.github.greytaiwolf.fakeaiplayer.memory.BotMemoryStore;
import io.github.greytaiwolf.fakeaiplayer.network.payload.BotChatS2C;
import io.github.greytaiwolf.fakeaiplayer.network.payload.BotAiCredentialStatusS2C;
import io.github.greytaiwolf.fakeaiplayer.network.payload.BotCommandC2S;
import io.github.greytaiwolf.fakeaiplayer.network.payload.OpenBotAiSetupS2C;
import io.github.greytaiwolf.fakeaiplayer.network.payload.OpenBotInventoryC2S;
import io.github.greytaiwolf.fakeaiplayer.inventory.BotInventorySessionManager;
import io.github.greytaiwolf.fakeaiplayer.network.payload.BotTeleportC2S;
import io.github.greytaiwolf.fakeaiplayer.network.payload.BotSnapshotS2C;
import io.github.greytaiwolf.fakeaiplayer.network.payload.PayloadLimits;
import io.github.greytaiwolf.fakeaiplayer.network.payload.SetOptionC2S;
import io.github.greytaiwolf.fakeaiplayer.network.payload.SetBotAiCredentialC2S;
import io.github.greytaiwolf.fakeaiplayer.network.payload.RestoreBotAiCredentialC2S;
import io.github.greytaiwolf.fakeaiplayer.network.payload.SubscribeBotC2S;
import io.github.greytaiwolf.fakeaiplayer.runtime.IntentController;
import io.github.greytaiwolf.fakeaiplayer.runtime.RuntimeLifecycleCoordinator;
import io.github.greytaiwolf.fakeaiplayer.runtime.TaskOrigin;
import io.github.greytaiwolf.fakeaiplayer.task.CraftTask;
import io.github.greytaiwolf.fakeaiplayer.task.EatTask;
import io.github.greytaiwolf.fakeaiplayer.task.MineTask;
import io.github.greytaiwolf.fakeaiplayer.task.MoveTask;
import io.github.greytaiwolf.fakeaiplayer.task.SmeltTask;
import io.github.greytaiwolf.fakeaiplayer.task.SleepTask;
import io.github.greytaiwolf.fakeaiplayer.task.Task;
import io.github.greytaiwolf.fakeaiplayer.task.TaskManager;
import io.github.greytaiwolf.fakeaiplayer.task.TaskStatus;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Objects;

public final class AIBotServerNetworking {
    public static final AIBotServerNetworking INSTANCE = new AIBotServerNetworking();

    private static final int SNAPSHOT_INTERVAL_TICKS = 10;
    private static final Set<String> COMMAND_ACTIONS = Set.of(
            "move", "mine", "craft", "smelt", "eat", "sleep", "abort", "chat", "pause", "resume", "reset");
    private static final Set<String> OPTION_KEYS = Set.of("manual", "memory", "reports");
    private static final int CREDENTIAL_ATTEMPT_WINDOW_TICKS = 1_200;
    private static final int MAX_CREDENTIAL_ATTEMPTS_PER_WINDOW = 4;
    private final Map<UUID, UUID> subscriptions = new ConcurrentHashMap<>();
    /** Player whose successfully verified client credential is active for a bot. */
    private final Map<UUID, UUID> credentialActors = new ConcurrentHashMap<>();
    /** Exact pending generation and player waiting for a no-tool connection probe. */
    private final Map<UUID, PendingCredentialActor> pendingCredentialActors = new ConcurrentHashMap<>();
    private final Map<CredentialAttemptKey, ArrayDeque<Integer>> credentialAttempts = new ConcurrentHashMap<>();
    private volatile ServerNetworkTransport network = new ServerNetworkTransport() {
        @Override
        public boolean canSendToClient(ServerPlayer player,
                                       net.minecraft.network.protocol.common.custom.CustomPacketPayload.Type<?> type) {
            return false;
        }

        @Override
        public void send(ServerPlayer player,
                         net.minecraft.network.protocol.common.custom.CustomPacketPayload payload) {
        }
    };
    private int snapshotTick;

    private AIBotServerNetworking() {
    }

    public void configure(ServerNetworkTransport transport) {
        network = Objects.requireNonNull(transport, "transport");
    }

    public void onDisconnect(ServerPlayer player) {
        UUID playerId = player.getUUID();
        subscriptions.remove(playerId);
        BotAiSetupSessions.INSTANCE.clear(playerId);
        credentialAttempts.keySet().removeIf(key -> playerId.equals(key.playerId()));
        pendingCredentialActors.entrySet().removeIf(entry -> {
            PendingCredentialActor pending = entry.getValue();
            if (!playerId.equals(pending.playerId())) {
                return false;
            }
            BotAiConnectionService.INSTANCE.discardPending(entry.getKey());
            BrainCoordinator.INSTANCE.rejectBotApiKey(entry.getKey(), pending.generation());
            return true;
        });
        credentialActors.entrySet().removeIf(entry -> {
            if (!playerId.equals(entry.getValue())) {
                return false;
            }
            UUID botId = entry.getKey();
            BotAiConnectionService.INSTANCE.discardPending(botId);
            BrainCoordinator.INSTANCE.revokeBotApiKey(botId);
            PendingCredentialActor displaced = pendingCredentialActors.remove(botId);
            notifyDisplacedPending(player.getServer(), displaced);
            BotSocialCoordinator.INSTANCE.clear(botId);
            return true;
        });
    }

    public void tick(MinecraftServer server) {
        snapshotTick++;
        if (snapshotTick % SNAPSHOT_INTERVAL_TICKS != 0 || subscriptions.isEmpty()) {
            return;
        }
        for (Map.Entry<UUID, UUID> entry : subscriptions.entrySet()) {
            ServerPlayer viewer = server.getPlayerList().getPlayer(entry.getKey());
            if (viewer == null) {
                subscriptions.remove(entry.getKey());
                continue;
            }
            Optional<AIPlayerEntity> bot = AIPlayerManager.INSTANCE.getByUuid(entry.getValue());
            if (bot.isEmpty() || !BotAuthorizationGate.INSTANCE.authorize(
                    viewer, bot.get(), BotAuthorizationPolicy.Operation.VIEW, "network:snapshot_push")) {
                subscriptions.remove(entry.getKey(), entry.getValue());
                continue;
            }
            if (network.canSendToClient(viewer, BotSnapshotS2C.ID)) {
                network.send(viewer, snapshot(bot.get()));
            }
        }
    }

    public void clear() {
        subscriptions.clear();
        credentialActors.clear();
        pendingCredentialActors.clear();
        credentialAttempts.clear();
        BotAiSetupSessions.INSTANCE.clearAll();
        BotAiConnectionService.INSTANCE.reset();
        BotChatRouter.INSTANCE.clearAll();
        BotSocialCoordinator.INSTANCE.clearAll();
        snapshotTick = 0;
    }

    public void clearBot(UUID botId) {
        subscriptions.entrySet().removeIf(entry -> botId.equals(entry.getValue()));
        BotAiConnectionService.INSTANCE.discardPending(botId);
        credentialActors.remove(botId);
        pendingCredentialActors.remove(botId);
        BrainCoordinator.INSTANCE.revokeBotApiKey(botId);
        BotChatRouter.INSTANCE.clear(botId);
        BotSocialCoordinator.INSTANCE.clear(botId);
    }

    public void sendBotChat(AIPlayerEntity bot, String role, String text) {
        BotChatRouter.INSTANCE.route(bot, role, text, this::sendPanelChat);
    }

    private void sendPanelChat(AIPlayerEntity bot, String role, String text) {
        if (subscriptions.isEmpty()) {
            return;
        }
        for (Map.Entry<UUID, UUID> entry : subscriptions.entrySet()) {
            if (!bot.getUUID().equals(entry.getValue())) {
                continue;
            }
            ServerPlayer viewer = bot.getServer().getPlayerList().getPlayer(entry.getKey());
            if (viewer == null) {
                subscriptions.remove(entry.getKey(), entry.getValue());
                continue;
            }
            if (!BotAuthorizationGate.INSTANCE.authorize(
                    viewer, bot, BotAuthorizationPolicy.Operation.VIEW, "network:chat_push")) {
                subscriptions.remove(entry.getKey(), entry.getValue());
                continue;
            }
            if (network.canSendToClient(viewer, BotChatS2C.ID)) {
                network.send(viewer, new BotChatS2C(
                        PayloadLimits.truncate(bot.getGameProfile().getName(), PayloadLimits.BOT_NAME_LENGTH),
                        PayloadLimits.truncate(role, PayloadLimits.ROLE_LENGTH),
                        PayloadLimits.truncate(text, PayloadLimits.CHAT_TEXT_LENGTH)));
            }
        }
    }

    /** Issues a short-lived setup nonce and opens the masked client credential screen. */
    public boolean beginBotAiSetup(ServerPlayer player, AIPlayerEntity bot) {
        if (!network.canSendToClient(player, OpenBotAiSetupS2C.ID)) {
            return false;
        }
        UUID nonce = BotAiSetupSessions.INSTANCE.create(
                player.getUUID(),
                bot.getUUID(),
                bot.getGameProfile().getName(),
                player.getServer().getTickCount());
        network.send(player, new OpenBotAiSetupS2C(bot.getGameProfile().getName(), nonce));
        return true;
    }

    /** Disconnects active/pending session credentials and optionally deletes the caller's local key. */
    public void disconnectBotAiCredential(ServerPlayer player, AIPlayerEntity bot) {
        UUID botId = bot.getUUID();
        PendingCredentialActor pending = pendingCredentialActors.remove(botId);
        if (pending != null) {
            BotAiConnectionService.INSTANCE.discardPending(botId);
            BrainCoordinator.INSTANCE.rejectBotApiKey(botId, pending.generation());
        }
        credentialActors.remove(botId);
        BrainCoordinator.INSTANCE.revokeBotApiKey(botId);
        BotSocialCoordinator.INSTANCE.clear(botId);
        if (player != null) {
            sendCredentialStatus(
                    player,
                    bot.getGameProfile().getName(),
                    BotAiCredentialStatusS2C.NO_NONCE,
                    BotAiCredentialStatusS2C.DISCONNECTED,
                    false,
                    true);
        }
    }

    public void handleSetBotAiCredential(ServerPlayer player, SetBotAiCredentialC2S payload) {
        Optional<AIPlayerEntity> resolved = BotAuthorizationGate.INSTANCE.resolveAuthorized(
                player,
                payload.botName(),
                BotAuthorizationPolicy.Operation.ADMIN,
                "network:set_bot_ai_credential");
        if (resolved.isEmpty()) {
            sendCredentialStatus(player, payload.botName(), payload.nonce(),
                    BotAiCredentialStatusS2C.UNAUTHORIZED, false, false);
            return;
        }
        AIPlayerEntity bot = resolved.get();
        boolean validSession = BotAiSetupSessions.INSTANCE.consume(
                player.getUUID(),
                bot.getUUID(),
                bot.getGameProfile().getName(),
                payload.nonce(),
                player.getServer().getTickCount());
        if (!validSession) {
            sendCredentialStatus(player, bot.getGameProfile().getName(), payload.nonce(),
                    BotAiCredentialStatusS2C.REQUEST_EXPIRED, false, false);
            return;
        }
        bindAndProbe(player, bot, payload.apiKey(), payload.nonce(), false);
    }

    public void handleRestoreBotAiCredential(ServerPlayer player, RestoreBotAiCredentialC2S payload) {
        Optional<AIPlayerEntity> resolved = BotAuthorizationGate.INSTANCE.resolveAuthorized(
                player,
                payload.botName(),
                BotAuthorizationPolicy.Operation.ADMIN,
                "network:restore_bot_ai_credential");
        if (resolved.isEmpty()) {
            sendCredentialStatus(player, payload.botName(), BotAiCredentialStatusS2C.NO_NONCE,
                    BotAiCredentialStatusS2C.UNAUTHORIZED, false, false);
            return;
        }
        bindAndProbe(
                player,
                resolved.get(),
                payload.apiKey(),
                BotAiCredentialStatusS2C.NO_NONCE,
                true);
    }

    private void bindAndProbe(ServerPlayer player,
                              AIPlayerEntity bot,
                              String apiKey,
                              UUID nonce,
                              boolean restored) {
        if (!allowCredentialAttempt(player, bot.getUUID())) {
            sendCredentialStatus(player, bot.getGameProfile().getName(), nonce,
                    BotAiCredentialStatusS2C.RATE_LIMITED, false, false);
            return;
        }

        io.github.greytaiwolf.fakeaiplayer.brain.BotApiCredentialRegistry.CredentialUpdate update;
        try {
            update = BrainCoordinator.INSTANCE.stageBotApiKey(bot.getUUID(), apiKey);
        } catch (IllegalArgumentException invalid) {
            sendCredentialStatus(player, bot.getGameProfile().getName(), nonce,
                    BotAiCredentialStatusS2C.INVALID_KEY, false, false);
            return;
        }
        PendingCredentialActor pending = new PendingCredentialActor(
                player.getUUID(),
                update.generation(),
                bot.getGameProfile().getName(),
                nonce);
        PendingCredentialActor displaced = pendingCredentialActors.put(bot.getUUID(), pending);
        if (displaced != null && displaced.generation() != pending.generation()) {
            notifyDisplacedPending(player.getServer(), displaced);
        }

        BotAiConnectionService.INSTANCE.verifyAndGreet(bot, update.generation(), result -> {
            if (!pendingCredentialActors.remove(bot.getUUID(), pending)) {
                return;
            }
            if (result.connected()) {
                var committed = BrainCoordinator.INSTANCE.commitBotApiKey(
                        bot.getUUID(), result.credentialGeneration());
                if (!committed.changed()) {
                    sendCredentialStatus(player, bot.getGameProfile().getName(), nonce,
                            BotAiCredentialStatusS2C.REQUEST_EXPIRED, false, false);
                    return;
                }
                credentialActors.put(bot.getUUID(), player.getUUID());
                sendCredentialStatus(
                        player,
                        bot.getGameProfile().getName(),
                        nonce,
                        restored ? BotAiCredentialStatusS2C.RESTORED : BotAiCredentialStatusS2C.CONNECTED,
                        true,
                        false);
                if (!result.greeting().isBlank()) {
                    sendBotChat(bot, "bot", result.greeting());
                }
                return;
            }

            BrainCoordinator.INSTANCE.rejectBotApiKey(
                    bot.getUUID(), result.credentialGeneration());
            sendCredentialStatus(player, bot.getGameProfile().getName(), nonce,
                    credentialFailureStatus(result.statusCode()), false, false);
        });
    }

    private boolean allowCredentialAttempt(ServerPlayer player, UUID botId) {
        int tick = player.getServer().getTickCount();
        CredentialAttemptKey key = new CredentialAttemptKey(player.getUUID(), botId);
        ArrayDeque<Integer> attempts = credentialAttempts.computeIfAbsent(
                key, ignored -> new ArrayDeque<>());
        synchronized (attempts) {
            while (!attempts.isEmpty() && tick - attempts.peekFirst() > CREDENTIAL_ATTEMPT_WINDOW_TICKS) {
                attempts.removeFirst();
            }
            if (attempts.size() >= MAX_CREDENTIAL_ATTEMPTS_PER_WINDOW) {
                return false;
            }
            attempts.addLast(tick);
            return true;
        }
    }

    private void sendCredentialStatus(ServerPlayer player,
                                      String botName,
                                      UUID nonce,
                                      String status,
                                      boolean connected,
                                      boolean forgetLocal) {
        if (player == null || player.getServer() == null) {
            return;
        }
        ServerPlayer online = player.getServer().getPlayerList().getPlayer(player.getUUID());
        if (online != null && network.canSendToClient(online, BotAiCredentialStatusS2C.ID)) {
            network.send(online, new BotAiCredentialStatusS2C(
                    PayloadLimits.truncate(botName, PayloadLimits.BOT_NAME_LENGTH),
                    nonce,
                    status,
                    connected,
                    forgetLocal));
        }
    }

    private void notifyDisplacedPending(MinecraftServer server, PendingCredentialActor pending) {
        if (server == null || pending == null) {
            return;
        }
        ServerPlayer player = server.getPlayerList().getPlayer(pending.playerId());
        if (player != null) {
            sendCredentialStatus(player, pending.botName(), pending.nonce(),
                    BotAiCredentialStatusS2C.BUSY, false, false);
        }
    }

    private static String credentialFailureStatus(String code) {
        return switch (code) {
            case "auth_error" -> BotAiCredentialStatusS2C.INVALID_KEY;
            case "rate_limited" -> BotAiCredentialStatusS2C.RATE_LIMITED;
            case "busy" -> BotAiCredentialStatusS2C.BUSY;
            default -> BotAiCredentialStatusS2C.PROVIDER_ERROR;
        };
    }

    private record PendingCredentialActor(
            UUID playerId,
            long generation,
            String botName,
            UUID nonce
    ) {
    }

    private record CredentialAttemptKey(UUID playerId, UUID botId) {
    }

    public void handleSubscribe(ServerPlayer player, SubscribeBotC2S payload) {
        if (!payload.subscribe()) {
            subscriptions.remove(player.getUUID());
            return;
        }
        if (!PayloadLimits.validBotName(payload.botName())) {
            subscriptions.remove(player.getUUID());
            sendSystem(player, "", "无效的 Bot 名称。");
            return;
        }
        Optional<AIPlayerEntity> bot = BotAuthorizationGate.INSTANCE.resolveAuthorized(
                player, payload.botName(), BotAuthorizationPolicy.Operation.VIEW, "network:subscribe");
        if (bot.isEmpty()) {
            subscriptions.remove(player.getUUID());
            sendSystem(player, "", "找不到该 Bot 或无权限。");
            return;
        }
        AIPlayerEntity target = bot.get();
        subscriptions.put(player.getUUID(), target.getUUID());
        if (network.canSendToClient(player, BotSnapshotS2C.ID)) {
            network.send(player, snapshot(target));
        }
        sendSystem(player, target.getGameProfile().getName(), "已订阅 " + target.getGameProfile().getName());
    }

    public void handleCommand(ServerPlayer player, BotCommandC2S payload) {
        if (!PayloadLimits.validBotName(payload.botName())) {
            sendSystem(player, "", "无效的 Bot 名称。");
            return;
        }
        String action = normalizedAction(payload.action());
        if (!COMMAND_ACTIONS.contains(action)) {
            sendSystem(player, payload.botName(), "无效的命令动作。");
            return;
        }
        if (payload.count() < 1 || payload.count() > PayloadLimits.MAX_COMMAND_COUNT) {
            sendSystem(player, payload.botName(),
                    "命令数量必须在 1.." + PayloadLimits.MAX_COMMAND_COUNT + " 之间。");
            return;
        }
        if (!validCommandArguments(action, payload)) {
            sendSystem(player, payload.botName(), "命令参数为空或格式不完整。");
            return;
        }
        Optional<AIPlayerEntity> bot = BotAuthorizationGate.INSTANCE.resolveAuthorized(
                player, payload.botName(), BotAuthorizationPolicy.Operation.COMMAND, "network:command");
        if (bot.isEmpty()) {
            sendSystem(player, "", "找不到该 Bot 或无权限。");
            return;
        }
        if (rejectRecoveryControl(player, bot.get(), "执行面板命令")) {
            return;
        }
        try {
            dispatch(player, bot.get(), payload);
        } catch (RuntimeException exception) {
            BotLog.error(bot.get(), "panel_command_exception", exception, "action", payload.action());
            String reason = exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
            sendSystem(player, payload.botName(), "命令执行失败: " + reason);
        }
    }

    public void handleSetOption(ServerPlayer player, SetOptionC2S payload) {
        if (!PayloadLimits.validBotName(payload.botName()) || payload.key() == null || !OPTION_KEYS.contains(payload.key())) {
            sendSystem(player, "", "无效的 Bot 名称或设置项。");
            return;
        }
        Optional<AIPlayerEntity> bot = BotAuthorizationGate.INSTANCE.resolveAuthorized(
                player, payload.botName(), BotAuthorizationPolicy.Operation.ADMIN, "network:set_option");
        if (bot.isEmpty()) {
            sendSystem(player, "", "找不到该 Bot 或无权限。");
            return;
        }
        AIPlayerEntity target = bot.get();
        if (rejectRecoveryControl(player, target, "修改运行选项")) {
            return;
        }
        switch (payload.key()) {
            case "manual" -> BrainCoordinator.INSTANCE.setManualMode(target, payload.value());
            case "memory" -> BotRuntimeOptions.INSTANCE.setMemoryToolsEnabled(target, payload.value());
            case "reports" -> BotRuntimeOptions.INSTANCE.setVerboseReportsEnabled(target, payload.value());
            default -> throw new IllegalArgumentException("unknown_option: " + payload.key());
        }
        sendSystem(player, target.getGameProfile().getName(), "设置已更新: " + payload.key() + "=" + payload.value());
    }

    // 面板传送：server thread 内执行；授权在解析目标后、任何坐标修改前完成。
    public void handleTeleport(ServerPlayer player, BotTeleportC2S payload) {
        if (!PayloadLimits.validBotName(payload.botName())
                || (payload.direction() != BotTeleportC2S.TO_AI
                && payload.direction() != BotTeleportC2S.RECALL_AI)) {
            sendSystem(player, "", "无效的 Bot 名称或传送方向。");
            return;
        }
        Optional<AIPlayerEntity> bot = BotAuthorizationGate.INSTANCE.resolveAuthorized(
                player, payload.botName(), BotAuthorizationPolicy.Operation.TELEPORT, "network:teleport");
        if (bot.isEmpty()) {
            sendSystem(player, "", "找不到该 Bot 或无权限。");
            return;
        }
        AIPlayerEntity target = bot.get();
        if (rejectRecoveryControl(player, target, "执行传送")) {
            return;
        }
        if (!io.github.greytaiwolf.fakeaiplayer.mode.CapabilityRuntime.decide(
                target, io.github.greytaiwolf.fakeaiplayer.mode.PrivilegedCapability.MANUAL_TELEPORT,
                "network_manual_teleport").allowed()) {
            sendSystem(player, target.getGameProfile().getName(),
                    "当前运行模式禁止面板传送；请显式启用 operator/manualTeleport。");
            return;
        }
        if (payload.direction() == BotTeleportC2S.TO_AI) {
            // 玩家 → AI 附近 10 格内可站立方块。
            net.minecraft.server.level.ServerLevel world = target.serverLevel();
            io.github.greytaiwolf.fakeaiplayer.pathfinding.Standability.findNearestStandable(world, target.blockPosition(), 10, 8, 8)
                    .ifPresent(p -> player.teleportTo(world, p.getX() + 0.5D, p.getY(), p.getZ() + 0.5D,
                            java.util.Set.of(), player.getYRot(), player.getXRot(), true));
        } else if (payload.direction() == BotTeleportC2S.RECALL_AI) {
            // AI → 玩家附近 10 格内可站立方块(先停手头动作再传)。
            net.minecraft.server.level.ServerLevel world = player.serverLevel();
            io.github.greytaiwolf.fakeaiplayer.pathfinding.Standability.findNearestStandable(world, player.blockPosition(), 10, 8, 8)
                    .ifPresent(p -> {
                        target.getActionPack().stopAll();
                        target.teleportTo(world, p.getX() + 0.5D, p.getY(), p.getZ() + 0.5D,
                                java.util.Set.of(), target.getYRot(), target.getXRot(), true);
                    });
        } else {
            sendSystem(player, target.getGameProfile().getName(), "无效的传送方向。");
        }
    }

    private static boolean rejectRecoveryControl(ServerPlayer player,
                                                 AIPlayerEntity bot,
                                                 String operation) {
        if (!TaskManager.INSTANCE.hasRuntimeRecoveryLock(bot)) {
            return false;
        }
        sendSystem(
                player,
                bot.getGameProfile().getName(),
                "运行时存档处于只读恢复保护中，无法" + operation + "；修复 runtime.json 后请重启服务器。");
        return true;
    }

    public void handleOpenInventory(ServerPlayer player, OpenBotInventoryC2S payload) {
        if (payload.botName() == null || payload.botName().length() > PayloadLimits.BOT_NAME_LENGTH) {
            sendSystem(player, "", "Invalid bot inventory request.");
            return;
        }
        BotInventorySessionManager.INSTANCE.tryOpenByName(
                player, payload.botName(), "network:open_bot_inventory");
    }

    private void dispatch(ServerPlayer player, AIPlayerEntity bot, BotCommandC2S payload) {
        String action = normalizedAction(payload.action());
        switch (action) {
            case "move" -> assign(bot, new MoveTask(bot, parseBlockPos(payload.arg1())));
            case "mine" -> assign(bot, new MineTask(requiredBlock(payload.arg1()), count(payload)));
            case "craft" -> assign(bot, new CraftTask(requiredItem(payload.arg1()), count(payload)));
            case "smelt" -> assign(bot, new SmeltTask(requiredItem(payload.arg1()), requiredItem(payload.arg2()), count(payload)));
            case "eat" -> assign(bot, new EatTask());
            case "sleep" -> assign(bot, new SleepTask());
            case "abort" -> {
                IntentController.INSTANCE.cancelAll(bot, IntentController.ControlOrigin.PLAYER_PANEL, "panel_abort");
            }
            case "chat" -> {
                sendBotChat(bot, "user", payload.arg1());
                if (!IntentController.INSTANCE.routePlayerControlPhrase(
                        bot, IntentController.ControlOrigin.PLAYER_PANEL, payload.arg1())) {
                    BrainCoordinator.INSTANCE.handleMessage(bot, player.getGameProfile().getName(), payload.arg1());
                }
            }
            case "pause" -> IntentController.INSTANCE.pause(
                    bot, IntentController.ControlOrigin.PLAYER_PANEL, "panel_pause");
            case "resume" -> IntentController.INSTANCE.resume(
                    bot, IntentController.ControlOrigin.PLAYER_PANEL, "panel_resume");
            case "reset" -> {
                RuntimeLifecycleCoordinator.INSTANCE.resetBot(
                        bot, IntentController.ControlOrigin.PLAYER_PANEL, "panel_brain_reset");
                sendSystem(player, bot.getGameProfile().getName(), "大脑已重置。");
            }
            default -> throw new IllegalArgumentException("unknown_action: " + payload.action());
        }
    }

    private static void assign(AIPlayerEntity bot, Task task) {
        IntentController.ReplaceResult result = IntentController.INSTANCE.replace(
                bot,
                IntentController.ControlOrigin.PLAYER_PANEL,
                "panel_assign:" + task.name(),
                () -> TaskManager.INSTANCE.assign(
                        bot, task,
                        TaskOrigin.of(TaskOrigin.Kind.PLAYER_PANEL, "panel_assign")).started());
        if (!result.replacementStarted()) {
            BotLog.task(bot, "panel_task_deferred", "task", task.name(), "reason", "safety_work_active");
        }
    }

    private BotSnapshotS2C snapshot(AIPlayerEntity bot) {
        TaskStatus task = TaskManager.INSTANCE.status(bot);
        BrainCoordinator.BrainStatus brain = BrainCoordinator.INSTANCE.status(bot);
        BotMemory memory = BotMemoryStore.INSTANCE.of(bot.getUUID());
        ArrayList<BotSnapshotS2C.ItemEntry> inventory = new ArrayList<>();
        for (int slot = 0; slot < bot.getInventory().items.size(); slot++) {
            ItemStack stack = bot.getInventory().items.get(slot);
            if (!stack.isEmpty()) {
                inventory.add(new BotSnapshotS2C.ItemEntry(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(), stack.getCount(), slot));
            }
        }
        // UI:全身装备(头/胸/腿/脚/主手/副手),slot index 0..5,供背包面板的装备区展示。
        ArrayList<BotSnapshotS2C.ItemEntry> equipment = new ArrayList<>();
        net.minecraft.world.entity.EquipmentSlot[] equipSlots = {
                net.minecraft.world.entity.EquipmentSlot.HEAD, net.minecraft.world.entity.EquipmentSlot.CHEST,
                net.minecraft.world.entity.EquipmentSlot.LEGS, net.minecraft.world.entity.EquipmentSlot.FEET,
                net.minecraft.world.entity.EquipmentSlot.MAINHAND, net.minecraft.world.entity.EquipmentSlot.OFFHAND};
        for (int slotIndex = 0; slotIndex < equipSlots.length; slotIndex++) {
            ItemStack equipped = bot.getItemBySlot(equipSlots[slotIndex]);
            if (!equipped.isEmpty()) {
                equipment.add(new BotSnapshotS2C.ItemEntry(
                        BuiltInRegistries.ITEM.getKey(equipped.getItem()).toString(), equipped.getCount(), slotIndex));
            }
        }
        // 任务链条:优先展示 GoalExecutor 的实际确定性计划(provision_food→[砍树/做镐/挖石/造炉/打猎/烤]…),
        // 没有激活计划时才回退到大脑 set_goal 记的目标(memory)。这样面板链条与 bot 真正在执行的步骤一致。
        boolean hasPlan = GoalExecutor.INSTANCE.hasActivePlan(bot);
        String goalTitle = hasPlan ? GoalExecutor.INSTANCE.activeGoalTitle(bot) : memory.goalTitle();
        List<String> goalSteps = hasPlan ? GoalExecutor.INSTANCE.activeGoalSteps(bot) : memory.goalSteps();
        int goalIndex = hasPlan ? GoalExecutor.INSTANCE.activeGoalCurrentIndex(bot) : memory.goalCurrentStepIndex();
        int goalTotal = hasPlan ? GoalExecutor.INSTANCE.activeGoalTotalSteps(bot) : memory.goalTotalSteps();
        String goalCurrentStep = goalIndex >= 0 && goalIndex < goalSteps.size()
                ? goalSteps.get(goalIndex) : memory.currentGoalStep().orElse("");
        List<String> boundedGoalSteps = goalSteps.stream()
                .limit(PayloadLimits.MAX_GOAL_STEPS)
                .map(step -> PayloadLimits.truncate(step, PayloadLimits.GOAL_TEXT_LENGTH))
                .toList();
        var goalResult = GoalExecutor.INSTANCE.lastResult(bot).orElse(null);
        var runtimeConfig = io.github.greytaiwolf.fakeaiplayer.AIBotConfig.get();
        List<String> effectiveCapabilities = java.util.Arrays.stream(
                        io.github.greytaiwolf.fakeaiplayer.mode.PrivilegedCapability.values())
                .filter(capability -> io.github.greytaiwolf.fakeaiplayer.mode.CapabilityPolicy.decide(
                        runtimeConfig.profile(), runtimeConfig.operatorCapabilities(), capability).allowed())
                .map(Enum::name)
                .toList();
        return new BotSnapshotS2C(
                PayloadLimits.truncate(bot.getGameProfile().getName(), PayloadLimits.BOT_NAME_LENGTH),
                bot.getHealth(),
                bot.getMaxHealth(),
                bot.getFoodData().getFoodLevel(),
                bot.getBlockX(),
                bot.getBlockY(),
                bot.getBlockZ(),
                PayloadLimits.truncate(task.name(), PayloadLimits.TASK_NAME_LENGTH),
                PayloadLimits.truncate(task.state().name(), PayloadLimits.TASK_STATE_LENGTH),
                (float) task.progress(),
                brain.busy(),
                brain.promptTokens(),
                brain.completionTokens(),
                PayloadLimits.truncate(goalTitle, PayloadLimits.GOAL_TEXT_LENGTH),
                PayloadLimits.truncate(goalCurrentStep, PayloadLimits.GOAL_TEXT_LENGTH),
                goalIndex,
                goalTotal,
                boundedGoalSteps,
                goalResult == null ? 0L : goalResult.sequence(),
                goalResult == null ? "" : goalResult.status().name(),
                goalResult == null ? "" : PayloadLimits.truncate(
                        GoalExecutor.INSTANCE.resultSummary(goalResult), PayloadLimits.GOAL_RESULT_SUMMARY_LENGTH),
                goalResult == null ? 0 : goalResult.evaluation().matched(),
                goalResult == null ? 0 : goalResult.evaluation().required(),
                TaskManager.INSTANCE.isUserPaused(bot),
                TaskManager.INSTANCE.pausedDepth(bot),
                runtimeConfig.profile().configValue(),
                effectiveCapabilities,
                BrainCoordinator.INSTANCE.manualMode(bot),
                BotRuntimeOptions.INSTANCE.memoryToolsEnabled(bot),
                BotRuntimeOptions.INSTANCE.verboseReportsEnabled(bot),
                inventory,
                equipment);
    }

    private void sendSystem(ServerPlayer player, String botName, String text) {
        if (network.canSendToClient(player, BotChatS2C.ID)) {
            network.send(player, new BotChatS2C(
                    PayloadLimits.truncate(botName, PayloadLimits.BOT_NAME_LENGTH),
                    "system",
                    PayloadLimits.truncate(text, PayloadLimits.CHAT_TEXT_LENGTH)));
        }
    }

    private static int count(BotCommandC2S payload) {
        return payload.count();
    }

    private static String normalizedAction(String action) {
        return action == null ? "" : action.toLowerCase(Locale.ROOT);
    }

    private static boolean validCommandArguments(String action, BotCommandC2S payload) {
        return switch (action) {
            case "move", "mine", "craft", "chat" -> !isBlank(payload.arg1());
            case "smelt" -> !isBlank(payload.arg1()) && !isBlank(payload.arg2());
            default -> true;
        };
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static BlockPos parseBlockPos(String value) {
        String[] parts = value.trim().split("\\s+");
        if (parts.length != 3) {
            throw new IllegalArgumentException("move expects arg1='x y z'");
        }
        return new BlockPos(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), Integer.parseInt(parts[2]));
    }

    private static Block requiredBlock(String idText) {
        ResourceLocation id = ResourceLocation.parse(idText);
        return BuiltInRegistries.BLOCK.getOptional(id)
                .orElseThrow(() -> new IllegalArgumentException("unknown_block: " + id));
    }

    private static Item requiredItem(String idText) {
        ResourceLocation id = ResourceLocation.parse(idText);
        return BuiltInRegistries.ITEM.getOptional(id)
                .orElseThrow(() -> new IllegalArgumentException("unknown_item: " + id));
    }

}
