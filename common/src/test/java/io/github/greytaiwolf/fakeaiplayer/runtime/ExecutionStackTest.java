package io.github.greytaiwolf.fakeaiplayer.runtime;

import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.EnumSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExecutionStackTest {
    @Test
    void nestedSafetyFramesResumeInLifoOrder() {
        ExecutionStack<String> stack = new ExecutionStack<>();
        stack.push("mission", TaskOrigin.mission(UUID.randomUUID(), "mine"));
        stack.push("combat", TaskOrigin.safety("combat"));

        assertEquals("combat", stack.popResumable(false).orElseThrow().work());
        assertEquals("mission", stack.popResumable(false).orElseThrow().work());
        assertTrue(stack.isEmpty());
    }

    @Test
    void userPauseAllowsSafetyUnwindButKeepsMissionPaused() {
        ExecutionStack<String> stack = new ExecutionStack<>();
        stack.push("mission", TaskOrigin.mission(UUID.randomUUID(), "build"));
        stack.push("combat", TaskOrigin.safety("combat"));

        assertEquals("combat", stack.popResumable(true).orElseThrow().work());
        assertTrue(stack.popResumable(true).isEmpty());
        assertEquals(1, stack.size());
        assertEquals("mission", stack.popResumable(false).orElseThrow().work());
    }

    @Test
    void cancellationDrainsEveryFrameTopFirst() {
        ExecutionStack<String> stack = new ExecutionStack<>();
        stack.push("mission", TaskOrigin.of(TaskOrigin.Kind.MISSION, "mission"));
        stack.push("combat", TaskOrigin.safety("combat"));
        stack.push("lava", TaskOrigin.safety("lava"));
        assertEquals(java.util.List.of("lava", "combat", "mission"),
                stack.drain().stream().map(ExecutionStack.Frame::work).toList());
    }

    @Test
    void pauseOwnerIsRetainedOnEachFrame() {
        ExecutionStack<String> stack = new ExecutionStack<>();
        stack.push("mission", TaskOrigin.mission(UUID.randomUUID(), "mine"), PauseOwner.INVENTORY);
        stack.push("combat", TaskOrigin.safety("combat"), PauseOwner.SAFETY);

        assertEquals(PauseOwner.SAFETY, stack.pop().orElseThrow().pauseOwner());
        assertEquals(PauseOwner.INVENTORY, stack.peek().orElseThrow().pauseOwner());
    }

    @Test
    void targetedCancellationRemovesOnlyOneMissionAndPreservesLifoOrder() {
        UUID cancelledMission = UUID.randomUUID();
        UUID preservedMission = UUID.randomUUID();
        ExecutionStack<String> stack = new ExecutionStack<>();
        stack.push("preserved", TaskOrigin.mission(preservedMission, "build"), PauseOwner.SYSTEM);
        stack.push("cancelled", TaskOrigin.mission(cancelledMission, "mine"), PauseOwner.SAFETY);
        stack.push("combat", TaskOrigin.safety("combat"), PauseOwner.SAFETY);

        assertEquals(java.util.List.of("cancelled"), stack.removeMatching(
                frame -> cancelledMission.equals(frame.origin().missionId())).stream()
                .map(ExecutionStack.Frame::work).toList());
        assertTrue(stack.anyMatch(frame -> frame.origin().safety()));
        assertEquals(java.util.List.of("combat", "preserved"),
                stack.drain().stream().map(ExecutionStack.Frame::work).toList());
    }

    @Test
    void finalPersistentLockReleaseResumesRegardlessOfAcquisitionOrder() {
        EnumSet<PauseOwner> inventoryThenUser = EnumSet.of(PauseOwner.INVENTORY, PauseOwner.USER);
        inventoryThenUser.remove(PauseOwner.INVENTORY);
        assertTrue(!PauseOwner.resumeAllowedAfterPersistentRelease(
                PauseOwner.INVENTORY, inventoryThenUser));
        inventoryThenUser.remove(PauseOwner.USER);
        assertTrue(PauseOwner.resumeAllowedAfterPersistentRelease(
                PauseOwner.INVENTORY, inventoryThenUser));

        EnumSet<PauseOwner> userThenInventory = EnumSet.of(PauseOwner.USER, PauseOwner.INVENTORY);
        userThenInventory.remove(PauseOwner.USER);
        assertTrue(!PauseOwner.resumeAllowedAfterPersistentRelease(
                PauseOwner.USER, userThenInventory));
        userThenInventory.remove(PauseOwner.INVENTORY);
        assertTrue(PauseOwner.resumeAllowedAfterPersistentRelease(
                PauseOwner.USER, userThenInventory));
    }
}
