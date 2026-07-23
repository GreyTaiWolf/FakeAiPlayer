package io.github.greytaiwolf.fakeaiplayer.task;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MissionTaskCapabilityTest {
    @Test
    void oneNearbyUtilitySatisfiesOnlyASingleUnitCraftRequest() {
        assertTrue(CraftTask.utilityRequestSatisfied(0, 1, true));
        assertTrue(CraftTask.utilityRequestSatisfied(2, 2, false));
        assertFalse(CraftTask.utilityRequestSatisfied(0, 2, true));
        assertFalse(CraftTask.utilityRequestSatisfied(1, 2, true));
    }

    @Test
    void matureCropsDoNotRequirePlantingSupplies() {
        assertTrue(FarmTask.harvestOrPlantOpportunity(
                true, false, false, false, false, false));
    }

    @Test
    void plantingNeedsSeedSpaceAndEitherFarmlandOrAHoe() {
        assertTrue(FarmTask.harvestOrPlantOpportunity(
                false, true, true, true, false, false));
        assertTrue(FarmTask.harvestOrPlantOpportunity(
                false, true, true, false, true, true));
        assertFalse(FarmTask.harvestOrPlantOpportunity(
                false, true, true, false, false, true));
        assertFalse(FarmTask.harvestOrPlantOpportunity(
                false, false, true, true, true, true));
        assertFalse(FarmTask.harvestOrPlantOpportunity(
                false, true, false, true, true, true));
    }
}
