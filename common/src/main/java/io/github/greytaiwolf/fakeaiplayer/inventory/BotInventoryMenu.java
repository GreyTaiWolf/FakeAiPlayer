package io.github.greytaiwolf.fakeaiplayer.inventory;

import com.mojang.datafixers.util.Pair;
import io.github.greytaiwolf.fakeaiplayer.entity.AIPlayerEntity;
import io.github.greytaiwolf.fakeaiplayer.platform.PlatformServices;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.DataSlot;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.EnchantmentEffectComponents;
import net.minecraft.world.item.enchantment.EnchantmentHelper;

/**
 * Server-authoritative inventory for one fake player. The client constructor uses a 41-slot
 * placeholder; vanilla container synchronization supplies all real stacks from the server menu.
 */
public final class BotInventoryMenu extends AbstractContainerMenu {
    public static final int BOT_ARMOR_START = 0;
    public static final int BOT_ARMOR_END = 4;
    public static final int BOT_OFFHAND_SLOT = 4;
    public static final int BOT_STORAGE_START = 5;
    public static final int BOT_MAIN_START = 5;
    public static final int BOT_MAIN_END = 32;
    public static final int BOT_HOTBAR_START = 32;
    public static final int BOT_HOTBAR_END = 41;
    public static final int VIEWER_MAIN_START = 41;
    public static final int VIEWER_MAIN_END = 68;
    public static final int VIEWER_HOTBAR_START = 68;
    public static final int VIEWER_HOTBAR_END = 77;

    private static final EquipmentSlot[] ARMOR_SLOTS = {
            EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET
    };
    private static final ResourceLocation[] ARMOR_ICONS = {
            InventoryMenu.EMPTY_ARMOR_SLOT_HELMET,
            InventoryMenu.EMPTY_ARMOR_SLOT_CHESTPLATE,
            InventoryMenu.EMPTY_ARMOR_SLOT_LEGGINGS,
            InventoryMenu.EMPTY_ARMOR_SLOT_BOOTS
    };

    private final Container botInventory;
    private final LivingEntity equipmentOwner;
    private final AIPlayerEntity serverBot;
    private final DataSlot selectedHotbar;

    /** Vanilla menu factory used by the client. */
    public BotInventoryMenu(int containerId, Inventory viewerInventory) {
        this(containerId, viewerInventory, new SimpleContainer(41), viewerInventory.player, null);
    }

    /** Server menu factory. */
    public BotInventoryMenu(int containerId, Inventory viewerInventory, AIPlayerEntity bot) {
        this(containerId, viewerInventory, bot.getInventory(), bot, bot);
    }

    private BotInventoryMenu(int containerId,
                             Inventory viewerInventory,
                             Container botInventory,
                             LivingEntity equipmentOwner,
                             AIPlayerEntity serverBot) {
        super(BotMenuTypes.inventory(), containerId);
        checkContainerSize(botInventory, 41);
        this.botInventory = botInventory;
        this.equipmentOwner = equipmentOwner;
        this.serverBot = serverBot;

        for (int i = 0; i < ARMOR_SLOTS.length; i++) {
            EquipmentSlot equipmentSlot = ARMOR_SLOTS[i];
            addSlot(new BotEquipmentSlot(botInventory, equipmentOwner, equipmentSlot,
                    39 - i, 8 + i * 18, 18, ARMOR_ICONS[i]));
        }
        addSlot(new BotOffhandSlot(botInventory, equipmentOwner, 40, 80, 18));

        addInventoryRows(botInventory, 9, 8, 44);
        addHotbar(botInventory, 0, 8, 102);
        addInventoryRows(viewerInventory, 9, 8, 138);
        addHotbar(viewerInventory, 0, 8, 196);

        if (serverBot == null) {
            this.selectedHotbar = DataSlot.standalone();
        } else {
            this.selectedHotbar = new DataSlot() {
                @Override
                public int get() {
                    return serverBot.getInventory().selected;
                }

                @Override
                public void set(int value) {
                    // Server to client only. The selected hand is not editable through this menu.
                }
            };
        }
        addDataSlot(this.selectedHotbar);
    }

    private void addInventoryRows(Container container, int firstIndex, int x, int y) {
        for (int row = 0; row < 3; row++) {
            for (int column = 0; column < 9; column++) {
                addSlot(new Slot(container, firstIndex + column + row * 9,
                        x + column * 18, y + row * 18));
            }
        }
    }

    private void addHotbar(Container container, int firstIndex, int x, int y) {
        for (int column = 0; column < 9; column++) {
            addSlot(new Slot(container, firstIndex + column, x + column * 18, y));
        }
    }

    public int selectedBotHotbar() {
        return Math.max(0, Math.min(8, selectedHotbar.get()));
    }

    public boolean isFor(AIPlayerEntity bot) {
        return serverBot == bot;
    }

    public AIPlayerEntity serverBot() {
        return serverBot;
    }

