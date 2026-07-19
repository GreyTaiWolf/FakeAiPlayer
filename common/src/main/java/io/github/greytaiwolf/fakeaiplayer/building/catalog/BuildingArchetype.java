package io.github.greytaiwolf.fakeaiplayer.building.catalog;

/** Stable semantic families used by the append-only building catalogue. */
public enum BuildingArchetype {
    TOWNHOUSE("townhouse"),
    MANOR("manor"),
    KEEP("keep"),
    LODGE("lodge"),
    APARTMENT("apartment");

    private final String id;

    BuildingArchetype(String id) {
        this.id = id;
    }

    public String id() {
        return id;
    }
}
