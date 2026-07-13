package io.github.greytaiwolf.fakeaiplayer.client.screen.ui.cards;

import io.github.greytaiwolf.fakeaiplayer.client.screen.ui.Theme;
import io.github.greytaiwolf.fakeaiplayer.network.payload.BotSnapshotS2C;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

public final class InventoryCard extends PanelCard {
    private static final int SLOT = 20;
    private static final int ICON = 16;
    private static final int COLS = 6;

    @Override
    protected String titleKey() {
        return "card.fakeaiplayer.inventory";
    }

    @Override
    protected int bodyHeight() {
        return 66;
    }

    @Override
    protected void renderBody(GuiGraphics context, int mouseX, int mouseY, float delta, Font renderer, int bx, int by, int bw, int bh) {
        if (snapshot == null) {
            context.drawString(renderer, Theme.tr("status.fakeaiplayer.waiting"), bx, by, Theme.TEXT_DIM);
            return;
        }
        if (snapshot.inventory().isEmpty()) {
            context.drawString(renderer, Theme.tr("inventory.fakeaiplayer.empty"), bx, by, Theme.TEXT_DIM);
            return;
        }
        int visible = Math.min(snapshot.inventory().size(), COLS * 2);
        for (int index = 0; index < visible; index++) {
            BotSnapshotS2C.ItemEntry entry = snapshot.inventory().get(index);
            int gx = bx + (index % COLS) * SLOT;
            int gy = by + (index / COLS) * SLOT;
            context.fill(gx, gy, gx + 18, gy + 18, Theme.TRACK);
            context.hLine(gx, gx + 17, gy, Theme.BORDER);
            context.hLine(gx, gx + 17, gy + 17, Theme.BORDER);
            context.vLine(gx, gy, gy + 17, Theme.BORDER);
            context.vLine(gx + 17, gy, gy + 17, Theme.BORDER);
            ItemStack stack = stack(entry);
            context.renderItem(stack, gx + 1, gy + 1);
            context.renderItemDecorations(renderer, stack, gx + 1, gy + 1);
        }
        int hidden = snapshot.inventory().size() - visible;
        if (hidden > 0) {
            String more = "+" + hidden;
            context.drawString(renderer, more, bx + bw - renderer.width(more), by + SLOT + 5, Theme.TEXT_DIM);
        }
    }

    private static ItemStack stack(BotSnapshotS2C.ItemEntry entry) {
        Item item = BuiltInRegistries.ITEM.getOptional(ResourceLocation.parse(entry.itemId())).orElse(net.minecraft.world.item.Items.BARRIER);
        return new ItemStack(item, entry.count());
    }
}
