package io.github.greytaiwolf.fakeaiplayer.building.generator;

import io.github.greytaiwolf.fakeaiplayer.building.style.HouseMaterialStyle;

/** Complete deterministic input to the modular house generator. */
public record ModularHouseRequest(
        String planId,
        String name,
        HouseDimensions dimensions,
        long seed,
        HouseMaterialStyle materialStyle
) {
    public ModularHouseRequest {
        if (planId == null || planId.isBlank()) {
            throw new IllegalArgumentException("house_plan_id_missing");
        }
        name = name == null || name.isBlank() ? planId : name;
        if (dimensions == null) {
            throw new IllegalArgumentException("house_dimensions_missing");
        }
        if (materialStyle == null) {
            throw new IllegalArgumentException("house_material_style_missing");
        }
    }

    public static ModularHouseRequest standard(
            String planId,
            int width,
            int depth,
            long seed,
            HouseMaterialStyle materialStyle
    ) {
        return new ModularHouseRequest(
                planId,
                planId,
                new HouseDimensions(width, depth, 5),
                seed,
                materialStyle);
    }
}
