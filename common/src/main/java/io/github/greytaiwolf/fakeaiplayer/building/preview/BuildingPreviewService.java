package io.github.greytaiwolf.fakeaiplayer.building.preview;

import io.github.greytaiwolf.fakeaiplayer.auth.BotAuthorizationGate;
import io.github.greytaiwolf.fakeaiplayer.auth.BotAuthorizationPolicy;
import io.github.greytaiwolf.fakeaiplayer.building.plan.BlockStateResolver;
import io.github.greytaiwolf.fakeaiplayer.building.plan.BlockStateSpec;
import io.github.greytaiwolf.fakeaiplayer.building.plan.BuildingPlan;
import io.github.greytaiwolf.fakeaiplayer.building.plan.BuildingPlanBlueprintAdapter;
import io.github.greytaiwolf.fakeaiplayer.building.plan.BuildingPlanFingerprint;
import io.github.greytaiwolf.fakeaiplayer.building.plan.BuildingPlanOrder;
import io.github.greytaiwolf.fakeaiplayer.building.plan.BuildingPlanValidator;
import io.github.greytaiwolf.fakeaiplayer.building.plan.BuildingSupportContract;
import io.github.greytaiwolf.fakeaiplayer.building.plan.CellOperation;
import io.github.greytaiwolf.fakeaiplayer.building.plan.PlanPlacement;
import io.github.greytaiwolf.fakeaiplayer.building.plan.PlanTransform;
import io.github.greytaiwolf.fakeaiplayer.building.plan.ReplacePolicy;
import io.github.greytaiwolf.fakeaiplayer.brain.BrainCoordinator;
import io.github.greytaiwolf.fakeaiplayer.entity.AIPlayerEntity;
import io.github.greytaiwolf.fakeaiplayer.goal.Goal;
import io.github.greytaiwolf.fakeaiplayer.goal.GoalExecutor;
import io.github.greytaiwolf.fakeaiplayer.goal.GoalPlanner;
import io.github.greytaiwolf.fakeaiplayer.goal.GoalSnapshotCollector;
import io.github.greytaiwolf.fakeaiplayer.log.BotLog;
import io.github.greytaiwolf.fakeaiplayer.manager.AIPlayerManager;
import io.github.greytaiwolf.fakeaiplayer.network.ServerNetworkTransport;
import io.github.greytaiwolf.fakeaiplayer.network.payload.BuildingPreviewBeginS2C;
import io.github.greytaiwolf.fakeaiplayer.network.payload.BuildingPreviewCancelC2S;
import io.github.greytaiwolf.fakeaiplayer.network.payload.BuildingPreviewChunkS2C;
import io.github.greytaiwolf.fakeaiplayer.network.payload.BuildingPreviewClearS2C;
import io.github.greytaiwolf.fakeaiplayer.network.payload.BuildingPreviewCommitS2C;
import io.github.greytaiwolf.fakeaiplayer.network.payload.BuildingPreviewConfirmC2S;
import io.github.greytaiwolf.fakeaiplayer.network.payload.BuildingPreviewReadyC2S;
import io.github.greytaiwolf.fakeaiplayer.network.payload.PayloadLimits;
import io.github.greytaiwolf.fakeaiplayer.runtime.IntentController;
import io.github.greytaiwolf.fakeaiplayer.task.BlueprintLoader;
import io.github.greytaiwolf.fakeaiplayer.task.BlueprintSchema;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/** Server authority for draft publication, projection lifetime, and exact confirmation. */
public final class BuildingPreviewService {
    public static final BuildingPreviewService INSTANCE = new BuildingPreviewService();

    private static final int SESSION_TTL_TICKS = 20 * 300;
    private static final double MAX_CONFIRM_DISTANCE_SQUARED = 128.0D * 128.0D;
    private static final int CHUNK_SIZE = PayloadLimits.MAX_PREVIEW_CHUNK_CELLS;
    private static final int MAX_TRANSFER_CHUNKS_PER_TICK = 8;
    private static final int MAX_CONFIRM_VALIDATION_CELLS_PER_VIEWER_TICK = 1_024;
    private static final int MAX_CONFIRM_VALIDATION_CELLS_GLOBAL_TICK = 4_096;
    private static final int CONFIRM_RETRY_COOLDOWN_TICKS = 100;
    private static final int MAX_REPORTED_CONFLICTS = 8;

    private final Map<UUID, Session> sessionsByViewer = new ConcurrentHashMap<>();
    private final Map<UUID, BuildingPreviewTransfer> transfersByViewer = new ConcurrentHashMap<>();
    private final Map<UUID, ConfirmationValidation> confirmationsByViewer = new ConcurrentHashMap<>();
    private final Map<UUID, ConfirmationAttemptWindow> confirmationAttemptsByViewer =
            new ConcurrentHashMap<>();
    private int confirmationFairCursor;
    private volatile ServerNetworkTransport network = NoNetwork.INSTANCE;

    private BuildingPreviewService() {
    }

    public void configure(ServerNetworkTransport transport) {
        network = Objects.requireNonNull(transport, "transport");
    }

    public OpenResult open(ServerPlayer viewer,
                           AIPlayerEntity bot,
                           BuildingPlan plan,
                           BlockPos anchor,
                           PlanTransform transform) {
        return open(viewer, bot, plan, anchor, transform, false);
    }

