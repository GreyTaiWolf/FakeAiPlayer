package io.github.greytaiwolf.fakeaiplayer.building.style;

import io.github.greytaiwolf.fakeaiplayer.building.plan.BlockStateSpec;
import java.util.Map;

/** Registry-independent built-in palettes composed only from vanilla 1.21 block states. */
public final class VanillaHouseStyles {
    public static final HouseMaterialStyle OAK_COTTAGE = new HouseMaterialStyle(
            "oak_cottage",
            state("minecraft:cobblestone"),
            state("minecraft:oak_planks"),
            state("minecraft:oak_planks"),
            frame("minecraft:oak_log"),
            state("minecraft:glass"),
            door("minecraft:oak_door"),
            roof("minecraft:oak_stairs", state("minecraft:oak_planks")),
            state("minecraft:oak_planks"),
            stairs("minecraft:oak_stairs", "south"),
            state("minecraft:oak_log", Map.of("axis", "y")));

    public static final HouseMaterialStyle SPRUCE_LODGE = new HouseMaterialStyle(
            "spruce_lodge",
            state("minecraft:cobblestone"),
            state("minecraft:spruce_planks"),
            state("minecraft:spruce_planks"),
            frame("minecraft:spruce_log"),
            state("minecraft:glass"),
            door("minecraft:spruce_door"),
            roof("minecraft:spruce_stairs", state("minecraft:spruce_planks")),
            state("minecraft:spruce_planks"),
            stairs("minecraft:spruce_stairs", "south"),
            state("minecraft:spruce_log", Map.of("axis", "y")));

    private VanillaHouseStyles() {
    }

    private static HouseMaterialStyle.FrameStates frame(String blockId) {
        return new HouseMaterialStyle.FrameStates(
                state(blockId, Map.of("axis", "y")),
                state(blockId, Map.of("axis", "x")),
                state(blockId, Map.of("axis", "z")));
    }

    private static HouseMaterialStyle.DoorStates door(String blockId) {
        return new HouseMaterialStyle.DoorStates(
                doorHalf(blockId, "left", "lower"),
                doorHalf(blockId, "left", "upper"),
                doorHalf(blockId, "right", "lower"),
                doorHalf(blockId, "right", "upper"));
    }

    private static BlockStateSpec doorHalf(String blockId, String hinge, String half) {
        return state(blockId, Map.of(
                // The entrance sits on the north facade. A player/Bot standing outside that
                // facade looks south while placing it, which is also vanilla's door facing.
                "facing", "south",
                "half", half,
                "hinge", hinge,
                "open", "false",
                "powered", "false"));
    }

    private static HouseMaterialStyle.RoofStates roof(String stairBlockId, BlockStateSpec ridge) {
        return new HouseMaterialStyle.RoofStates(
                // Stair facing is the uphill direction: north eave rises south toward the ridge,
                // and south eave rises north.
                stairs(stairBlockId, "south"),
                stairs(stairBlockId, "north"),
                ridge,
                ridge);
    }

    private static BlockStateSpec stairs(String blockId, String facing) {
        return state(blockId, Map.of(
                "facing", facing,
                "half", "bottom",
                "shape", "straight",
                "waterlogged", "false"));
    }

    private static BlockStateSpec state(String blockId) {
        return new BlockStateSpec(blockId);
    }

    private static BlockStateSpec state(String blockId, Map<String, String> properties) {
        return new BlockStateSpec(blockId, properties);
    }
}
