package io.github.greytaiwolf.fakeaiplayer.network.payload;

import io.github.greytaiwolf.fakeaiplayer.FakeAiPlayer;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Restores an already validated client-local credential after joining its scoped server. */
public record RestoreBotAiCredentialC2S(String botName, String apiKey)
        implements CustomPacketPayload {
    public static final Type<RestoreBotAiCredentialC2S> ID = new Type<>(
            ResourceLocation.fromNamespaceAndPath(FakeAiPlayer.MOD_ID, "restore_bot_ai_credential"));
    public static final StreamCodec<RegistryFriendlyByteBuf, RestoreBotAiCredentialC2S> CODEC =
            StreamCodec.ofMember(RestoreBotAiCredentialC2S::write, RestoreBotAiCredentialC2S::new);

    public RestoreBotAiCredentialC2S {
        if (!PayloadLimits.validBotName(botName)) {
            throw new IllegalArgumentException("invalid bot name");
        }
        if (!PayloadLimits.validApiKey(apiKey)) {
            throw new IllegalArgumentException("invalid API credential");
        }
    }

    private RestoreBotAiCredentialC2S(RegistryFriendlyByteBuf buffer) {
        this(
                buffer.readUtf(PayloadLimits.BOT_NAME_LENGTH),
                buffer.readUtf(PayloadLimits.API_KEY_LENGTH));
    }

    private void write(RegistryFriendlyByteBuf buffer) {
        buffer.writeUtf(botName, PayloadLimits.BOT_NAME_LENGTH);
        buffer.writeUtf(apiKey, PayloadLimits.API_KEY_LENGTH);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return ID;
    }

    /** Never allow packet diagnostics to print the bearer token. */
    @Override
    public String toString() {
        return "RestoreBotAiCredentialC2S[botName=" + botName + ", apiKey=<redacted>]";
    }
}
