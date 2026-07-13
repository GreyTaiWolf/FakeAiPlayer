package io.github.greytaiwolf.fakeaiplayer.platform.neoforge;

import io.github.greytaiwolf.fakeaiplayer.client.AIBotClientNetworking;
import io.github.greytaiwolf.fakeaiplayer.network.AIBotServerNetworking;
import io.github.greytaiwolf.fakeaiplayer.network.payload.BotChatS2C;
import io.github.greytaiwolf.fakeaiplayer.network.payload.BotCommandC2S;
import io.github.greytaiwolf.fakeaiplayer.network.payload.BotItemMoveC2S;
import io.github.greytaiwolf.fakeaiplayer.network.payload.BotSnapshotS2C;
import io.github.greytaiwolf.fakeaiplayer.network.payload.BotTeleportC2S;
import io.github.greytaiwolf.fakeaiplayer.network.payload.SetOptionC2S;
import io.github.greytaiwolf.fakeaiplayer.network.payload.SubscribeBotC2S;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

final class NeoForgePayloads {
    private static final String PROTOCOL_VERSION = "1";

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
        registrar.playToServer(BotItemMoveC2S.ID, BotItemMoveC2S.CODEC, (payload, context) -> {
            if (context.player() instanceof ServerPlayer player) {
                server.handleItemMove(player, payload);
            }
        });
        registrar.playToServer(BotTeleportC2S.ID, BotTeleportC2S.CODEC, (payload, context) -> {
            if (context.player() instanceof ServerPlayer player) {
                server.handleTeleport(player, payload);
            }
        });
        registrar.playToClient(BotSnapshotS2C.ID, BotSnapshotS2C.CODEC,
                (payload, context) -> AIBotClientNetworking.handle(payload));
        registrar.playToClient(BotChatS2C.ID, BotChatS2C.CODEC,
                (payload, context) -> AIBotClientNetworking.handle(payload));
    }
}
