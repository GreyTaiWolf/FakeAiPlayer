package io.github.greytaiwolf.fakeaiplayer.goal;

import io.github.greytaiwolf.fakeaiplayer.mission.GoalSpec;
import io.github.greytaiwolf.fakeaiplayer.mission.MissionPlan;
import io.github.greytaiwolf.fakeaiplayer.mission.MissionPolicy;
import io.github.greytaiwolf.fakeaiplayer.mission.PlanNode;
import io.github.greytaiwolf.fakeaiplayer.mission.SkillSpec;
import io.github.greytaiwolf.fakeaiplayer.task.DigDownTask;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import net.minecraft.core.registries.BuiltInRegistries;

/**
 * Compatibility compiler that turns the existing GoalPlanner output into the new declarative
 * Mission/Skill contract while execution is still delegated to proven Task implementations.
 */
public final class LegacyMissionCompiler {
    public static final String PLANNER_VERSION = "goal-planner-v2-skill-adapter-1";

    private LegacyMissionCompiler() {
    }

    public static CompiledMission compile(UUID missionId,
                                          int revision,
                                          Goal goal,
                                          GoalSpec.Source source,
                                          String dimension,
                                          List<GoalStep> steps) {
        GoalSpec spec = goalSpec(goal, source, dimension);
        List<ExecutableSkill> executable = compileSkills(goal, steps);
        if (executable.isEmpty()) {
            throw new IllegalArgumentException("mission_plan_requires_skills");
        }
        PlanNode root = new PlanNode.Sequence(executable.stream()
                .map(skill -> (PlanNode) new PlanNode.Skill(skill.spec()))
                .toList());
        MissionPlan plan = new MissionPlan(missionId, revision, spec, root, PLANNER_VERSION);
        return new CompiledMission(plan, executable);
    }

    public static List<ExecutableSkill> compileSkills(List<GoalStep> steps) {
        return compileSkills(null, steps);
    }

    private static List<ExecutableSkill> compileSkills(Goal goal, List<GoalStep> steps) {
        if (steps == null) {
            return List.of();
        }
        List<ExecutableSkill> result = new ArrayList<>(steps.size());
        for (int index = 0; index < steps.size(); index++) {
            GoalStep step = steps.get(index);
            if (step == null) {
                throw new IllegalArgumentException("null_goal_step:" + index);
            }
            result.add(new ExecutableSkill(skillSpec(step, index, goal), step));
        }
        return List.copyOf(result);
    }

    static GoalSpec goalSpec(Goal goal, GoalSpec.Source source, String dimension) {
        if (goal == null) {
            throw new IllegalArgumentException("goal_missing");
        }
        if (goal instanceof Goal.Build build && !build.hasCompleteConfirmedBinding()) {
            throw new IllegalArgumentException("build_goal_requires_confirmed_binding");
        }
        GoalSpec.Source resolvedSource = source == null ? GoalSpec.Source.LEGACY : source;
        String type = goalType(goal);
        MissionPolicy policy = policyFor(goal);
        String boundDimension = dimension;
        if (goal instanceof Goal.Build build && build.dimension() != null) {
            if (dimension != null && !dimension.equals(build.dimension())) {
                throw new IllegalArgumentException("build_goal_dimension_mismatch");
            }
            boundDimension = build.dimension();
        }
        return new GoalSpec(
                type,
                resolvedSource,
                GoalSpec.defaultPriority(resolvedSource),
                successPredicate(goal),
                boundDimension,
                policy,
                goalAttributes(goal));
    }

    private static SkillSpec skillSpec(GoalStep step, int index, Goal goal) {
        String capabilityId = "legacy." + step.kind().name().toLowerCase(java.util.Locale.ROOT);
        return new SkillSpec(
                "step." + index + "." + capabilityId,
                capabilityId,
                1,
                stepParameters(step, goal),
                preconditions(step),
                List.of(successPredicate(step)),
                SkillSpec.RetryPolicy.standard(),
                mutationScope(step));
    }

