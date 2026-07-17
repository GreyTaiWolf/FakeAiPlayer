package io.github.greytaiwolf.fakeaiplayer.building.generator;

/** Deterministic roof modules supported by the multi-storey compiler. */
public enum BuildingRoofType {
    FLAT_PARAPET("flat_parapet"),
    STEPPED_GABLE("stepped_gable");

    private final String id;

    BuildingRoofType(String id) {
        this.id = id;
    }

    public String id() {
        return id;
    }
}
