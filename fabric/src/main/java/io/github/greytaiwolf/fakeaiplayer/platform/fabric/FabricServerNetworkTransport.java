package io.github.greytaiwolf.fakeaiplayer.platform.fabric;

import io.github.greytaiwolf.fakeaiplayer.network.ServerNetworkTransport;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;

final class FabricServerNetworkTransport implements ServerNetworkTransport {
    @Override
    public boolean canSendToClient(ServerPlayer player, CustomPacketPayload.Type<?> type) {
        return ServerPlayNetworking.canSend(player, type);
    }

    @Override
    public void send(ServerPlayer player, CustomPacketPayload payload) {
        ServerPlayNetworking.send(player, payload);
    }
}
