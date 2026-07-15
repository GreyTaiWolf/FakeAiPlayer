package io.github.greytaiwolf.fakeaiplayer.network.payload;

import io.github.greytaiwolf.fakeaiplayer.FakeAiPlayer;
import java.util.UUID;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Confirms the exact transformed projection digest and revision the player inspected. */
public record BuildingPreviewConfirmC2S(UUID sessionId, String previewHash, int transformRevision)
        implements CustomPacketPayload {
    public static final Type<BuildingPreviewConfirmC2S> ID = new Type<>(
            ResourceLocation.fromNamespaceAndPath(FakeAiPlayer.MOD_ID, "building_preview_confirm"));
    public static final StreamCodec<RegistryFriendlyByteBuf, BuildingPreviewConfirmC2S> CODEC =
            StreamCodec.ofMember(BuildingPreviewConfirmC2S::write, BuildingPreviewConfirmC2S::new);

    public BuildingPreviewConfirmC2S {
        if (sessionId == null || !PayloadLimits.validSha256Hex(previewHash)) {
            throw new IllegalArgumentException("invalid preview confirmation identity");
        }
    }

    private BuildingPreviewConfirmC2S(RegistryFriendlyByteBuf buf) {
        this(buf.readUUID(), buf.readUtf(PayloadLimits.PREVIEW_HASH_LENGTH), buf.readInt());
    }

    private void write(RegistryFriendlyByteBuf buf) {
        buf.writeUUID(sessionId);
        buf.writeUtf(previewHash, PayloadLimits.PREVIEW_HASH_LENGTH);
        buf.writeInt(transformRevision);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return ID;
    }
}