    /**
     * Opens a preview and records whether this exact session is holding an AI conversation at
     * the human-confirmation gate. Command-created previews use the public five-argument overload.
     */
    public OpenResult open(ServerPlayer viewer,
                           AIPlayerEntity bot,
                           BuildingPlan plan,
                           BlockPos anchor,
                           PlanTransform transform,
                           boolean conversationLinked) {
        Objects.requireNonNull(viewer, "viewer");
        Objects.requireNonNull(bot, "bot");
        Objects.requireNonNull(anchor, "anchor");
        if (!BotAuthorizationGate.INSTANCE.authorize(
                viewer, bot, BotAuthorizationPolicy.Operation.COMMAND, "building_preview:open")) {
            return OpenResult.failed("找不到该 Bot 或无权限。");
        }
        if (viewer.serverLevel() != bot.serverLevel()) {
            return OpenResult.failed("玩家、Bot 与建筑预览必须位于同一维度。");
        }
        if (!supportsPreview(viewer)) {
            return OpenResult.failed("客户端未启用 FakeAiPlayer 建筑投影协议。");
        }
        BuildingPlanValidator.ValidationResult validation = BuildingPlanValidator.validateForExecution(plan);
        if (!validation.valid()) {
            BuildingPlanValidator.Problem problem = validation.problems().get(0);
            return OpenResult.failed("建筑方案无效: " + problem.code() + " (" + problem.detail() + ")");
        }
        if (plan.placements().size() > PayloadLimits.MAX_PREVIEW_PLACEMENTS) {
            return OpenResult.failed("当前投影上限为 " + PayloadLimits.MAX_PREVIEW_PLACEMENTS + " 个单元。");
        }
        if (plan.placements().stream().anyMatch(cell -> cell.operation() == CellOperation.TEMPORARY)) {
            return OpenResult.failed("当前执行器尚不支持需要清理的 TEMPORARY 单元。");
        }
        PlanTransform normalized = transform == null ? PlanTransform.IDENTITY : transform;
        PreparedPreview prepared;
        try {
            prepared = prepare(plan, normalized);
        } catch (IllegalArgumentException exception) {
            return OpenResult.failed("投影编译失败: " + exception.getMessage());
        }
        if (prepared.palette().size() > PayloadLimits.MAX_PREVIEW_PALETTE) {
            return OpenResult.failed("投影调色板超过 " + PayloadLimits.MAX_PREVIEW_PALETTE + " 种方块状态。");
        }

        UUID sessionId = UUID.randomUUID();
        String hash = BuildingPlanFingerprint.sha256(plan);
        String dimension = viewer.serverLevel().dimension().location().toString();
        String previewHash = previewHash(
                sessionId, bot.getUUID(), plan, hash, 0, dimension, anchor, prepared);
        int now = viewer.getServer().getTickCount();
        Session session = new Session(
                sessionId,
                viewer.getUUID(),
                bot.getUUID(),
                plan,
                hash,
                previewHash,
                dimension,
                anchor.immutable(),
                normalized,
                0,
                now + SESSION_TTL_TICKS,
                prepared,
                conversationLinked,
                -1);
        try {
            beginPayload(bot, session);
        } catch (IllegalArgumentException exception) {
            return OpenResult.failed("投影网络编码超出安全限制: " + exception.getMessage());
        }
        Session previous = sessionsByViewer.put(viewer.getUUID(), session);
        confirmationsByViewer.remove(viewer.getUUID());
        confirmationAttemptsByViewer.remove(viewer.getUUID());
        if (previous != null) {
            cancelTransfer(viewer.getUUID(), previous.sessionId());
            releaseExternalWaitIfNoSession(previous);
            sendClear(viewer, previous.sessionId(), "replaced");
        }
        try {
            publish(viewer, bot, session);
        } catch (RuntimeException exception) {
            cancelTransfer(viewer.getUUID(), session.sessionId());
            if (sessionsByViewer.remove(viewer.getUUID(), session)) {
                releaseExternalWaitIfNoSession(session);
            }
            // Begin and some chunks may already have reached the client. Clear both pending and
            // active state for this exact session even though publication did not reach Commit.
            sendClear(viewer, session.sessionId(), "publish_failed");
            BotLog.error("building_preview_publish_failed", exception,
                    "viewer_id", viewer.getUUID(), "session_id", sessionId);
            return OpenResult.failed("建筑投影发送失败，请重试。");
        }
        return OpenResult.opened(sessionId, hash, plan.placements().size(), prepared.palette().size());
    }

    public ConfirmResult confirmLatest(ServerPlayer player) {
        Session session = sessionsByViewer.get(player.getUUID());
        if (session == null) {
            return ConfirmResult.failed("没有可确认的建筑投影。");
        }
        if (session.acknowledgedTransformRevision() != session.transformRevision()) {
            return ConfirmResult.failed("客户端尚未完整显示当前投影，请稍候或使用投影确认按键。");
        }
        return requestConfirmation(player, session, IntentController.ControlOrigin.PLAYER_COMMAND);
    }

    public void handleReady(ServerPlayer player, BuildingPreviewReadyC2S payload) {
        Session current = sessionsByViewer.get(player.getUUID());
        if (current == null
                || !current.sessionId().equals(payload.sessionId())
                || !current.previewHash().equals(payload.previewHash())
                || current.transformRevision() != payload.transformRevision()) {
            return;
        }
        BuildingPreviewTransfer transfer = transfersByViewer.get(player.getUUID());
        if (transfer != null && transfer.matches(
                payload.sessionId(), payload.previewHash(), payload.transformRevision())) {
            // A Ready received before the authoritative Commit cannot acknowledge a partial
            // staging buffer, even if a modified client guesses the tuple from Begin.
            return;
        }
        Session acknowledged = new Session(
                current.sessionId(), current.viewerId(), current.botId(), current.plan(),
                current.planHash(), current.previewHash(), current.dimension(), current.anchor(), current.transform(),
                current.transformRevision(), current.expiresAtTick(), current.prepared(),
                current.conversationLinked(),
                current.transformRevision());
        sessionsByViewer.replace(player.getUUID(), current, acknowledged);
    }

    public ConfirmResult handleConfirm(ServerPlayer player, BuildingPreviewConfirmC2S payload) {
        Session session = sessionsByViewer.get(player.getUUID());
        if (session == null
                || !session.sessionId().equals(payload.sessionId())
                || !session.previewHash().equals(payload.previewHash())
                || session.transformRevision() != payload.transformRevision()) {
            return fail(player, "预览已变化或确认数据过期，请重新查看后再确认。");
        }
        if (session.acknowledgedTransformRevision() != session.transformRevision()) {
            return fail(player, "客户端尚未完成当前投影的摘要校验，请等待投影完全显示后再确认。");
        }
        return requestConfirmation(player, session, IntentController.ControlOrigin.PLAYER_PANEL);
    }

    public boolean cancelLatest(ServerPlayer player, String reason) {
        Session session = sessionsByViewer.remove(player.getUUID());
        if (session == null) {
            return false;
        }
        cancelTransfer(player.getUUID(), session.sessionId());
        confirmationsByViewer.remove(player.getUUID());
        confirmationAttemptsByViewer.remove(player.getUUID());
        releaseExternalWaitIfNoSession(session);
        sendClear(player, session.sessionId(), reason == null ? "cancelled" : reason);
        return true;
    }

    public void handleCancel(ServerPlayer player, BuildingPreviewCancelC2S payload) {
        Session session = sessionsByViewer.get(player.getUUID());
        if (session != null && session.sessionId().equals(payload.sessionId())) {
            cancelLatest(player, "cancelled");
        }
    }

    public Optional<SessionView> session(ServerPlayer player) {
        Session value = sessionsByViewer.get(player.getUUID());
        return value == null ? Optional.empty() : Optional.of(value.view());
    }

    /** Exact AI-authored session token consumed by {@link BrainCoordinator}. */
    public Optional<UUID> linkedSessionForBot(UUID botId) {
        if (botId == null) {
            return Optional.empty();
        }
        return sessionsByViewer.values().stream()
                .filter(Session::conversationLinked)
                .filter(session -> session.botId().equals(botId))
                .map(Session::sessionId)
                .findFirst();
    }

