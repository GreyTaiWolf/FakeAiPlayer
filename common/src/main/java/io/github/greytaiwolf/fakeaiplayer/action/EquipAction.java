package io.github.greytaiwolf.fakeaiplayer.action;

import io.github.greytaiwolf.fakeaiplayer.entity.AIPlayerEntity;
import io.github.greytaiwolf.fakeaiplayer.inventory.BotInventorySessionManager;
import io.github.greytaiwolf.fakeaiplayer.log.BotLog;
import java.util.EnumMap;
import java.util.Map;
import java.util.OptionalInt;
import net.minecraft.core.Holder;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.EnchantmentEffectComponents;
import net.minecraft.world.item.enchantment.EnchantmentHelper;

public final class EquipAction {
    private static final EquipmentSlot[] ARMOR_SLOTS = {
            EquipmentSlot.HEAD,
            EquipmentSlot.CHEST,
            EquipmentSlot.LEGS,
            EquipmentSlot.FEET
    };

    private EquipAction() {
    }

    public static int equipBestArmor(AIPlayerEntity bot) {
        return equipBestArmor(bot, "explicit_request");
    }

    public static int equipBestArmor(AIPlayerEntity bot, String reason) {
        if (BotInventorySessionManager.INSTANCE.isOpen(bot)) {
            BotLog.action(bot, "equip_armor_blocked", "reason", "inventory_session");
            return 0;
        }
        Inventory inventory = bot.getInventory();
        Map<EquipmentSlot, Candidate> best = new EnumMap<>(EquipmentSlot.class);
        for (int slot = 0; slot < inventory.items.size(); slot++) {
            ItemStack stack = inventory.items.get(slot);
            if (stack.isEmpty()) {
                continue;
            }
            if (stack.isDamageableItem() && stack.getDamageValue() >= stack.getMaxDamage() - 1) {
                continue;
            }
            EquipmentSlot equipmentSlot = bot.getEquipmentSlotForItem(stack);
            if (!isArmorSlot(equipmentSlot)) {
                continue;
            }
            if (!canReplaceEquippedArmor(bot, equipmentSlot)) {
                continue;
            }
            double score = armorScore(stack, equipmentSlot);
            if (score <= equippedArmorScore(bot, equipmentSlot)) {
                continue;
            }
            Candidate current = best.get(equipmentSlot);
            if (current == null || score > current.score()) {
                best.put(equipmentSlot, new Candidate(slot, stack.copy(), score));
            }
        }
        int equipped = 0;
        for (Map.Entry<EquipmentSlot, Candidate> entry : best.entrySet()) {
            EquipmentSlot slot = entry.getKey();
            Candidate candidate = entry.getValue();
            ItemStack old = bot.getItemBySlot(slot).copy();
            inventory.items.set(candidate.sourceSlot(), old);
            bot.setItemSlot(slot, candidate.stack());
            inventory.setChanged();
            equipped++;
            BotLog.action(bot, "equip_armor", "slot", slot.getSerializedName(),
                    "item", candidate.stack().getItem(), "score", candidate.score(),
                    "reason", reason == null ? "unknown" : reason);
        }
        return equipped;
    }

    public static OptionalInt equipBestWeapon(AIPlayerEntity bot) {
        if (BotInventorySessionManager.INSTANCE.isOpen(bot)) {
            return OptionalInt.empty();
        }
        OptionalInt slot = bestWeaponSlot(bot);
        slot.ifPresent(value -> InventoryAction.equipFromSlot(bot, value));
        return slot;
    }

    public static OptionalInt bestWeaponSlot(AIPlayerEntity bot) {
        Inventory inventory = bot.getInventory();
        int bestSlot = -1;
        double bestDamage = 1.0D;
        for (int slot = 0; slot < inventory.items.size(); slot++) {
            ItemStack stack = inventory.items.get(slot);
            if (stack.isEmpty()
                    || (stack.isDamageableItem() && stack.getDamageValue() >= stack.getMaxDamage() - 1)) {
                continue;
            }
            double damage = attackDamage(stack);
            if (damage > bestDamage) {
                bestDamage = damage;
                bestSlot = slot;
            }
        }
        return bestSlot < 0 ? OptionalInt.empty() : OptionalInt.of(bestSlot);
    }

    public static OptionalInt bestRangedSlot(AIPlayerEntity bot) {
        if (InventoryAction.countItem(bot, Items.ARROW) <= 0) {
            return OptionalInt.empty();
        }
        Inventory inventory = bot.getInventory();
        for (int slot = 0; slot < inventory.items.size(); slot++) {
            if (inventory.items.get(slot).is(Items.BOW)) {
                return OptionalInt.of(slot);
            }
        }
        return OptionalInt.empty();
    }

