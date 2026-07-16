package io.github.greytaiwolf.fakeaiplayer.network.payload;

import io.github.greytaiwolf.fakeaiplayer.FakeAiPlayer;
import java.util.Objects;
import java.util.UUID;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Opens the client-only, masked credential prompt for one authorized bot. */
public record OpenBotAiSetupS2C(String botName, UUID nonce) implements CustomPacketPayload {
    public static final Type<OpenBotAiSetupS2C> ID = new Type<>(
            ResourceLocation.fromNamespaceAndPath(FakeAiPlayer.MOD_ID, "open_bot_ai_setup"));
    public static final StreamCodec<RegistryFriendlyByteBuf, OpenBotAiSetupS2C> CODEC =
            StreamCodec.ofMember(OpenBotAiSetupS2C::write, OpenBotAiSetupS2C::new);

    public OpenBotAiSetupS2C {
        if (!PayloadLimits.validBotName(botName)) {
            throw new IllegalArgumentException("invalid bot name");
        }
        Objects.requireNonNull(nonce, "nonce");
    }

    private OpenBotAiSetupS2C(RegistryFriendlyByteBuf buffer) {
        this(buffer.readUtf(PayloadLimits.BOT_NAME_LENGTH), buffer.readUUID());
    }

    private void write(RegistryFriendlyByteBuf buffer) {
        buffer.writeUtf(botName, PayloadLimits.BOT_NAME_LENGTH);
        buffer.writeUUID(nonce);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return ID;
    }
}