    public UpdateResult moveLatest(ServerPlayer player, BlockPos anchor) {
        Objects.requireNonNull(anchor, "anchor");
        Session session = sessionsByViewer.get(player.getUUID());
        if (session == null) {
            return UpdateResult.failed("没有可移动的建筑投影。");
        }
        if (isSiteLocked(session.plan())) {
            return UpdateResult.failed("该方案已绑定地形调查坐标，不能移动；请在新位置重新调查并生成。");
        }
        return update(player, session, anchor.immutable(), session.transform());
    }

    public UpdateResult rotateLatest(ServerPlayer player, Rotation rotation) {
        Session session = sessionsByViewer.get(player.getUUID());
        if (session == null) {
            return UpdateResult.failed("没有可旋转的建筑投影。");
        }
        if (isSiteLocked(session.plan())) {
            return UpdateResult.failed("该方案已绑定地形调查坐标，不能旋转；请按新朝向重新调查并生成。");
        }
        PlanTransform transform = new PlanTransform(session.transform().mirror(), rotation);
        int oldWidth = session.prepared().width();
        int oldDepth = session.prepared().depth();
        int newWidth = transform.transformedWidth(session.plan().width(), session.plan().depth());
        int newDepth = transform.transformedDepth(session.plan().width(), session.plan().depth());
        BlockPos recentered = session.anchor().offset(
                oldWidth / 2 - newWidth / 2,
                0,
                oldDepth / 2 - newDepth / 2);
        return update(player, session, recentered, transform);
    }

    public void tick(MinecraftServer server) {
        int now = server.getTickCount();
        for (Map.Entry<UUID, Session> entry : sessionsByViewer.entrySet()) {
            Session session = entry.getValue();
            if (now <= session.expiresAtTick()) {
                continue;
            }
            if (sessionsByViewer.remove(entry.getKey(), session)) {
                cancelTransfer(entry.getKey(), session.sessionId());
                confirmationsByViewer.remove(entry.getKey());
                confirmationAttemptsByViewer.remove(entry.getKey());
                releaseExternalWaitIfNoSession(session);
                ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
                if (player != null) {
                    sendClear(player, session.sessionId(), "expired");
                    player.sendSystemMessage(Component.literal("[FakeAiPlayer] 建筑投影已过期。"));
                }
            }
        }
        tickTransfers(server);
        tickConfirmations(server);
    }

    public void onDisconnect(ServerPlayer player) {
        Session removed = sessionsByViewer.remove(player.getUUID());
        transfersByViewer.remove(player.getUUID());
        confirmationsByViewer.remove(player.getUUID());
        confirmationAttemptsByViewer.remove(player.getUUID());
        if (removed != null) {
            releaseExternalWaitIfNoSession(removed);
        }
    }

    public void clear(MinecraftServer server) {
        List<Map.Entry<UUID, Session>> removed = List.copyOf(sessionsByViewer.entrySet());
        sessionsByViewer.clear();
        transfersByViewer.clear();
        confirmationsByViewer.clear();
        confirmationAttemptsByViewer.clear();
        confirmationFairCursor = 0;
        for (Map.Entry<UUID, Session> entry : removed) {
            releaseExternalWaitIfNoSession(entry.getValue());
            ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
            if (player != null) {
                sendClear(player, entry.getValue().sessionId(), "server_stopping");
            }
        }
    }

    private ConfirmResult requestConfirmation(ServerPlayer player,
                                              Session session,
                                              IntentController.ControlOrigin controlOrigin) {
        MinecraftServer server = player.getServer();
        if (server.getTickCount() > session.expiresAtTick()) {
            return rejectAndClear(player, session, "建筑投影已过期，请重新生成。");
        }
        Optional<AIPlayerEntity> botOptional = AIPlayerManager.INSTANCE.getByUuid(session.botId());
        if (botOptional.isEmpty()) {
            return fail(player, "Bot 已离线；投影会保留到过期，Bot 上线后可重试。");
        }
        AIPlayerEntity bot = botOptional.get();
        if (!BotAuthorizationGate.INSTANCE.authorize(
                player, bot, BotAuthorizationPolicy.Operation.COMMAND, "building_preview:confirm")) {
            return rejectAndClear(player, session, "找不到该 Bot 或无权限。");
        }
        ServerLevel world = player.serverLevel();
        if (world != bot.serverLevel()
                || !world.dimension().location().toString().equals(session.dimension())) {
            return fail(player, "玩家、Bot 或投影所在维度已经变化。");
        }
        if (player.distanceToSqr(Vec3.atCenterOf(session.anchor())) > MAX_CONFIRM_DISTANCE_SQUARED) {
            return fail(player, "距离建筑锚点超过 128 格。");
        }

        UUID viewerId = player.getUUID();
        ConfirmationValidation active = confirmationsByViewer.get(viewerId);
        if (active != null && active.matches(session)) {
            return ConfirmResult.accepted("建筑确认校验正在分批进行，请稍候。");
        }
        int now = server.getTickCount();
        ConfirmationAttemptWindow attempt = confirmationAttemptsByViewer.get(viewerId);
        if (attempt != null && attempt.sessionId().equals(session.sessionId())
                && now < attempt.nextAllowedTick()) {
            // Do not emit a chat line for every repeated C2S packet. The first accepted request
            // already tells the player that validation is in progress.
            return ConfirmResult.failed("建筑确认重试过快，请稍候。");
        }
        ConfirmationValidation validation = new ConfirmationValidation(session, controlOrigin);
        confirmationsByViewer.put(viewerId, validation);
        confirmationAttemptsByViewer.put(viewerId, new ConfirmationAttemptWindow(
                session.sessionId(), now + CONFIRM_RETRY_COOLDOWN_TICKS));
        String message = "已开始分批校验建筑投影；校验期间不会修改世界。";
        player.sendSystemMessage(Component.literal("[FakeAiPlayer] " + message));
        return ConfirmResult.accepted(message);
    }

