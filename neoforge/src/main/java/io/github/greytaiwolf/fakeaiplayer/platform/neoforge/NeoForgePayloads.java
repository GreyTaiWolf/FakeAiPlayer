package io.github.greytaiwolf.fakeaiplayer.platform.neoforge;

import io.github.greytaiwolf.fakeaiplayer.client.AIBotClientNetworking;
import io.github.greytaiwolf.fakeaiplayer.building.preview.BuildingPreviewService;
import io.github.greytaiwolf.fakeaiplayer.network.AIBotServerNetworking;
import io.github.greytaiwolf.fakeaiplayer.network.payload.BotChatS2C;
import io.github.greytaiwolf.fakeaiplayer.network.payload.BotCommandC2S;
import io.github.greytaiwolf.fakeaiplayer.network.payload.OpenBotInventoryC2S;
import io.github.greytaiwolf.fakeaiplayer.network.payload.BotSnapshotS2C;
import io.github.greytaiwolf.fakeaiplayer.network.payload.BotTeleportC2S;
import io.github.greytaiwolf.fakeaiplayer.network.payload.SetOptionC2S;
import io.github.greytaiwolf.fakeaiplayer.network.payload.SubscribeBotC2S;
import io.github.greytaiwolf.fakeaiplayer.network.payload.BuildingPreviewBeginS2C;
import io.github.greytaiwolf.fakeaiplayer.network.payload.BuildingPreviewCancelC2S;
import io.github.greytaiwolf.fakeaiplayer.network.payload.BuildingPreviewChunkS2C;
import io.github.greytaiwolf.fakeaiplayer.network.payload.BuildingPreviewClearS2C;
import io.github.greytaiwolf.fakeaiplayer.network.payload.BuildingPreviewCommitS2C;
import io.github.greytaiwolf.fakeaiplayer.network.payload.BuildingPreviewConfirmC2S;
import io.github.greytaiwolf.fakeaiplayer.network.payload.BuildingPreviewReadyC2S;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

final class NeoForgePayloads {
    // Version 3 replaces the direct item-move packet with the server-authoritative inventory
    // menu protocol. Keep old clients/servers from silently negotiating incompatible channels.
    private static final String PROTOCOL_VERSION = "3";

    private NeoForgePayloads() {
    }

    static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar(PROTOCOL_VERSION).optional();
        AIBotServerNetworking server = AIBotServerNetworking.INSTANCE;

        registrar.playToServer(SubscribeBotC2S.ID, SubscribeBotC2S.CODEC, (payload, context) -> {
            if (context.player() instanceof ServerPlayer player) {
                server.handleSubscribe(player, payload);
            }
        });
        registrar.playToServer(BotCommandC2S.ID, BotCommandC2S.CODEC, (payload, context) -> {
            if (context.player() instanceof ServerPlayer player) {
                server.handleCommand(player, payload);
            }
        });
        registrar.playToServer(SetOptionC2S.ID, SetOptionC2S.CODEC, (payload, context) -> {
            if (context.player() instanceof ServerPlayer player) {
                server.handleSetOption(player, payload);
            }
        });
        registrar.playToServer(OpenBotInventoryC2S.ID, OpenBotInventoryC2S.CODEC, (payload, context) -> {
            if (context.player() instanceof ServerPlayer player) {
                server.handleOpenInventory(player, payload);
            }
        });
        registrar.playToServer(BotTeleportC2S.ID, BotTeleportC2S.CODEC, (payload, context) -> {
            if (context.player() instanceof ServerPlayer player) {
                server.handleTeleport(player, payload);
            }
        });
        registrar.playToServer(BuildingPreviewConfirmC2S.ID, BuildingPreviewConfirmC2S.CODEC, (payload, context) -> {
            if (context.player() instanceof ServerPlayer player) {
                BuildingPreviewService.INSTANCE.handleConfirm(player, payload);
            }
        });
        registrar.playToServer(BuildingPreviewCancelC2S.ID, BuildingPreviewCancelC2S.CODEC, (payload, context) -> {
            if (context.player() instanceof ServerPlayer player) {
                BuildingPreviewService.INSTANCE.handleCancel(player, payload);
            }
        });
        registrar.playToServer(BuildingPreviewReadyC2S.ID, BuildingPreviewReadyC2S.CODEC, (payload, context) -> {
            if (context.player() instanceof ServerPlayer player) {
                BuildingPreviewService.INSTANCE.handleReady(player, payload);
            }
        });
        registrar.playToClient(BotSnapshotS2C.ID, BotSnapshotS2C.CODEC,
                (payload, context) -> AIBotClientNetworking.handle(payload));
        registrar.playToClient(BotChatS2C.ID, BotChatS2C.CODEC,
                (payload, context) -> AIBotClientNetworking.handle(payload));
        registrar.playToClient(BuildingPreviewBeginS2C.ID, BuildingPreviewBeginS2C.CODEC,
                (payload, context) -> AIBotClientNetworking.handle(payload));
        registrar.playToClient(BuildingPreviewChunkS2C.ID, BuildingPreviewChunkS2C.CODEC,
                (payload, context) -> AIBotClientNetworking.handle(payload));
        registrar.playToClient(BuildingPreviewCommitS2C.ID, BuildingPreviewCommitS2C.CODEC,
                (payload, context) -> AIBotClientNetworking.handle(payload));
        registrar.playToClient(BuildingPreviewClearS2C.ID, BuildingPreviewClearS2C.CODEC,
                (payload, context) -> AIBotClientNetworking.handle(payload));
    }
}
