package io.github.greytaiwolf.fakeaiplayer.building.site;

import io.github.greytaiwolf.fakeaiplayer.entity.AIPlayerEntity;
import io.github.greytaiwolf.fakeaiplayer.mode.CapabilityRuntime;
import io.github.greytaiwolf.fakeaiplayer.mode.ObservableWorldQuery;
import io.github.greytaiwolf.fakeaiplayer.mode.PrivilegedCapability;
import io.github.greytaiwolf.fakeaiplayer.pathfinding.Standability;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/** Main-thread, loaded-chunk-only site snapshotter. */
public final class BuildingSiteSurveyor {
    public static final int MAX_SURVEY_COLUMNS = 128 * 128;
    public static final int DEFAULT_VERTICAL_RANGE = 8;
    /** Hard ceiling shared by every candidate considered during one site-selection call. */
    public static final int MAX_SELECTION_STANDABILITY_PROBES = 40_000;

    private BuildingSiteSurveyor() {
    }

    public static Optional<BuildingSiteSurvey> survey(AIPlayerEntity bot,
                                                       BlockPos horizontalOrigin,
                                                       int width,
                                                       int depth,
                                                       int preferredY) {
        if (bot == null || horizontalOrigin == null || width < 1 || depth < 1
                || (long) width * depth > MAX_SURVEY_COLUMNS) {
            return Optional.empty();
        }
        boolean hiddenScanAllowed = CapabilityRuntime.decide(
                bot, PrivilegedCapability.HIDDEN_BLOCK_SCAN, "building_site_survey").allowed();
        return survey(bot, horizontalOrigin, width, depth, preferredY,
                new ProbeBudget(MAX_SELECTION_STANDABILITY_PROBES), hiddenScanAllowed);
    }

    static Optional<BuildingSiteSurvey> survey(AIPlayerEntity bot,
                                               BlockPos horizontalOrigin,
                                               int width,
                                               int depth,
                                               int preferredY,
                                               ProbeBudget probeBudget,
                                               boolean hiddenScanAllowed) {
        if (bot == null || horizontalOrigin == null || width < 1 || depth < 1
                || (long) width * depth > MAX_SURVEY_COLUMNS || probeBudget == null) {
            return Optional.empty();
        }
        ServerLevel world = bot.serverLevel();
        List<Integer> heights = new ArrayList<>(width * depth);
        int minimum = Integer.MAX_VALUE;
        int maximum = Integer.MIN_VALUE;
        int water = 0;
        int blocked = 0;
        int unloaded = 0;
        double sum = 0.0D;

        for (int z = 0; z < depth; z++) {
            for (int x = 0; x < width; x++) {
                if (probeBudget.exhausted()) {
                    return Optional.empty();
                }
                int worldX = horizontalOrigin.getX() + x;
                int worldZ = horizontalOrigin.getZ() + z;
                BlockPos probe = new BlockPos(worldX, preferredY, worldZ);
                if (!world.hasChunkAt(probe)) {
                    unloaded++;
                    heights.add(preferredY);
                    minimum = Math.min(minimum, preferredY);
                    maximum = Math.max(maximum, preferredY);
                    sum += preferredY;
                    continue;
                }
                OptionalInt surface = findSurface(
                        bot, world, worldX, worldZ, preferredY, DEFAULT_VERTICAL_RANGE,
                        hiddenScanAllowed, probeBudget);
                if (surface.isEmpty()) {
                    blocked++;
                    heights.add(preferredY);
                    minimum = Math.min(minimum, preferredY);
                    maximum = Math.max(maximum, preferredY);
                    sum += preferredY;
                    continue;
                }
                int feetY = surface.getAsInt();
                heights.add(feetY);
                minimum = Math.min(minimum, feetY);
                maximum = Math.max(maximum, feetY);
                sum += feetY;
                BlockPos feet = new BlockPos(worldX, feetY, worldZ);
                if (!world.getFluidState(feet).isEmpty()
                        || !world.getFluidState(feet.below()).isEmpty()) {
                    water++;
                }
            }
        }
        if (heights.isEmpty()) {
            return Optional.empty();
        }
        double mean = sum / heights.size();
        double variance = 0.0D;
        for (int height : heights) {
            double delta = height - mean;
            variance += delta * delta;
        }
        variance /= heights.size();
        BlockPos origin = new BlockPos(
                horizontalOrigin.getX(), preferredY, horizontalOrigin.getZ());
        return Optional.of(new BuildingSiteSurvey(
                world.dimension().location().toString(), origin,
                width, depth, heights, minimum, maximum,
                water, blocked, unloaded, variance, ""));
    }

    /**
     * Samples the four corners, edge midpoints and centre without scanning the whole footprint.
     * Site selection uses this immutable summary to choose a small number of full surveys.
     */
    public static Optional<CoarseSurvey> coarseSurvey(AIPlayerEntity bot,
                                                       BlockPos horizontalOrigin,
                                                       int width,
                                                       int depth,
                                                       int preferredY) {
        if (bot == null || horizontalOrigin == null || width < 1 || depth < 1
                || (long) width * depth > MAX_SURVEY_COLUMNS) {
            return Optional.empty();
        }
        boolean hiddenScanAllowed = CapabilityRuntime.decide(
                bot, PrivilegedCapability.HIDDEN_BLOCK_SCAN, "building_site_coarse_survey").allowed();
        return coarseSurvey(bot, horizontalOrigin, width, depth, preferredY,
                new ProbeBudget(MAX_SELECTION_STANDABILITY_PROBES), hiddenScanAllowed);
    }

