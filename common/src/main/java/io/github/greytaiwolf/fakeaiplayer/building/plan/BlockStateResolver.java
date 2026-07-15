package io.github.greytaiwolf.fakeaiplayer.building.plan;

import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

/** Canonical bridge between serializable plan states and the Minecraft state definition API. */
public final class BlockStateResolver {
    private BlockStateResolver() {
    }

    /** Resolve every declared property, failing closed on unknown blocks, names or values. */
    public static BlockState resolve(BlockStateSpec spec) {
        ResourceLocation id = ResourceLocation.tryParse(spec.blockId());
        if (id == null) {
            throw new IllegalArgumentException("invalid_block_id: " + spec.blockId());
        }
        Block block = BuiltInRegistries.BLOCK.getOptional(id)
                .orElseThrow(() -> new IllegalArgumentException("unknown_block_id: " + id));
        return applyProperties(block.defaultBlockState(), spec.properties(), spec.blockId());
    }

    /** Apply a property mask to an already selected material-family block. */
    public static BlockState applyProperties(BlockState base, Map<String, String> properties) {
        ResourceLocation id = BuiltInRegistries.BLOCK.getKey(base.getBlock());
        return applyProperties(base, properties, id == null ? "unregistered" : id.toString());
    }

    private static BlockState applyProperties(BlockState base,
                                              Map<String, String> properties,
                                              String blockId) {
        BlockState state = base;
        if (properties == null || properties.isEmpty()) {
            return state;
        }
        for (Map.Entry<String, String> entry : properties.entrySet()) {
            Property<?> property = state.getBlock().getStateDefinition().getProperty(entry.getKey());
            if (property == null) {
                throw new IllegalArgumentException(
                        "unknown_block_property: " + blockId + "[" + entry.getKey() + "]");
            }
            state = setValue(state, property, entry.getValue(), blockId);
        }
        return state;
    }

    /** Serialize a complete state, including default-valued properties, in canonical name order. */
    public static BlockStateSpec encode(BlockState state) {
        ResourceLocation id = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        if (id == null) {
            throw new IllegalArgumentException("unregistered_block: " + state.getBlock());
        }
        Map<String, String> properties = new TreeMap<>();
        for (Property<?> property : state.getProperties()) {
            properties.put(property.getName(), propertyName(state, property));
        }
        return new BlockStateSpec(id.toString(), properties);
    }

    /** Vanilla-compatible transform order used by StructureTemplate: mirror first, then rotate. */
    public static BlockStateSpec transform(BlockStateSpec spec, Mirror mirror, Rotation rotation) {
        BlockState transformed = resolve(spec)
                .mirror(mirror == null ? Mirror.NONE : mirror)
                .rotate(rotation == null ? Rotation.NONE : rotation);
        // A partial legacy mask must stay partial. Filling every default-valued property here
        // would silently turn block-only matching into exact-state matching after a rotation.
        BlockStateSpec encoded = encode(transformed);
        Map<String, String> transformedMask = new TreeMap<>();
        for (String propertyName : spec.properties().keySet()) {
            transformedMask.put(propertyName, encoded.properties().get(propertyName));
        }
        return new BlockStateSpec(encoded.blockId(), transformedMask);
    }

    /**
     * Match the exact block plus every property declared by the plan.
     *
     * <p>A legacy state with no properties remains block-only. A compiled V2 placement should
     * normally use {@link #encode(BlockState)} and therefore compares the complete state.</p>
     */
    public static boolean matches(BlockState actual, BlockStateSpec expected) {
        ResourceLocation expectedId = ResourceLocation.tryParse(expected.blockId());
        Block expectedBlock = expectedId == null
                ? null
                : BuiltInRegistries.BLOCK.getOptional(expectedId).orElse(null);
        return expectedBlock != null
                && actual.is(expectedBlock)
                && matchesProperties(actual, expected.properties());
    }

    /** Match a property mask against any block that exposes the same state properties. */
    public static boolean matchesProperties(BlockState actual, Map<String, String> expectedProperties) {
        if (expectedProperties == null || expectedProperties.isEmpty()) {
            return true;
        }
        for (Map.Entry<String, String> entry : expectedProperties.entrySet()) {
            Property<?> property = actual.getBlock().getStateDefinition().getProperty(entry.getKey());
            if (property == null || !propertyName(actual, property).equals(entry.getValue())) {
                return false;
            }
        }
        return true;
    }

    private static <T extends Comparable<T>> BlockState setValue(BlockState state,
                                                                  Property<T> property,
                                                                  String serialized,
                                                                  String blockId) {
        Optional<T> value = property.getValue(serialized);
        if (value.isEmpty()) {
            throw new IllegalArgumentException(
                    "invalid_block_property_value: " + blockId + "[" + property.getName() + "=" + serialized + "]");
        }
        return state.setValue(property, value.get());
    }

    private static <T extends Comparable<T>> String propertyName(BlockState state, Property<T> property) {
        return property.getName(state.getValue(property));
    }
}
