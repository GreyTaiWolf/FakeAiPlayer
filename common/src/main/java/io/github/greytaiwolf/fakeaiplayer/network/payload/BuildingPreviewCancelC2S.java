package io.github.greytaiwolf.fakeaiplayer.network.payload;

import io.github.greytaiwolf.fakeaiplayer.FakeAiPlayer;
import java.util.UUID;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record BuildingPreviewCancelC2S(UUID sessionId) implements CustomPacketPayload {
    public static final Type<BuildingPreviewCancelC2S> ID = new Type<>(
            ResourceLocation.fromNamespaceAndPath(FakeAiPlayer.MOD_ID, "building_preview_cancel"));
    public static final StreamCodec<RegistryFriendlyByteBuf, BuildingPreviewCancelC2S> CODEC =
            StreamCodec.ofMember(BuildingPreviewCancelC2S::write, BuildingPreviewCancelC2S::new);

    public BuildingPreviewCancelC2S {
        if (sessionId == null) {
            throw new IllegalArgumentException("preview session is missing");
        }
    }

    private BuildingPreviewCancelC2S(RegistryFriendlyByteBuf buf) {
        this(buf.readUUID());
    }

    private void write(RegistryFriendlyByteBuf buf) {
        buf.writeUUID(sessionId);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return ID;
    }
}
