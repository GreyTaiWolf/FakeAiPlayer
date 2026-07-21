package io.github.greytaiwolf.fakeaiplayer.platform.fabric;

import io.github.greytaiwolf.fakeaiplayer.FakeAiPlayer;
import io.github.greytaiwolf.fakeaiplayer.brain.ChatCaptureListener;
import io.github.greytaiwolf.fakeaiplayer.building.preview.BuildingPreviewService;
import io.github.greytaiwolf.fakeaiplayer.inventory.BotInventorySessionManager;
import io.github.greytaiwolf.fakeaiplayer.network.AIBotServerNetworking;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;

public final class FakeAiPlayerFabric implements ModInitializer {
    @Override
    public void onInitialize() {
        FabricMenuRegistry.register();
        FakeAiPlayer.initialize(new FabricEnvironment(), new FabricServerNetworkTransport());
        FabricPayloads.register();

        ServerLifecycleEvents.SERVER_STARTED.register(FakeAiPlayer::onServerStarted);
        ServerLifecycleEvents.SERVER_STOPPING.register(FakeAiPlayer::onServerStopping);
        ServerLifecycleEvents.SERVER_STOPPED.register(FakeAiPlayer::onServerStopped);
        ServerTickEvents.END_SERVER_TICK.register(FakeAiPlayer::onServerTick);
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                FakeAiPlayer.registerCommands(dispatcher, registryAccess));
        ServerMessageEvents.CHAT_MESSAGE.register((message, sender, params) ->
                ChatCaptureListener.handle(sender, message.decoratedContent().getString()));
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            AIBotServerNetworking.INSTANCE.onDisconnect(handler.player);
            BuildingPreviewService.INSTANCE.onDisconnect(handler.player);
            BotInventorySessionManager.INSTANCE.onViewerDisconnect(handler.player);
        });
    }
}
