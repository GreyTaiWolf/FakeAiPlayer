package io.github.greytaiwolf.fakeaiplayer.building.plan;

import io.github.greytaiwolf.fakeaiplayer.task.BlueprintSchema;
import io.github.greytaiwolf.fakeaiplayer.task.BlueprintLoader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Converts the current V1 blueprint representation into the reviewed V2 planning boundary. */
public final class LegacyBlueprintAdapter {
    private LegacyBlueprintAdapter() {
    }

    public static BuildingPlan adapt(BlueprintSchema blueprint) {
        if (blueprint == null) {
            throw new IllegalArgumentException("legacy_blueprint_missing");
        }
        BlueprintSchema expanded;
        try {
            expanded = BlueprintLoader.expand(blueprint);
        } catch (IOException exception) {
            throw new IllegalArgumentException("legacy_blueprint_invalid: " + exception.getMessage(), exception);
        }
        List<PlanPlacement> placements = new ArrayList<>();
        List<BlueprintSchema.BlockPlacement> legacy = expanded.placements() == null
                ? List.of()
                : expanded.placements();
        for (int index = 0; index < legacy.size(); index++) {
            BlueprintSchema.BlockPlacement placement = legacy.get(index);
            boolean clear = "minecraft:air".equals(placement.blockId());
            placements.add(new PlanPlacement(
                    "legacy-%05d".formatted(index),
                    placement.dx(),
                    placement.dy(),
                    placement.dz(),
                    new BlockStateSpec(placement.blockId(), placement.properties()),
                    clear ? CellOperation.CLEAR : CellOperation.PLACE,
                    clear ? ReplacePolicy.CLEAR_AUTHORIZED : ReplacePolicy.REPLACE_REPLACEABLE,
                    role(expanded, placement, clear),
                    phase(expanded, placement, clear),
                    "legacy:" + expanded.name(),
                    List.of(),
                    ""));
        }
        return new BuildingPlan(
                BuildingPlan.CURRENT_SCHEMA_VERSION,
                "legacy:" + expanded.name(),
                0,
                expanded.name(),
                expanded.width(),
                expanded.height(),
                expanded.depth(),
                0L,
                "legacy-blueprint-v1",
                placements,
                Map.of("sourceSchema", "BlueprintSchema"));
    }

    private static MaterialRole role(BlueprintSchema blueprint,
                                     BlueprintSchema.BlockPlacement placement,
                                     boolean clear) {
        if (clear) {
            return MaterialRole.DOOR;
        }
        String palette = placement.palette();
        if ("logs".equals(palette)) {
            return MaterialRole.FRAME;
        }
        if ("glass".equals(palette)) {
            return MaterialRole.WINDOW;
        }
        if ("dirt_like".equals(palette)) {
            return MaterialRole.FOUNDATION;
        }
        if (placement.dy() == 0) {
            return MaterialRole.FLOOR;
        }
        if (placement.dy() == blueprint.height() - 1) {
            return MaterialRole.ROOF;
        }
        return MaterialRole.WALL;
    }

    private static BuildPhase phase(BlueprintSchema blueprint,
                                    BlueprintSchema.BlockPlacement placement,
                                    boolean clear) {
        if (clear) {
            return BuildPhase.WALLS_AND_OPENINGS;
        }
        if (placement.dy() == 0) {
            return BuildPhase.FLOORS_AND_STAIRS;
        }
        if (placement.dy() == blueprint.height() - 1) {
            return BuildPhase.ROOF;
        }
        return BuildPhase.WALLS_AND_OPENINGS;
    }
}