    private ConfirmResult finishConfirmation(ServerPlayer player,
                                             Session session,
                                             IntentController.ControlOrigin controlOrigin,
                                             WorldValidation worldValidation) {
        if (sessionsByViewer.get(player.getUUID()) != session) {
            return ConfirmResult.failed("建筑投影已变化，已丢弃旧校验结果。");
        }
        MinecraftServer server = player.getServer();
        if (server.getTickCount() > session.expiresAtTick()) {
            return rejectAndClear(player, session, "建筑投影已过期，请重新生成。");
        }
        Optional<AIPlayerEntity> botOptional = AIPlayerManager.INSTANCE.getByUuid(session.botId());
        if (botOptional.isEmpty()) {
            return fail(player, "Bot 已离线；投影会保留到过期，Bot 上线后可重试。");
        }
        AIPlayerEntity bot = botOptional.get();
        if (!BotAuthorizationGate.INSTANCE.authorize(
                player, bot, BotAuthorizationPolicy.Operation.COMMAND, "building_preview:confirm_finish")) {
            return rejectAndClear(player, session, "找不到该 Bot 或无权限。");
        }
        ServerLevel world = player.serverLevel();
        if (world != bot.serverLevel()
                || !world.dimension().location().toString().equals(session.dimension())) {
            return fail(player, "玩家、Bot 或投影所在维度已经变化。");
        }
        if (player.distanceToSqr(Vec3.atCenterOf(session.anchor())) > MAX_CONFIRM_DISTANCE_SQUARED) {
            return fail(player, "距离建筑锚点超过 128 格。");
        }
        if (!worldValidation.valid()) {
            return fail(player, worldValidation.message());
        }
        String currentHash = BuildingPlanFingerprint.sha256(session.plan());
        if (!currentHash.equals(session.planHash())) {
            return rejectAndClear(player, session, "服务端建筑方案哈希发生变化。");
        }
        BuildingPlanValidator.ValidationResult validation = BuildingPlanValidator.validateForExecution(session.plan());
        if (!validation.valid()) {
            return rejectAndClear(player, session, "建筑方案已失效: " + validation.problems().get(0).code());
        }
        String currentPreviewHash = previewHash(
                session.sessionId(), session.botId(), session.plan(), session.planHash(),
                session.transformRevision(), session.dimension(), session.anchor(), session.prepared());
        if (!currentPreviewHash.equals(session.previewHash())) {
            return rejectAndClear(player, session, "服务端投影摘要发生变化。");
        }

        BlueprintSchema blueprint;
        try {
            blueprint = BuildingPlanBlueprintAdapter.adapt(session.plan(), session.transform());
        } catch (IllegalArgumentException exception) {
            return rejectAndClear(player, session, "建筑执行编译失败: " + exception.getMessage());
        }
        String parityProblem = executionParityProblem(session.prepared(), blueprint);
        if (parityProblem != null) {
            return rejectAndClear(player, session, "投影与执行蓝图不一致: " + parityProblem);
        }
        String generatedName = generatedBlueprintName(
                session.plan(), session.sessionId(), session.transformRevision());
        String blueprintDigest;
        try {
            blueprintDigest = BlueprintLoader.canonicalDigest(blueprint);
        } catch (IOException exception) {
            return rejectAndClear(player, session,
                    "建筑执行蓝图无法生成规范摘要: " + exception.getMessage());
        }
        Goal.Build buildGoal = new Goal.Build(
                generatedName, session.anchor(), session.dimension(), blueprintDigest);
        GoalSnapshotCollector.Context planningContext = new GoalSnapshotCollector.Context(
                session.anchor(), Set.of(), blueprint, session.anchor(), 0, 0);
        GoalPlanner.GoalPlan materialPlan = GoalPlanner.plan(bot, buildGoal, planningContext);
        if (!materialPlan.success()) {
            return fail(player, "材料规划未闭合，未打断 Bot 当前任务: "
                    + String.join(",", materialPlan.unresolved()));
        }
        try {
            BlueprintLoader.saveGenerated(generatedName, blueprint);
            // Do not trust a successful filesystem write alone. Re-open through the normal,
            // bounded loader and bind the mission only after canonical content verification.
            BlueprintLoader.loadVerified(generatedName, blueprintDigest);
        } catch (IOException exception) {
            return fail(player, "保存已确认建筑方案失败，未启动任务: " + exception.getMessage());
        }
        IntentController.ReplaceResult result = IntentController.INSTANCE.replace(
                bot,
                controlOrigin,
                "building_preview_confirmed",
                () -> GoalExecutor.INSTANCE.submit(
                        bot,
                        buildGoal,
                        io.github.greytaiwolf.fakeaiplayer.mission.GoalSpec.Source.PLAYER_CONFIRMED));
        if (!result.replacementStarted()) {
            return fail(player, "建造任务未能启动；投影已保留，可排查材料规划后重试。");
        }
        boolean removed = sessionsByViewer.remove(player.getUUID(), session);
        cancelTransfer(player.getUUID(), session.sessionId());
        confirmationsByViewer.remove(player.getUUID());
        confirmationAttemptsByViewer.remove(player.getUUID());
        BrainCoordinator.INSTANCE.markExternalWorkStarted(bot, session.sessionId());
        if (removed) {
            sendClear(player, session.sessionId(), "queued");
        } else {
            BotLog.lifecycle("building_preview_confirmed_after_session_changed",
                    "viewer_id", player.getUUID(), "session_id", session.sessionId());
        }
        String message = "已确认方案 " + session.plan().name() + "，Bot 将先自动备料，再按阶段建造。";
        player.sendSystemMessage(Component.literal("[FakeAiPlayer] " + message));
        return ConfirmResult.confirmed(message);
    }

    private UpdateResult update(ServerPlayer player,
                                Session current,
                                BlockPos anchor,
                                PlanTransform transform) {
        Optional<AIPlayerEntity> botOptional = AIPlayerManager.INSTANCE.getByUuid(current.botId());
        if (botOptional.isEmpty()) {
            return UpdateResult.failed("Bot 已离线。");
        }
        AIPlayerEntity bot = botOptional.get();
        if (!BotAuthorizationGate.INSTANCE.authorize(
                player, bot, BotAuthorizationPolicy.Operation.COMMAND, "building_preview:update")) {
            return UpdateResult.failed("找不到该 Bot 或无权限。");
        }
        if (player.serverLevel() != bot.serverLevel()
                || !player.serverLevel().dimension().location().toString().equals(current.dimension())) {
            return UpdateResult.failed("玩家、Bot 与投影必须位于同一维度。");
        }
        if (isSiteLocked(current.plan())) {
            // Defense in depth for future transform entry points: terrain-adapted foundations and
            // stilts contain surveyed absolute assumptions and cannot follow an arbitrary anchor.
            return UpdateResult.failed("该方案已绑定地形调查坐标；请重新调查，而不是变换现有投影。");
        }
        PreparedPreview prepared;
        try {
            prepared = prepare(current.plan(), transform);
        } catch (IllegalArgumentException exception) {
            return UpdateResult.failed("投影变换失败: " + exception.getMessage());
        }
        int nextRevision = current.transformRevision() + 1;
        String nextPreviewHash = previewHash(
                current.sessionId(), current.botId(), current.plan(), current.planHash(),
                nextRevision, current.dimension(), anchor, prepared);
        Session updated = new Session(
                current.sessionId(),
                current.viewerId(),
                current.botId(),
                current.plan(),
                current.planHash(),
                nextPreviewHash,
                current.dimension(),
                anchor,
                transform,
                nextRevision,
                player.getServer().getTickCount() + SESSION_TTL_TICKS,
                prepared,
                current.conversationLinked(),
                -1);
        try {
            beginPayload(bot, updated);
        } catch (IllegalArgumentException exception) {
            return UpdateResult.failed("投影网络编码超出安全限制: " + exception.getMessage());
        }
        if (!sessionsByViewer.replace(player.getUUID(), current, updated)) {
            return UpdateResult.failed("投影已被另一个操作更新，请重试。");
        }
        confirmationsByViewer.remove(player.getUUID());
        confirmationAttemptsByViewer.remove(player.getUUID());
        try {
            publish(player, bot, updated);
        } catch (RuntimeException exception) {
            cancelTransfer(player.getUUID(), updated.sessionId());
            if (sessionsByViewer.remove(player.getUUID(), updated)) {
                releaseExternalWaitIfNoSession(updated);
            }
            sendClear(player, updated.sessionId(), "update_publish_failed");
            BotLog.error("building_preview_update_publish_failed", exception,
                    "viewer_id", player.getUUID(), "session_id", updated.sessionId());
            return UpdateResult.failed("投影更新发送失败，请重新生成。");
        }
        return UpdateResult.updated(updated.view());
    }

