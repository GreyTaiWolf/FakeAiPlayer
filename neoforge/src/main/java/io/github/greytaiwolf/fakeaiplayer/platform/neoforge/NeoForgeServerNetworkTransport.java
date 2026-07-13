package io.github.greytaiwolf.fakeaiplayer.platform.neoforge;

import io.github.greytaiwolf.fakeaiplayer.network.ServerNetworkTransport;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;

final class NeoForgeServerNetworkTransport implements ServerNetworkTransport {
    @Override
    public boolean canSend(ServerPlayer player, CustomPacketPayload.Type<?> type) {
        return player.connection != null && player.connection.hasChannel(type);
    }

    @Override
    public void send(ServerPlayer player, CustomPacketPayload payload) {
        PacketDistributor.sendToPlayer(player, payload);
    }
}
