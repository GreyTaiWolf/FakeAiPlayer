package io.github.greytaiwolf.fakeaiplayer.task;

import io.github.greytaiwolf.fakeaiplayer.entity.AIPlayerEntity;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TaskManagerStartFailureTransactionTest {
    @Test
    void restoredPreemptionSkipsFallibleIncomingTaskDiagnostics() {
        List<String> order = new ArrayList<>();
        RuntimeException startFailure = new IllegalStateException("start_failed");

        TaskManager.FailedStartResolution resolution = TaskManager.settleFailedStart(
                () -> {
                    order.add("rollback");
                    return true;
                },
                () -> {
                    order.add("diagnostics");
                    throw new IllegalStateException("describe_failed");
                },
                startFailure);

        assertTrue(resolution.restoredPreemptedWork());
        assertNull(resolution.failedStatus());
        assertEquals(List.of("rollback"), order,
                "The displaced owner must be restored before reading the broken incoming Task");
        assertEquals(0, startFailure.getSuppressed().length);
    }

    @Test
    void idleStartFailureCapturesStatusOnlyAfterRollbackDecision() {
        List<String> order = new ArrayList<>();
        RuntimeException startFailure = new IllegalStateException("start_failed");
        TaskStatus failed = new TaskStatus(
                "incoming", "broken", TaskState.FAILED, 0.0D, "start_failed", 0);

        TaskManager.FailedStartResolution resolution = TaskManager.settleFailedStart(
                () -> {
                    order.add("rollback");
                    return false;
                },
                () -> {
                    order.add("diagnostics");
                    return failed;
                },
                startFailure);

        assertFalse(resolution.restoredPreemptedWork());
        assertTrue(failed == resolution.failedStatus());
        assertEquals(List.of("rollback", "diagnostics"), order);
    }

    @Test
    void rollbackAndDiagnosticFailuresRemainSuppressedOnTheOriginalStartFailure() {
        RuntimeException startFailure = new IllegalStateException("start_failed");
        RuntimeException rollbackFailure = new IllegalStateException("resume_failed");
        RuntimeException diagnosticFailure = new IllegalStateException("describe_failed");

        TaskManager.FailedStartResolution resolution = TaskManager.settleFailedStart(
                () -> {
                    throw rollbackFailure;
                },
                () -> {
                    throw diagnosticFailure;
                },
                startFailure);

        assertFalse(resolution.restoredPreemptedWork());
        assertNull(resolution.failedStatus());
        assertEquals(List.of(rollbackFailure, diagnosticFailure),
                List.of(startFailure.getSuppressed()));
    }

    @Test
    void committedAssignmentSurvivesBrokenTaskStatusAndReporterDiagnostics() {
        TaskManager.AssignmentDiagnostics diagnostics = TaskManager.captureCommittedStatus(
                new ThrowingDescriptionTask(), "stable_name");

        assertEquals("stable_name", diagnostics.status().name());
        assertEquals(TaskState.RUNNING, diagnostics.status().state());
        assertTrue(diagnostics.status().description().contains("diagnostics unavailable"));
        assertEquals("describe_failed", diagnostics.failure().getMessage());

        RuntimeException reporterFailure = new IllegalStateException("reporter_failed");
        RuntimeException combined = TaskManager.isolatePostStartDiagnostic(
                diagnostics.failure(),
                () -> {
                    throw reporterFailure;
                });

        assertTrue(diagnostics.failure() == combined);
        assertEquals(List.of(reporterFailure), List.of(combined.getSuppressed()));
    }

    private static final class ThrowingDescriptionTask implements Task {
        @Override
        public String name() {
            return "unstable_name";
        }

        @Override
        public String describe() {
            throw new IllegalStateException("describe_failed");
        }

        @Override
        public TaskState state() {
            return TaskState.RUNNING;
        }

        @Override
        public String failureReason() {
            return "";
        }

        @Override
        public void start(AIPlayerEntity bot) {
        }

        @Override
        public void tick(AIPlayerEntity bot) {
        }

        @Override
        public void pause(AIPlayerEntity bot) {
        }

        @Override
        public void resume(AIPlayerEntity bot) {
        }

        @Override
        public void abort(AIPlayerEntity bot) {
        }

        @Override
        public void cancel(AIPlayerEntity bot, String reason) {
        }

        @Override
        public void cancelDetached(AIPlayerEntity bot, String reason) {
        }

        @Override
        public double progress() {
            return 0.0D;
        }

        @Override
        public int elapsedTicks() {
            return 0;
        }
    }
}
