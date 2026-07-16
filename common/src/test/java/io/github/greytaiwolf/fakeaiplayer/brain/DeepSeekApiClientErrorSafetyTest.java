package io.github.greytaiwolf.fakeaiplayer.brain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class DeepSeekApiClientErrorSafetyTest {
    @Test
    void httpErrorsExposeOnlyStableCodeAndStatus() {
        assertEquals("auth_error: status=401", DeepSeekApiClient.classifyStatus(401));
        assertEquals("auth_error: status=403", DeepSeekApiClient.classifyStatus(403));
        assertEquals("rate_limited: status=429", DeepSeekApiClient.classifyStatus(429));
        assertEquals("server_error: status=503", DeepSeekApiClient.classifyStatus(503));
        assertEquals("http_error: status=422", DeepSeekApiClient.classifyStatus(422));
    }
}
