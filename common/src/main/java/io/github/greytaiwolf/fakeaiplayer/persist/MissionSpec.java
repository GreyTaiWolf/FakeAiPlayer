package io.github.greytaiwolf.fakeaiplayer.persist;

import io.github.greytaiwolf.fakeaiplayer.goal.Goal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;

/** Stable, declarative Goal representation. No Task, phase, path, entity, or world object is serialized. */
public record MissionSpec(String type, Map<String, String> params, List<String> values) {
    public MissionSpec {
        type = type == null ? "" : type;
        params = params == null ? Map.of() : Map.copyOf(params);
        values = values == null ? List.of() : List.copyOf(values);
    }

    public static MissionSpec fromGoal(Goal goal) {
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
        return new MissionSpec(type, params, values);
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
}
