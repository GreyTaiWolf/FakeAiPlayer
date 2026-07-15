package io.github.greytaiwolf.fakeaiplayer.network.payload;

import io.github.greytaiwolf.fakeaiplayer.FakeAiPlayer;
import java.util.UUID;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Client acknowledgement that Begin/Chunk/Commit passed digest verification and became visible. */
public record BuildingPreviewReadyC2S(UUID sessionId, String previewHash, int transformRevision)
        implements CustomPacketPayload {
    public static final Type<BuildingPreviewReadyC2S> ID = new Type<>(
            ResourceLocation.fromNamespaceAndPath(FakeAiPlayer.MOD_ID, "building_preview_ready"));
    public static final StreamCodec<RegistryFriendlyByteBuf, BuildingPreviewReadyC2S> CODEC =
            StreamCodec.ofMember(BuildingPreviewReadyC2S::write, BuildingPreviewReadyC2S::new);

    public BuildingPreviewReadyC2S {
        if (sessionId == null || !PayloadLimits.validSha256Hex(previewHash)) {
            throw new IllegalArgumentException("invalid preview-ready identity");
        }
    }

    private BuildingPreviewReadyC2S(RegistryFriendlyByteBuf buf) {
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
