package io.github.greytaiwolf.fakeaiplayer.action;

import io.github.greytaiwolf.fakeaiplayer.entity.AIPlayerEntity;
import io.github.greytaiwolf.fakeaiplayer.log.BotLog;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.OptionalInt;
import net.minecraft.core.NonNullList;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

public final class InventoryAction {
    private InventoryAction() {
    }

    public static ActionResult selectHotbar(AIPlayerEntity player, int slot) {
        if (!Inventory.isHotbarSlot(slot)) {
            return ActionResult.failed("slot_out_of_range");
        }
        player.getInventory().selected = slot;
        BotLog.action(player, "select_slot", "slot", slot);
        return ActionResult.SUCCESS;
    }

    public static OptionalInt findItem(AIPlayerEntity player, Item item) {
        var inventory = player.getInventory();
        for (int slot = 0; slot < inventory.items.size(); slot++) {
            if (inventory.items.get(slot).is(item)) {
                return OptionalInt.of(slot);
            }
        }
        return OptionalInt.empty();
    }

    public static int countItem(AIPlayerEntity player, Item item) {
        int count = 0;
        var inventory = player.getInventory();
        for (ItemStack stack : inventory.items) {
            if (stack.is(item)) {
                count += stack.getCount();
            }
        }
        for (ItemStack stack : inventory.offhand) {
            if (stack.is(item)) {
                count += stack.getCount();
            }
        }
        return count;
    }

    public static int equipFromSlot(AIPlayerEntity player, int sourceSlot) {
        Inventory inventory = player.getInventory();
        if (sourceSlot < 0 || sourceSlot >= inventory.items.size() || inventory.items.get(sourceSlot).isEmpty()) {
            return -1;
        }
        if (Inventory.isHotbarSlot(sourceSlot)) {
            inventory.selected = sourceSlot;
            inventory.setChanged();
            BotLog.action(player, "equip_slot", "source_slot", sourceSlot, "hotbar_slot", sourceSlot);
            return sourceSlot;
        }
        int hotbar = firstEmptyHotbar(inventory);
        if (hotbar < 0) {
            hotbar = inventory.selected;
        }
        ItemStack moving = inventory.items.get(sourceSlot);
        ItemStack inHotbar = inventory.items.get(hotbar);
        inventory.items.set(hotbar, moving);
        inventory.items.set(sourceSlot, inHotbar);
        inventory.selected = hotbar;
        inventory.setChanged();
        BotLog.action(player, "equip_slot", "source_slot", sourceSlot, "hotbar_slot", hotbar);
        return hotbar;
    }

    public static int firstEmptyHotbar(Inventory inventory) {
        for (int slot = 0; slot <= 8; slot++) {
            if (inventory.items.get(slot).isEmpty()) {
                return slot;
            }
        }
        return -1;
    }

    public static boolean hasItems(AIPlayerEntity player, Item item, int count) {
        return countItem(player, item) >= count;
    }

    public static boolean removeItems(AIPlayerEntity player, Item item, int count) {
        if (count <= 0) {
            return true;
        }
        if (countItem(player, item) < count) {
            return false;
        }
        Inventory inventory = player.getInventory();
        int remaining = removeFromList(inventory.items, item, count);
        if (remaining > 0) {
            remaining = removeFromList(inventory.offhand, item, remaining);
        }
        inventory.setChanged();
        BotLog.action(player, "remove_items", "item", item, "count", count);
        return remaining == 0;
    }

    public static int removeFromList(NonNullList<ItemStack> list, Item item, int remaining) {
        for (int slot = 0; slot < list.size() && remaining > 0; slot++) {
            ItemStack stack = list.get(slot);
            if (!stack.is(item)) {
                continue;
            }
            int take = Math.min(remaining, stack.getCount());
            stack.shrink(take);
            remaining -= take;
        }
        return remaining;
    }

    // 有害/有副作用的食物:生鸡肉(30% 中毒)、腐肉、河豚、蜘蛛眼、毒马铃薯——只在没别的可吃时才退而求其次。
    private static final java.util.Set<Item> HARMFUL_FOODS = java.util.Set.of(
            Items.CHICKEN, Items.ROTTEN_FLESH, Items.PUFFERFISH, Items.SPIDER_EYE, Items.POISONOUS_POTATO);

