package io.github.greytaiwolf.fakeaiplayer.building.plan;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Pure validation that runs before preview publication, material reservation or world mutation. */
public final class BuildingPlanValidator {
    public static final int MAX_DIMENSION = 128;
    public static final int MAX_PLACEMENTS = 65_536;

    private BuildingPlanValidator() {
    }

    public static ValidationResult validate(BuildingPlan plan) {
        List<Problem> problems = new ArrayList<>();
        if (plan == null) {
            return new ValidationResult(List.of(new Problem("plan_missing", "", "Building plan is null")));
        }
        if (plan.schemaVersion() != BuildingPlan.CURRENT_SCHEMA_VERSION) {
            problems.add(new Problem("unsupported_schema", "",
                    "Expected schema " + BuildingPlan.CURRENT_SCHEMA_VERSION + " but got " + plan.schemaVersion()));
        }
        validateDimension("width", plan.width(), problems);
        validateDimension("height", plan.height(), problems);
        validateDimension("depth", plan.depth(), problems);
        if (plan.placements().size() > MAX_PLACEMENTS) {
            problems.add(new Problem("too_many_placements", "",
                    plan.placements().size() + " exceeds " + MAX_PLACEMENTS));
        }
        if (plan.placements().isEmpty()) {
            problems.add(new Problem("empty_plan", "", "Building plan has no placements"));
        }

        Map<String, PlanPlacement> byId = new LinkedHashMap<>();
        Map<Cell, String> occupied = new HashMap<>();
        for (PlanPlacement placement : plan.placements()) {
            if (byId.putIfAbsent(placement.id(), placement) != null) {
                problems.add(new Problem("duplicate_placement_id", placement.id(), "Placement id is not unique"));
            }
            if (placement.dx() < 0 || placement.dx() >= plan.width()
                    || placement.dy() < 0 || placement.dy() >= plan.height()
                    || placement.dz() < 0 || placement.dz() >= plan.depth()) {
                problems.add(new Problem("placement_out_of_bounds", placement.id(),
                        placement.dx() + "," + placement.dy() + "," + placement.dz()));
            }
            String previous = occupied.putIfAbsent(new Cell(placement.dx(), placement.dy(), placement.dz()), placement.id());
            if (previous != null) {
                problems.add(new Problem("duplicate_cell", placement.id(), "Cell already used by " + previous));
            }
            validateOperation(placement, problems);
        }
        validateAtomicGroupContracts(plan.placements(), problems);

        for (PlanPlacement placement : plan.placements()) {
            Set<String> seen = new HashSet<>();
            for (String dependencyId : placement.dependencies()) {
                if (dependencyId == null || dependencyId.isBlank()) {
                    problems.add(new Problem("blank_dependency", placement.id(), "Dependency id is blank"));
                    continue;
                }
                if (!seen.add(dependencyId)) {
                    problems.add(new Problem("duplicate_dependency", placement.id(), dependencyId));
                }
                PlanPlacement dependency = byId.get(dependencyId);
                if (dependency == null) {
                    problems.add(new Problem("missing_dependency", placement.id(), dependencyId));
                } else if (dependency.phase().ordinal() > placement.phase().ordinal()) {
                    problems.add(new Problem("future_phase_dependency", placement.id(), dependencyId));
                }
            }
        }
        detectCycles(byId, problems);
        return new ValidationResult(problems);
    }

    /** Structural validation plus Minecraft registry/BlockState resolution for execution. */
    public static ValidationResult validateForExecution(BuildingPlan plan) {
        ValidationResult structural = validate(plan);
        if (plan == null) {
            return structural;
        }
        List<Problem> problems = new ArrayList<>(structural.problems());
        BuildingPlanExecutionValidator.validate(plan, problems);
        return new ValidationResult(problems);
    }

    private static void validateDimension(String name, int value, List<Problem> problems) {
        if (value < 1 || value > MAX_DIMENSION) {
            problems.add(new Problem("invalid_" + name, "", value + " is outside 1.." + MAX_DIMENSION));
        }
    }

