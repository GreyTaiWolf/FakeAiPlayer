package io.github.greytaiwolf.fakeaiplayer.building.generator;

import io.github.greytaiwolf.fakeaiplayer.building.catalog.BuildingArchetype;
import io.github.greytaiwolf.fakeaiplayer.building.catalog.BuildingCatalogEntry;
import io.github.greytaiwolf.fakeaiplayer.building.catalog.BuildingSeedCode;
import io.github.greytaiwolf.fakeaiplayer.building.style.HouseMaterialStyle;

/** Complete deterministic input to the large multi-storey building compiler. */
public record MultiStoreyBuildingRequest(
        String planId,
        String name,
        BuildingSeedCode buildingCode,
        long entropy,
        BuildingArchetype archetype,
        int width,
        int depth,
        int floors,
        HouseMaterialStyle materialStyle,
        BuildingRoofType roofType,
        String catalogVersion
) {
    public static final int MIN_FOOTPRINT = 11;
    public static final int MAX_FOOTPRINT = 48;
    public static final int MIN_FLOORS = 2;
    public static final int MAX_FLOORS = 8;
    public static final int FLOOR_HEIGHT = 4;

    public MultiStoreyBuildingRequest {
        if (planId == null || planId.isBlank()) {
            throw new IllegalArgumentException("multi_storey_plan_id_missing");
        }
        name = name == null || name.isBlank() ? planId : name;
        if (buildingCode == null || archetype == null || materialStyle == null || roofType == null) {
            throw new IllegalArgumentException("multi_storey_request_required_field_missing");
        }
        validateDimensions(width, depth, floors);
        if (catalogVersion == null || catalogVersion.isBlank()) {
            throw new IllegalArgumentException("multi_storey_catalog_version_missing");
        }
    }

    public static MultiStoreyBuildingRequest fromCatalog(
            String planId,
            String name,
            BuildingCatalogEntry entry
    ) {
        if (entry == null) {
            throw new IllegalArgumentException("building_catalog_entry_missing");
        }
        return entry.toRequest(planId, name);
    }

    public static void validateDimensions(int width, int depth, int floors) {
        requireRange("width", width, MIN_FOOTPRINT, MAX_FOOTPRINT);
        requireRange("depth", depth, MIN_FOOTPRINT, MAX_FOOTPRINT);
        requireRange("floors", floors, MIN_FLOORS, MAX_FLOORS);
    }

    private static void requireRange(String name, int value, int minimum, int maximum) {
        if (value < minimum || value > maximum) {
            throw new IllegalArgumentException(
                    "multi_storey_" + name + "_outside_" + minimum + "_" + maximum + ": " + value);
        }
    }
}
