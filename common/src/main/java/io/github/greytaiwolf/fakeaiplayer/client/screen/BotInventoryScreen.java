package io.github.greytaiwolf.fakeaiplayer.client.screen;

import io.github.greytaiwolf.fakeaiplayer.inventory.BotInventoryMenu;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;

/** Compact vanilla container screen: fake-player inventory above, viewer inventory below. */
public final class BotInventoryScreen extends AbstractContainerScreen<BotInventoryMenu> {
    private static final ResourceLocation BACKGROUND =
            ResourceLocation.withDefaultNamespace("textures/gui/container/generic_54.png");
    private static final ResourceLocation SLOT =
            ResourceLocation.withDefaultNamespace("container/slot");
    private static final ResourceLocation HOTBAR_SELECTION =
            ResourceLocation.withDefaultNamespace("hud/hotbar_selection");

    private static final int TEXTURE_SIZE = 256;
    private static final int BACKGROUND_WIDTH = 176;
    private static final int BACKGROUND_HEIGHT = 222;
    private static final int BOT_PANEL_X = 7;
    private static final int BOT_PANEL_Y = 17;
    private static final int BOT_PANEL_WIDTH = 162;
    private static final int BOT_PANEL_HEIGHT = 109;
    private static final int BLANK_BACKGROUND_SAMPLE_Y = 127;
    private static final int SLOT_BACKGROUND_SIZE = 18;
    private static final int HOTBAR_SELECTION_WIDTH = 24;
    private static final int HOTBAR_SELECTION_HEIGHT = 23;

    public BotInventoryScreen(BotInventoryMenu menu, Inventory playerInventory, Component title) {
        super(menu, playerInventory, title);
        imageWidth = BACKGROUND_WIDTH;
        imageHeight = BACKGROUND_HEIGHT;
        titleLabelX = 8;
        titleLabelY = 6;
        inventoryLabelX = 8;
        inventoryLabelY = 128;
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
        graphics.blit(RenderType::guiTextured, BACKGROUND, left, top, 0.0F, 0.0F,
                imageWidth, imageHeight, TEXTURE_SIZE, TEXTURE_SIZE);

        // The vanilla six-row container supplies the frame and complete viewer-inventory region.
        // Replace only its chest-slot interior with a blank strip sampled from the same texture,
        // then lay out the fake player's non-chest inventory using vanilla slot sprites.
        graphics.blit(RenderType::guiTextured, BACKGROUND,
                left + BOT_PANEL_X, top + BOT_PANEL_Y,
                BOT_PANEL_X, BLANK_BACKGROUND_SAMPLE_Y,
                BOT_PANEL_WIDTH, BOT_PANEL_HEIGHT,
                BOT_PANEL_WIDTH, 1,
                TEXTURE_SIZE, TEXTURE_SIZE);

        for (int index = 0; index < BotInventoryMenu.BOT_HOTBAR_END; index++) {
            Slot slot = menu.slots.get(index);
            graphics.blitSprite(RenderType::guiTextured, SLOT,
                    left + slot.x - 1, top + slot.y - 1,
                    SLOT_BACKGROUND_SIZE, SLOT_BACKGROUND_SIZE);
        }

        Slot selected = menu.slots.get(BotInventoryMenu.BOT_HOTBAR_START + menu.selectedBotHotbar());
        graphics.blitSprite(RenderType::guiTextured, HOTBAR_SELECTION,
                left + selected.x - 4, top + selected.y - 4,
                HOTBAR_SELECTION_WIDTH, HOTBAR_SELECTION_HEIGHT);
    }
}
