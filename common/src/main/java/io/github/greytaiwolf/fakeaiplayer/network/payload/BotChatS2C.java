package io.github.greytaiwolf.fakeaiplayer.network.payload;

import io.github.greytaiwolf.fakeaiplayer.FakeAiPlayer;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record BotChatS2C(String botName, String role, String text) implements CustomPacketPayload {
    public static final Type<BotChatS2C> ID = new Type<>(ResourceLocation.fromNamespaceAndPath(FakeAiPlayer.MOD_ID, "bot_chat"));
    public static final StreamCodec<RegistryFriendlyByteBuf, BotChatS2C> CODEC = StreamCodec.ofMember(BotChatS2C::write, BotChatS2C::new);

    private BotChatS2C(RegistryFriendlyByteBuf buf) {
        this(
                buf.readUtf(PayloadLimits.BOT_NAME_LENGTH),
                buf.readUtf(PayloadLimits.ROLE_LENGTH),
                buf.readUtf(PayloadLimits.CHAT_TEXT_LENGTH));
    }

    private void write(RegistryFriendlyByteBuf buf) {
        buf.writeUtf(botName, PayloadLimits.BOT_NAME_LENGTH);
        buf.writeUtf(role, PayloadLimits.ROLE_LENGTH);
        buf.writeUtf(text, PayloadLimits.CHAT_TEXT_LENGTH);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return ID;
    }
}
