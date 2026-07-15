package io.github.greytaiwolf.fakeaiplayer.building.plan;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

/** Stable dependency order shared by preview diagnostics, execution adapters, and tests. */
public final class BuildingPlanOrder {
    private static final Comparator<PlanPlacement> STABLE_ORDER = Comparator
            .comparingInt((PlanPlacement placement) -> placement.phase().ordinal())
            .thenComparingInt(PlanPlacement::dy)
            .thenComparingInt(PlanPlacement::dz)
            .thenComparingInt(PlanPlacement::dx)
            .thenComparing(PlanPlacement::id);

    private BuildingPlanOrder() {
    }

    public static List<PlanPlacement> stableTopological(BuildingPlan plan) {
        BuildingPlanValidator.ValidationResult validation = BuildingPlanValidator.validate(plan);
        if (!validation.valid()) {
            throw new IllegalArgumentException("invalid_building_plan: " + validation.problems().get(0).code());
        }
        Map<String, PlanPlacement> byId = new HashMap<>();
        Map<String, Integer> remaining = new HashMap<>();
        Map<String, List<String>> dependents = new HashMap<>();
        for (PlanPlacement placement : plan.placements()) {
            byId.put(placement.id(), placement);
        }
        for (PlanPlacement placement : plan.placements()) {
            Set<String> uniqueDependencies = new HashSet<>(placement.dependencies());
            remaining.put(placement.id(), uniqueDependencies.size());
            for (String dependency : uniqueDependencies) {
                dependents.computeIfAbsent(dependency, ignored -> new ArrayList<>()).add(placement.id());
            }
        }

        PriorityQueue<PlanPlacement> ready = new PriorityQueue<>(STABLE_ORDER);
        for (PlanPlacement placement : plan.placements()) {
            if (remaining.get(placement.id()) == 0) {
                ready.add(placement);
            }
        }
        List<PlanPlacement> ordered = new ArrayList<>(plan.placements().size());
        while (!ready.isEmpty()) {
            PlanPlacement placement = ready.remove();
            ordered.add(placement);
            for (String dependentId : dependents.getOrDefault(placement.id(), List.of())) {
                int count = remaining.computeIfPresent(dependentId, (ignored, value) -> value - 1);
                if (count == 0) {
                    ready.add(byId.get(dependentId));
                }
            }
        }
        if (ordered.size() != plan.placements().size()) {
            throw new IllegalArgumentException("building_plan_dependency_cycle");
        }
        return List.copyOf(ordered);
    }
}
