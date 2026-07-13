package io.github.greytaiwolf.fakeaiplayer.platform.fabric;

import io.github.greytaiwolf.fakeaiplayer.network.AIBotServerNetworking;
import io.github.greytaiwolf.fakeaiplayer.network.payload.BotChatS2C;
import io.github.greytaiwolf.fakeaiplayer.network.payload.BotCommandC2S;
import io.github.greytaiwolf.fakeaiplayer.network.payload.BotItemMoveC2S;
import io.github.greytaiwolf.fakeaiplayer.network.payload.BotSnapshotS2C;
import io.github.greytaiwolf.fakeaiplayer.network.payload.BotTeleportC2S;
import io.github.greytaiwolf.fakeaiplayer.network.payload.SetOptionC2S;
import io.github.greytaiwolf.fakeaiplayer.network.payload.SubscribeBotC2S;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;

final class FabricPayloads {
    private FabricPayloads() {
    }

    static void register() {
        PayloadTypeRegistry.playC2S().register(SubscribeBotC2S.ID, SubscribeBotC2S.CODEC);
        PayloadTypeRegistry.playC2S().register(BotCommandC2S.ID, BotCommandC2S.CODEC);
        PayloadTypeRegistry.playC2S().register(SetOptionC2S.ID, SetOptionC2S.CODEC);
        PayloadTypeRegistry.playC2S().register(BotItemMoveC2S.ID, BotItemMoveC2S.CODEC);
        PayloadTypeRegistry.playC2S().register(BotTeleportC2S.ID, BotTeleportC2S.CODEC);
        PayloadTypeRegistry.playS2C().register(BotSnapshotS2C.ID, BotSnapshotS2C.CODEC);
        PayloadTypeRegistry.playS2C().register(BotChatS2C.ID, BotChatS2C.CODEC);

        AIBotServerNetworking service = AIBotServerNetworking.INSTANCE;
        ServerPlayNetworking.registerGlobalReceiver(SubscribeBotC2S.ID, (payload, context) ->
                context.server().execute(() -> service.handleSubscribe(context.player(), payload)));
        ServerPlayNetworking.registerGlobalReceiver(BotCommandC2S.ID, (payload, context) ->
                context.server().execute(() -> service.handleCommand(context.player(), payload)));
        ServerPlayNetworking.registerGlobalReceiver(SetOptionC2S.ID, (payload, context) ->
                context.server().execute(() -> service.handleSetOption(context.player(), payload)));
        ServerPlayNetworking.registerGlobalReceiver(BotItemMoveC2S.ID, (payload, context) ->
                context.server().execute(() -> service.handleItemMove(context.player(), payload)));
        ServerPlayNetworking.registerGlobalReceiver(BotTeleportC2S.ID, (payload, context) ->
                context.server().execute(() -> service.handleTeleport(context.player(), payload)));
    }
}
