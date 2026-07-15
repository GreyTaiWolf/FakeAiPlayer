package io.github.greytaiwolf.fakeaiplayer.task;

import io.github.greytaiwolf.fakeaiplayer.craft.RecipeRegistry;
import java.util.Set;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GatherQuotaTaskExactTest {
    @Test
    void exactModeNarrowsLogsWithoutChangingLegacyFamilyMode() {
        assertEquals(Set.copyOf(RecipeRegistry.LOGS),
                GatherQuotaTask.acceptItemsFor(Items.OAK_LOG, false));
        assertEquals(Set.of(Items.OAK_LOG),
                GatherQuotaTask.acceptItemsFor(Items.OAK_LOG, true));
        assertEquals(Set.of(Items.SPRUCE_LOG),
                GatherQuotaTask.acceptItemsFor(Items.SPRUCE_LOG, true));
    }

    @Test
    void nonLogMaterialsAreUnchangedInEitherMode() {
        assertEquals(Set.of(Items.SAND), GatherQuotaTask.acceptItemsFor(Items.SAND, false));
        assertEquals(Set.of(Items.SAND), GatherQuotaTask.acceptItemsFor(Items.SAND, true));
        assertEquals(Set.of(Items.COBBLESTONE),
                GatherQuotaTask.acceptItemsFor(Items.COBBLESTONE, true));
    }

    @Test
    void exactPlannerStepCountsNewlyGatheredDeltaWithoutChangingLegacyQuotaMath() {
        assertEquals(2, GatherQuotaTask.progressFromAbsolute(true, 5, 7));
        assertEquals(0, GatherQuotaTask.progressFromAbsolute(true, 5, 4));
        assertEquals(7, GatherQuotaTask.progressFromAbsolute(false, 5, 7));
    }

    @Test
    void generatedBuildingSandDemandCountsTheMissingDeltaFromPartialInventory() {
        int existingSand = 5;
        int missingSand = 15;

        assertEquals(0,
                GatherQuotaTask.progressFromAbsolute(true, existingSand, existingSand));
        assertEquals(missingSand,
                GatherQuotaTask.progressFromAbsolute(
                        true, existingSand, existingSand + missingSand));
        int legacyAbsoluteInventoryAtCompletion = missingSand;
        assertEquals(missingSand,
                GatherQuotaTask.progressFromAbsolute(
                        false, existingSand, legacyAbsoluteInventoryAtCompletion));
        assertEquals(10, legacyAbsoluteInventoryAtCompletion - existingSand,
                "an absolute quota would stop after gathering only ten of the fifteen missing sand");
    }
}
