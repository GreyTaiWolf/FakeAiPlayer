package io.github.greytaiwolf.fakeaiplayer.building.generator;

/** Enclosed footprint and wall height, measured in whole Minecraft blocks. */
public record HouseDimensions(int width, int depth, int wallHeight) {
    public static final int MIN_FOOTPRINT = 7;
    public static final int MAX_FOOTPRINT = 48;
    public static final int MIN_WALL_HEIGHT = 4;
    public static final int MAX_WALL_HEIGHT = 12;

    public HouseDimensions {
        requireRange("width", width, MIN_FOOTPRINT, MAX_FOOTPRINT);
        requireRange("depth", depth, MIN_FOOTPRINT, MAX_FOOTPRINT);
        requireRange("wall_height", wallHeight, MIN_WALL_HEIGHT, MAX_WALL_HEIGHT);
    }

    private static void requireRange(String name, int value, int minimum, int maximum) {
        if (value < minimum || value > maximum) {
            throw new IllegalArgumentException(
                    "house_" + name + "_outside_" + minimum + "_" + maximum + ": " + value);
        }
    }
}
