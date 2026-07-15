package io.github.greytaiwolf.fakeaiplayer.network.payload;

import io.github.greytaiwolf.fakeaiplayer.FakeAiPlayer;
import java.util.UUID;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Atomically publishes a completely received building projection on the client. */
public record BuildingPreviewCommitS2C(UUID sessionId, String previewHash, int transformRevision)
        implements CustomPacketPayload {
    public static final Type<BuildingPreviewCommitS2C> ID = new Type<>(
            ResourceLocation.fromNamespaceAndPath(FakeAiPlayer.MOD_ID, "building_preview_commit"));
    public static final StreamCodec<RegistryFriendlyByteBuf, BuildingPreviewCommitS2C> CODEC =
            StreamCodec.ofMember(BuildingPreviewCommitS2C::write, BuildingPreviewCommitS2C::new);

    public BuildingPreviewCommitS2C {
        if (sessionId == null || !PayloadLimits.validSha256Hex(previewHash)) {
            throw new IllegalArgumentException("invalid preview commit identity");
        }
    }

    private BuildingPreviewCommitS2C(RegistryFriendlyByteBuf buf) {
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