    static boolean isSiteLocked(BuildingPlan plan) {
        return plan != null && "true".equals(plan.metadata().get("site_locked"));
    }

    static String generatedBlueprintName(BuildingPlan plan, UUID sessionId, int transformRevision) {
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(sessionId, "sessionId");
        String buildingCode = sanitizedBuildingCode(plan.metadata().get("building_code"));
        String codeSegment = buildingCode.isEmpty() ? "" : "b" + buildingCode + "_";
        return "generated_"
                + codeSegment
                + sessionId.toString().replace("-", "")
                + "_r" + transformRevision;
    }

    private static String sanitizedBuildingCode(String candidate) {
        if (candidate == null || candidate.isEmpty()) {
            return "";
        }
        StringBuilder digits = new StringBuilder(Math.min(candidate.length(), 32));
        for (int index = 0; index < candidate.length() && digits.length() < 32; index++) {
            char character = candidate.charAt(index);
            if (character >= '0' && character <= '9') {
                digits.append(character);
            }
        }
        return digits.toString();
    }

    private ConfirmResult rejectAndClear(ServerPlayer player, Session session, String message) {
        if (session != null) {
            if (sessionsByViewer.remove(player.getUUID(), session)) {
                cancelTransfer(player.getUUID(), session.sessionId());
                confirmationsByViewer.remove(player.getUUID());
                confirmationAttemptsByViewer.remove(player.getUUID());
                releaseExternalWaitIfNoSession(session);
                sendClear(player, session.sessionId(), "rejected: " + message);
            }
        }
        player.sendSystemMessage(Component.literal("[FakeAiPlayer] " + message));
        return ConfirmResult.failed(message);
    }

    /**
     * A bot may have previews open for more than one viewer. Only release the AI
     * conversation after its last outstanding human confirmation has gone away.
     */
    private void releaseExternalWaitIfNoSession(Session removed) {
        if (removed.conversationLinked()) {
            BrainCoordinator.INSTANCE.clearExternalConfirmationWait(
                    removed.botId(), removed.sessionId());
        }
    }

    private ConfirmResult fail(ServerPlayer player, String message) {
        player.sendSystemMessage(Component.literal("[FakeAiPlayer] " + message));
        return ConfirmResult.failed(message);
    }

    /** Advances all confirmation scans under one global main-thread world-read budget. */
    private void tickConfirmations(MinecraftServer server) {
        int remaining = MAX_CONFIRM_VALIDATION_CELLS_GLOBAL_TICK;
        List<Map.Entry<UUID, ConfirmationValidation>> pending =
                new ArrayList<>(confirmationsByViewer.entrySet());
        pending.sort(Comparator.comparing(entry -> entry.getKey().toString()));
        if (pending.isEmpty()) {
            confirmationFairCursor = 0;
            return;
        }
        int start = Math.floorMod(confirmationFairCursor, pending.size());
        confirmationFairCursor = (start + 1) % pending.size();
        for (int offset = 0; offset < pending.size(); offset++) {
            if (remaining <= 0) {
                break;
            }
            Map.Entry<UUID, ConfirmationValidation> entry =
                    pending.get((start + offset) % pending.size());
            UUID viewerId = entry.getKey();
            ConfirmationValidation validation = entry.getValue();
            Session session = sessionsByViewer.get(viewerId);
            ServerPlayer player = server.getPlayerList().getPlayer(viewerId);
            if (player == null || session == null || !validation.matches(session)) {
                confirmationsByViewer.remove(viewerId, validation);
                continue;
            }
            if (player.serverLevel().dimension().location().toString()
                    .equals(session.dimension())) {
                try {
                    int allowance = Math.min(
                            remaining, MAX_CONFIRM_VALIDATION_CELLS_PER_VIEWER_TICK);
                    int consumed = validation.advance(player.serverLevel(), allowance);
                    remaining -= consumed;
                    if (validation.complete()
                            && confirmationsByViewer.remove(viewerId, validation)) {
                        finishConfirmation(
                                player, session, validation.controlOrigin(), validation.result());
                    }
                } catch (RuntimeException exception) {
                    if (confirmationsByViewer.remove(viewerId, validation)) {
                        player.sendSystemMessage(Component.literal(
                                "[FakeAiPlayer] 建筑确认校验失败，请重试。"));
                        BotLog.error("building_confirmation_validation_failed", exception,
                                "viewer_id", viewerId,
                                "session_id", session.sessionId(),
                                "phase", validation.phaseName());
                    }
                }
            } else if (confirmationsByViewer.remove(viewerId, validation)) {
                player.sendSystemMessage(Component.literal(
                        "[FakeAiPlayer] 玩家维度已变化，建筑确认校验已取消。"));
            }
        }
    }

    private static BlockPos worldPos(Session session, PlanPlacement placement) {
        BlockPos local = session.transform().apply(
                new BlockPos(placement.dx(), placement.dy(), placement.dz()),
                session.plan().width(),
                session.plan().depth());
        return session.anchor().offset(local);
    }

    private static boolean inspectable(ServerLevel world, BlockPos pos) {
        return pos.getY() >= world.getMinY()
                && pos.getY() < world.getMinY() + world.getHeight()
                && world.getWorldBorder().isWithinBounds(pos)
                && world.hasChunkAt(pos);
    }

    private static boolean alreadySatisfied(BlockState actual,
                                            BlockStateSpec expected,
                                            CellOperation operation) {
        return operation == CellOperation.CLEAR
                ? actual.isAir()
                : BlockStateResolver.matches(actual, expected);
    }

