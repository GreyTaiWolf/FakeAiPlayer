package io.github.greytaiwolf.fakeaiplayer.task;

import io.github.greytaiwolf.fakeaiplayer.action.EquipAction;
import io.github.greytaiwolf.fakeaiplayer.action.InventoryAction;
import io.github.greytaiwolf.fakeaiplayer.entity.AIPlayerEntity;
import io.github.greytaiwolf.fakeaiplayer.inventory.BotInventorySessionManager;
import java.util.Set;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Equips a survival loadout and verifies the real server equipment slots. Crafting armor into the
 * inventory is deliberately not treated as Goal completion.
 */
public final class EquipLoadoutTask extends AbstractTask {
    private static final int SESSION_WAIT_TICKS = 100;
    private static final Set<Item> HELMETS = Set.of(
            Items.IRON_HELMET, Items.DIAMOND_HELMET, Items.NETHERITE_HELMET);
    private static final Set<Item> CHESTPLATES = Set.of(
            Items.IRON_CHESTPLATE, Items.DIAMOND_CHESTPLATE, Items.NETHERITE_CHESTPLATE);
    private static final Set<Item> LEGGINGS = Set.of(
            Items.IRON_LEGGINGS, Items.DIAMOND_LEGGINGS, Items.NETHERITE_LEGGINGS);
    private static final Set<Item> BOOTS = Set.of(
            Items.IRON_BOOTS, Items.DIAMOND_BOOTS, Items.NETHERITE_BOOTS);
    private static final Set<Item> SWORDS = Set.of(
            Items.IRON_SWORD, Items.DIAMOND_SWORD, Items.NETHERITE_SWORD);

    @Override
    public String name() {
        return "equip_loadout";
    }

    @Override
    public String describe() {
        return "Equipping and verifying armor loadout";
    }

    @Override
    public double progress() {
        return state == TaskState.COMPLETED ? 1.0D : 0.5D;
    }

    @Override
    public boolean isWaiting() {
        return true;
    }

    @Override
    protected void onStart(AIPlayerEntity bot) {
        // Work is performed on tick so an open inventory session can close without losing the
        // Mission step or mutating player-owned inventory state.
    }

    @Override
    protected void onTick(AIPlayerEntity bot) {
        if (ready(bot)) {
            complete();
            return;
        }
        if (BotInventorySessionManager.INSTANCE.isOpen(bot)) {
            if (elapsed >= SESSION_WAIT_TICKS) {
                fail("inventory_session_blocked_equip");
            }
            return;
        }
        equipArmorFromOffhand(bot);
        EquipAction.equipBestArmor(bot, "armor_goal");
        equipRequiredSword(bot);
        if (ready(bot)) {
            complete();
        } else if (bindingCurseBlocksRequiredSlot(bot)) {
            fail("binding_curse_locked_armor_slot");
        } else {
            fail("missing_equipment_after_craft");
        }
    }

    public static boolean ready(AIPlayerEntity bot) {
        return usableInSlot(bot, EquipmentSlot.HEAD, HELMETS)
                && usableInSlot(bot, EquipmentSlot.CHEST, CHESTPLATES)
                && usableInSlot(bot, EquipmentSlot.LEGS, LEGGINGS)
                && usableInSlot(bot, EquipmentSlot.FEET, BOOTS)
                && usableInSlot(bot, EquipmentSlot.MAINHAND, SWORDS);
    }

    private static boolean usableInSlot(AIPlayerEntity bot, EquipmentSlot slot, Set<Item> allowed) {
        ItemStack stack = bot.getItemBySlot(slot);
        return !stack.isEmpty() && allowed.contains(stack.getItem()) && usable(stack);
    }

    private static boolean bindingCurseBlocksRequiredSlot(AIPlayerEntity bot) {
        return lockedAndUnsatisfied(bot, EquipmentSlot.HEAD, HELMETS)
                || lockedAndUnsatisfied(bot, EquipmentSlot.CHEST, CHESTPLATES)
                || lockedAndUnsatisfied(bot, EquipmentSlot.LEGS, LEGGINGS)
                || lockedAndUnsatisfied(bot, EquipmentSlot.FEET, BOOTS);
    }

    private static boolean lockedAndUnsatisfied(AIPlayerEntity bot,
                                                EquipmentSlot slot,
                                                Set<Item> allowed) {
        return !usableInSlot(bot, slot, allowed)
                && !EquipAction.canReplaceEquippedArmor(bot, slot);
    }

    private static void equipArmorFromOffhand(AIPlayerEntity bot) {
        ItemStack offhand = bot.getOffhandItem();
        if (offhand.isEmpty() || !usable(offhand)) {
            return;
        }
        EquipmentSlot target = bot.getEquipmentSlotForItem(offhand);
        Set<Item> allowed = switch (target) {
            case HEAD -> HELMETS;
            case CHEST -> CHESTPLATES;
            case LEGS -> LEGGINGS;
            case FEET -> BOOTS;
            default -> Set.of();
        };
        if (allowed.isEmpty() || usableInSlot(bot, target, allowed)
                || !EquipAction.canReplaceEquippedArmor(bot, target)) {
            return;
        }
        ItemStack old = bot.getItemBySlot(target).copy();
        bot.setItemSlot(target, offhand.copy());
        bot.setItemSlot(EquipmentSlot.OFFHAND, old);
    }

    private static void equipRequiredSword(AIPlayerEntity bot) {
        if (usableInSlot(bot, EquipmentSlot.MAINHAND, SWORDS)) {
            return;
        }
        int bestSlot = -1;
        int bestRank = -1;
        for (int slot = 0; slot < bot.getInventory().items.size(); slot++) {
            ItemStack stack = bot.getInventory().items.get(slot);
            int rank = swordRank(stack);
            if (rank > bestRank) {
                bestRank = rank;
                bestSlot = slot;
            }
        }
        if (bestSlot >= 0) {
            InventoryAction.equipFromSlot(bot, bestSlot);
            return;
        }
        ItemStack offhand = bot.getOffhandItem();
        if (swordRank(offhand) < 0) {
            return;
        }
        ItemStack oldMainHand = bot.getMainHandItem().copy();
        bot.setItemSlot(EquipmentSlot.MAINHAND, offhand.copy());
        bot.setItemSlot(EquipmentSlot.OFFHAND, oldMainHand);
        bot.getInventory().setChanged();
    }

    private static int swordRank(ItemStack stack) {
        if (stack.isEmpty() || !usable(stack)) {
            return -1;
        }
        if (stack.is(Items.NETHERITE_SWORD)) {
            return 3;
        }
        if (stack.is(Items.DIAMOND_SWORD)) {
            return 2;
        }
        if (stack.is(Items.IRON_SWORD)) {
            return 1;
        }
        return -1;
    }

    private static boolean usable(ItemStack stack) {
        return !stack.isDamageableItem() || stack.getDamageValue() < stack.getMaxDamage() - 1;
    }
}
