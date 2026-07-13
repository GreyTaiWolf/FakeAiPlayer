package io.github.greytaiwolf.fakeaiplayer.client;

import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Loader-specific client payload transport. */
public interface ClientNetworkTransport {
    boolean canSend(CustomPacketPayload.Type<?> type);

    void send(CustomPacketPayload payload);
}
