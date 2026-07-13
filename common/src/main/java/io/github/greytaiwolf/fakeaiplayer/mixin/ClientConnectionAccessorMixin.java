package io.github.greytaiwolf.fakeaiplayer.mixin;

import io.github.greytaiwolf.fakeaiplayer.network.ClientConnectionAccessor;
import io.netty.channel.Channel;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketListener;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(Connection.class)
public abstract class ClientConnectionAccessorMixin implements ClientConnectionAccessor {
    @Override
    @Accessor("channel")
    public abstract void fakeaiplayer$setChannel(Channel channel);

    @Override
    @Accessor("packetListener")
    public abstract void fakeaiplayer$setPacketListener(PacketListener packetListener);
}
