package io.github.greytaiwolf.fakeaiplayer.task;

import io.github.greytaiwolf.fakeaiplayer.mission.MissionArbiter;

/** Observable result of the single Task installation gate. */
public record TaskAssignmentResult(
        boolean started,
        MissionArbiter.Action action,
        String reason
) {
    public TaskAssignmentResult {
        if (action == null || reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("task_assignment_result_incomplete");
        }
        if (started != (action == MissionArbiter.Action.START
                || action == MissionArbiter.Action.PREEMPT
                || action == MissionArbiter.Action.REPLACE)) {
            throw new IllegalArgumentException("task_assignment_result_action_mismatch");
        }
    }
}
