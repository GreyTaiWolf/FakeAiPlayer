package io.github.greytaiwolf.fakeaiplayer.brain;

import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class BrainCoordinatorControlContinuationTest {
    @Test
    void controlBatchKeepsLeaseOpenForAnyReplacementWork() {
        assertFalse(BrainCoordinator.shouldContinueAfterControl(
                ActionDispatcher.ControlEffect.NONE, true, true, 1, true));
        assertFalse(BrainCoordinator.shouldContinueAfterControl(
                ActionDispatcher.ControlEffect.CANCEL_CURRENT, false, false, 0, false));
        assertFalse(BrainCoordinator.shouldContinueAfterControl(
                ActionDispatcher.ControlEffect.AWAIT_EXTERNAL_CONFIRMATION,
                true, true, 1, true));

        assertTrue(BrainCoordinator.shouldContinueAfterControl(
                ActionDispatcher.ControlEffect.CANCEL_CURRENT, true, false, 0, false));
        assertTrue(BrainCoordinator.shouldContinueAfterControl(
                ActionDispatcher.ControlEffect.CANCEL_CURRENT, false, true, 0, false));
        assertTrue(BrainCoordinator.shouldContinueAfterControl(
                ActionDispatcher.ControlEffect.CANCEL_ALL, false, false, 1, false));
        assertTrue(BrainCoordinator.shouldContinueAfterControl(
                ActionDispatcher.ControlEffect.CANCEL_CURRENT, false, false, 0, true));

        assertTrue(BrainCoordinator.hasRuntimeWork(false, false, 1, false));
        assertTrue(BrainCoordinator.hasRuntimeWork(false, false, 0, true));
        assertFalse(BrainCoordinator.hasRuntimeWork(false, false, 0, false));
    }

    @Test
    void externalConfirmationAddsResultsForEveryDeferredToolCallId() {
        List<ChatToolCall> calls = List.of(
                new ChatToolCall("draft-id", "draft_building", "{}"),
                new ChatToolCall("move-id", "move_to", "{}"),
                new ChatToolCall("say-id", "say", "{}"));

        List<ChatMessage> deferred = ActionDispatcher.deferredToolResults(calls, 1);

        assertEquals(2, deferred.size());
        assertEquals(List.of("move-id", "say-id"), deferred.stream()
                .map(ChatMessage::toolCallId)
                .toList());
        assertTrue(deferred.stream().allMatch(message -> message.role().equals("tool")));
        assertTrue(deferred.stream().allMatch(
                message -> message.content().contains("awaiting_human_confirmation")));
    }
}
