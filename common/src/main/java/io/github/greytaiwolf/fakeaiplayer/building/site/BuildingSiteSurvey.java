package io.github.greytaiwolf.fakeaiplayer.building.site;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import net.minecraft.core.BlockPos;

/**
 * Immutable, bounded surface snapshot used to score and adapt one building footprint.
 *
 * <p>Values are player-feet Y coordinates: the supporting terrain block is one block below.
 * Keeping the sampled grid in the confirmed plan prevents a later heightmap read from silently
 * changing which foundation variant a building code resolves to.</p>
 */
public record BuildingSiteSurvey(
        String dimension,
        BlockPos horizontalOrigin,
        int width,
        int depth,
        List<Integer> surfaceY,
        int minimumSurfaceY,
        int maximumSurfaceY,
        int waterColumns,
        int blockedColumns,
        int unloadedColumns,
        double variance,
        String signature
) {
    public BuildingSiteSurvey {
        if (dimension == null || dimension.isBlank()
                || horizontalOrigin == null || width < 1 || depth < 1) {
            throw new IllegalArgumentException("invalid_building_site_survey_bounds");
        }
        horizontalOrigin = horizontalOrigin.immutable();
        surfaceY = surfaceY == null ? List.of() : List.copyOf(surfaceY);
        if (surfaceY.size() != width * depth) {
            throw new IllegalArgumentException("building_site_surface_grid_size_mismatch");
        }
        if (waterColumns < 0 || blockedColumns < 0 || unloadedColumns < 0) {
            throw new IllegalArgumentException("building_site_negative_counter");
        }
        DerivedSurface derived = derive(surfaceY);
        if (minimumSurfaceY != derived.minimum()
                || maximumSurfaceY != derived.maximum()) {
            throw new IllegalArgumentException("building_site_surface_range_mismatch");
        }
        if (!Double.isFinite(variance)
                || Double.doubleToLongBits(variance)
                != Double.doubleToLongBits(derived.variance())) {
            throw new IllegalArgumentException("building_site_surface_variance_mismatch");
        }
        // Store the values recomputed from the immutable grid, never caller-controlled aliases.
        minimumSurfaceY = derived.minimum();
        maximumSurfaceY = derived.maximum();
        variance = derived.variance();
        String expected = fingerprint(
                dimension, horizontalOrigin, width, depth, surfaceY,
                minimumSurfaceY, maximumSurfaceY, variance,
                waterColumns, blockedColumns, unloadedColumns);
        if (signature == null || signature.isBlank()) {
            signature = expected;
        } else if (!signature.equals(expected)) {
            throw new IllegalArgumentException("building_site_signature_mismatch");
        }
    }

    public int surfaceY(int x, int z) {
        if (x < 0 || x >= width || z < 0 || z >= depth) {
            throw new IndexOutOfBoundsException(x + "," + z);
        }
        return surfaceY.get(z * width + x);
    }

    public int terrainSpan() {
        return maximumSurfaceY - minimumSurfaceY;
    }

    public boolean complete() {
        return unloadedColumns == 0 && blockedColumns == 0;
    }

    public BlockPos planAnchor() {
        return new BlockPos(horizontalOrigin.getX(), minimumSurfaceY, horizontalOrigin.getZ());
    }

    public static String fingerprint(String dimension,
                                     BlockPos origin,
                                     int width,
                                     int depth,
                                     List<Integer> surfaceY,
                                     int minimumSurfaceY,
                                     int maximumSurfaceY,
                                     double variance,
                                     int waterColumns,
                                     int blockedColumns,
                                     int unloadedColumns) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            update(digest, "fakeaiplayer-site-survey-v2");
            update(digest, dimension);
            update(digest, origin.getX() + "," + origin.getY() + "," + origin.getZ());
            update(digest, width + "x" + depth);
            for (Integer value : surfaceY) {
                update(digest, Integer.toString(value == null ? Integer.MIN_VALUE : value));
            }
            update(digest, minimumSurfaceY + "," + maximumSurfaceY);
            update(digest, Long.toUnsignedString(Double.doubleToLongBits(variance)));
            update(digest, waterColumns + "," + blockedColumns + "," + unloadedColumns);
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("sha256_unavailable", impossible);
        }
    }

    private static DerivedSurface derive(List<Integer> surfaceY) {
        int minimum = Integer.MAX_VALUE;
        int maximum = Integer.MIN_VALUE;
        double sum = 0.0D;
        for (int height : surfaceY) {
            minimum = Math.min(minimum, height);
            maximum = Math.max(maximum, height);
            sum += height;
        }
        double mean = sum / surfaceY.size();
        double variance = 0.0D;
        for (int height : surfaceY) {
            double delta = height - mean;
            variance += delta * delta;
        }
        return new DerivedSurface(minimum, maximum, variance / surfaceY.size());
    }

    private static void update(MessageDigest digest, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update((byte) (bytes.length >>> 24));
        digest.update((byte) (bytes.length >>> 16));
        digest.update((byte) (bytes.length >>> 8));
        digest.update((byte) bytes.length);
        digest.update(bytes);
    }

    private record DerivedSurface(int minimum, int maximum, double variance) {
    }
}
