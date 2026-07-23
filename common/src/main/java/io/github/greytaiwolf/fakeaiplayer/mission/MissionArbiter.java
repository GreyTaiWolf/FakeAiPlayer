package io.github.greytaiwolf.fakeaiplayer.mission;

/**
 * Pure single-owner policy for Task/Skill installation. Callers perform the chosen side effect;
 * this class only decides, making every producer obey one auditable priority system.
 */
public final class MissionArbiter {
    private MissionArbiter() {
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

    public record WorkClaim(WorkKind kind, String owner, int priority, boolean resumable) {
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
