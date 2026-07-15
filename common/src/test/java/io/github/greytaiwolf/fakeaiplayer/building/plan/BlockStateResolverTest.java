package io.github.greytaiwolf.fakeaiplayer.building.plan;

import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.Half;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BlockStateResolverTest {
    @BeforeAll
    static void bootstrapRegistries() {
        Bootstrap.bootStrap();
    }

    @Test
    void resolvesAndExactlyMatchesDirectionalState() {
        BlockStateSpec stairs = new BlockStateSpec("minecraft:oak_stairs", Map.of(
                "facing", "east",
                "half", "top",
                "shape", "straight",
                "waterlogged", "false"));

        var resolved = BlockStateResolver.resolve(stairs);

        assertEquals(Half.TOP, resolved.getValue(BlockStateProperties.HALF));
        assertTrue(BlockStateResolver.matches(resolved, stairs));
        assertEquals(stairs, BlockStateResolver.encode(resolved));
    }

    @Test
    void rejectsUnknownPropertyNamesAndValues() {
        assertThrows(IllegalArgumentException.class,
                () -> BlockStateResolver.resolve(new BlockStateSpec(
                        "minecraft:oak_stairs", Map.of("missing_property", "north"))));
        assertThrows(IllegalArgumentException.class,
                () -> BlockStateResolver.resolve(new BlockStateSpec(
                        "minecraft:oak_stairs", Map.of("facing", "up"))));
    }

    @Test
    void vanillaRotationTransformsPillarAxis() {
        BlockStateSpec log = BlockStateResolver.encode(Blocks.OAK_LOG.defaultBlockState()
                .setValue(RotatedPillarBlock.AXIS, net.minecraft.core.Direction.Axis.X));

        BlockStateSpec rotated = BlockStateResolver.transform(log, Mirror.NONE, Rotation.CLOCKWISE_90);

        assertEquals("z", rotated.properties().get("axis"));
    }

    @Test
    void transformKeepsALegacyPartialMaskPartial() {
        BlockStateSpec blockOnly = new BlockStateSpec("minecraft:oak_stairs");

        BlockStateSpec rotated = BlockStateResolver.transform(
                blockOnly, Mirror.NONE, Rotation.CLOCKWISE_90);

        assertTrue(rotated.properties().isEmpty());
    }

    @Test
    void fourNormalizedQuarterTurnsRestoreCoordinates() {
        BlockPos original = new BlockPos(1, 2, 4);
        BlockPos transformed = original;
        int width = 4;
        int depth = 6;
        PlanTransform quarterTurn = new PlanTransform(Mirror.NONE, Rotation.CLOCKWISE_90);
        for (int index = 0; index < 4; index++) {
            transformed = quarterTurn.apply(transformed, width, depth);
            int previousWidth = width;
            width = quarterTurn.transformedWidth(width, depth);
            depth = quarterTurn.transformedDepth(previousWidth, depth);
        }

        assertEquals(original, transformed);
    }
}
