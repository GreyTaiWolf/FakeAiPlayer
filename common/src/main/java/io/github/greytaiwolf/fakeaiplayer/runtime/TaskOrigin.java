package io.github.greytaiwolf.fakeaiplayer.runtime;

import io.github.greytaiwolf.fakeaiplayer.mission.GoalSpec;
import java.util.UUID;

public record TaskOrigin(
        Kind kind,
        UUID missionId,
        UUID jobId,
        String reason,
        GoalSpec.Source missionSource,
        Integer goalPriority,
        String missionDimension
) {
    public enum Kind {
        MISSION,
        PLAYER_COMMAND,
        PLAYER_PANEL,
        LLM_TOOL,
        LLM_SKILL,
        JOB,
        SAFETY,
        REFLEX,
        SYSTEM_BACKGROUND,
        VERIFY
    }

    public TaskOrigin {
        kind = kind == null ? Kind.SYSTEM_BACKGROUND : kind;
        reason = reason == null ? "" : reason;
        missionDimension = missionDimension == null ? null : missionDimension.trim();
        if (missionDimension != null && missionDimension.isEmpty()) {
            throw new IllegalArgumentException("goal_dimension_missing");
        }
        if (kind != Kind.MISSION
                && (missionSource != null || goalPriority != null || missionDimension != null)) {
            throw new IllegalArgumentException("goal_metadata_requires_mission_origin");
        }
        if (goalPriority != null && (goalPriority < 0 || goalPriority > 100)) {
            throw new IllegalArgumentException("goal_priority_out_of_range");
        }
        if (kind == Kind.MISSION && (missionSource != null || goalPriority != null)) {
            missionSource = missionSource == null ? GoalSpec.Source.LEGACY : missionSource;
            goalPriority = goalPriority == null
                    ? GoalSpec.defaultPriority(missionSource) : goalPriority;
        }
    }

    /** Source-compatible constructor for pre-P3 callers without Goal admission metadata. */
    public TaskOrigin(Kind kind, UUID missionId, UUID jobId, String reason) {
        this(kind, missionId, jobId, reason, null, null, null);
    }

    /** Source-compatible constructor for P3 admission callers created before dimension binding. */
    public TaskOrigin(Kind kind,
                      UUID missionId,
                      UUID jobId,
                      String reason,
                      GoalSpec.Source missionSource,
                      Integer goalPriority) {
        this(kind, missionId, jobId, reason, missionSource, goalPriority, null);
    }

    public boolean safety() {
        return kind == Kind.SAFETY;
    }

    public static TaskOrigin of(Kind kind, String reason) {
        return new TaskOrigin(kind, null, null, reason);
    }

    public static TaskOrigin mission(UUID missionId, String reason) {
        return new TaskOrigin(Kind.MISSION, missionId, null, reason);
    }

    public static TaskOrigin mission(UUID missionId,
                                     String reason,
                                     GoalSpec.Source source,
                                     int goalPriority) {
        return new TaskOrigin(
                Kind.MISSION, missionId, null, reason, source, goalPriority, null);
    }

    public static TaskOrigin mission(UUID missionId, String reason, GoalSpec goal) {
        if (goal == null) {
            throw new IllegalArgumentException("goal_spec_missing");
        }
        return new TaskOrigin(
                Kind.MISSION,
                missionId,
                null,
                reason,
                goal.source(),
                goal.priority(),
                goal.dimension());
    }

    public boolean playerMission() {
        return kind == Kind.MISSION
                && (missionSource == GoalSpec.Source.PLAYER_COMMAND
                || missionSource == GoalSpec.Source.PLAYER_CONFIRMED);
    }

    /** Returns a typed runtime-gate reason when this Mission no longer owns the current world. */
    public String dimensionFailure(String actualDimension) {
        if (kind != Kind.MISSION || missionDimension == null) {
            return null;
        }
        String actual = actualDimension == null ? "" : actualDimension.trim();
        if (missionDimension.equals(actual)) {
            return null;
        }
        return "mission_bound_dimension_changed: expected=" + missionDimension
                + " actual=" + (actual.isEmpty() ? "unknown" : actual);
    }

    public static TaskOrigin job(UUID jobId, String reason) {
        return new TaskOrigin(Kind.JOB, null, jobId, reason);
    }

    public static TaskOrigin safety(String reason) {
        return of(Kind.SAFETY, reason);
    }
}
