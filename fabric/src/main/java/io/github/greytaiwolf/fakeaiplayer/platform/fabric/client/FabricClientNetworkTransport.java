package io.github.greytaiwolf.fakeaiplayer.platform.fabric.client;

import io.github.greytaiwolf.fakeaiplayer.client.ClientNetworkTransport;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

final class FabricClientNetworkTransport implements ClientNetworkTransport {
    @Override
    public boolean canSend(CustomPacketPayload.Type<?> type) {
        return ClientPlayNetworking.canSend(type);
    }

    @Override
    public void send(CustomPacketPayload payload) {
        ClientPlayNetworking.send(payload);
    }
}