    public static int findFoodSlot(AIPlayerEntity player) {
        Inventory inventory = player.getInventory();
        int harmfulSlot = -1;
        for (int slot = 0; slot < inventory.items.size(); slot++) {
            ItemStack stack = inventory.items.get(slot);
            if (stack.isEmpty() || !stack.has(DataComponents.FOOD)) {
                continue;
            }
            if (HARMFUL_FOODS.contains(stack.getItem())) {
                if (harmfulSlot < 0) {
                    harmfulSlot = slot; // 记下作为最后兜底
                }
                continue;
            }
            return slot; // 优先安全食物(熟肉/面包/生牛猪羊)
        }
        return harmfulSlot; // 只剩有害食物时才吃(总比饿死强);都没有则 -1
    }

    public static Map<String, Integer> summarize(AIPlayerEntity player) {
        Map<String, Integer> summary = new LinkedHashMap<>();
        var inventory = player.getInventory();
        for (ItemStack stack : inventory.items) {
            addStack(summary, stack);
        }
        for (ItemStack stack : inventory.offhand) {
            addStack(summary, stack);
        }
        return summary;
    }

    public static ActionResult giveItem(AIPlayerEntity player, ItemStack stack) {
        Item item = stack.getItem();
        int count = stack.getCount();
        boolean inserted = player.getInventory().add(stack);
        player.getInventory().setChanged();
        BotLog.action(player, "give", "item", item, "count", count, "inserted_ok", inserted);
        return inserted ? ActionResult.SUCCESS : ActionResult.failed("inventory_full");
    }

    public static ActionResult dropSlot(AIPlayerEntity player, int slot, boolean wholeStack) {
        var inventory = player.getInventory();
        if (slot < 0 || slot >= inventory.getContainerSize()) {
            return ActionResult.failed("slot_out_of_range");
        }
        ItemStack removed = wholeStack ? inventory.removeItemNoUpdate(slot) : inventory.removeItem(slot, 1);
        if (removed.isEmpty()) {
            return ActionResult.failed("empty_slot");
        }
        player.drop(removed, false, true);
        BotLog.action(player, "drop", "slot", slot, "whole_stack", wholeStack);
        return ActionResult.SUCCESS;
    }

    // P0 背包满自救:丢低值占位方块(圆石/泥土/砂砾族),每种保留 keepEach 个(搭路垫脚仍够用)。
    // 挖矿挖到背包满="破了块捡不起→计数不涨→白挖到超时"(挖掘类任务的隐形杀手)。
    private static final net.minecraft.world.item.Item[] JUNK_ITEMS = {
            net.minecraft.world.item.Items.COBBLESTONE, net.minecraft.world.item.Items.COBBLED_DEEPSLATE,
            net.minecraft.world.item.Items.DIRT, net.minecraft.world.item.Items.GRAVEL, net.minecraft.world.item.Items.SAND,
            net.minecraft.world.item.Items.DIORITE, net.minecraft.world.item.Items.ANDESITE,
            net.minecraft.world.item.Items.GRANITE, net.minecraft.world.item.Items.TUFF};

    public static boolean dropJunk(AIPlayerEntity player, int keepEach) {
        boolean droppedAny = false;
        for (net.minecraft.world.item.Item junk : JUNK_ITEMS) {
            int have = countItem(player, junk);
            if (have > keepEach && removeItems(player, junk, have - keepEach)) {
                // 必须按最大堆叠分堆扔:单个 ItemStack/ItemEntity 的 count 上限 99,
                // 一次性扔 2232 个 → 存档 ItemStack.toNbt 抛 "range [1;99]" → server 崩(实测 geo_flow 崩服根因)。
                int toDrop = have - keepEach;
                int max = Math.max(1, new ItemStack(junk).getMaxStackSize());
                while (toDrop > 0) {
                    int chunk = Math.min(toDrop, max);
                    player.drop(new ItemStack(junk, chunk), false, true);
                    toDrop -= chunk;
                }
                BotLog.action(player, "drop_junk", "item", junk, "count", have - keepEach);
                droppedAny = true;
            }
        }
        return droppedAny;
    }

    private static void addStack(Map<String, Integer> summary, ItemStack stack) {
        if (stack.isEmpty()) {
            return;
        }
        String key = stack.getItem().toString();
        summary.merge(key, stack.getCount(), Integer::sum);
    }
}
