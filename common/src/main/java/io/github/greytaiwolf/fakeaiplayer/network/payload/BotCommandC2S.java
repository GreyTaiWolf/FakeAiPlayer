package io.github.greytaiwolf.fakeaiplayer.network.payload;

import io.github.greytaiwolf.fakeaiplayer.FakeAiPlayer;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record BotCommandC2S(String botName, String action, String arg1, String arg2, int count) implements CustomPacketPayload {
    public static final Type<BotCommandC2S> ID = new Type<>(ResourceLocation.fromNamespaceAndPath(FakeAiPlayer.MOD_ID, "bot_command"));
    public static final StreamCodec<RegistryFriendlyByteBuf, BotCommandC2S> CODEC = StreamCodec.ofMember(BotCommandC2S::write, BotCommandC2S::new);

    private BotCommandC2S(RegistryFriendlyByteBuf buf) {
        this(
                buf.readUtf(PayloadLimits.BOT_NAME_LENGTH),
                buf.readUtf(PayloadLimits.ACTION_LENGTH),
                buf.readUtf(PayloadLimits.COMMAND_ARGUMENT_LENGTH),
                buf.readUtf(PayloadLimits.COMMAND_ARGUMENT_LENGTH),
                buf.readInt());
    }

    private void write(RegistryFriendlyByteBuf buf) {
        buf.writeUtf(botName, PayloadLimits.BOT_NAME_LENGTH);
        buf.writeUtf(action, PayloadLimits.ACTION_LENGTH);
        buf.writeUtf(arg1, PayloadLimits.COMMAND_ARGUMENT_LENGTH);
        buf.writeUtf(arg2, PayloadLimits.COMMAND_ARGUMENT_LENGTH);
        buf.writeInt(count);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return ID;
    }
}
