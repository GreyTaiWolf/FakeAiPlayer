package io.github.greytaiwolf.fakeaiplayer.task;

import java.util.Set;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DigDownTaskExactDropsTest {
    @Test
    void exactStoneCountsOnlyCobblestoneWhileLegacyToolChainsAcceptSubstitutes() {
        assertEquals(Set.of(Items.COBBLESTONE),
                DigDownTask.targetDropsFor(Blocks.STONE, true));

        Set<net.minecraft.world.item.Item> legacy =
                DigDownTask.targetDropsFor(Blocks.STONE, false);
        assertTrue(legacy.contains(Items.COBBLESTONE));
        assertTrue(legacy.contains(Items.COBBLED_DEEPSLATE));
        assertTrue(legacy.contains(Items.BLACKSTONE));
    }
}
