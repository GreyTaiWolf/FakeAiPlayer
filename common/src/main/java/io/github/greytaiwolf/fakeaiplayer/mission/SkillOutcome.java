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
        if (!compatible(status, failureKind)) {
            throw new IllegalArgumentException(
                    "incompatible_skill_outcome:" + status + ':' + failureKind);
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
        // Control and adapter-integrity outcomes must win over ordinary words embedded in the
        // same legacy message (for example "cancelled_no_path" or "start_failed_timeout").
        if (containsAny(normalized, "cancelled", "canceled")) {
            kind = FailureKind.CANCELLED;
            status = Status.CANCELLED;
        } else if (containsAny(normalized, "start_failed", "exception", "internal_error")) {
            kind = FailureKind.INTERNAL;
            status = Status.FATAL_FAILURE;
        } else if (containsAny(normalized, "invalid_block_id", "unknown_block_id", "unknown_palette",
                "unsupported_resource_type", "goal_out_of_world")) {
            kind = FailureKind.INTERNAL;
            status = Status.FATAL_FAILURE;
        } else if (containsAny(normalized, "binding_curse", "locked_armor_slot",
                "confirmed_build_dimension_mismatch", "resume_mining_wrong_dimension")) {
            kind = FailureKind.RESOURCE_UNAVAILABLE;
            status = Status.BLOCKED;
        } else if (containsAny(normalized, "no_resource", "no_ore", "resource_exhausted",
                "no_container", "no_base", "no_supply", "no_raw_food", "no_food", "no_lava")) {
            kind = FailureKind.RESOURCE_UNAVAILABLE;
            status = Status.BLOCKED;
        } else if (containsAny(normalized, "need_", "need:", "need ", "missing_", "missing:",
                "missing ", "out_of_fuel", "inventory_full", "inventory_session_blocked")) {
            // Resource identifiers may contain words such as "lava". Missing/need grammar is
            // authoritative and must win before hazard classification.
            kind = FailureKind.PRECONDITION;
            status = Status.RETRYABLE_FAILURE;
        } else if (containsAnySemanticToken(normalized,
                "survival_guard", "drowning", "on_fire", "hazard", "under_attack", "unsafe",
                "threat", "low_hp", "in_lava", "blocked_lava", "lava_escape")) {
            // Do not classify a bare "lava" substring as danger: "no_lava" and item ids such as
            // minecraft:lava_bucket describe resource state, not an active safety incident.
            kind = FailureKind.SAFETY;
            status = Status.RETRYABLE_FAILURE;
        } else if (containsAny(normalized, "no_reachable", "no_path", "no_stand", "stuck:",
                "dig_down_blocked", "no_progress", "not_reachable", "out_of_reach",
                "path_to_", "return_path_failed", "no_place_for")) {
            kind = FailureKind.PATH_UNREACHABLE;
            status = Status.RETRYABLE_FAILURE;
        } else if (containsAny(normalized, "timeout", "timed_out")) {
            kind = FailureKind.TIMEOUT;
            status = Status.RETRYABLE_FAILURE;
        } else if (containsAny(normalized, "world_changed", "target_changed", "tree_changed",
                "furnace_missing", "container_missing", "stale", "invalidated")) {
            kind = FailureKind.WORLD_CHANGED;
            status = Status.RETRYABLE_FAILURE;
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

    private static boolean containsAnySemanticToken(String value, String... tokens) {
        for (String token : tokens) {
            int from = 0;
            while (from <= value.length() - token.length()) {
                int index = value.indexOf(token, from);
                if (index < 0) {
                    break;
                }
                int end = index + token.length();
                boolean leftBoundary = index == 0 || !isSemanticWordCharacter(value.charAt(index - 1));
                boolean rightBoundary = end == value.length()
                        || !isSemanticWordCharacter(value.charAt(end));
                if (leftBoundary && rightBoundary) {
                    return true;
                }
                from = index + 1;
            }
        }
        return false;
    }

    private static boolean isSemanticWordCharacter(char value) {
        return value >= 'a' && value <= 'z' || value >= '0' && value <= '9';
    }

    /**
     * Keeps policy decisions unambiguous: callers may inspect either axis without seeing a
     * contradictory control result. BLOCKED is allowed to retain the ordinary root cause after a
     * retry/recovery budget is exhausted; control and integrity kinds remain exclusive.
     */
    public static boolean compatible(Status status, FailureKind kind) {
        if (status == null || kind == null) {
            return false;
        }
        return switch (status) {
            case SUCCEEDED -> kind == FailureKind.NONE;
            case PREEMPTED -> kind == FailureKind.SAFETY;
            case CANCELLED -> kind == FailureKind.CANCELLED;
            case FATAL_FAILURE -> kind == FailureKind.INTERNAL;
            case RETRYABLE_FAILURE -> kind == FailureKind.PRECONDITION
                    || kind == FailureKind.PATH_UNREACHABLE
                    || kind == FailureKind.WORLD_CHANGED
                    || kind == FailureKind.SAFETY
                    || kind == FailureKind.TIMEOUT
                    || kind == FailureKind.UNKNOWN;
            case BLOCKED -> kind != FailureKind.NONE
                    && kind != FailureKind.CANCELLED
                    && kind != FailureKind.INTERNAL;
        };
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
