package io.github.greytaiwolf.fakeaiplayer.perception;

import io.github.greytaiwolf.fakeaiplayer.AIBotConfig;
import io.github.greytaiwolf.fakeaiplayer.action.InventoryAction;
import io.github.greytaiwolf.fakeaiplayer.entity.AIPlayerEntity;
import io.github.greytaiwolf.fakeaiplayer.log.BotLog;
import io.github.greytaiwolf.fakeaiplayer.log.LogFields;
import io.github.greytaiwolf.fakeaiplayer.mode.CapabilityRuntime;
import io.github.greytaiwolf.fakeaiplayer.mode.ObservableWorldQuery;
import io.github.greytaiwolf.fakeaiplayer.mode.PrivilegedCapability;
import io.github.greytaiwolf.fakeaiplayer.task.TaskManager;
import io.github.greytaiwolf.fakeaiplayer.task.TaskStatus;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

public final class PerceptionCollector {
    private PerceptionCollector() {
    }

    public static PerceptionSnapshot collect(AIPlayerEntity bot) {
        long started = System.currentTimeMillis();
        AIBotConfig.Perception config = AIBotConfig.get().perception();
        ServerLevel world = bot.serverLevel();
        BlockPos center = bot.blockPosition();
        PerceptionSnapshot.SelfState self = new PerceptionSnapshot.SelfState(
                bot.getX(),
                bot.getY(),
                bot.getZ(),
                bot.getYRot(),
                bot.getXRot(),
                bot.getHealth(),
                bot.getFoodData().getFoodLevel(),
                BuiltInRegistries.ITEM.getKey(bot.getMainHandItem().getItem()).toString(),
                InventoryAction.summarize(bot));

        CapabilityRuntime.decide(bot, PrivilegedCapability.HIDDEN_BLOCK_SCAN, "perception_snapshot");
        BlockScan blockScan = collectBlocks(bot, world, center, Math.min(config.radius(), 8), config.maxBlocks());
        List<PerceptionSnapshot.NearbyEntity> entities = collectEntities(bot, world, config.radius(), config.maxEntities());
        List<PerceptionSnapshot.NearbyItem> items = collectItems(bot, world, config.radius(), config.maxItems());
        PerceptionSnapshot.Highlights highlights = buildHighlights(blockScan.highlights(), entities);
        List<PerceptionSnapshot.NearbyBlock> blocks = config.includeRawLists() ? blockScan.blocks() : List.of();
        List<PerceptionSnapshot.NearbyEntity> rawEntities = config.includeRawLists() ? entities : List.of();
        List<PerceptionSnapshot.NearbyItem> rawItems = config.includeRawLists() ? items : List.of();
        TaskStatus status = TaskManager.INSTANCE.status(bot);
        PerceptionSnapshot.TaskInfo task = new PerceptionSnapshot.TaskInfo(
                status.name(),
                status.state().name(),
                round(status.progress()),
                status.elapsedTicks(),
                status.description(),
                status.failureReason());
        long elapsed = System.currentTimeMillis() - started;
        BotLog.perception(bot, "snapshot",
                "hp", bot.getHealth(),
                "hunger", bot.getFoodData().getFoodLevel(),
                "pos", LogFields.pos(center),
                "holding", BuiltInRegistries.ITEM.getKey(bot.getMainHandItem().getItem()),
                "blocks_n", blockScan.blocks().size(),
                "entities_n", entities.size(),
                "items_n", items.size(),
                "light", world.getMaxLocalRawBrightness(center));
        if (elapsed > 10L) {
            BotLog.warn(io.github.greytaiwolf.fakeaiplayer.log.LogCategory.PERCEPTION, bot, "snapshot_slow", "elapsed_ms", elapsed);
        }
        return new PerceptionSnapshot(
                self,
                task,
                highlights,
                blocks,
                rawEntities,
                rawItems,
                new PerceptionSnapshot.TimeInfo(world.getDayTime() % 24000L, world.isDay(), world.getMaxLocalRawBrightness(center)));
    }