    public static boolean equipShieldOffhand(AIPlayerEntity bot) {
        if (BotInventorySessionManager.INSTANCE.isOpen(bot)) {
            return false;
        }
        if (bot.getOffhandItem().is(Items.SHIELD)) {
            return true;
        }
        Inventory inventory = bot.getInventory();
        for (int slot = 0; slot < inventory.items.size(); slot++) {
            ItemStack stack = inventory.items.get(slot);
            if (!stack.is(Items.SHIELD)) {
                continue;
            }
            ItemStack oldOffhand = bot.getOffhandItem().copy();
            bot.setItemSlot(EquipmentSlot.OFFHAND, stack.copy());
            inventory.items.set(slot, oldOffhand);
            inventory.setChanged();
            BotLog.action(bot, "equip_shield_offhand", "source_slot", slot);
            return true;
        }
        return false;
    }

    public static double attackDamage(ItemStack stack) {
        return attributeValue(stack, EquipmentSlot.MAINHAND, Attributes.ATTACK_DAMAGE);
    }

    private static double equippedArmorScore(AIPlayerEntity bot, EquipmentSlot slot) {
        return armorScore(bot.getItemBySlot(slot), slot);
    }

    private static double armorScore(ItemStack stack, EquipmentSlot slot) {
        if (stack.isEmpty()
                || (stack.isDamageableItem() && stack.getDamageValue() >= stack.getMaxDamage() - 1)) {
            return 0.0D;
        }
        double armor = attributeValue(stack, slot, Attributes.ARMOR);
        double toughness = attributeValue(stack, slot, Attributes.ARMOR_TOUGHNESS);
        // Vanilla helmets can have equal armor points across gold/chain/iron. A tiny material
        // tie-break lets a survival loadout replace an equal-defense lower-tier piece while never
        // overriding a genuinely stronger armor/toughness score.
        return armor + toughness * 0.25D + armorMaterialRank(stack) * 0.001D;
    }

    private static int armorMaterialRank(ItemStack stack) {
        var item = stack.getItem();
        if (item == Items.NETHERITE_HELMET || item == Items.NETHERITE_CHESTPLATE
                || item == Items.NETHERITE_LEGGINGS || item == Items.NETHERITE_BOOTS) {
            return 6;
        }
        if (item == Items.DIAMOND_HELMET || item == Items.DIAMOND_CHESTPLATE
                || item == Items.DIAMOND_LEGGINGS || item == Items.DIAMOND_BOOTS) {
            return 5;
        }
        if (item == Items.IRON_HELMET || item == Items.IRON_CHESTPLATE
                || item == Items.IRON_LEGGINGS || item == Items.IRON_BOOTS) {
            return 4;
        }
        if (item == Items.CHAINMAIL_HELMET || item == Items.CHAINMAIL_CHESTPLATE
                || item == Items.CHAINMAIL_LEGGINGS || item == Items.CHAINMAIL_BOOTS
                || item == Items.TURTLE_HELMET) {
            return 3;
        }
        if (item == Items.GOLDEN_HELMET || item == Items.GOLDEN_CHESTPLATE
                || item == Items.GOLDEN_LEGGINGS || item == Items.GOLDEN_BOOTS) {
            return 2;
        }
        if (item == Items.LEATHER_HELMET || item == Items.LEATHER_CHESTPLATE
                || item == Items.LEATHER_LEGGINGS || item == Items.LEATHER_BOOTS) {
            return 1;
        }
        return 0;
    }

    private static double attributeValue(ItemStack stack,
                                         EquipmentSlot slot,
                                         Holder<Attribute> attribute) {
        double[] value = {0.0D};
        stack.forEachModifier(slot, (entry, modifier) -> {
            if (entry.equals(attribute) && modifier.operation() == AttributeModifier.Operation.ADD_VALUE) {
                value[0] += modifier.amount();
            }
        });
        return value[0];
    }

    private static boolean isArmorSlot(EquipmentSlot slot) {
        for (EquipmentSlot armorSlot : ARMOR_SLOTS) {
            if (slot == armorSlot) {
                return true;
            }
        }
        return false;
    }

    /** Mirrors vanilla survival inventory rules for automated armor swaps. */
    public static boolean canReplaceEquippedArmor(AIPlayerEntity bot, EquipmentSlot slot) {
        ItemStack equipped = bot.getItemBySlot(slot);
        return equipped.isEmpty()
                || bot.isCreative()
                || !EnchantmentHelper.has(equipped, EnchantmentEffectComponents.PREVENT_ARMOR_CHANGE);
    }

    private record Candidate(int sourceSlot, ItemStack stack, double score) {
    }
}
