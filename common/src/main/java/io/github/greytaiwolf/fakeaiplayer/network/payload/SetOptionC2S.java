package io.github.greytaiwolf.fakeaiplayer.network.payload;

import io.github.greytaiwolf.fakeaiplayer.FakeAiPlayer;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record SetOptionC2S(String botName, String key, boolean value) implements CustomPacketPayload {
    public static final Type<SetOptionC2S> ID = new Type<>(ResourceLocation.fromNamespaceAndPath(FakeAiPlayer.MOD_ID, "set_option"));
    public static final StreamCodec<RegistryFriendlyByteBuf, SetOptionC2S> CODEC = StreamCodec.ofMember(SetOptionC2S::write, SetOptionC2S::new);

    private SetOptionC2S(RegistryFriendlyByteBuf buf) {
        this(
                buf.readUtf(PayloadLimits.BOT_NAME_LENGTH),
                buf.readUtf(PayloadLimits.OPTION_KEY_LENGTH),
                buf.readBoolean());
    }

    private void write(RegistryFriendlyByteBuf buf) {
        buf.writeUtf(botName, PayloadLimits.BOT_NAME_LENGTH);
        buf.writeUtf(key, PayloadLimits.OPTION_KEY_LENGTH);
        buf.writeBoolean(value);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return ID;
    }
}