    private static BlockScan collectBlocks(AIPlayerEntity bot, ServerLevel world, BlockPos center, int radius, int limit) {
        List<PerceptionSnapshot.NearbyBlock> blocks = new ArrayList<>();
        Map<String, List<PerceptionSnapshot.NearbyBlock>> highlights = new HashMap<>();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -radius; dy <= radius; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    BlockPos pos = center.offset(dx, dy, dz);
                    BlockState state = world.getBlockState(pos);
                    boolean water = state.getFluidState().is(FluidTags.WATER);
                    if (state.isAir() && !water) {
                        continue;
                    }
                    if (!ObservableWorldQuery.canObserveBlock(bot, pos)) {
                        continue;
                    }
                    double distance = Math.sqrt(center.distSqr(pos));
                    blocks.add(new PerceptionSnapshot.NearbyBlock(
                            BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString(),
                            pos.getX(),
                            pos.getY(),
                            pos.getZ(),
                            round(distance)));
                    addHighlights(highlights, state, pos, round(distance), water);
                }
            }
        }
        blocks.sort(Comparator.comparingDouble(PerceptionSnapshot.NearbyBlock::distance));
        highlights.replaceAll((ignored, values) -> values.stream()
                .sorted(Comparator.comparingDouble(PerceptionSnapshot.NearbyBlock::distance))
                .limit(2)
                .toList());
        return new BlockScan(blocks.stream().limit(limit).toList(), highlights);
    }

    private static List<PerceptionSnapshot.NearbyEntity> collectEntities(AIPlayerEntity bot, ServerLevel world, int radius, int limit) {
        return world.getEntities(bot, bot.getBoundingBox().inflate(radius), entity -> entity instanceof LivingEntity)
                .stream()
                .filter(entity -> ObservableWorldQuery.canObserveEntity(bot, entity))
                .sorted(Comparator.comparingDouble(bot::distanceTo))
                .limit(limit)
                .map(entity -> toNearbyEntity(bot, entity))
                .toList();
    }

    private static PerceptionSnapshot.NearbyEntity toNearbyEntity(AIPlayerEntity bot, Entity entity) {
        float hp = entity instanceof LivingEntity living ? living.getHealth() : 0.0F;
        return new PerceptionSnapshot.NearbyEntity(
                BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString(),
                round(entity.getX()),
                round(entity.getY()),
                round(entity.getZ()),
                round(bot.distanceTo(entity)),
                entity instanceof Enemy,
                hp);
    }

    private static List<PerceptionSnapshot.NearbyItem> collectItems(AIPlayerEntity bot, ServerLevel world, int radius, int limit) {
        return world.getEntities(bot, bot.getBoundingBox().inflate(radius), entity -> entity instanceof ItemEntity)
                .stream()
                .filter(entity -> ObservableWorldQuery.canObserveEntity(bot, entity))
                .sorted(Comparator.comparingDouble(bot::distanceTo))
                .limit(limit)
                .map(entity -> {
                    ItemEntity item = (ItemEntity) entity;
                    return new PerceptionSnapshot.NearbyItem(
                            BuiltInRegistries.ITEM.getKey(item.getItem().getItem()).toString(),
                            round(item.getX()),
                            round(item.getY()),
                            round(item.getZ()));
                })
                .toList();
    }

    private static void addHighlights(Map<String, List<PerceptionSnapshot.NearbyBlock>> highlights,
                                      BlockState state,
                                      BlockPos pos,
                                      double distance,
                                      boolean water) {
        if (water) {
            addHighlight(highlights, "nearest_water", "minecraft:water", pos, distance);
        }
        if (state.is(BlockTags.LOGS)) {
            addHighlight(highlights, "nearest_tree", BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString(), pos, distance);
        }
        if (state.is(Blocks.STONE) || state.is(Blocks.COBBLESTONE) || state.is(Blocks.DEEPSLATE)) {
            addHighlight(highlights, "nearest_stone", BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString(), pos, distance);
        }
        String id = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
        if (id.endsWith("_ore")) {
            addHighlight(highlights, "nearest_ore", id, pos, distance);
        }
        if (state.is(Blocks.FURNACE) || state.is(Blocks.BLAST_FURNACE) || state.is(Blocks.SMOKER)) {
            addHighlight(highlights, "nearest_furnace", id, pos, distance);
        }
        if (state.is(Blocks.CHEST) || state.is(Blocks.TRAPPED_CHEST) || state.is(Blocks.BARREL)) {
            addHighlight(highlights, "nearest_chest", id, pos, distance);
        }
        if (state.is(BlockTags.BEDS)) {
            addHighlight(highlights, "nearest_bed", id, pos, distance);
        }
        if (state.is(Blocks.CRAFTING_TABLE)) {
            addHighlight(highlights, "nearest_crafting_table", id, pos, distance);
        }
    }

    private static void addHighlight(Map<String, List<PerceptionSnapshot.NearbyBlock>> highlights,
                                     String key,
                                     String id,
                                     BlockPos pos,
                                     double distance) {
        highlights.computeIfAbsent(key, ignored -> new ArrayList<>())
                .add(new PerceptionSnapshot.NearbyBlock(id, pos.getX(), pos.getY(), pos.getZ(), distance));
    }

    private static PerceptionSnapshot.Highlights buildHighlights(Map<String, List<PerceptionSnapshot.NearbyBlock>> blockHighlights,
                                                                 List<PerceptionSnapshot.NearbyEntity> entities) {
        List<PerceptionSnapshot.NearbyEntity> hostiles = entities.stream()
                .filter(PerceptionSnapshot.NearbyEntity::hostile)
                .limit(2)
                .toList();
        return new PerceptionSnapshot.Highlights(
                blockHighlights.getOrDefault("nearest_tree", List.of()),
                blockHighlights.getOrDefault("nearest_stone", List.of()),
                blockHighlights.getOrDefault("nearest_ore", List.of()),
                blockHighlights.getOrDefault("nearest_water", List.of()),
                blockHighlights.getOrDefault("nearest_furnace", List.of()),
                blockHighlights.getOrDefault("nearest_chest", List.of()),
                blockHighlights.getOrDefault("nearest_bed", List.of()),
                blockHighlights.getOrDefault("nearest_crafting_table", List.of()),
                hostiles);
    }

    private static double round(double value) {
        return Math.round(value * 100.0D) / 100.0D;
    }

    private record BlockScan(List<PerceptionSnapshot.NearbyBlock> blocks,
                             Map<String, List<PerceptionSnapshot.NearbyBlock>> highlights) {
    }
}