    private static String validateCell(ServerLevel world,
                                       BlockPos pos,
                                       BlockStateSpec expected,
                                       PlanPlacement placement,
                                       Map<String, PlanPlacement> placementsById) {
        if (pos.getY() < world.getMinY()
                || pos.getY() >= world.getMinY() + world.getHeight()) {
            return "超出建筑高度";
        }
        if (!world.getWorldBorder().isWithinBounds(pos)) {
            return "超出世界边界";
        }
        if (!world.hasChunkAt(pos)) {
            return "区块未加载";
        }
        if (BuildingSupportContract.requiresExternalSupport(placement, placementsById)) {
            BlockPos supportPos = pos.below();
            if (!inspectable(world, supportPos)) {
                return "地基下方不可检查";
            }
            BlockState support = world.getBlockState(supportPos);
            if (!support.getFluidState().isEmpty()
                    || !support.isFaceSturdy(world, supportPos, Direction.UP)) {
                return "地基下方没有稳固干燥支撑";
            }
        }
        BlockState actual = world.getBlockState(pos);
        if (placement.operation() == CellOperation.PRESERVE) {
            return BlockStateResolver.matches(actual, expected) ? null : "保留条件不匹配";
        }
        if (placement.operation() == CellOperation.TEMPORARY) {
            return "当前执行器不支持临时单元";
        }
        if (placement.operation() == CellOperation.CLEAR && actual.isAir()) {
            return null;
        }
        if (placement.operation() == CellOperation.PLACE && BlockStateResolver.matches(actual, expected)) {
            return null;
        }
        if (placement.replacePolicy() == ReplacePolicy.FORCE_AUTHORIZED) {
            return "拒绝 FORCE_AUTHORIZED";
        }
        if (world.getBlockEntity(pos) != null) {
            return "不会覆盖方块实体";
        }
        return switch (placement.replacePolicy()) {
            case REQUIRE_EMPTY -> actual.isAir() ? null : "目标必须为空";
            case REPLACE_REPLACEABLE -> actual.isAir()
                    || actual.canBeReplaced() && actual.getFluidState().isEmpty()
                    ? null : "目标不可替换";
            case REPLACE_NATURAL -> actual.isAir()
                    || actual.canBeReplaced() && actual.getFluidState().isEmpty()
                    ? null : isNatural(actual)
                            ? "当前执行器尚不能自动挖除自然实体方块，请移动投影或先整地"
                            : "仅允许替换自然地形";
            case CLEAR_AUTHORIZED -> actual.getDestroySpeed(world, pos) >= 0.0F
                    ? null : "目标不可破坏";
            case PRESERVE_EXISTING -> BlockStateResolver.matches(actual, expected)
                    ? null : "保留条件不匹配";
            case FORCE_AUTHORIZED -> "拒绝 FORCE_AUTHORIZED";
        };
    }

    private static boolean isNatural(BlockState state) {
        return state.isAir()
                || state.canBeReplaced()
                || state.is(BlockTags.DIRT)
                || state.is(BlockTags.SAND)
                || state.is(BlockTags.SNOW)
                || state.is(BlockTags.BASE_STONE_OVERWORLD)
                || state.is(BlockTags.BASE_STONE_NETHER)
                || state.is(BlockTags.LEAVES)
                || state.is(BlockTags.LOGS);
    }

    private PreparedPreview prepare(BuildingPlan plan, PlanTransform transform) {
        Map<BlockStateSpec, Integer> paletteIndexes = new LinkedHashMap<>();
        List<BuildingPreviewChunkS2C.Cell> cells = new ArrayList<>(plan.placements().size());
        for (PlanPlacement placement : BuildingPlanOrder.stableTopological(plan)) {
            BlockPos local = transform.apply(
                    new BlockPos(placement.dx(), placement.dy(), placement.dz()),
                    plan.width(),
                    plan.depth());
            BlockStateSpec state = transform.apply(placement.state());
            int paletteIndex = paletteIndexes.computeIfAbsent(state, ignored -> paletteIndexes.size());
            cells.add(new BuildingPreviewChunkS2C.Cell(
                    local.getX(), local.getY(), local.getZ(), paletteIndex,
                    placement.operation(), placement.replacePolicy(), placement.phase()));
        }
        return new PreparedPreview(
                List.copyOf(paletteIndexes.keySet()),
                List.copyOf(cells),
                transform.transformedWidth(plan.width(), plan.depth()),
                plan.height(),
                transform.transformedDepth(plan.width(), plan.depth()));
    }

    private static String previewHash(UUID sessionId,
                                      UUID botId,
                                      BuildingPlan plan,
                                      String planHash,
                                      int transformRevision,
                                      String dimension,
                                      BlockPos anchor,
                                      PreparedPreview prepared) {
        return BuildingPreviewFingerprint.sha256(
                sessionId,
                botId,
                PayloadLimits.truncate(plan.planId(), PayloadLimits.PREVIEW_PLAN_ID_LENGTH),
                plan.revision(),
                planHash,
                transformRevision,
                dimension,
                anchor.getX(),
                anchor.getY(),
                anchor.getZ(),
                prepared.width(),
                prepared.height(),
                prepared.depth(),
                prepared.palette(),
                prepared.cells());
    }

    private static String executionParityProblem(PreparedPreview preview, BlueprintSchema blueprint) {
        if (preview.cells().size() != blueprint.placements().size()) {
            return "cell_count";
        }
        for (int index = 0; index < preview.cells().size(); index++) {
            BuildingPreviewChunkS2C.Cell cell = preview.cells().get(index);
            BlueprintSchema.BlockPlacement placement = blueprint.placements().get(index);
            if (cell.dx() != placement.dx()
                    || cell.dy() != placement.dy()
                    || cell.dz() != placement.dz()) {
                return "coordinates_at_" + index;
            }
            if (cell.paletteIndex() < 0 || cell.paletteIndex() >= preview.palette().size()) {
                return "palette_index_at_" + index;
            }
            BlockStateSpec visibleState = preview.palette().get(cell.paletteIndex());
            BlockStateSpec executableState = new BlockStateSpec(
                    placement.blockId(), placement.properties());
            if (!visibleState.equals(executableState)) {
                return "block_state_at_" + index;
            }
            if (cell.operation() != placement.operation()
                    || cell.replacePolicy() != placement.replacePolicy()) {
                return "semantics_at_" + index;
            }
            if (placement.sequence() != null && placement.sequence() != index) {
                return "sequence_at_" + index;
            }
        }
        return null;
    }

    private void publish(ServerPlayer viewer, AIPlayerEntity bot, Session session) {
        PreparedPreview prepared = session.prepared();
        int chunkCount = (prepared.cells().size() + CHUNK_SIZE - 1) / CHUNK_SIZE;
        network.send(viewer, beginPayload(bot, session));
        // The client publishes staging data only after Commit. Queue the chunks instead of
        // synchronously emitting up to 256 packets from one command/server tick.
        transfersByViewer.put(viewer.getUUID(), new BuildingPreviewTransfer(
                session.sessionId(), session.previewHash(), session.transformRevision(), chunkCount));
    }

