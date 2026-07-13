package io.github.greytaiwolf.fakeaiplayer.network;

import io.netty.channel.Channel;
import io.netty.channel.embedded.EmbeddedChannel;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketListener;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.handshake.HandshakeProtocols;

public class FakeClientConnection extends Connection {
    private static final SocketAddress FAKE_ADDRESS = new InetSocketAddress("127.0.0.1", 0);
    private final EmbeddedChannel embeddedChannel;
    private final AtomicBoolean channelReleased = new AtomicBoolean();
    private volatile ProtocolInfo<?> inboundProtocol;

    public FakeClientConnection(PacketFlow side) {
        super(side);
        this.embeddedChannel = new EmbeddedChannel();
        accessor().fakeaiplayer$setChannel(this.embeddedChannel);
    }

    @Override
    public <T extends PacketListener> void setupInboundProtocol(ProtocolInfo<T> protocolInfo, T packetListener) {
        Objects.requireNonNull(protocolInfo, "protocolInfo");
        Objects.requireNonNull(packetListener, "packetListener");
        if (protocolInfo.flow() != getReceiving()) {
            throw new IllegalStateException("Invalid fake inbound protocol flow: " + protocolInfo.flow());
        }
        if (packetListener.flow() != getReceiving()) {
            throw new IllegalStateException("Invalid fake packet listener flow: " + packetListener.flow());
        }
        if (protocolInfo.id() != packetListener.protocol()) {
            throw new IllegalStateException(
                    "Fake listener protocol " + packetListener.protocol() + " does not match " + protocolInfo.id());
        }

        ClientConnectionAccessor accessor = accessor();
        this.inboundProtocol = protocolInfo;
        accessor.fakeaiplayer$setPacketListener(packetListener);
    }

    @Override
    public void setupOutboundProtocol(ProtocolInfo<?> protocolInfo) {
        Objects.requireNonNull(protocolInfo, "protocolInfo");
        if (protocolInfo.flow() != getSending()) {
            throw new IllegalStateException("Invalid fake outbound protocol flow: " + protocolInfo.flow());
        }
        // There is no remote endpoint and therefore no encoder pipeline to reconfigure.
    }

    @Override
    public void setListenerForServerboundHandshake(PacketListener packetListener) {
        Objects.requireNonNull(packetListener, "packetListener");
        if (getPacketListener() != null) {
            throw new IllegalStateException("Fake packet listener is already set");
        }
        if (getReceiving() != PacketFlow.SERVERBOUND
                || packetListener.flow() != PacketFlow.SERVERBOUND
                || packetListener.protocol() != HandshakeProtocols.SERVERBOUND.id()) {
            throw new IllegalStateException("Invalid initial fake packet listener");
        }

        ClientConnectionAccessor accessor = accessor();
        this.inboundProtocol = HandshakeProtocols.SERVERBOUND;
        accessor.fakeaiplayer$setPacketListener(packetListener);
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
    public void send(Packet<?> packet, PacketSendListener callbacks, boolean flush) {
        if (callbacks != null) {
            callbacks.onSuccess();
        }
    }

    @Override
    public void handleDisconnection() {
        try {
            super.handleDisconnection();
        } finally {
            releaseChannel();
        }
    }

    @Override
    public SocketAddress getRemoteAddress() {
        return FAKE_ADDRESS;
    }

    @Override
    public String getLoggableAddress(boolean useSnooperSetting) {
        return "127.0.0.1";
    }

    /**
     * NeoForge stores negotiated payload data as channel attributes. Keeping a real channel
     * available makes {@code hasChannel(...)} return false for unnegotiated payloads instead of
     * failing on a missing channel. This method is a NeoForge extension in 1.21.3; on Fabric it is
     * simply an additional harmless method.
     */
    public Channel channel() {
        return this.embeddedChannel;
    }

    /**
     * NeoForge queries this during payload/channel checks. Fabric's 1.21.3 Connection does not
     * expose the method, but retaining it here is binary-safe and keeps the shared implementation
     * usable by both loaders.
     */
    public ProtocolInfo<?> getInboundProtocol() {
        return Objects.requireNonNull(this.inboundProtocol, "Fake inbound protocol not set");
    }

    void releaseChannel() {
        if (!isConnected() && this.channelReleased.compareAndSet(false, true)) {
            this.embeddedChannel.finishAndReleaseAll();
        }
    }

    private ClientConnectionAccessor accessor() {
        return (ClientConnectionAccessor) this;
    }
}
