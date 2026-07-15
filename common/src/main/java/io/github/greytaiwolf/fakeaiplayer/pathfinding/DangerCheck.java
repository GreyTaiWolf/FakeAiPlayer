package io.github.greytaiwolf.fakeaiplayer.pathfinding;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

public final class DangerCheck {
    private DangerCheck() {
    }

    public static String scan(ServerLevel world, BlockPos node) {
        return scan(world, node, TraversalPolicy.TASK_WALK_DRY);
    }

    public static String scan(ServerLevel world, BlockPos node, TraversalPolicy policy) {
        if (node.getY() < world.getMinY() + 1) {
            return "void";
        }
        BlockState at = world.getBlockState(node);
        BlockState head = world.getBlockState(node.above());
        BlockState below = world.getBlockState(node.below());
        if (policy.requiresDryPath()) {
            if (at.getFluidState().is(FluidTags.WATER)) {
                return "water_at";
            }
            if (head.getFluidState().is(FluidTags.WATER)) {
                return "water_head";
            }
            if (below.getFluidState().is(FluidTags.WATER)) {
                return "water_below";
            }
            if (!at.getFluidState().isEmpty()
                    || !head.getFluidState().isEmpty()
                    || !below.getFluidState().isEmpty()) {
                return "fluid_in_column";
            }
        }
        if (at.getFluidState().is(FluidTags.LAVA)) {
            return "lava_at";
        }
        if (head.getFluidState().is(FluidTags.LAVA)) {
            return "lava_head";
        }
        if (below.getFluidState().is(FluidTags.LAVA)) {
            return "lava_below";
        }
        if (below.is(Blocks.FIRE) || below.is(Blocks.SOUL_FIRE)) {
            return "fire_below";
        }
        if (below.is(Blocks.MAGMA_BLOCK)) {
            return "magma_below";
        }
        if (below.is(Blocks.CACTUS)) {
            return "cactus_below";
        }
        if (Standability.isDangerous(at)) {
            return "danger_at";
        }
        if (Standability.isDangerous(below)) {
            return "danger_below";
        }
        return null;
    }
}
