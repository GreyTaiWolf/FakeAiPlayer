package io.github.greytaiwolf.fakeaiplayer.brain.chat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BotChatPolicyTest {
    @Test
    void sanitizesControlFormattingWhitespaceAndLegacyFormattingMarker() {
        String value = "  你好\n\t世界\u0000\u202e\u00a7a  \ud83d\ude00 ";

        assertEquals("你好 世界 a \ud83d\ude00", BotChatPolicy.sanitize(value, 100));
    }

    @Test
    void truncationIsCodePointSafeAndUsesEllipsis() {
        assertEquals("甲\u2026", BotChatPolicy.truncate("甲\ud83d\ude00乙", 2));
        assertEquals("甲\ud83d\ude00", BotChatPolicy.sanitize("甲\ud83d\ude00乙", 2));
    }

    @Test
    void botNameCannotForgeTheOwnerChatEnvelope() {
        assertEquals("Helper___fake", BotChatPolicy.sanitizeBotName("Helper> <fake"));
    }

    @Test
    void botAndAssistantRolesGoToOwnerButUserDoesNotEcho() {
        BotChatPolicy.PreparedMessage bot = BotChatPolicy.prepare("bot", "你好");
        BotChatPolicy.PreparedMessage assistant = BotChatPolicy.prepare("ASSISTANT", "在这里");
        BotChatPolicy.PreparedMessage user = BotChatPolicy.prepare("user", "你好 Bot");

        assertTrue(bot.ownerVisible());
        assertEquals("bot", bot.panelRole());
        assertTrue(assistant.ownerVisible());
        assertEquals("bot", assistant.panelRole());
        assertFalse(user.ownerVisible());
        assertEquals("user", user.panelRole());
    }

    @Test
    void systemMessagesAreShortForOwnerButRemainDetailedInPanel() {
        String longText = "状".repeat(BotChatPolicy.OWNER_SYSTEM_TEXT_LIMIT + 40);
        BotChatPolicy.PreparedMessage prepared = BotChatPolicy.prepare("system", longText);

        assertEquals(longText, prepared.panelText());
        assertEquals(BotChatPolicy.OWNER_SYSTEM_TEXT_LIMIT,
                prepared.ownerText().codePointCount(0, prepared.ownerText().length()));
        assertTrue(prepared.ownerText().endsWith("\u2026"));
        assertTrue(prepared.ownerVisible());
    }

    @Test
    void unknownAndEmptyOutputStayOffOwnerChat() {
        BotChatPolicy.PreparedMessage unknown = BotChatPolicy.prepare("debug", "detail");
        BotChatPolicy.PreparedMessage empty = BotChatPolicy.prepare("bot", "\u0000\n\t");

        assertEquals("system", unknown.panelRole());
        assertFalse(unknown.ownerVisible());
        assertTrue(empty.empty());
    }
}