    static Optional<CoarseSurvey> coarseSurvey(AIPlayerEntity bot,
                                               BlockPos horizontalOrigin,
                                               int width,
                                               int depth,
                                               int preferredY,
                                               ProbeBudget probeBudget,
                                               boolean hiddenScanAllowed) {
        if (bot == null || horizontalOrigin == null || width < 1 || depth < 1
                || (long) width * depth > MAX_SURVEY_COLUMNS || probeBudget == null) {
            return Optional.empty();
        }
        ServerLevel world = bot.serverLevel();
        Set<SampleColumn> columns = new LinkedHashSet<>();
        int[] xs = {0, (width - 1) / 2, width - 1};
        int[] zs = {0, (depth - 1) / 2, depth - 1};
        for (int z : zs) {
            for (int x : xs) {
                columns.add(new SampleColumn(x, z));
            }
        }

        List<Integer> heights = new ArrayList<>(columns.size());
        int minimum = Integer.MAX_VALUE;
        int maximum = Integer.MIN_VALUE;
        int water = 0;
        int blocked = 0;
        int unloaded = 0;
        double sum = 0.0D;
        for (SampleColumn column : columns) {
            if (probeBudget.exhausted()) {
                return Optional.empty();
            }
            int worldX = horizontalOrigin.getX() + column.x();
            int worldZ = horizontalOrigin.getZ() + column.z();
            BlockPos probe = new BlockPos(worldX, preferredY, worldZ);
            if (!world.hasChunkAt(probe)) {
                unloaded++;
                continue;
            }
            OptionalInt surface = findSurface(
                    bot, world, worldX, worldZ, preferredY, DEFAULT_VERTICAL_RANGE,
                    hiddenScanAllowed, probeBudget);
            if (surface.isEmpty()) {
                blocked++;
                continue;
            }
            int feetY = surface.getAsInt();
            heights.add(feetY);
            minimum = Math.min(minimum, feetY);
            maximum = Math.max(maximum, feetY);
            sum += feetY;
            BlockPos feet = new BlockPos(worldX, feetY, worldZ);
            if (!world.getFluidState(feet).isEmpty()
                    || !world.getFluidState(feet.below()).isEmpty()) {
                water++;
            }
        }
        if (heights.isEmpty()) {
            return Optional.of(new CoarseSurvey(
                    columns.size(), 0, 0, water, blocked, unloaded, Double.POSITIVE_INFINITY));
        }
        double mean = sum / heights.size();
        double variance = 0.0D;
        for (int height : heights) {
            double delta = height - mean;
            variance += delta * delta;
        }
        variance /= heights.size();
        return Optional.of(new CoarseSurvey(
                columns.size(), minimum, maximum, water, blocked, unloaded, variance));
    }

    private static OptionalInt findSurface(AIPlayerEntity bot,
                                           ServerLevel world,
                                           int x,
                                           int z,
                                           int preferredY,
                                           int verticalRange,
                                           boolean hiddenScanAllowed,
                                           ProbeBudget probeBudget) {
        for (int delta = 0; delta <= verticalRange; delta++) {
            int high = preferredY + delta;
            if (!probeBudget.tryProbe()) {
                return OptionalInt.empty();
            }
            if (standable(bot, world, x, high, z, hiddenScanAllowed)) {
                return OptionalInt.of(high);
            }
            if (delta > 0) {
                int low = preferredY - delta;
                if (!probeBudget.tryProbe()) {
                    return OptionalInt.empty();
                }
                if (standable(bot, world, x, low, z, hiddenScanAllowed)) {
                    return OptionalInt.of(low);
                }
            }
        }
        return OptionalInt.empty();
    }

    private static boolean standable(AIPlayerEntity bot,
                                     ServerLevel world,
                                     int x,
                                     int y,
                                     int z,
                                     boolean hiddenScanAllowed) {
        if (y <= world.getMinY() || y >= world.getMinY() + world.getHeight() - 1) {
            return false;
        }
        BlockPos feet = new BlockPos(x, y, z);
        if (!world.hasChunkAt(feet)) {
            return false;
        }
        if (!hiddenScanAllowed
                && (!ObservableWorldQuery.canObserveBlock(bot, feet.below())
                || !ObservableWorldQuery.canObserveCell(bot, feet)
                || !ObservableWorldQuery.canObserveCell(bot, feet.above()))) {
            return false;
        }
        return Standability.isStandable(world, feet);
    }

    public record CoarseSurvey(
            int sampledColumns,
            int minimumSurfaceY,
            int maximumSurfaceY,
            int waterColumns,
            int blockedColumns,
            int unloadedColumns,
            double variance
    ) {
        public CoarseSurvey {
            if (sampledColumns < 1 || waterColumns < 0 || blockedColumns < 0
                    || unloadedColumns < 0 || Double.isNaN(variance)) {
                throw new IllegalArgumentException("invalid_coarse_building_site_survey");
            }
        }

        public boolean complete() {
            return blockedColumns == 0 && unloadedColumns == 0;
        }

        public int terrainSpan() {
            return complete() ? maximumSurfaceY - minimumSurfaceY : Integer.MAX_VALUE;
        }
    }

    private record SampleColumn(int x, int z) {
    }

    /** Mutable main-thread counter; one instance is shared across all candidates and orientations. */
    static final class ProbeBudget {
        private final int maximum;
        private int used;

        ProbeBudget(int maximum) {
            if (maximum < 1) {
                throw new IllegalArgumentException("building_site_probe_budget_must_be_positive");
            }
            this.maximum = maximum;
        }

        boolean tryProbe() {
            if (used >= maximum) {
                return false;
            }
            used++;
            return true;
        }

        boolean exhausted() {
            return used >= maximum;
        }

        int used() {
            return used;
        }

        int maximum() {
            return maximum;
        }
    }
}