    private void tickTransfers(MinecraftServer server) {
        for (Map.Entry<UUID, BuildingPreviewTransfer> entry : transfersByViewer.entrySet()) {
            UUID viewerId = entry.getKey();
            BuildingPreviewTransfer transfer = entry.getValue();
            Session session = sessionsByViewer.get(viewerId);
            if (session == null || !transfer.matches(
                    session.sessionId(), session.previewHash(), session.transformRevision())) {
                transfersByViewer.remove(viewerId, transfer);
                continue;
            }
            ServerPlayer viewer = server.getPlayerList().getPlayer(viewerId);
            if (viewer == null) {
                transfersByViewer.remove(viewerId, transfer);
                continue;
            }
            try {
                int sent = 0;
                while (!transfer.complete() && sent < MAX_TRANSFER_CHUNKS_PER_TICK) {
                    int chunkIndex = transfer.nextChunk();
                    int from = chunkIndex * CHUNK_SIZE;
                    int to = Math.min(session.prepared().cells().size(), from + CHUNK_SIZE);
                    network.send(viewer, new BuildingPreviewChunkS2C(
                            session.sessionId(),
                            session.previewHash(),
                            session.transformRevision(),
                            chunkIndex,
                            session.prepared().cells().subList(from, to)));
                    sent++;
                }
                if (transfer.complete()) {
                    network.send(viewer, new BuildingPreviewCommitS2C(
                            session.sessionId(), session.previewHash(), session.transformRevision()));
                    transfersByViewer.remove(viewerId, transfer);
                }
            } catch (RuntimeException exception) {
                if (!transfersByViewer.remove(viewerId, transfer)) {
                    continue;
                }
                if (sessionsByViewer.remove(viewerId, session)) {
                    releaseExternalWaitIfNoSession(session);
                }
                sendClear(viewer, session.sessionId(), "transfer_failed");
                viewer.sendSystemMessage(Component.literal(
                        "[FakeAiPlayer] 建筑投影分片发送失败，请重新生成。"));
                BotLog.error("building_preview_transfer_failed", exception,
                        "viewer_id", viewerId,
                        "session_id", session.sessionId(),
                        "remaining_chunks", transfer.remainingChunks());
            }
        }
    }

    private void cancelTransfer(UUID viewerId, UUID sessionId) {
        transfersByViewer.computeIfPresent(viewerId, (ignored, transfer) ->
                transfer.sessionId().equals(sessionId) ? null : transfer);
    }

    private BuildingPreviewBeginS2C beginPayload(AIPlayerEntity bot, Session session) {
        PreparedPreview prepared = session.prepared();
        int chunkCount = (prepared.cells().size() + CHUNK_SIZE - 1) / CHUNK_SIZE;
        return new BuildingPreviewBeginS2C(
                session.sessionId(),
                bot.getUUID(),
                PayloadLimits.truncate(bot.getGameProfile().getName(), PayloadLimits.BOT_NAME_LENGTH),
                PayloadLimits.truncate(session.plan().planId(), PayloadLimits.PREVIEW_PLAN_ID_LENGTH),
                session.plan().revision(),
                session.planHash(),
                session.previewHash(),
                session.transformRevision(),
                session.dimension(),
                session.anchor().getX(),
                session.anchor().getY(),
                session.anchor().getZ(),
                prepared.width(),
                prepared.height(),
                prepared.depth(),
                prepared.cells().size(),
                chunkCount,
                prepared.palette());
    }

    private boolean supportsPreview(ServerPlayer player) {
        // ServerPlayNetworking.canSend is directional: it can only probe receivers advertised by
        // the client for S2C payloads. C2S registration is enforced when those packets arrive and
        // must never be queried through this transport (Fabric otherwise rejects every preview).
        return network.canSendToClient(player, BuildingPreviewBeginS2C.ID)
                && network.canSendToClient(player, BuildingPreviewChunkS2C.ID)
                && network.canSendToClient(player, BuildingPreviewCommitS2C.ID)
                && network.canSendToClient(player, BuildingPreviewClearS2C.ID);
    }

    private void sendClear(ServerPlayer player, UUID sessionId, String reason) {
        try {
            if (network.canSendToClient(player, BuildingPreviewClearS2C.ID)) {
                network.send(player, new BuildingPreviewClearS2C(sessionId, reason));
            }
        } catch (RuntimeException exception) {
            // Internal session/brain state is always transitioned before this best-effort packet.
            BotLog.error("building_preview_clear_send_failed", exception,
                    "viewer_id", player.getUUID(), "session_id", sessionId);
        }
    }

    /** Mutable server-thread cursor for a bounded, revision-specific world validation. */
    private static final class ConfirmationValidation {
        private final Session session;
        private final IntentController.ControlOrigin controlOrigin;
        private final Map<String, PlanPlacement> placementsById = new LinkedHashMap<>();
        private final Map<String, List<PlanPlacement>> atomicGroups = new LinkedHashMap<>();
        private final Set<String> partialGroups = new HashSet<>();
        private final List<String> conflicts = new ArrayList<>();

        private ValidationPhase phase = ValidationPhase.INDEX;
        private int indexCursor;
        private List<Map.Entry<String, List<PlanPlacement>>> atomicEntries = List.of();
        private int atomicEntryCursor;
        private int atomicMemberCursor;
        private int atomicSatisfied;
        private boolean atomicInspectable;
        private BlockPos atomicFirstPos;
        private int cellCursor;
        private int conflictCount;

        private ConfirmationValidation(
                Session session,
                IntentController.ControlOrigin controlOrigin
        ) {
            this.session = session;
            this.controlOrigin = controlOrigin;
        }

        private boolean matches(Session candidate) {
            return candidate != null
                    && session.sessionId().equals(candidate.sessionId())
                    && session.previewHash().equals(candidate.previewHash())
                    && session.transformRevision() == candidate.transformRevision();
        }

