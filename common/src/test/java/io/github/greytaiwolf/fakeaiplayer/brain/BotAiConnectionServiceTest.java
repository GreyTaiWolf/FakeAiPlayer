package io.github.greytaiwolf.fakeaiplayer.brain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class BotAiConnectionServiceTest {
    @Test
    void exposesOnlyBoundedStatusCodes() {
        assertEquals("auth_error", BotAiConnectionService.safeStatusCode(
                new RuntimeException("auth_error: status=401")));
        assertEquals("rate_limited", BotAiConnectionService.safeStatusCode(
                new RuntimeException("rate_limited: status=429")));
        assertEquals("timeout", BotAiConnectionService.safeStatusCode(
                new RuntimeException("api_timeout")));
        assertEquals("provider_unavailable", BotAiConnectionService.safeStatusCode(
                new RuntimeException("server_error: status=503")));
        assertEquals("connection_failed", BotAiConnectionService.safeStatusCode(
                new RuntimeException("sk-secret-must-not-be-returned")));
    }
}
