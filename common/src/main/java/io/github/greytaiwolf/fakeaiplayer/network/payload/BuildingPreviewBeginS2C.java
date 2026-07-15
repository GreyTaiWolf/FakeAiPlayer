package io.github.greytaiwolf.fakeaiplayer.network.payload;

import io.github.greytaiwolf.fakeaiplayer.FakeAiPlayer;
import io.github.greytaiwolf.fakeaiplayer.building.plan.BlockStateSpec;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Starts an atomic, chunked transfer of a server-authoritative building projection. */
public record BuildingPreviewBeginS2C(
        UUID sessionId,
        UUID botId,
        String botName,
        String planId,
        int planRevision,
        String planHash,
        String previewHash,
        int transformRevision,
        String dimension,
        int anchorX,
        int anchorY,
        int anchorZ,
        int width,
        int height,
        int depth,
        int placementCount,
        int chunkCount,
        List<BlockStateSpec> palette
) implements CustomPacketPayload {
    public static final Type<BuildingPreviewBeginS2C> ID = new Type<>(
            ResourceLocation.fromNamespaceAndPath(FakeAiPlayer.MOD_ID, "building_preview_begin"));
    public static final StreamCodec<RegistryFriendlyByteBuf, BuildingPreviewBeginS2C> CODEC =
            StreamCodec.ofMember(BuildingPreviewBeginS2C::write, BuildingPreviewBeginS2C::new);

    public BuildingPreviewBeginS2C {
        if (sessionId == null || botId == null) {
            throw new IllegalArgumentException("preview identity is missing");
        }
        if (!PayloadLimits.validSha256Hex(planHash)
                || !PayloadLimits.validSha256Hex(previewHash)) {
            throw new IllegalArgumentException("plan and preview hashes must be lowercase SHA-256 hex strings");
        }
        if (width < 1 || height < 1 || depth < 1) {
            throw new IllegalArgumentException("preview dimensions must be positive");
        }
        PayloadLimits.requireSize(placementCount, PayloadLimits.MAX_PREVIEW_PLACEMENTS, "preview placements");
        PayloadLimits.requireSize(chunkCount, PayloadLimits.MAX_PREVIEW_CHUNKS, "preview chunks");
        int expectedChunks = (placementCount + PayloadLimits.MAX_PREVIEW_CHUNK_CELLS - 1)
                / PayloadLimits.MAX_PREVIEW_CHUNK_CELLS;
        if (chunkCount != expectedChunks) {
            throw new IllegalArgumentException(
                    "preview chunk count " + chunkCount + " does not match placement count " + placementCount);
        }
        palette = palette == null ? List.of() : List.copyOf(palette);
        PayloadLimits.requireSize(palette.size(), PayloadLimits.MAX_PREVIEW_PALETTE, "preview palette");
        PayloadLimits.requireUtf(botName, PayloadLimits.BOT_NAME_LENGTH, "preview bot name");
        PayloadLimits.requireUtf(planId, PayloadLimits.PREVIEW_PLAN_ID_LENGTH, "preview plan id");
        PayloadLimits.requireUtf(dimension, PayloadLimits.PREVIEW_DIMENSION_LENGTH, "preview dimension");
        for (BlockStateSpec state : palette) {
            if (state == null) {
                throw new IllegalArgumentException("preview palette contains a null state");
            }
            PayloadLimits.requireUtf(state.blockId(), PayloadLimits.ITEM_ID_LENGTH, "preview block id");
            PayloadLimits.requireSize(
                    state.properties().size(), PayloadLimits.MAX_PREVIEW_PROPERTIES, "block properties");
            for (Map.Entry<String, String> property : state.properties().entrySet()) {
                PayloadLimits.requireUtf(
                        property.getKey(), PayloadLimits.BLOCK_PROPERTY_LENGTH, "block property name");
                PayloadLimits.requireUtf(
                        property.getValue(), PayloadLimits.BLOCK_PROPERTY_LENGTH, "block property value");
            }
        }
        if (estimatedEncodedBytes(botName, planId, planHash, previewHash, dimension, palette)
                > PayloadLimits.MAX_PREVIEW_BEGIN_BYTES) {
            throw new IllegalArgumentException(
                    "preview begin exceeds " + PayloadLimits.MAX_PREVIEW_BEGIN_BYTES + " bytes");
        }
    }

    private BuildingPreviewBeginS2C(RegistryFriendlyByteBuf buf) {
        this(
                buf.readUUID(),
                buf.readUUID(),
                buf.readUtf(PayloadLimits.BOT_NAME_LENGTH),
                buf.readUtf(PayloadLimits.PREVIEW_PLAN_ID_LENGTH),
                buf.readInt(),
                buf.readUtf(PayloadLimits.PREVIEW_HASH_LENGTH),
                buf.readUtf(PayloadLimits.PREVIEW_HASH_LENGTH),
                buf.readInt(),
                buf.readUtf(PayloadLimits.PREVIEW_DIMENSION_LENGTH),
                buf.readInt(),
                buf.readInt(),
                buf.readInt(),
                buf.readInt(),
                buf.readInt(),
                buf.readInt(),
                buf.readInt(),
                buf.readInt(),
                readPalette(buf));
    }

    private void write(RegistryFriendlyByteBuf buf) {
        buf.writeUUID(sessionId);
        buf.writeUUID(botId);
        buf.writeUtf(botName, PayloadLimits.BOT_NAME_LENGTH);
        buf.writeUtf(planId, PayloadLimits.PREVIEW_PLAN_ID_LENGTH);
        buf.writeInt(planRevision);
        buf.writeUtf(planHash, PayloadLimits.PREVIEW_HASH_LENGTH);
        buf.writeUtf(previewHash, PayloadLimits.PREVIEW_HASH_LENGTH);
        buf.writeInt(transformRevision);
        buf.writeUtf(dimension, PayloadLimits.PREVIEW_DIMENSION_LENGTH);
        buf.writeInt(anchorX);
        buf.writeInt(anchorY);
        buf.writeInt(anchorZ);
        buf.writeInt(width);
        buf.writeInt(height);
        buf.writeInt(depth);
        buf.writeInt(placementCount);
        buf.writeInt(chunkCount);
        buf.writeInt(palette.size());
        for (BlockStateSpec state : palette) {
            buf.writeUtf(state.blockId(), PayloadLimits.ITEM_ID_LENGTH);
            PayloadLimits.requireSize(
                    state.properties().size(), PayloadLimits.MAX_PREVIEW_PROPERTIES, "block properties");
            buf.writeInt(state.properties().size());
            for (Map.Entry<String, String> property : state.properties().entrySet()) {
                buf.writeUtf(property.getKey(), PayloadLimits.BLOCK_PROPERTY_LENGTH);
                buf.writeUtf(property.getValue(), PayloadLimits.BLOCK_PROPERTY_LENGTH);
            }
        }
    }

    private static List<BlockStateSpec> readPalette(RegistryFriendlyByteBuf buf) {
        int count = PayloadLimits.readSize(buf, PayloadLimits.MAX_PREVIEW_PALETTE, "preview palette");
        List<BlockStateSpec> palette = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            String blockId = buf.readUtf(PayloadLimits.ITEM_ID_LENGTH);
            int propertyCount = PayloadLimits.readSize(
                    buf, PayloadLimits.MAX_PREVIEW_PROPERTIES, "block properties");
            Map<String, String> properties = new TreeMap<>();
            for (int propertyIndex = 0; propertyIndex < propertyCount; propertyIndex++) {
                String name = buf.readUtf(PayloadLimits.BLOCK_PROPERTY_LENGTH);
                String value = buf.readUtf(PayloadLimits.BLOCK_PROPERTY_LENGTH);
                if (properties.put(name, value) != null) {
                    throw new IllegalArgumentException("duplicate block property: " + name);
                }
            }
            palette.add(new BlockStateSpec(blockId, properties));
        }
        return List.copyOf(palette);
    }

    static int estimatedEncodedBytes(String botName,
                                     String planId,
                                     String planHash,
                                     String previewHash,
                                     String dimension,
                                     List<BlockStateSpec> palette) {
        long bytes = 32L + (13L * Integer.BYTES);
        bytes += PayloadLimits.estimatedUtfBytes(botName);
        bytes += PayloadLimits.estimatedUtfBytes(planId);
        bytes += PayloadLimits.estimatedUtfBytes(planHash);
        bytes += PayloadLimits.estimatedUtfBytes(previewHash);
        bytes += PayloadLimits.estimatedUtfBytes(dimension);
        for (BlockStateSpec state : palette) {
            bytes += Integer.BYTES + PayloadLimits.estimatedUtfBytes(state.blockId());
            for (Map.Entry<String, String> property : state.properties().entrySet()) {
                bytes += PayloadLimits.estimatedUtfBytes(property.getKey());
                bytes += PayloadLimits.estimatedUtfBytes(property.getValue());
            }
        }
        return bytes > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) bytes;
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return ID;
    }
}
