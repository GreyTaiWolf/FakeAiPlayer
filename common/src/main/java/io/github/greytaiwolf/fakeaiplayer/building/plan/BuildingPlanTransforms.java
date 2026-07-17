package io.github.greytaiwolf.fakeaiplayer.building.plan;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import net.minecraft.core.BlockPos;

/** Pure plan-level transformations used when a site-specific design must lock its orientation. */
public final class BuildingPlanTransforms {
    private BuildingPlanTransforms() {
    }

    public static BuildingPlan bake(BuildingPlan plan, PlanTransform transform) {
        if (plan == null) {
            throw new IllegalArgumentException("building_plan_missing");
        }
        PlanTransform normalized = transform == null ? PlanTransform.IDENTITY : transform;
        if (normalized.equals(PlanTransform.IDENTITY)) {
            return plan;
        }
        List<PlanPlacement> transformed = new ArrayList<>(plan.placements().size());
        for (PlanPlacement placement : plan.placements()) {
            BlockPos position = normalized.apply(
                    new BlockPos(placement.dx(), placement.dy(), placement.dz()),
                    plan.width(), plan.depth());
            transformed.add(new PlanPlacement(
                    placement.id(), position.getX(), position.getY(), position.getZ(),
                    normalized.apply(placement.state()), placement.operation(),
                    placement.replacePolicy(), placement.materialRole(), placement.phase(),
                    placement.componentId(), placement.dependencies(), placement.atomicGroup()));
        }
        TreeMap<String, String> metadata = new TreeMap<>(plan.metadata());
        metadata.put("baked_mirror", normalized.mirror().name());
        metadata.put("baked_rotation", normalized.rotation().name());
        BuildingPlan baked = new BuildingPlan(
                plan.schemaVersion(), plan.planId(), plan.revision(), plan.name(),
                normalized.transformedWidth(plan.width(), plan.depth()), plan.height(),
                normalized.transformedDepth(plan.width(), plan.depth()), plan.seed(),
                plan.generatorVersion(), transformed, Map.copyOf(metadata));
        BuildingPlanValidator.ValidationResult validation = BuildingPlanValidator.validate(baked);
        if (!validation.valid()) {
            BuildingPlanValidator.Problem first = validation.problems().get(0);
            throw new IllegalStateException("baked_building_plan_invalid: "
                    + first.code() + ":" + first.detail());
        }
        return baked;
    }
}
