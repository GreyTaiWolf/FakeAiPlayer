package io.github.greytaiwolf.fakeaiplayer.pathfinding;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;

public final class Standability {
    private static final Map<CacheKey, Boolean> CACHE = new ConcurrentHashMap<>(4096);
    private static volatile long version;

    private Standability() {
    }

    public static void clearCache() {
        CACHE.clear();
    }

    public static void invalidateAll() {
        version++;
        CACHE.clear();
    }

    public static boolean isStandable(ServerLevel world, BlockPos pos) {
        return isStandable(world, pos, TraversalPolicy.TASK_WALK_DRY);
    }

    public static boolean isDryStandable(ServerLevel world, BlockPos pos) {
        return isStandable(world, pos, TraversalPolicy.TASK_WALK_DRY);
    }

    public static boolean isStandable(ServerLevel world, BlockPos pos, TraversalPolicy policy) {
        CacheKey key = new CacheKey(world.dimension().location().toString(), version, pos, policy);
        Boolean cached = CACHE.get(key);
        if (cached != null) {
            return cached;
        }
        boolean result = compute(world, pos, policy);
        CACHE.put(key, result);
        return result;
    }

    /** Uncached check for live interaction ownership where the supporting cell may just change. */
    public static boolean isStandableFresh(
            ServerLevel world, BlockPos pos, TraversalPolicy policy) {
        return compute(world, pos, policy);
    }

    public static Optional<BlockPos> findNearestStandable(ServerLevel world,
                                                          BlockPos origin,
                                                          int horizontalRadius,
                                                          int verticalDown,
                                                          int verticalUp) {
        return findNearestStandable(
                world, origin, horizontalRadius, verticalDown, verticalUp,
                TraversalPolicy.TASK_WALK_DRY, ignored -> true);
    }

    public static Optional<BlockPos> findNearestStandable(ServerLevel world,
                                                          BlockPos origin,
                                                          int horizontalRadius,
                                                          int verticalDown,
                                                          int verticalUp,
                                                          TraversalPolicy policy) {
        return findNearestStandable(
                world, origin, horizontalRadius, verticalDown, verticalUp, policy, ignored -> true);
    }

    public static Optional<BlockPos> findNearestStandable(ServerLevel world,
                                                          BlockPos origin,
                                                          int horizontalRadius,
                                                          int verticalDown,
                                                          int verticalUp,
                                                          TraversalPolicy policy,
                                                          Predicate<BlockPos> additionalCheck) {
        Optional<BlockPos> sameColumn = findStandableInColumn(
                world, origin, verticalDown, verticalUp, policy, additionalCheck);
        if (sameColumn.isPresent()) {
            return sameColumn;
        }

        int radiusLimit = Math.max(0, horizontalRadius);
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        for (int radius = 1; radius <= radiusLimit; radius++) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != radius) {
                        continue;
                    }
                    Optional<BlockPos> candidate = findStandableInColumn(
                            world, origin.offset(dx, 0, dz), verticalDown, verticalUp,
                            policy, additionalCheck);
                    if (candidate.isEmpty()) {
                        continue;
                    }
                    double distance = candidate.get().distSqr(origin);
                    if (distance < bestDistance) {
                        best = candidate.get();
                        bestDistance = distance;
                    }
                }
            }
            if (best != null) {
                return Optional.of(best.immutable());
            }
        }
        return Optional.empty();
    }

    private static Optional<BlockPos> findStandableInColumn(ServerLevel world,
                                                            BlockPos origin,
                                                            int verticalDown,
                                                            int verticalUp,
                                                            TraversalPolicy policy,
                                                            Predicate<BlockPos> additionalCheck) {
        int topY = world.getMinY() + world.getHeight();
        int minY = Math.max(world.getMinY() + 1, origin.getY() - Math.max(0, verticalDown));
        int maxY = Math.min(topY - 2, origin.getY() + Math.max(0, verticalUp));
        for (int y = Math.min(origin.getY(), maxY); y >= minY; y--) {
            BlockPos candidate = new BlockPos(origin.getX(), y, origin.getZ());
            if (isStandable(world, candidate, policy) && additionalCheck.test(candidate)) {
                return Optional.of(candidate.immutable());
            }
        }
        for (int y = Math.max(origin.getY() + 1, minY); y <= maxY; y++) {
            BlockPos candidate = new BlockPos(origin.getX(), y, origin.getZ());
            if (isStandable(world, candidate, policy) && additionalCheck.test(candidate)) {
                return Optional.of(candidate.immutable());
            }
        }
        return Optional.empty();
    }

    private static boolean compute(ServerLevel world, BlockPos pos, TraversalPolicy policy) {
        int topY = world.getMinY() + world.getHeight();
        if (pos.getY() < world.getMinY() + 1 || pos.getY() >= topY - 1) {
            return false;
        }

        BlockState feet = world.getBlockState(pos);
        BlockState head = world.getBlockState(pos.above());
        BlockState below = world.getBlockState(pos.below());
        if (policy.requiresDryPath()
                && (!feet.getFluidState().isEmpty()
                || !head.getFluidState().isEmpty()
                || !below.getFluidState().isEmpty())) {
            return false;
        }
        if (!feet.getCollisionShape(world, pos).isEmpty()) {
            return false;
        }
        if (!head.getCollisionShape(world, pos.above()).isEmpty()) {
            return false;
        }
        if (isDangerous(feet) || isDangerous(head) || isDangerous(below)) {
            return false;
        }
        if (policy.allowsWater()
                && (feet.getFluidState().is(FluidTags.WATER)
                || head.getFluidState().is(FluidTags.WATER))) {
            // WATER_CAPABLE is the only policy that models swimming nodes. Unlike a land node it
            // does not require solid support below; the executor still revalidates collision and
            // NavSafetyNet takes over if air becomes unsafe.
            return true;
        }
        // NAV-11:梯子/藤蔓等可攀爬方块,站在其中即可,无需下方支撑。
        if (feet.is(BlockTags.CLIMBABLE)) {
            return true;
        }
        if (below.isAir()) {
            return false;
        }
        return below.getCollisionShape(world, pos.below()).max(Direction.Axis.Y) > 0.0D;
    }

    public static boolean isDangerous(BlockState state) {
        FluidState fluid = state.getFluidState();
        return fluid.is(FluidTags.LAVA)
                || state.is(Blocks.FIRE)
                || state.is(Blocks.SOUL_FIRE)
                || state.is(Blocks.CACTUS)
                || state.is(Blocks.MAGMA_BLOCK)
                || state.is(Blocks.CAMPFIRE)
                || state.is(Blocks.SOUL_CAMPFIRE)
                || state.is(Blocks.SWEET_BERRY_BUSH)
                || state.is(Blocks.WITHER_ROSE)
                || state.is(Blocks.POWDER_SNOW)
                || state.is(Blocks.POINTED_DRIPSTONE);
    }

    private record CacheKey(String dimension, long version, BlockPos pos, TraversalPolicy policy) {
        private CacheKey {
            pos = pos.immutable();
        }
    }
}
