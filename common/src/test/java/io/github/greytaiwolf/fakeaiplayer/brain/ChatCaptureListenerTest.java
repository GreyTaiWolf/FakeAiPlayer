package io.github.greytaiwolf.fakeaiplayer.brain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ChatCaptureListenerTest {
    @Test
    void rejectsCommonBearerCredentialShapesBeforeLoggingOrModelSubmission() {
        assertTrue(ChatCaptureListener.looksLikeCredential("sk-1234567890abcdef"));
        assertTrue(ChatCaptureListener.looksLikeCredential("key=(sk-1234567890abcdef), do not log"));
        assertTrue(ChatCaptureListener.looksLikeCredential("please use Bearer abcdefghijklmnop"));
    }

    @Test
    void allowsOrdinaryConversation() {
        assertFalse(ChatCaptureListener.looksLikeCredential("你好，帮我收集一些木头"));
        assertFalse(ChatCaptureListener.looksLikeCredential("the sky-is-blue today"));
    }
}
