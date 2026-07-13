package io.github.greytaiwolf.fakeaiplayer.network;

import io.netty.channel.Channel;
import net.minecraft.network.PacketListener;

public interface ClientConnectionAccessor {
    void fakeaiplayer$setChannel(Channel channel);

    void fakeaiplayer$setPacketListener(PacketListener packetListener);
}
