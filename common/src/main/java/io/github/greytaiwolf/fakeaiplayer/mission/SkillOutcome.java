package io.github.greytaiwolf.fakeaiplayer.mission;

import java.util.Locale;
import java.util.Map;

/** Typed terminal or interrupt result returned by a Skill adapter. */
public record SkillOutcome(
        Status status,
        FailureKind failureKind,
        String reason,
        int progress,
        Map<String, String> evidence
) {
    public SkillOutcome {
        status = status == null ? Status.FATAL_FAILURE : status;
        failureKind = failureKind == null ? FailureKind.INTERNAL : failureKind;
        reason = reason == null ? "" : reason;
        progress = Math.max(0, progress);
        evidence = evidence == null ? Map.of() : Map.copyOf(evidence);
        if (status == Status.SUCCEEDED && failureKind != FailureKind.NONE) {
            throw new IllegalArgumentException("successful_skill_cannot_have_failure_kind");
        }
        if (status != Status.SUCCEEDED && failureKind == FailureKind.NONE) {
            throw new IllegalArgumentException("failed_skill_requires_failure_kind");
        }
    }

    public static SkillOutcome succeeded(int progress, Map<String, String> evidence) {
        return new SkillOutcome(Status.SUCCEEDED, FailureKind.NONE, "", progress, evidence);
    }

    public static SkillOutcome cancelled(String reason, int progress) {
        return new SkillOutcome(Status.CANCELLED, FailureKind.CANCELLED, reason, progress, Map.of());
    }

    public static SkillOutcome preempted(String reason, int progress) {
        return new SkillOutcome(Status.PREEMPTED, FailureKind.SAFETY, reason, progress, Map.of());
    }

    /**
     * Compatibility classifier for legacy Tasks. New Skills should return a FailureKind directly;
     * this method keeps free-form legacy strings out of Mission policy decisions.
     */
    public static SkillOutcome fromLegacyFailure(String reason, int progress) {
        String normalized = reason == null ? "" : reason.trim().toLowerCase(Locale.ROOT);
        FailureKind kind;
        Status status;
        if (containsAny(normalized, "binding_curse", "locked_armor_slot")) {
            kind = FailureKind.RESOURCE_UNAVAILABLE;
            status = Status.BLOCKED;
        } else if (containsAny(normalized, "no_resource", "no_ore", "resource_exhausted")) {
            kind = FailureKind.RESOURCE_UNAVAILABLE;
            status = Status.BLOCKED;
        } else if (containsAny(normalized, "no_reachable", "no_path", "no_stand", "stuck:",
                "dig_down_blocked")) {
            kind = FailureKind.PATH_UNREACHABLE;
            status = Status.RETRYABLE_FAILURE;
        } else if (containsAny(normalized, "timeout", "timed_out")) {
            kind = FailureKind.TIMEOUT;
            status = Status.RETRYABLE_FAILURE;
        } else if (containsAny(normalized, "need_", "missing_", "missing:", "out_of_fuel",
                "inventory_full")) {
            kind = FailureKind.PRECONDITION;
            status = Status.RETRYABLE_FAILURE;
        } else if (containsAny(normalized, "world_changed", "target_changed", "stale", "invalidated")) {
            kind = FailureKind.WORLD_CHANGED;
            status = Status.RETRYABLE_FAILURE;
        } else if (containsAny(normalized, "survival_guard", "drowning", "lava", "threat", "low_hp")) {
            kind = FailureKind.SAFETY;
            status = Status.RETRYABLE_FAILURE;
        } else if (containsAny(normalized, "cancelled", "canceled")) {
            kind = FailureKind.CANCELLED;
            status = Status.CANCELLED;
        } else if (containsAny(normalized, "start_failed", "exception", "internal_error")) {
            kind = FailureKind.INTERNAL;
            status = Status.FATAL_FAILURE;
        } else {
            kind = FailureKind.UNKNOWN;
            status = Status.RETRYABLE_FAILURE;
        }
        return new SkillOutcome(status, kind, normalized.isBlank() ? "legacy_task_failed" : reason,
                progress, Map.of("legacy_reason", normalized));
    }

    public boolean retryable() {
        return status == Status.RETRYABLE_FAILURE || status == Status.PREEMPTED;
    }

    public boolean terminalFailure() {
        return status == Status.BLOCKED || status == Status.FATAL_FAILURE || status == Status.CANCELLED;
    }

    private static boolean containsAny(String value, String... needles) {
        for (String needle : needles) {
            if (value.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    public enum Status {
        SUCCEEDED,
        PREEMPTED,
        RETRYABLE_FAILURE,
        BLOCKED,
        FATAL_FAILURE,
        CANCELLED
    }

    public enum FailureKind {
        NONE,
        PRECONDITION,
        RESOURCE_UNAVAILABLE,
        PATH_UNREACHABLE,
        WORLD_CHANGED,
        SAFETY,
        TIMEOUT,
        CANCELLED,
        INTERNAL,
        UNKNOWN
    }
}
