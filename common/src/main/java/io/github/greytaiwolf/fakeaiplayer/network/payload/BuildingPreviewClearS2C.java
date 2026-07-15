package io.github.greytaiwolf.fakeaiplayer.network.payload;

import io.github.greytaiwolf.fakeaiplayer.FakeAiPlayer;
import java.util.UUID;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Removes a projection after cancel, expiry, rejection, disconnect, or successful queueing. */
public record BuildingPreviewClearS2C(UUID sessionId, String reason) implements CustomPacketPayload {
    public static final Type<BuildingPreviewClearS2C> ID = new Type<>(
            ResourceLocation.fromNamespaceAndPath(FakeAiPlayer.MOD_ID, "building_preview_clear"));
    public static final StreamCodec<RegistryFriendlyByteBuf, BuildingPreviewClearS2C> CODEC =
            StreamCodec.ofMember(BuildingPreviewClearS2C::write, BuildingPreviewClearS2C::new);

    public BuildingPreviewClearS2C {
        if (sessionId == null) {
            throw new IllegalArgumentException("preview session is missing");
        }
        reason = PayloadLimits.truncate(reason, PayloadLimits.PREVIEW_REASON_LENGTH);
    }

    private BuildingPreviewClearS2C(RegistryFriendlyByteBuf buf) {
        this(buf.readUUID(), buf.readUtf(PayloadLimits.PREVIEW_REASON_LENGTH));
    }

    private void write(RegistryFriendlyByteBuf buf) {
        buf.writeUUID(sessionId);
        buf.writeUtf(reason, PayloadLimits.PREVIEW_REASON_LENGTH);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return ID;
    }
}
