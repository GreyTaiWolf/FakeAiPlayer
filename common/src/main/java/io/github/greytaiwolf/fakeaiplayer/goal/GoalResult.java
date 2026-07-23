package io.github.greytaiwolf.fakeaiplayer.goal;

import io.github.greytaiwolf.fakeaiplayer.mission.SkillOutcome;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public record GoalResult(
        long sequence,
        UUID missionId,
        Goal goal,
        Status status,
        GoalEvaluation evaluation,
        String reason,
        int startedTick,
        int finishedTick,
        List<SkippedStep> skippedSteps,
        StructureReport structure,
        SkillOutcome terminalOutcome
) {
    public enum Status {
        COMPLETED,
        PARTIAL,
        BLOCKED,
        FAILED,
        CANCELLED
    }

    public record SkippedStep(String step, String reason) {
        public SkippedStep {
            step = step == null ? "" : step;
            reason = reason == null ? "" : reason;
        }
    }

    public GoalResult {
        Objects.requireNonNull(missionId, "missionId");
        Objects.requireNonNull(goal, "goal");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(evaluation, "evaluation");
        reason = reason == null ? "" : reason;
        skippedSteps = skippedSteps == null ? List.of() : List.copyOf(skippedSteps);
    }

    /** Source-compatible constructor for successful and pre-P3 result publishers. */
    public GoalResult(long sequence,
                      UUID missionId,
                      Goal goal,
                      Status status,
                      GoalEvaluation evaluation,
                      String reason,
                      int startedTick,
                      int finishedTick,
                      List<SkippedStep> skippedSteps,
                      StructureReport structure) {
        this(sequence, missionId, goal, status, evaluation, reason, startedTick, finishedTick,
                skippedSteps, structure, null);
    }

    public Optional<SkillOutcome> terminalSkillOutcome() {
        return Optional.ofNullable(terminalOutcome);
    }

    public static Status classify(GoalEvaluation evaluation, boolean cancelled) {
        return classify(evaluation, cancelled, null);
    }

    public static Status classify(GoalEvaluation evaluation,
                                  boolean cancelled,
                                  SkillOutcome terminalOutcome) {
        if (cancelled) {
            return Status.CANCELLED;
        }
        if (evaluation.state() == GoalEvaluation.State.SATISFIED) {
            return Status.COMPLETED;
        }
        if (terminalOutcome != null) {
            return switch (terminalOutcome.status()) {
                case BLOCKED -> Status.BLOCKED;
                case CANCELLED -> Status.CANCELLED;
                case FATAL_FAILURE -> Status.FAILED;
                case SUCCEEDED, PREEMPTED, RETRYABLE_FAILURE ->
                        evaluation.matched() > 0 ? Status.PARTIAL : Status.FAILED;
            };
        }
        return evaluation.matched() > 0 ? Status.PARTIAL : Status.FAILED;
    }
}