    private static Map<String, String> stepParameters(GoalStep step, Goal goal) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("quota", String.valueOf(step.count()));
        switch (step.kind()) {
            case GATHER, GATHER_EXACT, CRAFT, STOCKPILE -> params.put("item", itemId(step.item()));
            case MINE, MINE_EXACT, FARM -> params.put("block", blockId(step.block()));
            case MINE_ORE -> params.put("ores", blocks(step.ores()));
            case SMELT -> {
                params.put("input", itemId(step.input()));
                params.put("output", itemId(step.output()));
            }
            case MOVE, MOVE_NON_MUTATING -> {
                params.put("x", String.valueOf(step.pos().getX()));
                params.put("y", String.valueOf(step.pos().getY()));
                params.put("z", String.valueOf(step.pos().getZ()));
            }
            case DESCEND_TO_Y -> params.put("y", String.valueOf(step.pos().getY()));
            case BUILD -> {
                if (!(goal instanceof Goal.Build build) || !build.hasCompleteConfirmedBinding()) {
                    throw new IllegalArgumentException("build_skill_requires_confirmed_binding");
                }
                if (!build.blueprint().equals(step.tag())) {
                    throw new IllegalArgumentException("build_skill_blueprint_mismatch");
                }
                params.put("blueprint", step.tag());
                params.put("anchor_x", String.valueOf(build.anchor().getX()));
                params.put("anchor_y", String.valueOf(build.anchor().getY()));
                params.put("anchor_z", String.valueOf(build.anchor().getZ()));
                params.put("dimension", build.dimension());
                params.put("blueprint_digest", build.blueprintDigest());
            }
            case HUNT, COOK_FOOD, MILK_COW, EQUIP_LOADOUT, PLACE_STATIONS, MAKE_OBSIDIAN -> {
            }
        }
        if (step.kind() == GoalStep.Kind.GATHER || step.kind() == GoalStep.Kind.GATHER_EXACT) {
            params.put("quota_mode", "delta_from_baseline");
            params.put("item_match", step.kind() == GoalStep.Kind.GATHER_EXACT ? "exact" : "family");
        }
        return params;
    }

    private static List<String> preconditions(GoalStep step) {
        return switch (step.kind()) {
            case GATHER, GATHER_EXACT -> List.of("world:observable_resource", "inventory:pickup_space");
            case MINE, MINE_EXACT, MINE_ORE, DESCEND_TO_Y, MAKE_OBSIDIAN ->
                    List.of("inventory:usable_tool", "world:safe_work_face");
            case CRAFT -> List.of("inventory:recipe_inputs");
            case SMELT, COOK_FOOD -> List.of("inventory:smelt_input", "inventory:fuel", "station:furnace");
            case MOVE, MOVE_NON_MUTATING -> List.of("world:standable_interaction_route");
            case FARM -> List.of("inventory:seed_and_hoe", "world:farmable_site");
            case HUNT -> List.of("world:reachable_preys", "safety:combat_budget");
            case MILK_COW -> List.of("inventory:bucket", "world:reachable_cow");
            case EQUIP_LOADOUT -> List.of("inventory:owned_armor_and_weapon", "inventory:no_open_session");
            case PLACE_STATIONS -> List.of("inventory:stations", "world:legal_placement_faces");
            case STOCKPILE -> List.of("world:reachable_container");
            case BUILD -> List.of("human:confirmed_blueprint", "inventory:building_materials",
                    "world:confirmed_dimension_and_anchor");
        };
    }

    private static String successPredicate(GoalStep step) {
        return switch (step.kind()) {
            case GATHER, GATHER_EXACT -> "inventory_delta(" + itemId(step.item()) + ")>=" + step.count();
            case MINE, MINE_EXACT -> "inventory_drop_delta("
                    + items(DigDownTask.targetDropsFor(step.block(), step.kind() == GoalStep.Kind.MINE_EXACT))
                    + ")>=" + step.count();
            case MINE_ORE -> "inventory_ore_drop_delta(" + blocks(step.ores()) + ")>=" + step.count();
            case CRAFT -> "inventory(" + itemId(step.item()) + ")>=" + step.count();
            case SMELT -> "smelt_output_delta(" + itemId(step.output()) + ")>=" + step.count();
            case MOVE, MOVE_NON_MUTATING -> "position_reaches_interaction_pose";
            case FARM -> "inventory_crop_delta(" + itemId(step.item()) + ")>=" + step.count();
            case HUNT -> "inventory_raw_food_delta>=" + step.count();
            case COOK_FOOD -> "inventory_cooked_food_delta>=" + step.count();
            case MILK_COW -> "inventory_milk_bucket_delta>=" + step.count();
            case EQUIP_LOADOUT -> "authoritative_armor_slots_and_owned_weapon_ready";
            case PLACE_STATIONS -> "nearby_workstation_set_complete";
            case STOCKPILE -> "bound_container_contains_requested_items";
            case DESCEND_TO_Y -> "position_y_at_or_below(" + step.pos().getY() + ")";
            case MAKE_OBSIDIAN -> "inventory_obsidian_delta>=" + step.count();
            case BUILD -> "confirmed_blueprint_world_diff_is_empty";
        };
    }

    private static MissionPolicy.MutationScope mutationScope(GoalStep step) {
        return switch (step.kind()) {
            case MOVE_NON_MUTATING, HUNT, MILK_COW, EQUIP_LOADOUT, STOCKPILE ->
                    MissionPolicy.MutationScope.NONE;
            case BUILD -> MissionPolicy.MutationScope.CONFIRMED_AREA;
            default -> MissionPolicy.MutationScope.SURVIVAL;
        };
    }

    private static String goalType(Goal goal) {
        return switch (goal) {
            case Goal.HaveItem ignored -> "have_item";
            case Goal.HavePickaxeTier ignored -> "have_pickaxe_tier";
            case Goal.MineOre ignored -> "mine_ore";
            case Goal.HarvestCrop ignored -> "harvest_crop";
            case Goal.Armor ignored -> "armor";
            case Goal.Workstation ignored -> "workstation";
            case Goal.Stockpile ignored -> "stockpile";
            case Goal.Food ignored -> "food";
            case Goal.Build ignored -> "build";
        };
    }

    private static String successPredicate(Goal goal) {
        return switch (goal) {
            case Goal.HaveItem g -> "inventory(" + itemId(g.item()) + ")>=" + g.count();
            case Goal.HavePickaxeTier g -> "best_pickaxe_tier>=" + g.tier();
            case Goal.MineOre g -> "inventory_ore_drops(" + blocks(g.ores()) + ")>=" + g.count();
            case Goal.HarvestCrop g -> "inventory(" + itemId(g.produce()) + ")>=" + g.count();
            case Goal.Armor ignored -> "equipped_armor_set_and_owned_sword";
            case Goal.Workstation ignored -> "nearby_workstation_set_complete";
            case Goal.Stockpile g -> "bound_container(" + itemId(g.item()) + ")>=" + g.count();
            case Goal.Food g -> "cooked_food_units>=" + g.cookedCount();
            case Goal.Build ignored -> "confirmed_blueprint_world_diff_is_empty";
        };
    }

    private static Map<String, String> goalAttributes(Goal goal) {
        Map<String, String> attributes = new LinkedHashMap<>();
        switch (goal) {
            case Goal.HaveItem g -> {
                attributes.put("item", itemId(g.item()));
                attributes.put("count", String.valueOf(g.count()));
            }
            case Goal.HavePickaxeTier g -> attributes.put("tier", String.valueOf(g.tier()));
            case Goal.MineOre g -> {
                attributes.put("ores", blocks(g.ores()));
                attributes.put("count", String.valueOf(g.count()));
            }
            case Goal.HarvestCrop g -> {
                attributes.put("crop", blockId(g.crop()));
                attributes.put("produce", itemId(g.produce()));
                attributes.put("count", String.valueOf(g.count()));
            }
            case Goal.Stockpile g -> {
                attributes.put("item", itemId(g.item()));
                attributes.put("count", String.valueOf(g.count()));
            }
            case Goal.Food g -> attributes.put("count", String.valueOf(g.cookedCount()));
            case Goal.Build g -> {
                attributes.put("blueprint", g.blueprint());
                if (g.anchor() != null) {
                    attributes.put("anchor_x", String.valueOf(g.anchor().getX()));
                    attributes.put("anchor_y", String.valueOf(g.anchor().getY()));
                    attributes.put("anchor_z", String.valueOf(g.anchor().getZ()));
                }
                if (g.dimension() != null) {
                    attributes.put("dimension", g.dimension());
                }
                if (g.blueprintDigest() != null) {
                    attributes.put("blueprint_digest", g.blueprintDigest());
                }
            }
            case Goal.Armor ignored -> {
            }
            case Goal.Workstation ignored -> {
            }
        }
        return attributes;
    }

    private static MissionPolicy policyFor(Goal goal) {
        if (goal instanceof Goal.Build) {
            return new MissionPolicy(
                    MissionPolicy.RiskLevel.CONSERVATIVE,
                    MissionPolicy.MutationScope.CONFIRMED_AREA,
                    144_000,
                    64,
                    MissionPolicy.InterruptionPolicy.RESUME_AFTER_SAFETY);
        }
        if (goal instanceof Goal.MineOre || goal instanceof Goal.Armor) {
            return new MissionPolicy(
                    MissionPolicy.RiskLevel.BALANCED,
                    MissionPolicy.MutationScope.SURVIVAL,
                    72_000,
                    12,
                    MissionPolicy.InterruptionPolicy.RESUME_AFTER_SAFETY);
        }
        return MissionPolicy.standard();
    }

    private static String itemId(net.minecraft.world.item.Item item) {
        return item == null ? "none" : BuiltInRegistries.ITEM.getKey(item).toString();
    }

    private static String blockId(net.minecraft.world.level.block.Block block) {
        return block == null ? "none" : BuiltInRegistries.BLOCK.getKey(block).toString();
    }

    private static String blocks(Set<net.minecraft.world.level.block.Block> blocks) {
        return blocks == null ? "" : blocks.stream()
                .map(LegacyMissionCompiler::blockId)
                .sorted()
                .collect(Collectors.joining(","));
    }

    private static String items(Set<net.minecraft.world.item.Item> items) {
        return items == null ? "" : items.stream()
                .map(LegacyMissionCompiler::itemId)
                .sorted()
                .collect(Collectors.joining(","));
    }

    public record ExecutableSkill(SkillSpec spec, GoalStep step) {
        public ExecutableSkill {
            if (spec == null || step == null) {
                throw new IllegalArgumentException("executable_skill_incomplete");
            }
        }
    }

    public record CompiledMission(MissionPlan plan, List<ExecutableSkill> skills) {
        public CompiledMission {
            if (plan == null || skills == null || skills.isEmpty()) {
                throw new IllegalArgumentException("compiled_mission_incomplete");
            }
            skills = List.copyOf(skills);
            if (!plan.requireLinearSkills().equals(skills.stream().map(ExecutableSkill::spec).toList())) {
                throw new IllegalArgumentException("compiled_mission_skill_mismatch");
            }
        }
    }
}
