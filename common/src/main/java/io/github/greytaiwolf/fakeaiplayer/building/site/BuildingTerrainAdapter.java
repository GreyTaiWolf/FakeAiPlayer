package io.github.greytaiwolf.fakeaiplayer.building.site;

import io.github.greytaiwolf.fakeaiplayer.building.plan.BuildPhase;
import io.github.greytaiwolf.fakeaiplayer.building.plan.BuildingDesignFingerprint;
import io.github.greytaiwolf.fakeaiplayer.building.plan.BuildingPlan;
import io.github.greytaiwolf.fakeaiplayer.building.plan.BuildingPlanTransforms;
import io.github.greytaiwolf.fakeaiplayer.building.plan.BuildingPlanValidator;
import io.github.greytaiwolf.fakeaiplayer.building.plan.CellOperation;
import io.github.greytaiwolf.fakeaiplayer.building.plan.MaterialRole;
import io.github.greytaiwolf.fakeaiplayer.building.plan.PlanPlacement;
import io.github.greytaiwolf.fakeaiplayer.building.plan.PlanTransform;
import io.github.greytaiwolf.fakeaiplayer.building.plan.ReplacePolicy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Compiles a surveyed, gently uneven site into a permanent stilt/fill foundation.
 *
 * <p>The generated plan is site locked. Moving or rotating it would detach the reviewed piers
 * from the terrain snapshot, so {@code BuildingPreviewService} must reject such updates.</p>
 */
public final class BuildingTerrainAdapter {
    public static final String ADAPTER_VERSION = "stilt-foundation-1";
    public static final int MAX_SUPPORTED_TERRAIN_SPAN = 5;

    private BuildingTerrainAdapter() {
    }

    public static AdaptedPlan adapt(BuildingPlan source,
                                    PlanTransform transform,
                                    BuildingSiteSurvey survey) {
        if (source == null || survey == null) {
            throw new IllegalArgumentException("building_terrain_adaptation_input_missing");
        }
        if (!survey.complete() || survey.waterColumns() > 0) {
            throw new IllegalArgumentException("building_site_not_dry_and_complete");
        }
        if (survey.terrainSpan() > MAX_SUPPORTED_TERRAIN_SPAN) {
            throw new IllegalArgumentException("building_site_too_steep: " + survey.terrainSpan());
        }
        BuildingPlan oriented = BuildingPlanTransforms.bake(source, transform);
        if (oriented.width() != survey.width() || oriented.depth() != survey.depth()) {
            throw new IllegalArgumentException("building_site_plan_footprint_mismatch");
        }
        int baseOffset = survey.terrainSpan();
        Map<Column, PlanPlacement> foundationByColumn = new HashMap<>();
        for (PlanPlacement placement : oriented.placements()) {
            if (placement.dy() == 0
                    && placement.phase() == BuildPhase.FOUNDATION
                    && placement.operation() == CellOperation.PLACE) {
                foundationByColumn.putIfAbsent(
                        new Column(placement.dx(), placement.dz()), placement);
            }
        }

        List<PlanPlacement> piers = new ArrayList<>();
        Map<Column, String> topPierByColumn = new HashMap<>();
        for (Map.Entry<Column, PlanPlacement> entry : foundationByColumn.entrySet().stream()
                .sorted(Map.Entry.comparingByKey()).toList()) {
            Column column = entry.getKey();
            PlanPlacement foundation = entry.getValue();
            int surfaceOffset = survey.surfaceY(column.x(), column.z()) - survey.minimumSurfaceY();
            String previous = null;
            for (int y = surfaceOffset; y < baseOffset; y++) {
                String id = "site:pier@" + column.x() + "," + y + "," + column.z();
                List<String> dependencies = previous == null ? List.of() : List.of(previous);
                piers.add(new PlanPlacement(
                        id, column.x(), y, column.z(), foundation.state(),
                        CellOperation.PLACE,
                        y == surfaceOffset
                                ? ReplacePolicy.REPLACE_REPLACEABLE
                                : ReplacePolicy.REQUIRE_EMPTY,
                        MaterialRole.FOUNDATION, BuildPhase.FOUNDATION,
                        "site:pier", dependencies, ""));
                previous = id;
            }
            if (previous != null) {
                topPierByColumn.put(column, previous);
            }
        }

        List<PlanPlacement> placements = new ArrayList<>(
                piers.size() + oriented.placements().size());
        placements.addAll(piers);
        for (PlanPlacement placement : oriented.placements()) {
            LinkedHashSet<String> dependencies = new LinkedHashSet<>(placement.dependencies());
            if (placement.dy() == 0) {
                String pier = topPierByColumn.get(new Column(placement.dx(), placement.dz()));
                if (pier != null) {
                    dependencies.add(pier);
                }
            }
            placements.add(new PlanPlacement(
                    placement.id(), placement.dx(), placement.dy() + baseOffset, placement.dz(),
                    placement.state(), placement.operation(), placement.replacePolicy(),
                    placement.materialRole(), placement.phase(), placement.componentId(),
                    List.copyOf(dependencies), placement.atomicGroup()));
        }

        TreeMap<String, String> metadata = new TreeMap<>(oriented.metadata());
        metadata.put("source_design_hash", BuildingDesignFingerprint.sha256(source));
        metadata.put("site_adapter", ADAPTER_VERSION);
        metadata.put("site_locked", "true");
        metadata.put("site_strategy", baseOffset == 0 ? "LEVEL" : "STILTS");
        metadata.put("site_surface_signature", survey.signature());
        metadata.put("site_dimension", survey.dimension());
        metadata.put("site_terrain_span", Integer.toString(survey.terrainSpan()));
        metadata.put("site_base_offset", Integer.toString(baseOffset));
        metadata.put("site_origin_x", Integer.toString(survey.horizontalOrigin().getX()));
        metadata.put("site_origin_z", Integer.toString(survey.horizontalOrigin().getZ()));
        BuildingPlan adapted = new BuildingPlan(
                oriented.schemaVersion(), oriented.planId(), oriented.revision() + 1,
                oriented.name(), oriented.width(), oriented.height() + baseOffset,
                oriented.depth(), oriented.seed(),
                oriented.generatorVersion() + "+" + ADAPTER_VERSION,
                placements, Map.copyOf(metadata));
        BuildingPlanValidator.ValidationResult validation = BuildingPlanValidator.validate(adapted);
        if (!validation.valid()) {
            BuildingPlanValidator.Problem first = validation.problems().get(0);
            throw new IllegalStateException("terrain_adapted_plan_invalid: "
                    + first.code() + ":" + first.detail());
        }
        return new AdaptedPlan(
                adapted, survey.planAnchor(), PlanTransform.IDENTITY, survey, piers.size());
    }

    public record AdaptedPlan(
            BuildingPlan plan,
            net.minecraft.core.BlockPos anchor,
            PlanTransform transform,
            BuildingSiteSurvey survey,
            int pierCount
    ) {
        public AdaptedPlan {
            if (plan == null || anchor == null || transform == null || survey == null
                    || pierCount < 0 || pierCount > plan.placements().size()) {
                throw new IllegalArgumentException("invalid_adapted_building_plan");
            }
            anchor = anchor.immutable();
        }
    }

    private record Column(int x, int z) implements Comparable<Column> {
        @Override
        public int compareTo(Column other) {
            int xOrder = Integer.compare(x, other.x);
            return xOrder != 0 ? xOrder : Integer.compare(z, other.z);
        }
    }
}
