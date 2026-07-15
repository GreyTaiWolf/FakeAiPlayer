package io.github.greytaiwolf.fakeaiplayer.task;

import java.util.Map;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SmeltTaskFuelSelectionTest {
    @Test
    void partialCoalIsConsumedBeforeAFullBatchOfReservedBuildingLogs() {
        SmeltTask.FuelChoice first = SmeltTask.chooseFuel(
                Map.of(Items.COAL, 1, Items.OAK_LOG, 64), 10);

        assertEquals(Items.COAL, first.item());
        assertEquals(1, first.count());

        // Once the coal has burned eight items, the remaining two need only two logs. The old
        // selector chose seven logs up front and could consume four logs promised to the frame.
        SmeltTask.FuelChoice second = SmeltTask.chooseFuel(Map.of(Items.OAK_LOG, 64), 2);
        assertEquals(Items.OAK_LOG, second.item());
        assertEquals(2, second.count());
    }
}
