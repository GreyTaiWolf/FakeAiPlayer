package io.github.greytaiwolf.fakeaiplayer.platform.fabric.client;

import io.github.greytaiwolf.fakeaiplayer.client.AIBotClientNetworking;
import io.github.greytaiwolf.fakeaiplayer.client.AIBotKeyBindings;
import io.github.greytaiwolf.fakeaiplayer.client.BotChatCapture;
import io.github.greytaiwolf.fakeaiplayer.client.ClientNetworkServices;
import io.github.greytaiwolf.fakeaiplayer.client.FakeAiPlayerClient;
import io.github.greytaiwolf.fakeaiplayer.client.screen.BotInventoryScreen;
import io.github.greytaiwolf.fakeaiplayer.inventory.BotMenuTypes;
import net.minecraft.client.gui.screens.MenuScreens;
import io.github.greytaiwolf.fakeaiplayer.network.payload.BotChatS2C;
import io.github.greytaiwolf.fakeaiplayer.network.payload.BotSnapshotS2C;
import io.github.greytaiwolf.fakeaiplayer.network.payload.BuildingPreviewBeginS2C;
import io.github.greytaiwolf.fakeaiplayer.network.payload.BuildingPreviewChunkS2C;
import io.github.greytaiwolf.fakeaiplayer.network.payload.BuildingPreviewClearS2C;
import io.github.greytaiwolf.fakeaiplayer.network.payload.BuildingPreviewCommitS2C;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

public final class FakeAiPlayerFabricClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        MenuScreens.register(BotMenuTypes.inventory(), BotInventoryScreen::new);
        ClientNetworkServices.initialize(new FabricClientNetworkTransport());
        KeyBindingHelper.registerKeyBinding(AIBotKeyBindings.openPanel());
        KeyBindingHelper.registerKeyBinding(AIBotKeyBindings.openActions());
        KeyBindingHelper.registerKeyBinding(AIBotKeyBindings.confirmBuildingPreview());
        KeyBindingHelper.registerKeyBinding(AIBotKeyBindings.cancelBuildingPreview());
        ClientPlayNetworking.registerGlobalReceiver(BotSnapshotS2C.ID, (payload, context) ->
                context.client().execute(() -> AIBotClientNetworking.handle(payload)));
        ClientPlayNetworking.registerGlobalReceiver(BotChatS2C.ID, (payload, context) ->
                context.client().execute(() -> AIBotClientNetworking.handle(payload)));
        ClientPlayNetworking.registerGlobalReceiver(BuildingPreviewBeginS2C.ID, (payload, context) ->
                context.client().execute(() -> AIBotClientNetworking.handle(payload)));
        ClientPlayNetworking.registerGlobalReceiver(BuildingPreviewChunkS2C.ID, (payload, context) ->
                context.client().execute(() -> AIBotClientNetworking.handle(payload)));
        ClientPlayNetworking.registerGlobalReceiver(BuildingPreviewCommitS2C.ID, (payload, context) ->
                context.client().execute(() -> AIBotClientNetworking.handle(payload)));
        ClientPlayNetworking.registerGlobalReceiver(BuildingPreviewClearS2C.ID, (payload, context) ->
                context.client().execute(() -> AIBotClientNetworking.handle(payload)));
        FabricBuildingPreviewRenderer.register();
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> BotChatCapture.handle(message));
        ClientTickEvents.END_CLIENT_TICK.register(FakeAiPlayerClient::onClientTick);
    }
}
