package io.github.greytaiwolf.fakeaiplayer.network;

import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;

/** Loader-specific server payload transport. */
public interface ServerNetworkTransport {
    /** Return whether {@code player}'s client advertised a receiver for this S2C payload. */
    boolean canSendToClient(ServerPlayer player, CustomPacketPayload.Type<?> type);

    void send(ServerPlayer player, CustomPacketPayload payload);
}
