package io.github.greytaiwolf.fakeaiplayer.task;

import io.github.greytaiwolf.fakeaiplayer.entity.AIPlayerEntity;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TaskManagerDetachedCancellationTest {
    @Test
    void detachedCancellationReleasesPrivateStateWithoutInvokingSharedControlCleanup() {
        RecordingTask task = RecordingTask.paused();

        TaskManager.cancelDetachedTask(null, task, "mission_cancelled", true);

        assertEquals(TaskState.CANCELLED, task.state());
        assertEquals("mission_cancelled", task.failureReason());
        assertEquals(1, task.detachedCleanups);
        assertEquals(0, task.sharedControlCleanups);

        TaskManager.cancelDetachedTask(null, task, "duplicate", true);
        assertEquals(1, task.detachedCleanups, "terminal cancellation must be idempotent");
        assertEquals("mission_cancelled", task.failureReason());
    }

    @Test
    void cancellationWithoutAnotherControlOwnerUsesNormalAbortCleanup() {
        RecordingTask task = RecordingTask.paused();

        TaskManager.cancelDetachedTask(null, task, "mission_cancelled", false);

        assertEquals(TaskState.CANCELLED, task.state());
        assertEquals(0, task.detachedCleanups);
        assertEquals(1, task.sharedControlCleanups);
    }

    private static final class RecordingTask extends AbstractTask {
        private int detachedCleanups;
        private int sharedControlCleanups;

        static RecordingTask paused() {
            RecordingTask task = new RecordingTask();
            task.state = TaskState.PAUSED;
            return task;
        }

        @Override
        public String name() {
            return "recording";
        }

        @Override
        public String describe() {
            return "recording";
        }

        @Override
        public double progress() {
            return 0.0D;
        }

        @Override
        protected void onStart(AIPlayerEntity bot) {
        }

        @Override
        protected void onTick(AIPlayerEntity bot) {
        }

        @Override
        protected void onAbort(AIPlayerEntity bot) {
            sharedControlCleanups++;
        }

        @Override
        protected void onDetachedCancel(AIPlayerEntity bot) {
            detachedCleanups++;
        }
    }
}
