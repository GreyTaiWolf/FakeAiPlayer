package io.github.greytaiwolf.fakeaiplayer.building.plan;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BlockStateSpecTest {
    @Test
    void propertiesAreSortedAndDefensivelyCopied() {
        Map<String, String> source = new LinkedHashMap<>();
        source.put("shape", "straight");
        source.put("facing", "north");

        BlockStateSpec spec = new BlockStateSpec("minecraft:oak_stairs", source);
        source.put("half", "top");

        assertEquals(Map.of("facing", "north", "shape", "straight"), spec.properties());
        assertEquals("facing", spec.properties().keySet().iterator().next());
        assertThrows(UnsupportedOperationException.class,
                () -> spec.properties().put("waterlogged", "true"));
    }

    @Test
    void rejectsInvalidIdentifiersAndPropertiesBeforeWorldAccess() {
        assertThrows(IllegalArgumentException.class, () -> new BlockStateSpec("invalid id"));
        assertThrows(IllegalArgumentException.class,
                () -> new BlockStateSpec("minecraft:oak_stairs", Map.of("Facing", "north")));
        assertThrows(IllegalArgumentException.class,
                () -> new BlockStateSpec("minecraft:oak_stairs", Map.of("facing", "north west")));
    }
}
