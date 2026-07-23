package io.github.greytaiwolf.fakeaiplayer.goal;

import io.github.greytaiwolf.fakeaiplayer.action.ContainerAction;
import io.github.greytaiwolf.fakeaiplayer.entity.AIPlayerEntity;
import io.github.greytaiwolf.fakeaiplayer.mining.ToolTier;
import io.github.greytaiwolf.fakeaiplayer.mode.CapabilityRuntime;
import io.github.greytaiwolf.fakeaiplayer.mode.ObservableWorldQuery;
import io.github.greytaiwolf.fakeaiplayer.mode.PrivilegedCapability;
import io.github.greytaiwolf.fakeaiplayer.task.BlueprintSchema;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

public final class GoalSnapshotCollector {
    private static final int STATION_RADIUS = 8;
    private static final int CONTAINER_RADIUS = 16;

    private GoalSnapshotCollector() {
    }

    public record Context(
            BlockPos origin,
            Set<BlockPos> boundContainers,
            BlueprintSchema blueprint,
            BlockPos buildAnchor,
            int buildPlaced,
            int buildSkipped
    ) {
        public Context {
            origin = origin == null ? BlockPos.ZERO : origin.immutable();
            boundContainers = boundContainers == null ? Set.of() : boundContainers.stream()
                    .map(BlockPos::immutable).collect(java.util.stream.Collectors.toUnmodifiableSet());
            buildAnchor = buildAnchor == null ? null : buildAnchor.immutable();
        }

        public static Context at(BlockPos origin) {
            return new Context(origin, Set.of(), null, null, 0, 0);
        }
    }

    public static GoalSnapshot collect(AIPlayerEntity bot, Goal goal, Context context) {
        Context resolved = context == null ? Context.at(bot.blockPosition()) : context;
        Map<String, Integer> inventory = inventoryCounts(bot);
        Set<String> capabilities = armorCapabilities(bot);
        if (goal instanceof Goal.Workstation || goal instanceof Goal.Stockpile) {
            CapabilityRuntime.decide(bot, PrivilegedCapability.HIDDEN_BLOCK_SCAN, "goal_postcondition");
        }
        Map<String, Integer> nearbyBlocks = goal instanceof Goal.Workstation
                ? stationCounts(bot, resolved.origin()) : Map.of();
        Map<String, Integer> containerItems = goal instanceof Goal.Stockpile
                ? containerCounts(bot, resolved) : Map.of();
        int foodUnits = goal instanceof Goal.Food ? foodUnits(inventory) : 0;
        Optional<StructureReport> structure = Optional.empty();
        if (goal instanceof Goal.Build && resolved.blueprint() != null && resolved.buildAnchor() != null) {
            structure = Optional.of(StructureVerifier.verify(bot.serverLevel(), resolved.blueprint(),
                    resolved.buildAnchor(), resolved.buildPlaced(), resolved.buildSkipped()));
        }
        return new GoalSnapshot(inventory, ToolTier.bestPickaxeTier(bot), capabilities,
                nearbyBlocks, containerItems, foodUnits, structure);
    }

    private static Map<String, Integer> inventoryCounts(AIPlayerEntity bot) {
        Map<String, Integer> counts = new HashMap<>();
        List<ItemStack> stacks = new ArrayList<>();
        stacks.addAll(bot.getInventory().items);
        stacks.addAll(bot.getInventory().offhand);
        stacks.addAll(bot.getInventory().armor);
        for (ItemStack stack : stacks) {
            if (!stack.isEmpty() && !nearlyBroken(stack)) {
                counts.merge(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(), stack.getCount(), Integer::sum);
            }
        }
        return counts;
    }

    private static Set<String> armorCapabilities(AIPlayerEntity bot) {
        Set<String> capabilities = new HashSet<>();
        addEquippedArmorCapability(capabilities, bot.getItemBySlot(EquipmentSlot.HEAD), "helmet",
                Items.IRON_HELMET, Items.DIAMOND_HELMET, Items.NETHERITE_HELMET);
        addEquippedArmorCapability(capabilities, bot.getItemBySlot(EquipmentSlot.CHEST), "chestplate",
                Items.IRON_CHESTPLATE, Items.DIAMOND_CHESTPLATE, Items.NETHERITE_CHESTPLATE);
        addEquippedArmorCapability(capabilities, bot.getItemBySlot(EquipmentSlot.LEGS), "leggings",
                Items.IRON_LEGGINGS, Items.DIAMOND_LEGGINGS, Items.NETHERITE_LEGGINGS);
        addEquippedArmorCapability(capabilities, bot.getItemBySlot(EquipmentSlot.FEET), "boots",
                Items.IRON_BOOTS, Items.DIAMOND_BOOTS, Items.NETHERITE_BOOTS);
        for (ItemStack stack : ownedStacks(bot)) {
            if (!stack.isEmpty() && !nearlyBroken(stack)) {
                Item item = stack.getItem();
                if (item == Items.IRON_SWORD || item == Items.DIAMOND_SWORD || item == Items.NETHERITE_SWORD) {
                    capabilities.add("sword");
                }
            }
        }
        return capabilities;
    }

