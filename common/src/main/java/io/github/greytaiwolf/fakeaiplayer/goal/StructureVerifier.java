package io.github.greytaiwolf.fakeaiplayer.goal;

import io.github.greytaiwolf.fakeaiplayer.action.MaterialPalette;
import io.github.greytaiwolf.fakeaiplayer.building.plan.BlockStateResolver;
import io.github.greytaiwolf.fakeaiplayer.building.plan.BlockStateSpec;
import io.github.greytaiwolf.fakeaiplayer.building.plan.CellOperation;
import io.github.greytaiwolf.fakeaiplayer.task.BlueprintSchema;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;

public final class StructureVerifier {
    private StructureVerifier() {
    }

    public static StructureReport verify(ServerLevel world,
                                         BlueprintSchema blueprint,
                                         BlockPos anchor,
                                         int placed,
                                         int skipped) {
        if (blueprint == null || anchor == null || blueprint.placements() == null) {
            return new StructureReport("", 0, 0, placed, skipped, 0);
        }
        int matched = 0;
        int mismatched = 0;
        for (BlueprintSchema.BlockPlacement placement : blueprint.placements()) {
            if (matches(world, anchor, placement)) {
                matched++;
            } else {
                mismatched++;
            }
        }
        return new StructureReport(compact(anchor), blueprint.placements().size(), matched, placed, skipped, mismatched);
    }

    public static boolean matches(ServerLevel world,
                                  BlockPos anchor,
                                  BlueprintSchema.BlockPlacement placement) {
        if (placement == null || placement.blockId() == null) {
            return false;
        }
        ResourceLocation expectedId;
        try {
            expectedId = ResourceLocation.parse(placement.blockId());
        } catch (RuntimeException exception) {
            return false;
        }
        if (world == null || anchor == null) {
            return false;
        }
        BlockPos pos = anchor.offset(placement.dx(), placement.dy(), placement.dz());
        var state = world.getBlockState(pos);
        // CLEAR is an operation, not a magic block ID. This also makes verification robust for
        // legacy/serialized air variants while PRESERVE of a specific state still uses the exact
        // state matcher below.
        if (placement.operation() == CellOperation.CLEAR) {
            return state.isAir();
        }
        if (placement.palette() != null && !placement.palette().isBlank()) {
            return MaterialPalette.matchesBlock(state, placement.palette())
                    && BlockStateResolver.matchesProperties(state, placement.properties());
        }
        try {
            return BlockStateResolver.matches(
                    state,
                    new BlockStateSpec(expectedId.toString(), placement.properties()));
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private static String compact(BlockPos pos) {
        return pos.getX() + "," + pos.getY() + "," + pos.getZ();
    }
}
