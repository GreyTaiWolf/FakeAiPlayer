package io.github.greytaiwolf.fakeaiplayer.building.catalog;

import io.github.greytaiwolf.fakeaiplayer.building.generator.BuildingRoofType;
import io.github.greytaiwolf.fakeaiplayer.building.generator.MultiStoreyBuildingGenerator;
import io.github.greytaiwolf.fakeaiplayer.building.generator.MultiStoreyBuildingRequest;
import io.github.greytaiwolf.fakeaiplayer.building.style.HouseMaterialStyle;
import io.github.greytaiwolf.fakeaiplayer.building.style.VanillaHouseStyles;

/** Fully resolved, versioned catalogue design selected by one public building code. */
public record BuildingCatalogEntry(
        BuildingSeedCode seedCode,
        long entropy,
        BuildingArchetype archetype,
        String styleId,
        int width,
        int depth,
        int floors,
        BuildingRoofType roofType,
        String catalogVersion,
        String generatorVersion
) {
    public BuildingCatalogEntry {
        if (seedCode == null || archetype == null || roofType == null) {
            throw new IllegalArgumentException("building_catalog_entry_required_field_missing");
        }
        if (styleId == null || styleId.isBlank()) {
            throw new IllegalArgumentException("building_catalog_style_missing");
        }
        MultiStoreyBuildingRequest.validateDimensions(width, depth, floors);
        if (catalogVersion == null || catalogVersion.isBlank()) {
            throw new IllegalArgumentException("building_catalog_version_missing");
        }
        if (generatorVersion == null || generatorVersion.isBlank()) {
            throw new IllegalArgumentException("building_generator_version_missing");
        }
    }

    public HouseMaterialStyle materialStyle() {
        return VanillaHouseStyles.byId(styleId);
    }

    public MultiStoreyBuildingRequest toRequest(String planId, String name) {
        return new MultiStoreyBuildingRequest(
                planId,
                name,
                seedCode,
                entropy,
                archetype,
                width,
                depth,
                floors,
                materialStyle(),
                roofType,
                catalogVersion);
    }

    public MultiStoreyBuildingRequest toRequest(String planId) {
        return toRequest(planId, archetype.id() + " " + seedCode.value());
    }

    public boolean usesCurrentGenerator() {
        return MultiStoreyBuildingGenerator.GENERATOR_VERSION.equals(generatorVersion);
    }
}
