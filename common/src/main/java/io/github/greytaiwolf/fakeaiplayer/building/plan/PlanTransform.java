package io.github.greytaiwolf.fakeaiplayer.building.plan;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;

/**
 * One transform shared by projection, material preview, construction and verification.
 */
public record PlanTransform(Mirror mirror, Rotation rotation) {
    public static final PlanTransform IDENTITY = new PlanTransform(Mirror.NONE, Rotation.NONE);

    public PlanTransform {
        mirror = mirror == null ? Mirror.NONE : mirror;
        rotation = rotation == null ? Rotation.NONE : rotation;
    }

    /** Apply vanilla mirror/rotation around the local origin, then normalize into positive bounds. */
    public BlockPos apply(BlockPos local, int width, int depth) {
        requireDimensions(width, depth);
        Bounds bounds = bounds(width, depth);
        BlockPos transformed = StructureTemplate.transform(local, mirror, rotation, BlockPos.ZERO);
        return transformed.offset(-bounds.minX(), 0, -bounds.minZ());
    }

    public BlockStateSpec apply(BlockStateSpec state) {
        return BlockStateResolver.transform(state, mirror, rotation);
    }

    public int transformedWidth(int width, int depth) {
        Bounds bounds = bounds(width, depth);
        return bounds.maxX() - bounds.minX() + 1;
    }

    public int transformedDepth(int width, int depth) {
        Bounds bounds = bounds(width, depth);
        return bounds.maxZ() - bounds.minZ() + 1;
    }

    private Bounds bounds(int width, int depth) {
        requireDimensions(width, depth);
        int minX = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxZ = Integer.MIN_VALUE;
        for (int x : new int[]{0, width - 1}) {
            for (int z : new int[]{0, depth - 1}) {
                BlockPos corner = StructureTemplate.transform(
                        new BlockPos(x, 0, z), mirror, rotation, BlockPos.ZERO);
                minX = Math.min(minX, corner.getX());
                minZ = Math.min(minZ, corner.getZ());
                maxX = Math.max(maxX, corner.getX());
                maxZ = Math.max(maxZ, corner.getZ());
            }
        }
        return new Bounds(minX, minZ, maxX, maxZ);
    }

    private static void requireDimensions(int width, int depth) {
        if (width < 1 || depth < 1) {
            throw new IllegalArgumentException("invalid_plan_footprint: " + width + "x" + depth);
        }
    }

    private record Bounds(int minX, int minZ, int maxX, int maxZ) {
    }
}
