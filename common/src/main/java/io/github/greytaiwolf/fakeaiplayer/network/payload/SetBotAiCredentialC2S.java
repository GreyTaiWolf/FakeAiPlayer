package io.github.greytaiwolf.fakeaiplayer.network.payload;

import io.github.greytaiwolf.fakeaiplayer.FakeAiPlayer;
import java.util.Objects;
import java.util.UUID;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Submits a credential against the short-lived, server-issued setup nonce. */
public record SetBotAiCredentialC2S(String botName, UUID nonce, String apiKey)
        implements CustomPacketPayload {
    public static final Type<SetBotAiCredentialC2S> ID = new Type<>(
            ResourceLocation.fromNamespaceAndPath(FakeAiPlayer.MOD_ID, "set_bot_ai_credential"));
    public static final StreamCodec<RegistryFriendlyByteBuf, SetBotAiCredentialC2S> CODEC =
            StreamCodec.ofMember(SetBotAiCredentialC2S::write, SetBotAiCredentialC2S::new);

    public SetBotAiCredentialC2S {
        if (!PayloadLimits.validBotName(botName)) {
            throw new IllegalArgumentException("invalid bot name");
        }
        Objects.requireNonNull(nonce, "nonce");
        if (!PayloadLimits.validApiKey(apiKey)) {
            throw new IllegalArgumentException("invalid API credential");
        }
    }

    private SetBotAiCredentialC2S(RegistryFriendlyByteBuf buffer) {
        this(
                buffer.readUtf(PayloadLimits.BOT_NAME_LENGTH),
                buffer.readUUID(),
                buffer.readUtf(PayloadLimits.API_KEY_LENGTH));
    }

    private void write(RegistryFriendlyByteBuf buffer) {
        buffer.writeUtf(botName, PayloadLimits.BOT_NAME_LENGTH);
        buffer.writeUUID(nonce);
        buffer.writeUtf(apiKey, PayloadLimits.API_KEY_LENGTH);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return ID;
    }

    /** Never allow packet diagnostics to print the bearer token. */
    @Override
    public String toString() {
        return "SetBotAiCredentialC2S[botName=" + botName + ", nonce=" + nonce
                + ", apiKey=<redacted>]";
    }
}
