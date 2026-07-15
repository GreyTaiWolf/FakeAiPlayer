package io.github.greytaiwolf.fakeaiplayer.building.preview;

import io.github.greytaiwolf.fakeaiplayer.building.plan.BlockStateSpec;
import io.github.greytaiwolf.fakeaiplayer.network.payload.BuildingPreviewChunkS2C;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Canonical digest binding the visible transformed cells to one preview session revision. */
public final class BuildingPreviewFingerprint {
    private static final String FORMAT = "fakeaiplayer-building-preview-v1";

    private BuildingPreviewFingerprint() {
    }

    public static String sha256(
            UUID sessionId,
            UUID botId,
            String planId,
            int planRevision,
            String planHash,
            int transformRevision,
            String dimension,
            int anchorX,
            int anchorY,
            int anchorZ,
            int width,
            int height,
            int depth,
            List<BlockStateSpec> palette,
            List<BuildingPreviewChunkS2C.Cell> cells
    ) {
        if (sessionId == null || botId == null || palette == null || cells == null) {
            throw new IllegalArgumentException("preview fingerprint input is missing");
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            field(digest, FORMAT);
            uuid(digest, sessionId);
            uuid(digest, botId);
            field(digest, planId);
            integer(digest, planRevision);
            field(digest, planHash);
            integer(digest, transformRevision);
            field(digest, dimension);
            integer(digest, anchorX);
            integer(digest, anchorY);
            integer(digest, anchorZ);
            integer(digest, width);
            integer(digest, height);
            integer(digest, depth);
            integer(digest, palette.size());
            for (BlockStateSpec state : palette) {
                field(digest, state.blockId());
                List<Map.Entry<String, String>> properties = state.properties().entrySet().stream()
                        .sorted(Map.Entry.comparingByKey(Comparator.naturalOrder()))
                        .toList();
                integer(digest, properties.size());
                for (Map.Entry<String, String> property : properties) {
                    field(digest, property.getKey());
                    field(digest, property.getValue());
                }
            }
            integer(digest, cells.size());
            for (BuildingPreviewChunkS2C.Cell cell : cells) {
                integer(digest, cell.dx());
                integer(digest, cell.dy());
                integer(digest, cell.dz());
                integer(digest, cell.paletteIndex());
                field(digest, cell.operation().name());
                field(digest, cell.replacePolicy().name());
                field(digest, cell.phase().name());
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static void uuid(MessageDigest digest, UUID value) {
        longValue(digest, value.getMostSignificantBits());
        longValue(digest, value.getLeastSignificantBits());
    }

    private static void field(MessageDigest digest, String value) {
        byte[] bytes = (value == null ? "" : value).getBytes(StandardCharsets.UTF_8);
        integer(digest, bytes.length);
        digest.update(bytes);
    }

    private static void integer(MessageDigest digest, int value) {
        digest.update((byte) (value >>> 24));
        digest.update((byte) (value >>> 16));
        digest.update((byte) (value >>> 8));
        digest.update((byte) value);
    }

    private static void longValue(MessageDigest digest, long value) {
        digest.update((byte) (value >>> 56));
        digest.update((byte) (value >>> 48));
        digest.update((byte) (value >>> 40));
        digest.update((byte) (value >>> 32));
        digest.update((byte) (value >>> 24));
        digest.update((byte) (value >>> 16));
        digest.update((byte) (value >>> 8));
        digest.update((byte) value);
    }
}
