package io.github.greytaiwolf.fakeaiplayer.platform.neoforge.client;

import io.github.greytaiwolf.fakeaiplayer.client.ClientNetworkTransport;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.neoforge.network.PacketDistributor;

final class NeoForgeClientNetworkTransport implements ClientNetworkTransport {
    @Override
    public boolean canSend(CustomPacketPayload.Type<?> type) {
        ClientPacketListener listener = Minecraft.getInstance().getConnection();
        return listener != null && listener.hasChannel(type);
    }

    @Override
    public void send(CustomPacketPayload payload) {
        PacketDistributor.sendToServer(payload);
    }
}
