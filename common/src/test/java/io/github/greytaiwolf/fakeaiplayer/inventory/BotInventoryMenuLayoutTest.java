package io.github.greytaiwolf.fakeaiplayer.inventory;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class BotInventoryMenuLayoutTest {
    @Test
    void slotsAlignWithTheVanillaGenericContainerBackground() {
        assertEquals(18, BotInventoryMenu.SLOT_SPACING);

        assertEquals(8, BotInventoryMenu.BOT_EQUIPMENT_X);
        assertEquals(18, BotInventoryMenu.BOT_EQUIPMENT_Y);
        assertEquals(BotInventoryMenu.BOT_EQUIPMENT_X + 4 * BotInventoryMenu.SLOT_SPACING,
                BotInventoryMenu.BOT_OFFHAND_X);
        assertEquals(8, BotInventoryMenu.BOT_MAIN_X);
        assertEquals(44, BotInventoryMenu.BOT_MAIN_Y);
        assertEquals(8, BotInventoryMenu.BOT_HOTBAR_X);
        assertEquals(102, BotInventoryMenu.BOT_HOTBAR_Y);

        assertEquals(8, BotInventoryMenu.VIEWER_MAIN_X);
        assertEquals(140, BotInventoryMenu.VIEWER_MAIN_Y);
        assertEquals(8, BotInventoryMenu.VIEWER_HOTBAR_X);
        assertEquals(198, BotInventoryMenu.VIEWER_HOTBAR_Y);
        assertEquals(BotInventoryMenu.VIEWER_MAIN_Y + 3 * BotInventoryMenu.SLOT_SPACING + 4,
                BotInventoryMenu.VIEWER_HOTBAR_Y);
    }

    @Test
    void botAndViewerRangesAreContiguousAndDoNotOverlap() {
        assertEquals(0, BotInventoryMenu.BOT_ARMOR_START);
        assertEquals(BotInventoryMenu.BOT_ARMOR_END, BotInventoryMenu.BOT_OFFHAND_SLOT);
        assertEquals(BotInventoryMenu.BOT_OFFHAND_SLOT + 1, BotInventoryMenu.BOT_STORAGE_START);
        assertEquals(BotInventoryMenu.BOT_STORAGE_START, BotInventoryMenu.BOT_MAIN_START);
        assertEquals(BotInventoryMenu.BOT_MAIN_START + 27, BotInventoryMenu.BOT_MAIN_END);
        assertEquals(BotInventoryMenu.BOT_MAIN_END, BotInventoryMenu.BOT_HOTBAR_START);
        assertEquals(BotInventoryMenu.BOT_HOTBAR_START + 9, BotInventoryMenu.BOT_HOTBAR_END);
        assertEquals(BotInventoryMenu.BOT_HOTBAR_END, BotInventoryMenu.VIEWER_MAIN_START);
        assertEquals(BotInventoryMenu.VIEWER_MAIN_START + 27, BotInventoryMenu.VIEWER_MAIN_END);
        assertEquals(BotInventoryMenu.VIEWER_MAIN_END, BotInventoryMenu.VIEWER_HOTBAR_START);
        assertEquals(BotInventoryMenu.VIEWER_HOTBAR_START + 9, BotInventoryMenu.VIEWER_HOTBAR_END);
        assertEquals(77, BotInventoryMenu.VIEWER_HOTBAR_END);
    }
}
