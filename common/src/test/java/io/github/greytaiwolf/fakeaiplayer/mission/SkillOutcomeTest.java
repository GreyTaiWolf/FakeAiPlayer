package io.github.greytaiwolf.fakeaiplayer.mission;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkillOutcomeTest {
    @Test
    void classifiesLegacyFailuresIntoPolicySafeKinds() {
        List<LegacyCase> cases = List.of(
                new LegacyCase("no_resource_nearby", SkillOutcome.Status.BLOCKED,
                        SkillOutcome.FailureKind.RESOURCE_UNAVAILABLE, false, true),
                new LegacyCase("binding_curse_locked_armor_slot", SkillOutcome.Status.BLOCKED,
                        SkillOutcome.FailureKind.RESOURCE_UNAVAILABLE, false, true),
                new LegacyCase("no_path_to_target", SkillOutcome.Status.RETRYABLE_FAILURE,
                        SkillOutcome.FailureKind.PATH_UNREACHABLE, true, false),
                new LegacyCase("task_timeout", SkillOutcome.Status.RETRYABLE_FAILURE,
                        SkillOutcome.FailureKind.TIMEOUT, true, false),
                new LegacyCase("missing_tool", SkillOutcome.Status.RETRYABLE_FAILURE,
                        SkillOutcome.FailureKind.PRECONDITION, true, false),
                new LegacyCase("target_changed", SkillOutcome.Status.RETRYABLE_FAILURE,
                        SkillOutcome.FailureKind.WORLD_CHANGED, true, false),
                new LegacyCase("lava_escape", SkillOutcome.Status.RETRYABLE_FAILURE,
                        SkillOutcome.FailureKind.SAFETY, true, false),
                new LegacyCase("cancelled_by_owner", SkillOutcome.Status.CANCELLED,
                        SkillOutcome.FailureKind.CANCELLED, false, true),
                new LegacyCase("internal_error", SkillOutcome.Status.FATAL_FAILURE,
                        SkillOutcome.FailureKind.INTERNAL, false, true),
                new LegacyCase("unclassified_failure", SkillOutcome.Status.RETRYABLE_FAILURE,
                        SkillOutcome.FailureKind.UNKNOWN, true, false));

        for (LegacyCase expected : cases) {
            SkillOutcome outcome = SkillOutcome.fromLegacyFailure(expected.reason(), 7);
            assertEquals(expected.status(), outcome.status(), expected.reason());
            assertEquals(expected.failureKind(), outcome.failureKind(), expected.reason());
            assertEquals(expected.retryable(), outcome.retryable(), expected.reason());
            assertEquals(expected.terminalFailure(), outcome.terminalFailure(), expected.reason());
            assertEquals(7, outcome.progress(), expected.reason());
            assertEquals(expected.reason(), outcome.evidence().get("legacy_reason"), expected.reason());
        }
    }

    @Test
    void blankLegacyFailureGetsStableUnknownFallback() {
        SkillOutcome outcome = SkillOutcome.fromLegacyFailure("  ", -10);

        assertEquals(SkillOutcome.Status.RETRYABLE_FAILURE, outcome.status());
        assertEquals(SkillOutcome.FailureKind.UNKNOWN, outcome.failureKind());
        assertEquals("legacy_task_failed", outcome.reason());
        assertEquals("", outcome.evidence().get("legacy_reason"));
        assertEquals(0, outcome.progress());
        assertTrue(outcome.retryable());
        assertFalse(outcome.terminalFailure());
    }

    private record LegacyCase(
            String reason,
            SkillOutcome.Status status,
            SkillOutcome.FailureKind failureKind,
            boolean retryable,
            boolean terminalFailure
    ) {
    }
}
