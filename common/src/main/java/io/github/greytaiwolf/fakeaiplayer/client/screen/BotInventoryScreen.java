package io.github.greytaiwolf.fakeaiplayer.client.screen;

import io.github.greytaiwolf.fakeaiplayer.inventory.BotInventoryMenu;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;

/** Compact vanilla container screen: fake-player inventory above, viewer inventory below. */
public final class BotInventoryScreen extends AbstractContainerScreen<BotInventoryMenu> {
    private static final int PANEL = 0xFF20252D;
    private static final int PANEL_EDGE = 0xFF596270;
    private static final int SLOT_EDGE = 0xFF11151A;
    private static final int SLOT_FILL = 0xFF343B46;
    private static final int SELECTED = 0xFFE6C45A;

    public BotInventoryScreen(BotInventoryMenu menu, Inventory playerInventory, Component title) {
        super(menu, playerInventory, title);
        imageWidth = 176;
        imageHeight = 220;
        titleLabelX = 8;
        titleLabelY = 6;
        inventoryLabelX = 8;
        inventoryLabelY = 126;
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics, mouseX, mouseY, partialTick);
        super.render(graphics, mouseX, mouseY, partialTick);
        renderTooltip(graphics, mouseX, mouseY);
    }

    @Override
    protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        int left = leftPos;
        int top = topPos;
        graphics.fill(left, top, left + imageWidth, top + imageHeight, PANEL_EDGE);
        graphics.fill(left + 1, top + 1, left + imageWidth - 1, top + imageHeight - 1, PANEL);
        graphics.hLine(left + 7, left + imageWidth - 8, top + 124, PANEL_EDGE);

        int selectedSlot = BotInventoryMenu.BOT_HOTBAR_START + menu.selectedBotHotbar();
        for (int index = 0; index < menu.slots.size(); index++) {
            Slot slot = menu.slots.get(index);
            int color = index == selectedSlot ? SELECTED : SLOT_EDGE;
            graphics.fill(left + slot.x - 1, top + slot.y - 1,
                    left + slot.x + 17, top + slot.y + 17, color);
            graphics.fill(left + slot.x, top + slot.y,
                    left + slot.x + 16, top + slot.y + 16, SLOT_FILL);
        }
    }
}
