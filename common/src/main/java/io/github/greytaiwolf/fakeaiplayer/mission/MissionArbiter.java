package io.github.greytaiwolf.fakeaiplayer.mission;

/**
 * Pure single-owner policy for Task/Skill installation. Callers perform the chosen side effect;
 * this class only decides, making every producer obey one auditable priority system.
 */
public final class MissionArbiter {
    private MissionArbiter() {
    }

    /** Converts Goal admission metadata into the same priority band used by Mission Tasks. */
    public static WorkClaim goalClaim(GoalSpec goal, String owner) {
        if (goal == null) {
            throw new IllegalArgumentException("goal_claim_missing");
        }
        WorkKind kind = goal.source() == GoalSpec.Source.PLAYER_COMMAND
                || goal.source() == GoalSpec.Source.PLAYER_CONFIRMED
                ? WorkKind.PLAYER_MISSION : WorkKind.MISSION;
        return new WorkClaim(kind, owner, 600 + goal.priority(), true, goal.source());
    }

    public static Decision decide(WorkClaim current, WorkClaim incoming, boolean persistentPause) {
        if (incoming == null) {
            throw new IllegalArgumentException("incoming_work_claim_missing");
        }
        if (persistentPause && incoming.kind() != WorkKind.SAFETY) {
            return new Decision(Action.REJECT, "persistent_pause_owned");
        }
        if (current == null) {
            return new Decision(Action.START, "slot_idle");
        }
        if (incoming.kind() == WorkKind.SAFETY) {
            if (current.kind() != WorkKind.SAFETY) {
                return new Decision(Action.PREEMPT, "safety_preempts_ordinary_work");
            }
            if (incoming.priority() > current.priority()) {
                return new Decision(Action.PREEMPT, "higher_safety_priority");
            }
            return new Decision(Action.DEFER, current.owner().equals(incoming.owner())
                    ? "duplicate_safety_work" : "safety_work_already_active");
        }
        if (current.kind() == WorkKind.SAFETY) {
            return new Decision(Action.DEFER, "safety_work_active");
        }
        if (incoming.kind() == WorkKind.PLAYER) {
            return new Decision(Action.REPLACE, "explicit_player_replacement");
        }
        if (missionWork(current.kind()) && missionWork(incoming.kind())) {
            if (strongerMissionAdmission(incoming, current)) {
                return new Decision(Action.REPLACE, "stronger_mission_admission");
            }
            return new Decision(Action.DEFER, current.owner().equals(incoming.owner())
                    ? "duplicate_mission_admission" : "mission_admission_not_stronger");
        }
        if (incoming.kind() == WorkKind.PLAYER_MISSION) {
            if (current.kind() == WorkKind.REFLEX || current.kind() == WorkKind.VERIFY) {
                return new Decision(Action.DEFER, "higher_priority_interrupt_work_active");
            }
            return new Decision(Action.REPLACE, "explicit_player_mission_replacement");
        }
        if ((current.kind() == WorkKind.PLAYER || current.kind() == WorkKind.PLAYER_MISSION)
                && incoming.kind() != WorkKind.REFLEX
                && incoming.kind() != WorkKind.VERIFY) {
            return new Decision(Action.DEFER, "explicit_player_work_active");
        }
        if (incoming.priority() > current.priority()) {
            if (current.resumable()
                    && (incoming.kind() == WorkKind.REFLEX || incoming.kind() == WorkKind.VERIFY)) {
                return new Decision(Action.PREEMPT, "higher_priority_resumable_interrupt");
            }
            return new Decision(Action.REPLACE, "higher_priority_work");
        }
        return new Decision(Action.DEFER, current.owner().equals(incoming.owner())
                ? "duplicate_or_overlapping_work" : "higher_or_equal_priority_work_active");
    }

    private static boolean missionWork(WorkKind kind) {
        return kind == WorkKind.MISSION || kind == WorkKind.PLAYER_MISSION;
    }

    /**
     * Mission source authority is the outer admission band; numeric priority orders work only
     * inside the same source. This prevents an AI proposal with priority 100 from cancelling a
     * player command with priority 1, while still allowing a genuinely higher-priority command
     * from the same source to replace its predecessor.
     */
    private static boolean strongerMissionAdmission(WorkClaim incoming, WorkClaim current) {
        int incomingAuthority = sourceAuthority(incoming.missionSource());
        int currentAuthority = sourceAuthority(current.missionSource());
        return incomingAuthority > currentAuthority
                || incomingAuthority == currentAuthority
                && incoming.priority() > current.priority();
    }

    public static int sourceAuthority(GoalSpec.Source source) {
        return switch (source == null ? GoalSpec.Source.LEGACY : source) {
            case PLAYER_CONFIRMED -> 6;
            case PLAYER_COMMAND -> 5;
            case RESTORED -> 4;
            case AI_PROPOSAL -> 3;
            case LEGACY -> 2;
            case AUTONOMOUS -> 1;
        };
    }

    public record WorkClaim(
            WorkKind kind,
            String owner,
            int priority,
            boolean resumable,
            GoalSpec.Source missionSource
    ) {
        /** Compatibility constructor for non-Mission claims and legacy Mission callers. */
        public WorkClaim(WorkKind kind, String owner, int priority, boolean resumable) {
            this(kind, owner, priority, resumable, null);
        }

        public WorkClaim {
            if (kind == null) {
                throw new IllegalArgumentException("work_kind_missing");
            }
            if (owner == null || owner.isBlank()) {
                throw new IllegalArgumentException("work_owner_missing");
            }
            if (priority < 0 || priority > 1_000) {
                throw new IllegalArgumentException("work_priority_out_of_range");
            }
            if (!missionWork(kind) && missionSource != null) {
                throw new IllegalArgumentException("mission_source_requires_mission_work");
            }
            owner = owner.trim();
        }
    }

    public record Decision(Action action, String reason) {
        public Decision {
            if (action == null || reason == null || reason.isBlank()) {
                throw new IllegalArgumentException("arbiter_decision_incomplete");
            }
        }

        public boolean startsIncoming() {
            return action == Action.START || action == Action.PREEMPT || action == Action.REPLACE;
        }
    }

    public enum WorkKind {
        SAFETY,
        REFLEX,
        PLAYER,
        PLAYER_MISSION,
        VERIFY,
        MISSION,
        AI_DIRECT,
        JOB,
        BACKGROUND
    }

    public enum Action {
        START,
        PREEMPT,
        REPLACE,
        DEFER,
        REJECT
    }
}
