package io.github.greytaiwolf.fakeaiplayer.building.plan;

import io.github.greytaiwolf.fakeaiplayer.task.BlueprintSchema;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;

/** Compiles one reviewed plan instance into the legacy executor's ordered placement stream. */
public final class BuildingPlanBlueprintAdapter {
    private BuildingPlanBlueprintAdapter() {
    }

    public static BlueprintSchema adapt(BuildingPlan plan, PlanTransform transform) {
        BuildingPlanValidator.ValidationResult validation = BuildingPlanValidator.validateForExecution(plan);
        if (!validation.valid()) {
            throw new IllegalArgumentException("invalid_building_plan: " + validation.problems().get(0).code());
        }
        PlanTransform normalized = transform == null ? PlanTransform.IDENTITY : transform;
        List<PlanPlacement> ordered = BuildingPlanOrder.stableTopological(plan);
        Map<String, Integer> sequenceById = new LinkedHashMap<>();
        for (int index = 0; index < ordered.size(); index++) {
            sequenceById.put(ordered.get(index).id(), index);
        }
        List<BlueprintSchema.BlockPlacement> placements = new ArrayList<>();
        int sequence = 0;
        for (PlanPlacement placement : ordered) {
            if (placement.operation() == CellOperation.TEMPORARY) {
                throw new IllegalArgumentException("temporary_cells_require_cleanup_executor: " + placement.id());
            }
            BlockPos local = normalized.apply(
                    new BlockPos(placement.dx(), placement.dy(), placement.dz()),
                    plan.width(),
                    plan.depth());
            BlockStateSpec state = placement.operation() == CellOperation.CLEAR
                    ? new BlockStateSpec("minecraft:air")
                    : normalized.apply(placement.state());
            List<Integer> prerequisites = placement.dependencies().stream()
                    .map(dependencyId -> {
                        Integer dependencyIndex = sequenceById.get(dependencyId);
                        if (dependencyIndex == null) {
                            throw new IllegalArgumentException(
                                    "missing_compiled_dependency: " + dependencyId);
                        }
                        return dependencyIndex;
                    })
                    .toList();
            placements.add(new BlueprintSchema.BlockPlacement(
                    local.getX(),
                    local.getY(),
                    local.getZ(),
                    state.blockId(),
                    null,
                    state.properties(),
                    placement.operation(),
                    placement.replacePolicy(),
                    placement.atomicGroup(),
                    sequence++,
                    prerequisites));
        }
        return new BlueprintSchema(
                plan.name(),
                normalized.transformedWidth(plan.width(), plan.depth()),
                plan.height(),
                normalized.transformedDepth(plan.width(), plan.depth()),
                List.copyOf(placements),
                List.of());
    }

}
