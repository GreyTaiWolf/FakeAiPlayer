package io.github.greytaiwolf.fakeaiplayer.inventory;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class BotInventoryMenuLayoutTest {
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