        private int advance(ServerLevel world, int allowance) {
            if (allowance < 1 || complete()) {
                return 0;
            }
            int consumed = 0;
            List<PlanPlacement> placements = session.plan().placements();
            while (consumed < allowance && !complete()) {
                switch (phase) {
                    case INDEX -> {
                        if (indexCursor >= placements.size()) {
                            atomicEntries = List.copyOf(atomicGroups.entrySet());
                            phase = ValidationPhase.ATOMIC_GROUPS;
                            continue;
                        }
                        PlanPlacement placement = placements.get(indexCursor++);
                        placementsById.put(placement.id(), placement);
                        if (!placement.atomicGroup().isBlank()) {
                            atomicGroups.computeIfAbsent(
                                    placement.atomicGroup(), ignored -> new ArrayList<>())
                                    .add(placement);
                        }
                        consumed++;
                    }
                    case ATOMIC_GROUPS -> {
                        if (atomicEntryCursor >= atomicEntries.size()) {
                            phase = ValidationPhase.CELLS;
                            continue;
                        }
                        Map.Entry<String, List<PlanPlacement>> entry =
                                atomicEntries.get(atomicEntryCursor);
                        List<PlanPlacement> members = entry.getValue();
                        if (members.size() < 2) {
                            atomicEntryCursor++;
                            consumed++;
                            continue;
                        }
                        if (atomicMemberCursor == 0) {
                            atomicSatisfied = 0;
                            atomicInspectable = true;
                            atomicFirstPos = null;
                        }
                        PlanPlacement placement = members.get(atomicMemberCursor);
                        BlockPos pos = worldPos(session, placement);
                        if (atomicFirstPos == null) {
                            atomicFirstPos = pos;
                        }
                        if (!inspectable(world, pos)) {
                            atomicInspectable = false;
                            atomicMemberCursor = members.size();
                        } else {
                            BlockStateSpec expected = session.transform().apply(placement.state());
                            BlockState actual = world.getBlockState(pos);
                            if (alreadySatisfied(actual, expected, placement.operation())) {
                                atomicSatisfied++;
                            }
                            atomicMemberCursor++;
                        }
                        consumed++;
                        if (atomicMemberCursor >= members.size()) {
                            if (atomicInspectable
                                    && atomicSatisfied > 0
                                    && atomicSatisfied < members.size()) {
                                partialGroups.add(entry.getKey());
                                addConflict(atomicFirstPos.toShortString() + ":原子结构 "
                                        + entry.getKey() + " 仅部分存在；请先完整移除或完整保留");
                            }
                            atomicMemberCursor = 0;
                            atomicEntryCursor++;
                        }
                    }
                    case CELLS -> {
                        if (cellCursor >= placements.size()) {
                            phase = ValidationPhase.COMPLETE;
                            continue;
                        }
                        PlanPlacement placement = placements.get(cellCursor++);
                        consumed++;
                        if (!placement.atomicGroup().isBlank()
                                && partialGroups.contains(placement.atomicGroup())) {
                            continue;
                        }
                        BlockPos pos = worldPos(session, placement);
                        String problem = validateCell(
                                world,
                                pos,
                                session.transform().apply(placement.state()),
                                placement,
                                placementsById);
                        if (problem != null) {
                            addConflict(pos.toShortString() + ":" + problem);
                        }
                    }
                    case COMPLETE -> {
                        return consumed;
                    }
                }
            }
            return consumed;
        }

        private void addConflict(String conflict) {
            conflictCount++;
            if (conflicts.size() < MAX_REPORTED_CONFLICTS) {
                conflicts.add(conflict);
            }
        }

        private boolean complete() {
            return phase == ValidationPhase.COMPLETE;
        }

        private IntentController.ControlOrigin controlOrigin() {
            return controlOrigin;
        }

        private String phaseName() {
            return phase.name();
        }

        private WorldValidation result() {
            if (!complete()) {
                throw new IllegalStateException("building_confirmation_validation_incomplete");
            }
            if (conflictCount == 0) {
                return WorldValidation.ok();
            }
            String suffix = conflictCount > conflicts.size()
                    ? " 等，共 " + conflictCount + " 处"
                    : "";
            return WorldValidation.failed(
                    "确认校验发现冲突: " + String.join("; ", conflicts) + suffix);
        }
    }

    private enum ValidationPhase {
        INDEX,
        ATOMIC_GROUPS,
        CELLS,
        COMPLETE
    }

    private record ConfirmationAttemptWindow(UUID sessionId, int nextAllowedTick) {
    }

    private record Session(
            UUID sessionId,
            UUID viewerId,
            UUID botId,
            BuildingPlan plan,
            String planHash,
            String previewHash,
            String dimension,
            BlockPos anchor,
            PlanTransform transform,
            int transformRevision,
            int expiresAtTick,
            PreparedPreview prepared,
            boolean conversationLinked,
            int acknowledgedTransformRevision
    ) {
        private SessionView view() {
            return new SessionView(
                    sessionId, botId, plan.planId(), plan.name(), planHash, previewHash, dimension, anchor,
                    transform.mirror(), transform.rotation(), transformRevision,
                    prepared.width(), prepared.height(), prepared.depth(), prepared.cells().size());
        }
    }

    private record PreparedPreview(
            List<BlockStateSpec> palette,
            List<BuildingPreviewChunkS2C.Cell> cells,
            int width,
            int height,
            int depth
    ) {
    }

    public record SessionView(
            UUID sessionId,
            UUID botId,
            String planId,
            String planName,
            String planHash,
            String previewHash,
            String dimension,
            BlockPos anchor,
            Mirror mirror,
            Rotation rotation,
            int transformRevision,
            int width,
            int height,
            int depth,
            int placementCount
    ) {
    }

    public record OpenResult(
            boolean success,
            String message,
            UUID sessionId,
            String planHash,
            int placementCount,
            int paletteSize
    ) {
        private static OpenResult opened(UUID sessionId, String hash, int placementCount, int paletteSize) {
            return new OpenResult(true, "preview_opened", sessionId, hash, placementCount, paletteSize);
        }

        private static OpenResult failed(String message) {
            return new OpenResult(false, message, null, "", 0, 0);
        }
    }

    public record ConfirmResult(boolean success, String message) {
        private static ConfirmResult accepted(String message) {
            return new ConfirmResult(true, message);
        }

        private static ConfirmResult confirmed(String message) {
            return new ConfirmResult(true, message);
        }

        private static ConfirmResult failed(String message) {
            return new ConfirmResult(false, message);
        }
    }

    public record UpdateResult(boolean success, String message, SessionView session) {
        private static UpdateResult updated(SessionView session) {
            return new UpdateResult(true, "preview_updated", session);
        }

        private static UpdateResult failed(String message) {
            return new UpdateResult(false, message, null);
        }
    }

    private record WorldValidation(boolean valid, String message) {
        private static WorldValidation ok() {
            return new WorldValidation(true, "");
        }

        private static WorldValidation failed(String message) {
            return new WorldValidation(false, message);
        }
    }

    private enum NoNetwork implements ServerNetworkTransport {
        INSTANCE;

        @Override
        public boolean canSendToClient(ServerPlayer player,
                                       net.minecraft.network.protocol.common.custom.CustomPacketPayload.Type<?> type) {
            return false;
        }

        @Override
        public void send(ServerPlayer player,
                         net.minecraft.network.protocol.common.custom.CustomPacketPayload payload) {
        }
    }
}
