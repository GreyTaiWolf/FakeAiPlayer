package io.github.greytaiwolf.fakeaiplayer.network.payload;

import io.github.greytaiwolf.fakeaiplayer.FakeAiPlayer;
import java.util.Objects;
import java.util.UUID;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** A bounded status code; raw provider errors and credentials must never travel in this payload. */
public record BotAiCredentialStatusS2C(
        String botName,
        UUID nonce,
        String statusKey,
        boolean connected,
        boolean forgetLocal
) implements CustomPacketPayload {
    public static final UUID NO_NONCE = new UUID(0L, 0L);
    public static final String CONNECTED = "connected";
    public static final String RESTORED = "restored";
    public static final String DISCONNECTED = "disconnected";
    public static final String INVALID_KEY = "invalid_key";
    public static final String UNAUTHORIZED = "unauthorized";
    public static final String BOT_NOT_FOUND = "bot_not_found";
    public static final String REQUEST_EXPIRED = "request_expired";
    public static final String PROVIDER_ERROR = "provider_error";
    public static final String RATE_LIMITED = "rate_limited";
    public static final String BUSY = "busy";
    public static final Type<BotAiCredentialStatusS2C> ID = new Type<>(
            ResourceLocation.fromNamespaceAndPath(FakeAiPlayer.MOD_ID, "bot_ai_credential_status"));
    public static final StreamCodec<RegistryFriendlyByteBuf, BotAiCredentialStatusS2C> CODEC =
            StreamCodec.ofMember(BotAiCredentialStatusS2C::write, BotAiCredentialStatusS2C::new);

    public BotAiCredentialStatusS2C {
        if (!PayloadLimits.validBotName(botName)) {
            throw new IllegalArgumentException("invalid bot name");
        }
        Objects.requireNonNull(nonce, "nonce");
        PayloadLimits.requireUtf(statusKey, PayloadLimits.CREDENTIAL_STATUS_LENGTH, "credential status");
    }

    private BotAiCredentialStatusS2C(RegistryFriendlyByteBuf buffer) {
        this(
                buffer.readUtf(PayloadLimits.BOT_NAME_LENGTH),
                buffer.readUUID(),
                buffer.readUtf(PayloadLimits.CREDENTIAL_STATUS_LENGTH),
                buffer.readBoolean(),
                buffer.readBoolean());
    }

    private void write(RegistryFriendlyByteBuf buffer) {
        buffer.writeUtf(botName, PayloadLimits.BOT_NAME_LENGTH);
        buffer.writeUUID(nonce);
        buffer.writeUtf(statusKey, PayloadLimits.CREDENTIAL_STATUS_LENGTH);
        buffer.writeBoolean(connected);
        buffer.writeBoolean(forgetLocal);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return ID;
    }
}
