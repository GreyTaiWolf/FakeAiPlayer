package io.github.greytaiwolf.fakeaiplayer.building.generator;

import io.github.greytaiwolf.fakeaiplayer.building.plan.BuildingPlan;
import io.github.greytaiwolf.fakeaiplayer.building.plan.BuildingPlanValidator;
import io.github.greytaiwolf.fakeaiplayer.building.style.HouseMaterialStyle;
import io.github.greytaiwolf.fakeaiplayer.building.style.VanillaHouseStyles;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class VanillaHouseStylesRegistryTest {
    @BeforeAll
    static void bootstrapRegistries() {
        Bootstrap.bootStrap();
    }

    @Test
    void everyBuiltInStyleProducesResolvableVanillaBlockStates() {
        ModularHouseGenerator generator = new ModularHouseGenerator();
        for (HouseMaterialStyle style : VanillaHouseStyles.all()) {
            BuildingPlan plan = generator.generate(new ModularHouseRequest(
                    "test:" + style.id(),
                    "Registry test " + style.id(),
                    new HouseDimensions(9, 8, 5),
                    2026L,
                    style));

            BuildingPlanValidator.ValidationResult result =
                    BuildingPlanValidator.validateForExecution(plan);
            assertTrue(result.valid(), () -> style.id() + ": " + result.problems());
        }
    }
}
