package io.github.greytaiwolf.fakeaiplayer.building.plan;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Immutable result of the deterministic building compiler.
 *
 * <p>The world anchor, rotation and mirror are intentionally not embedded here. They belong to a
 * preview/project instance, so one compiled design can be moved without regenerating geometry.</p>
 */
public record BuildingPlan(
        int schemaVersion,
        String planId,
        int revision,
        String name,
        int width,
        int height,
        int depth,
        long seed,
        String generatorVersion,
        List<PlanPlacement> placements,
        Map<String, String> metadata
) {
    public static final int CURRENT_SCHEMA_VERSION = 2;

    public BuildingPlan {
        if (planId == null || planId.isBlank()) {
            throw new IllegalArgumentException("plan_id_missing");
        }
        if (revision < 0) {
            throw new IllegalArgumentException("plan_revision_negative");
        }
        name = name == null || name.isBlank() ? planId : name;
        generatorVersion = generatorVersion == null || generatorVersion.isBlank()
                ? "unknown"
                : generatorVersion;
        placements = placements == null ? List.of() : List.copyOf(placements);
        TreeMap<String, String> normalizedMetadata = new TreeMap<>();
        if (metadata != null) {
            for (Map.Entry<String, String> entry : metadata.entrySet()) {
                if (entry.getKey() == null || entry.getKey().isBlank() || entry.getValue() == null) {
                    throw new IllegalArgumentException("invalid_plan_metadata");
                }
                normalizedMetadata.put(entry.getKey(), entry.getValue());
            }
        }
        metadata = Collections.unmodifiableMap(normalizedMetadata);
    }
}
