package io.github.greytaiwolf.fakeaiplayer.network.payload;

import io.github.greytaiwolf.fakeaiplayer.FakeAiPlayer;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Requests the same server-authoritative menu used by empty-hand entity interaction. */
public record OpenBotInventoryC2S(String botName) implements CustomPacketPayload {
    public static final Type<OpenBotInventoryC2S> ID = new Type<>(
            ResourceLocation.fromNamespaceAndPath(FakeAiPlayer.MOD_ID, "open_bot_inventory"));
    public static final StreamCodec<RegistryFriendlyByteBuf, OpenBotInventoryC2S> CODEC =
            StreamCodec.ofMember(OpenBotInventoryC2S::write, OpenBotInventoryC2S::new);

    private OpenBotInventoryC2S(RegistryFriendlyByteBuf buffer) {
        this(buffer.readUtf(PayloadLimits.BOT_NAME_LENGTH));
    }

    private void write(RegistryFriendlyByteBuf buffer) {
        buffer.writeUtf(botName == null ? "" : botName, PayloadLimits.BOT_NAME_LENGTH);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return ID;
    }
}