    @Override
    public boolean stillValid(Player player) {
        return serverBot == null || BotInventorySessionManager.INSTANCE.isValidViewer(player, serverBot);
    }

    @Override
    public void removed(Player player) {
        super.removed(player);
        if (serverBot != null && !player.level().isClientSide) {
            BotInventorySessionManager.INSTANCE.onMenuClosed(player, serverBot);
        }
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        if (index < 0 || index >= slots.size()) {
            return ItemStack.EMPTY;
        }
        Slot sourceSlot = slots.get(index);
        if (!sourceSlot.hasItem() || !sourceSlot.mayPickup(player)) {
            return ItemStack.EMPTY;
        }

        ItemStack source = sourceSlot.getItem();
        ItemStack original = source.copy();
        boolean moved;
        if (index < BOT_HOTBAR_END) {
            moved = moveItemStackTo(source, VIEWER_MAIN_START, VIEWER_HOTBAR_END, true);
        } else {
            moved = moveViewerStackToBot(source);
        }
        if (!moved) {
            return ItemStack.EMPTY;
        }

        if (source.isEmpty()) {
            sourceSlot.setByPlayer(ItemStack.EMPTY, original);
        } else {
            sourceSlot.setChanged();
        }
        if (source.getCount() == original.getCount()) {
            return ItemStack.EMPTY;
        }
        sourceSlot.onTake(player, source);
        return original;
    }

    private boolean moveViewerStackToBot(ItemStack stack) {
        EquipmentSlot slot = equipmentOwner.getEquipmentSlotForItem(stack);
        if (slot.getType() == EquipmentSlot.Type.HUMANOID_ARMOR) {
            int menuIndex = armorMenuIndex(slot);
            if (menuIndex >= 0 && !slots.get(menuIndex).hasItem()
                    && moveItemStackTo(stack, menuIndex, menuIndex + 1, false)) {
                return true;
            }
        } else if (slot == EquipmentSlot.OFFHAND && !slots.get(BOT_OFFHAND_SLOT).hasItem()
                && moveItemStackTo(stack, BOT_OFFHAND_SLOT, BOT_OFFHAND_SLOT + 1, false)) {
            return true;
        }
        return moveItemStackTo(stack, BOT_STORAGE_START, BOT_HOTBAR_END, false);
    }

    private static int armorMenuIndex(EquipmentSlot slot) {
        return switch (slot) {
            case HEAD -> 0;
            case CHEST -> 1;
            case LEGS -> 2;
            case FEET -> 3;
            default -> -1;
        };
    }

    private static final class BotEquipmentSlot extends Slot {
        private final LivingEntity owner;
        private final EquipmentSlot equipmentSlot;
        private final ResourceLocation emptyIcon;

        private BotEquipmentSlot(Container container,
                                 LivingEntity owner,
                                 EquipmentSlot equipmentSlot,
                                 int inventoryIndex,
                                 int x,
                                 int y,
                                 ResourceLocation emptyIcon) {
            super(container, inventoryIndex, x, y);
            this.owner = owner;
            this.equipmentSlot = equipmentSlot;
            this.emptyIcon = emptyIcon;
        }

        @Override
        public void setByPlayer(ItemStack newStack, ItemStack oldStack) {
            owner.onEquipItem(equipmentSlot, oldStack, newStack);
            super.setByPlayer(newStack, oldStack);
        }

        @Override
        public int getMaxStackSize() {
            return 1;
        }

        @Override
        public boolean mayPlace(ItemStack stack) {
            return PlatformServices.canEquip(stack, equipmentSlot, owner);
        }

        @Override
        public boolean mayPickup(Player player) {
            ItemStack stack = getItem();
            if (!stack.isEmpty() && !player.isCreative()
                    && EnchantmentHelper.has(stack, EnchantmentEffectComponents.PREVENT_ARMOR_CHANGE)) {
                return false;
            }
            return super.mayPickup(player);
        }

        @Override
        public Pair<ResourceLocation, ResourceLocation> getNoItemIcon() {
            return Pair.of(InventoryMenu.BLOCK_ATLAS, emptyIcon);
        }
    }

    private static final class BotOffhandSlot extends Slot {
        private final LivingEntity owner;

        private BotOffhandSlot(Container container, LivingEntity owner, int inventoryIndex, int x, int y) {
            super(container, inventoryIndex, x, y);
            this.owner = owner;
        }

        @Override
        public void setByPlayer(ItemStack newStack, ItemStack oldStack) {
            owner.onEquipItem(EquipmentSlot.OFFHAND, oldStack, newStack);
            super.setByPlayer(newStack, oldStack);
        }

        @Override
        public Pair<ResourceLocation, ResourceLocation> getNoItemIcon() {
            return Pair.of(InventoryMenu.BLOCK_ATLAS, InventoryMenu.EMPTY_ARMOR_SLOT_SHIELD);
        }
    }
}
