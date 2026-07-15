package io.github.greytaiwolf.fakeaiplayer.building.plan;

import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * Loader-neutral, serializable description of a Minecraft block state.
 *
 * <p>Properties are deliberately explicit. Directional and multipart blocks must carry values
 * such as {@code facing}, {@code axis}, {@code half}, {@code shape} and {@code hinge}; an empty
 * map means the block's default state for legacy plans.</p>
 */
public record BlockStateSpec(String blockId, Map<String, String> properties) {
    private static final Pattern BLOCK_ID = Pattern.compile("[a-z0-9_.-]+:[a-z0-9/._-]+");
    private static final Pattern PROPERTY_NAME = Pattern.compile("[a-z0-9_]+");
    private static final Pattern PROPERTY_VALUE = Pattern.compile("[a-z0-9_.-]+");

    public BlockStateSpec {
        if (blockId == null || !BLOCK_ID.matcher(blockId).matches()) {
            throw new IllegalArgumentException("invalid_block_id: " + blockId);
        }
        TreeMap<String, String> normalized = new TreeMap<>();
        if (properties != null) {
            for (Map.Entry<String, String> entry : properties.entrySet()) {
                String name = entry.getKey();
                String value = entry.getValue();
                if (name == null || !PROPERTY_NAME.matcher(name).matches()) {
                    throw new IllegalArgumentException("invalid_block_property_name: " + name);
                }
                if (value == null || !PROPERTY_VALUE.matcher(value).matches()) {
                    throw new IllegalArgumentException("invalid_block_property_value: " + name + "=" + value);
                }
                normalized.put(name, value);
            }
        }
        properties = Collections.unmodifiableMap(normalized);
    }

    public BlockStateSpec(String blockId) {
        this(blockId, Map.of());
    }

    public boolean isAir() {
        return "minecraft:air".equals(blockId);
    }
}
