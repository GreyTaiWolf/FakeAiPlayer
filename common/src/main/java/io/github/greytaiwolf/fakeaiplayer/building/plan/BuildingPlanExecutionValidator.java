package io.github.greytaiwolf.fakeaiplayer.building.plan;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

/** Registry-aware validation kept separate from the generator's pure structural checks. */
final class BuildingPlanExecutionValidator {
    private BuildingPlanExecutionValidator() {
    }

    static void validate(BuildingPlan plan, List<BuildingPlanValidator.Problem> problems) {
        Map<PlanPlacement, BlockState> resolvedStates = new LinkedHashMap<>();
        for (PlanPlacement placement : plan.placements()) {
            try {
                resolvedStates.put(placement, BlockStateResolver.resolve(placement.state()));
            } catch (IllegalArgumentException exception) {
                problems.add(new BuildingPlanValidator.Problem(
                        "invalid_block_state", placement.id(), exception.getMessage()));
            }
        }
        validateAtomicGroups(resolvedStates, problems);
    }

    /**
     * The current executor has one supported multi-cell item contract: a vanilla-style door click
     * creates exactly the lower cell and the cell directly above it. Treating arbitrary repeated
     * blocks as one inventory item would under-count materials and could mutate an unreviewed cell.
     */
    private static void validateAtomicGroups(
            Map<PlanPlacement, BlockState> resolvedStates,
            List<BuildingPlanValidator.Problem> problems) {
        Map<String, List<Map.Entry<PlanPlacement, BlockState>>> groups = new LinkedHashMap<>();
        for (Map.Entry<PlanPlacement, BlockState> entry : resolvedStates.entrySet()) {
            String group = entry.getKey().atomicGroup();
            if (!group.isBlank()) {
                groups.computeIfAbsent(group, ignored -> new ArrayList<>()).add(entry);
            }
        }
        for (Map.Entry<String, List<Map.Entry<PlanPlacement, BlockState>>> groupEntry : groups.entrySet()) {
            String group = groupEntry.getKey();
            List<Map.Entry<PlanPlacement, BlockState>> members = groupEntry.getValue();
            if (members.stream().anyMatch(entry -> !(entry.getValue().getBlock() instanceof DoorBlock))) {
                problems.add(new BuildingPlanValidator.Problem(
                        "unsupported_atomic_group_block", members.get(0).getKey().id(), group));
                continue;
            }
            if (members.size() != 2) {
                problems.add(new BuildingPlanValidator.Problem(
                        "door_atomic_group_size", members.get(0).getKey().id(),
                        group + " has " + members.size() + " cells"));
                continue;
            }
            Map.Entry<PlanPlacement, BlockState> lower = memberWithProperty(members, "half", "lower");
            Map.Entry<PlanPlacement, BlockState> upper = memberWithProperty(members, "half", "upper");
            if (lower == null || upper == null) {
                problems.add(new BuildingPlanValidator.Problem(
                        "door_atomic_group_halves", members.get(0).getKey().id(), group));
                continue;
            }
            PlanPlacement lowerPlacement = lower.getKey();
            PlanPlacement upperPlacement = upper.getKey();
            if (upperPlacement.dx() != lowerPlacement.dx()
                    || upperPlacement.dy() != lowerPlacement.dy() + 1
                    || upperPlacement.dz() != lowerPlacement.dz()) {
                problems.add(new BuildingPlanValidator.Problem(
                        "door_atomic_group_footprint", upperPlacement.id(), group));
            }
            if (!upperPlacement.dependencies().contains(lowerPlacement.id())) {
                problems.add(new BuildingPlanValidator.Problem(
                        "door_atomic_group_missing_dependency", upperPlacement.id(), group));
            }
            for (String property : List.of("facing", "hinge", "open", "powered")) {
                if (!stateProperty(lower.getValue(), property)
                        .equals(stateProperty(upper.getValue(), property))) {
                    problems.add(new BuildingPlanValidator.Problem(
                            "door_atomic_group_state_mismatch", upperPlacement.id(), group + ":" + property));
                }
            }
        }
    }

    private static Map.Entry<PlanPlacement, BlockState> memberWithProperty(
            List<Map.Entry<PlanPlacement, BlockState>> members,
            String property,
            String expected) {
        for (Map.Entry<PlanPlacement, BlockState> member : members) {
            if (expected.equals(stateProperty(member.getValue(), property))) {
                return member;
            }
        }
        return null;
    }

    private static String stateProperty(BlockState state, String property) {
        for (Property<?> candidate : state.getProperties()) {
            if (candidate.getName().equals(property)) {
                return stateProperty(state, candidate);
            }
        }
        return "";
    }

    private static <T extends Comparable<T>> String stateProperty(BlockState state, Property<T> property) {
        return property.getName(state.getValue(property));
    }
}
