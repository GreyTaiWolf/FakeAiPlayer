package io.github.greytaiwolf.fakeaiplayer.persist;

import io.github.greytaiwolf.fakeaiplayer.goal.Goal;
import io.github.greytaiwolf.fakeaiplayer.mission.GoalSpec;
import io.github.greytaiwolf.fakeaiplayer.mission.MissionPolicy;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;

/** Stable, declarative Goal representation. No Task, phase, path, entity, or world object is serialized. */
public record MissionSpec(
        String type,
        Map<String, String> params,
        List<String> values,
        String source,
        Integer priority,
        PolicySpec policy,
        String binding
) {
    private static final HexFormat HEX = HexFormat.of();

    /** Backwards-compatible constructor for callers and schema-1 snapshots without provenance. */
    public MissionSpec(String type, Map<String, String> params, List<String> values) {
        this(type, params, values, "", null, null, "");
    }

    /** Schema-2 compatibility constructor for records that predate exact MissionPolicy state. */
    public MissionSpec(String type,
                       Map<String, String> params,
                       List<String> values,
                       String source,
                       Integer priority) {
        this(type, params, values, source, priority, null, "");
    }

    /** Compatibility constructor for P3 records that predate the outer MissionSpec binding. */
    public MissionSpec(String type,
                       Map<String, String> params,
                       List<String> values,
                       String source,
                       Integer priority,
                       PolicySpec policy) {
        this(type, params, values, source, priority, policy, "");
    }

    public MissionSpec {
        type = type == null ? "" : type;
        params = params == null ? Map.of() : Map.copyOf(params);
        values = values == null ? List.of() : List.copyOf(values);
        source = source == null ? "" : source.trim();
        binding = binding == null ? "" : binding.trim();
    }

    public static MissionSpec fromGoal(Goal goal) {
        return fromGoal(goal, GoalSpec.Source.LEGACY,
                GoalSpec.defaultPriority(GoalSpec.Source.LEGACY), null);
    }

    public static MissionSpec fromGoal(Goal goal, GoalSpec.Source source, int priority) {
        return fromGoal(goal, source, priority, null);
    }

    public static MissionSpec fromGoal(Goal goal,
                                       GoalSpec.Source source,
                                       int priority,
                                       MissionPolicy policy) {
        Map<String, String> params = new LinkedHashMap<>();
        List<String> values = List.of();
        String type;
        switch (goal) {
            case Goal.HaveItem g -> {
                type = "have_item";
                params.put("item", BuiltInRegistries.ITEM.getKey(g.item()).toString());
                params.put("count", String.valueOf(g.count()));
            }
            case Goal.HavePickaxeTier g -> {
                type = "have_pickaxe_tier";
                params.put("tier", String.valueOf(g.tier()));
            }
            case Goal.MineOre g -> {
                type = "mine_ore";
                params.put("count", String.valueOf(g.count()));
                values = g.ores().stream().map(block -> BuiltInRegistries.BLOCK.getKey(block).toString()).sorted().toList();
            }
            case Goal.HarvestCrop g -> {
                type = "harvest_crop";
                params.put("crop", BuiltInRegistries.BLOCK.getKey(g.crop()).toString());
                params.put("seed", BuiltInRegistries.ITEM.getKey(g.seed()).toString());
                params.put("produce", BuiltInRegistries.ITEM.getKey(g.produce()).toString());
                params.put("count", String.valueOf(g.count()));
            }
            case Goal.Armor ignored -> type = "armor";
            case Goal.Workstation ignored -> type = "workstation";
            case Goal.Stockpile g -> {
                type = "stockpile";
                params.put("item", BuiltInRegistries.ITEM.getKey(g.item()).toString());
                params.put("count", String.valueOf(g.count()));
            }
            case Goal.Food g -> {
                type = "food";
                params.put("count", String.valueOf(g.cookedCount()));
            }
            case Goal.Build g -> {
                type = "build";
                params.put("blueprint", g.blueprint());
                if (g.anchor() != null) {
                    params.put("anchor_x", String.valueOf(g.anchor().getX()));
                    params.put("anchor_y", String.valueOf(g.anchor().getY()));
                    params.put("anchor_z", String.valueOf(g.anchor().getZ()));
                }
                if (g.dimension() != null) {
                    params.put("dimension", g.dimension());
                }
                if (g.blueprintDigest() != null) {
                    params.put("blueprint_digest", g.blueprintDigest());
                }
            }
        }
        GoalSpec.Source resolvedSource = source == null ? GoalSpec.Source.LEGACY : source;
        MissionSpec unbound = new MissionSpec(
                type,
                params,
                values,
                resolvedSource.name(),
                priority,
                policy == null ? null : PolicySpec.from(policy),
                "");
        return unbound.withCurrentBinding();
    }

    /** Old snapshots intentionally restore at RESTORED priority rather than gaining authority. */
    public GoalSpec.Source sourceOrRestored() {
        if (!provenanceValid()) {
            return GoalSpec.Source.RESTORED;
        }
        try {
            return source.isBlank() ? GoalSpec.Source.RESTORED : GoalSpec.Source.valueOf(source);
        } catch (RuntimeException ignored) {
            return GoalSpec.Source.RESTORED;
        }
    }

    public int priorityOrDefault() {
        if (!provenanceValid()) {
            return GoalSpec.defaultPriority(GoalSpec.Source.RESTORED);
        }
        GoalSpec.Source resolved = sourceOrRestored();
        return priority == null ? GoalSpec.defaultPriority(resolved) : priority;
    }

    /** Current persisted MissionSpecs carry a self-binding over every semantic field. */
    public boolean bindingPresent() {
        return !binding.isBlank();
    }

    public boolean bindingValid() {
        return binding.matches("[0-9a-f]{64}") && binding.equals(contentFingerprint());
    }

    /** Exact pre-P3 persistence shape; it may migrate only at restricted RESTORED authority. */
    public boolean legacyUnboundShape() {
        return binding.isBlank() && source.isBlank() && priority == null && policy == null;
    }

    private boolean provenanceValid() {
        if (source.isBlank()) {
            return priority == null;
        }
        try {
            GoalSpec.Source.valueOf(source);
            return priority != null && priority >= 0 && priority <= 100;
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private MissionSpec withCurrentBinding() {
        return new MissionSpec(type, params, values, source, priority, policy,
                contentFingerprint());
    }

    private String contentFingerprint() {
        StringBuilder canonical = new StringBuilder();
        appendField(canonical, "type", type);
        appendField(canonical, "params.size", Integer.toString(params.size()));
        new TreeMap<>(params).forEach((key, value) -> {
            appendField(canonical, "params.key", key);
            appendField(canonical, "params.value", value);
        });
        appendField(canonical, "values.size", Integer.toString(values.size()));
        values.forEach(value -> appendField(canonical, "values.item", value));
        appendField(canonical, "source", source);
        appendNullable(canonical, "priority", priority == null ? null : priority.toString());
        if (policy == null) {
            appendField(canonical, "policy.present", "false");
        } else {
            appendField(canonical, "policy.present", "true");
            appendNullable(canonical, "policy.risk", policy.riskLevel());
            appendNullable(canonical, "policy.mutation", policy.mutationScope());
            appendNullable(canonical, "policy.time",
                    policy.timeBudgetTicks() == null ? null : policy.timeBudgetTicks().toString());
            appendNullable(canonical, "policy.recovery",
                    policy.recoveryBudget() == null ? null : policy.recoveryBudget().toString());
            appendNullable(canonical, "policy.interruption", policy.interruptionPolicy());
        }
        try {
            return HEX.formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("sha256_unavailable", exception);
        }
    }

    private static void appendNullable(StringBuilder target, String name, String value) {
        appendField(target, name + ".present", Boolean.toString(value != null));
        if (value != null) {
            appendField(target, name, value);
        }
    }

    private static void appendField(StringBuilder target, String name, String value) {
        appendLengthPrefixed(target, name);
        appendLengthPrefixed(target, value);
    }

    private static void appendLengthPrefixed(StringBuilder target, String value) {
        target.append(value.length()).append(':').append(value).append(';');
    }

    /** Empty means either a legacy record (no policy) or a corrupt policy; use policyPresent to distinguish. */
    public Optional<MissionPolicy> persistedPolicy() {
        return policy == null ? Optional.empty() : policy.toPolicy();
    }

    public boolean policyPresent() {
        return policy != null;
    }

    public Optional<Goal> toGoal() {
        try {
            return Optional.of(switch (type) {
                case "have_item" -> new Goal.HaveItem(item("item"), integer("count"));
                case "have_pickaxe_tier" -> new Goal.HavePickaxeTier(integer("tier"));
                case "mine_ore" -> new Goal.MineOre(values.stream()
                        .map(ResourceLocation::parse)
                        .map(id -> BuiltInRegistries.BLOCK.getOptional(id).orElseThrow())
                        .collect(java.util.stream.Collectors.toSet()), integer("count"));
                case "harvest_crop" -> new Goal.HarvestCrop(
                        block("crop"), item("seed"), item("produce"), integer("count"));
                case "armor" -> new Goal.Armor();
                case "workstation" -> new Goal.Workstation();
                case "stockpile" -> new Goal.Stockpile(item("item"), integer("count"));
                case "food" -> new Goal.Food(integer("count"));
                case "build" -> new Goal.Build(
                        required("blueprint"), optionalAnchor(), params.get("dimension"),
                        params.get("blueprint_digest"));
                default -> throw new IllegalArgumentException("unknown_mission_type:" + type);
            });
        } catch (RuntimeException exception) {
            return Optional.empty();
        }
    }

    private net.minecraft.world.item.Item item(String key) {
        return BuiltInRegistries.ITEM.getOptional(ResourceLocation.parse(required(key))).orElseThrow();
    }

    private net.minecraft.world.level.block.Block block(String key) {
        return BuiltInRegistries.BLOCK.getOptional(ResourceLocation.parse(required(key))).orElseThrow();
    }

    private int integer(String key) {
        return Integer.parseInt(required(key));
    }

    private BlockPos optionalAnchor() {
        boolean hasX = params.containsKey("anchor_x");
        boolean hasY = params.containsKey("anchor_y");
        boolean hasZ = params.containsKey("anchor_z");
        if (!hasX && !hasY && !hasZ) {
            return null;
        }
        if (!(hasX && hasY && hasZ)) {
            throw new IllegalArgumentException("incomplete_build_anchor");
        }
        return new BlockPos(integer("anchor_x"), integer("anchor_y"), integer("anchor_z"));
    }

    private String required(String key) {
        String value = params.get(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("missing_mission_param:" + key);
        }
        return value;
    }

    /** Pure persistence form with boxed counters so missing legacy fields cannot become zero silently. */
    public record PolicySpec(
            String riskLevel,
            String mutationScope,
            Integer timeBudgetTicks,
            Integer recoveryBudget,
            String interruptionPolicy
    ) {
        public static PolicySpec from(MissionPolicy policy) {
            if (policy == null) {
                throw new IllegalArgumentException("mission_policy_missing");
            }
            return new PolicySpec(
                    policy.riskLevel().name(),
                    policy.mutationScope().name(),
                    policy.timeBudgetTicks(),
                    policy.recoveryBudget(),
                    policy.interruptionPolicy().name());
        }

        public Optional<MissionPolicy> toPolicy() {
            try {
                if (riskLevel == null || mutationScope == null || timeBudgetTicks == null
                        || recoveryBudget == null || interruptionPolicy == null) {
                    return Optional.empty();
                }
                return Optional.of(new MissionPolicy(
                        MissionPolicy.RiskLevel.valueOf(riskLevel),
                        MissionPolicy.MutationScope.valueOf(mutationScope),
                        timeBudgetTicks,
                        recoveryBudget,
                        MissionPolicy.InterruptionPolicy.valueOf(interruptionPolicy)));
            } catch (RuntimeException exception) {
                return Optional.empty();
            }
        }
    }
}
