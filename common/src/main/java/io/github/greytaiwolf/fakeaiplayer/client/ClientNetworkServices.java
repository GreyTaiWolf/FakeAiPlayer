package io.github.greytaiwolf.fakeaiplayer.client;

import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.util.Objects;

/** Client-only bridge configured by the active loader. */
public final class ClientNetworkServices {
    private static volatile ClientNetworkTransport transport;

    private ClientNetworkServices() {
    }

    public static void initialize(ClientNetworkTransport value) {
        transport = Objects.requireNonNull(value, "value");
    }

    public static boolean canSend(CustomPacketPayload.Type<?> type) {
        ClientNetworkTransport value = transport;
        return value != null && value.canSend(type);
    }

    public static void send(CustomPacketPayload payload) {
        ClientNetworkTransport value = transport;
        if (value != null) {
            value.send(payload);
        }
    }
}