    private static void validateOperation(PlanPlacement placement, List<Problem> problems) {
        if (placement.operation() == CellOperation.CLEAR && !placement.state().isAir()) {
            problems.add(new Problem("clear_requires_air_state", placement.id(), placement.state().blockId()));
        }
        if ((placement.operation() == CellOperation.PLACE || placement.operation() == CellOperation.TEMPORARY)
                && placement.state().isAir()) {
            problems.add(new Problem("place_requires_non_air_state", placement.id(), placement.state().blockId()));
        }
        if (!policyAllowed(placement.operation(), placement.replacePolicy())) {
            problems.add(new Problem("operation_policy_mismatch", placement.id(),
                    placement.operation().name() + "+" + placement.replacePolicy().name()));
        }
    }

    private static boolean policyAllowed(CellOperation operation, ReplacePolicy policy) {
        return switch (operation) {
            case PLACE -> policy == ReplacePolicy.REQUIRE_EMPTY
                    || policy == ReplacePolicy.REPLACE_REPLACEABLE
                    || policy == ReplacePolicy.REPLACE_NATURAL
                    || policy == ReplacePolicy.FORCE_AUTHORIZED;
            case CLEAR -> policy == ReplacePolicy.CLEAR_AUTHORIZED
                    || policy == ReplacePolicy.FORCE_AUTHORIZED;
            case PRESERVE -> policy == ReplacePolicy.PRESERVE_EXISTING;
            case TEMPORARY -> policy == ReplacePolicy.REQUIRE_EMPTY
                    || policy == ReplacePolicy.REPLACE_REPLACEABLE;
        };
    }

    private static void validateAtomicGroupContracts(List<PlanPlacement> placements,
                                                     List<Problem> problems) {
        Map<String, PlanPlacement> firstByGroup = new LinkedHashMap<>();
        for (PlanPlacement placement : placements) {
            if (placement.atomicGroup().isBlank()) {
                continue;
            }
            if (placement.operation() != CellOperation.PLACE) {
                problems.add(new Problem("atomic_group_requires_place", placement.id(), placement.atomicGroup()));
            }
            PlanPlacement first = firstByGroup.putIfAbsent(placement.atomicGroup(), placement);
            if (first == null) {
                continue;
            }
            if (!first.state().blockId().equals(placement.state().blockId())) {
                problems.add(new Problem("atomic_group_block_mismatch", placement.id(), placement.atomicGroup()));
            }
            if (first.replacePolicy() != placement.replacePolicy()) {
                problems.add(new Problem("atomic_group_policy_mismatch", placement.id(), placement.atomicGroup()));
            }
        }
    }

    private static void detectCycles(Map<String, PlanPlacement> byId, List<Problem> problems) {
        Map<String, Integer> remainingDependencies = new LinkedHashMap<>();
        Map<String, List<String>> dependents = new HashMap<>();
        for (Map.Entry<String, PlanPlacement> entry : byId.entrySet()) {
            int count = 0;
            for (String dependency : entry.getValue().dependencies()) {
                if (byId.containsKey(dependency)) {
                    count++;
                    dependents.computeIfAbsent(dependency, ignored -> new ArrayList<>()).add(entry.getKey());
                }
            }
            remainingDependencies.put(entry.getKey(), count);
        }

        ArrayDeque<String> ready = new ArrayDeque<>();
        remainingDependencies.forEach((id, count) -> {
            if (count == 0) {
                ready.addLast(id);
            }
        });
        int processed = 0;
        while (!ready.isEmpty()) {
            String completed = ready.removeFirst();
            processed++;
            for (String dependent : dependents.getOrDefault(completed, List.of())) {
                int remaining = remainingDependencies.computeIfPresent(dependent, (ignored, count) -> count - 1);
                if (remaining == 0) {
                    ready.addLast(dependent);
                }
            }
        }
        if (processed != byId.size()) {
            String cycleMembers = remainingDependencies.entrySet().stream()
                    .filter(entry -> entry.getValue() > 0)
                    .limit(8)
                    .map(Map.Entry::getKey)
                    .reduce((left, right) -> left + "," + right)
                    .orElse("unknown");
            problems.add(new Problem("dependency_cycle", cycleMembers,
                    (byId.size() - processed) + " placements are part of or blocked by a dependency cycle"));
        }
    }

    public record Problem(String code, String placementId, String detail) {
    }

    public record ValidationResult(List<Problem> problems) {
        public ValidationResult {
            problems = problems == null ? List.of() : List.copyOf(problems);
        }

        public boolean valid() {
            return problems.isEmpty();
        }
    }

    private record Cell(int x, int y, int z) {
    }
}
