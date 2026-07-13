package io.github.greytaiwolf.fakeaiplayer.platform.fabric.client;

import io.github.greytaiwolf.fakeaiplayer.client.AIBotClientNetworking;
import io.github.greytaiwolf.fakeaiplayer.client.AIBotKeyBindings;
import io.github.greytaiwolf.fakeaiplayer.client.BotChatCapture;
import io.github.greytaiwolf.fakeaiplayer.client.ClientNetworkServices;
import io.github.greytaiwolf.fakeaiplayer.client.FakeAiPlayerClient;
import io.github.greytaiwolf.fakeaiplayer.network.payload.BotChatS2C;
import io.github.greytaiwolf.fakeaiplayer.network.payload.BotSnapshotS2C;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

public final class FakeAiPlayerFabricClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        ClientNetworkServices.initialize(new FabricClientNetworkTransport());
        KeyBindingHelper.registerKeyBinding(AIBotKeyBindings.openPanel());
        KeyBindingHelper.registerKeyBinding(AIBotKeyBindings.openActions());
        ClientPlayNetworking.registerGlobalReceiver(BotSnapshotS2C.ID, (payload, context) ->
                context.client().execute(() -> AIBotClientNetworking.handle(payload)));
        ClientPlayNetworking.registerGlobalReceiver(BotChatS2C.ID, (payload, context) ->
                context.client().execute(() -> AIBotClientNetworking.handle(payload)));
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> BotChatCapture.handle(message));
        ClientTickEvents.END_CLIENT_TICK.register(FakeAiPlayerClient::onClientTick);
    }
}
