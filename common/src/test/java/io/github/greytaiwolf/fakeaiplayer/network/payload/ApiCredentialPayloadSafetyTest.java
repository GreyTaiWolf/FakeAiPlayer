package io.github.greytaiwolf.fakeaiplayer.network.payload;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class ApiCredentialPayloadSafetyTest {
    @Test
    void credentialPacketsRedactTheirStringRepresentation() {
        String apiKey = "sk-never-print-this";
        SetBotAiCredentialC2S setup =
                new SetBotAiCredentialC2S("Bot", UUID.randomUUID(), apiKey);
        RestoreBotAiCredentialC2S restore = new RestoreBotAiCredentialC2S("Bot", apiKey);

        assertFalse(setup.toString().contains(apiKey));
        assertFalse(restore.toString().contains(apiKey));
        assertTrue(setup.toString().contains("<redacted>"));
        assertTrue(restore.toString().contains("<redacted>"));
    }

    @Test
    void credentialPacketsRejectControlsAndOversizedValues() {
        assertThrows(IllegalArgumentException.class,
                () -> new SetBotAiCredentialC2S("Bot", UUID.randomUUID(), "sk-line\nbreak"));
        assertThrows(IllegalArgumentException.class,
                () -> new RestoreBotAiCredentialC2S(
                        "Bot", "x".repeat(PayloadLimits.API_KEY_LENGTH + 1)));
        assertThrows(IllegalArgumentException.class,
                () -> new RestoreBotAiCredentialC2S("Bot", "sk-secret with-space"));
        assertThrows(IllegalArgumentException.class,
                () -> new RestoreBotAiCredentialC2S("Bot", "sk-密钥不应进入HTTP头"));
    }
}
