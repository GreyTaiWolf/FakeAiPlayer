package io.github.greytaiwolf.fakeaiplayer.network.payload;

import io.github.greytaiwolf.fakeaiplayer.FakeAiPlayer;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record SubscribeBotC2S(String botName, boolean subscribe) implements CustomPacketPayload {
    public static final Type<SubscribeBotC2S> ID = new Type<>(ResourceLocation.fromNamespaceAndPath(FakeAiPlayer.MOD_ID, "subscribe_bot"));
    public static final StreamCodec<RegistryFriendlyByteBuf, SubscribeBotC2S> CODEC = StreamCodec.ofMember(SubscribeBotC2S::write, SubscribeBotC2S::new);

    private SubscribeBotC2S(RegistryFriendlyByteBuf buf) {
        this(buf.readUtf(PayloadLimits.BOT_NAME_LENGTH), buf.readBoolean());
    }

    private void write(RegistryFriendlyByteBuf buf) {
        buf.writeUtf(botName, PayloadLimits.BOT_NAME_LENGTH);
        buf.writeBoolean(subscribe);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return ID;
    }
}