    private static void addEquippedArmorCapability(Set<String> capabilities,
                                                    ItemStack stack,
                                                    String capability,
                                                    Item... allowed) {
        if (stack.isEmpty() || nearlyBroken(stack)) {
            return;
        }
        for (Item item : allowed) {
            if (stack.is(item)) {
                capabilities.add(capability);
                return;
            }
        }
    }

    private static List<ItemStack> ownedStacks(AIPlayerEntity bot) {
        List<ItemStack> stacks = new ArrayList<>();
        stacks.addAll(bot.getInventory().items);
        stacks.addAll(bot.getInventory().offhand);
        return stacks;
    }

    private static Map<String, Integer> stationCounts(AIPlayerEntity bot, BlockPos origin) {
        Map<String, Integer> counts = new HashMap<>();
        for (BlockPos pos : BlockPos.withinManhattan(origin, STATION_RADIUS, 4, STATION_RADIUS)) {
            if (!ObservableWorldQuery.canObserveBlock(bot, pos)) {
                continue;
            }
            var state = bot.serverLevel().getBlockState(pos);
            if (state.is(Blocks.CRAFTING_TABLE)) {
                counts.merge("minecraft:crafting_table", 1, Integer::sum);
            } else if (state.is(Blocks.FURNACE)) {
                counts.merge("minecraft:furnace", 1, Integer::sum);
            } else if (state.is(Blocks.CHEST) || state.is(Blocks.TRAPPED_CHEST)) {
                counts.merge("minecraft:chest", 1, Integer::sum);
            }
        }
        return counts;
    }

    private static Map<String, Integer> containerCounts(AIPlayerEntity bot, Context context) {
        List<BlockPos> positions = context.boundContainers().isEmpty()
                ? scanContainerPositions(bot, context.origin())
                : List.copyOf(context.boundContainers());
        Set<Container> unique = Collections.newSetFromMap(new IdentityHashMap<>());
        Map<String, Integer> counts = new HashMap<>();
        for (BlockPos pos : positions) {
            if (!ObservableWorldQuery.canObserveBlock(bot, pos)) {
                continue;
            }
            Container inventory = ContainerAction.resolve(bot, pos).orElse(null);
            if (inventory == null || !unique.add(inventory)) {
                continue;
            }
            for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
                ItemStack stack = inventory.getItem(slot);
                if (!stack.isEmpty()) {
                    counts.merge(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(), stack.getCount(), Integer::sum);
                }
            }
        }
        return counts;
    }

    private static List<BlockPos> scanContainerPositions(AIPlayerEntity bot, BlockPos origin) {
        List<BlockPos> positions = new ArrayList<>();
        for (BlockPos pos : BlockPos.withinManhattan(origin, CONTAINER_RADIUS, 6, CONTAINER_RADIUS)) {
            if (ObservableWorldQuery.canObserveBlock(bot, pos)
                    && bot.serverLevel().getBlockEntity(pos) instanceof Container) {
                positions.add(pos.immutable());
            }
        }
        return positions;
    }

    private static int foodUnits(Map<String, Integer> inventory) {
        int units = 0;
        for (Item item : List.of(
                Items.COOKED_BEEF, Items.COOKED_CHICKEN, Items.COOKED_MUTTON, Items.COOKED_PORKCHOP,
                Items.COOKED_RABBIT, Items.COOKED_COD, Items.COOKED_SALMON, Items.BAKED_POTATO, Items.BREAD)) {
            units += inventory.getOrDefault(BuiltInRegistries.ITEM.getKey(item).toString(), 0);
        }
        units += inventory.getOrDefault(BuiltInRegistries.ITEM.getKey(Items.SWEET_BERRIES).toString(), 0) / 2;
        return units;
    }

    private static boolean nearlyBroken(ItemStack stack) {
        return stack.isDamageableItem() && stack.getDamageValue() >= stack.getMaxDamage() - 1;
    }
}
