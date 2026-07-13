package io.github.greytaiwolf.fakeaiplayer.network;

import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.network.Connection;
import net.minecraft.network.DisconnectionDetails;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;

public class AINetworkHandler extends ServerGamePacketListenerImpl {
    private final AtomicBoolean disconnected = new AtomicBoolean();

    public AINetworkHandler(MinecraftServer server,
                            Connection connection,
                            ServerPlayer player,
                            CommonListenerCookie clientData) {
        super(server, connection, player, clientData);
    }

    /**
     * A fake player is ticked as an entity by the server world. It has no client input stream to
     * service, so running the vanilla network-handler tick would tick the player a second time.
     */
    @Override
    public void tick() {
    }

    @Override
    public void send(Packet<?> packet) {
    }

    @Override
    public void send(Packet<?> packet, PacketSendListener callbacks) {
        if (callbacks != null) {
            callbacks.onSuccess();
        }
    }

    @Override
    public void disconnect(Component reason) {
        disconnect(new DisconnectionDetails(reason));
    }

    @Override
    public void disconnect(DisconnectionDetails disconnectionInfo) {
        this.connection.disconnect(disconnectionInfo);
        this.connection.handleDisconnection();
    }

    @Override
    public void onDisconnect(DisconnectionDetails disconnectionInfo) {
        if (this.disconnected.compareAndSet(false, true)) {
            try {
                if (this.connection.isConnected()) {
                    this.connection.disconnect(disconnectionInfo);
                }
                super.onDisconnect(disconnectionInfo);
            } finally {
                if (this.connection instanceof FakeClientConnection fakeConnection) {
                    fakeConnection.releaseChannel();
                }
            }
        }
    }
}
