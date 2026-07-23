package io.github.greytaiwolf.fakeaiplayer.mission;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
                new LegacyCase("guard_on_fire", SkillOutcome.Status.RETRYABLE_FAILURE,
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

    @Test
    void classifiesActualLegacyTaskReasonsWithoutUnknownRetryLoops() {
        List<LegacyCase> cases = List.of(
                new LegacyCase("need: minecraft:oak_planks x3",
                        SkillOutcome.Status.RETRYABLE_FAILURE,
                        SkillOutcome.FailureKind.PRECONDITION, true, false),
                new LegacyCase("missing minecraft:furnace",
                        SkillOutcome.Status.RETRYABLE_FAILURE,
                        SkillOutcome.FailureKind.PRECONDITION, true, false),
                new LegacyCase("no_base_container",
                        SkillOutcome.Status.BLOCKED,
                        SkillOutcome.FailureKind.RESOURCE_UNAVAILABLE, false, true),
                new LegacyCase("confirmed_build_dimension_mismatch: expected=minecraft:overworld",
                        SkillOutcome.Status.BLOCKED,
                        SkillOutcome.FailureKind.RESOURCE_UNAVAILABLE, false, true),
                new LegacyCase("invalid_block_id: broken:id",
                        SkillOutcome.Status.FATAL_FAILURE,
                        SkillOutcome.FailureKind.INTERNAL, false, true),
                new LegacyCase("path_to_safe_stand_for_place_failed: target_unreachable",
                        SkillOutcome.Status.RETRYABLE_FAILURE,
                        SkillOutcome.FailureKind.PATH_UNREACHABLE, true, false),
                new LegacyCase("ore_dig_no_progress collected=0",
                        SkillOutcome.Status.RETRYABLE_FAILURE,
                        SkillOutcome.FailureKind.PATH_UNREACHABLE, true, false));

        for (LegacyCase expected : cases) {
            SkillOutcome outcome = SkillOutcome.fromLegacyFailure(expected.reason(), 0);
            assertEquals(expected.status(), outcome.status(), expected.reason());
            assertEquals(expected.failureKind(), outcome.failureKind(), expected.reason());
            assertEquals(expected.retryable(), outcome.retryable(), expected.reason());
            assertEquals(expected.terminalFailure(), outcome.terminalFailure(), expected.reason());
        }
    }

    @Test
    void controlAndIntegrityPrefixesWinOverOrdinaryFailureWords() {
        SkillOutcome cancelled = SkillOutcome.fromLegacyFailure("cancelled_no_path", 0);
        SkillOutcome fatal = SkillOutcome.fromLegacyFailure("start_failed_timeout", 0);
        SkillOutcome integrity = SkillOutcome.fromLegacyFailure(
                "invalid_block_id:minecraft:lava", 0);
        SkillOutcome safety = SkillOutcome.fromLegacyFailure("cancelled_due_to_lava", 0);

        assertEquals(SkillOutcome.Status.CANCELLED, cancelled.status());
        assertEquals(SkillOutcome.FailureKind.CANCELLED, cancelled.failureKind());
        assertEquals(SkillOutcome.Status.FATAL_FAILURE, fatal.status());
        assertEquals(SkillOutcome.FailureKind.INTERNAL, fatal.failureKind());
        assertEquals(SkillOutcome.Status.FATAL_FAILURE, integrity.status());
        assertEquals(SkillOutcome.FailureKind.INTERNAL, integrity.failureKind());
        // Explicit cancellation is authoritative even when the cleanup reason mentions lava.
        assertEquals(SkillOutcome.Status.CANCELLED, safety.status());
        assertEquals(SkillOutcome.FailureKind.CANCELLED, safety.failureKind());
    }

    @Test
    void resourceGrammarWinsOverHazardWordsEmbeddedInAbsenceReasonsAndItemIds() {
        SkillOutcome absentLava = SkillOutcome.fromLegacyFailure(
                "create_obsidian_no_lava collected=0", 0);
        SkillOutcome missingLavaBucket = SkillOutcome.fromLegacyFailure(
                "missing minecraft:lava_bucket x1", 0);
        SkillOutcome actualHazard = SkillOutcome.fromLegacyFailure(
                "ore_dig_blocked_lava collected=0", 0);
        SkillOutcome unrelatedWord = SkillOutcome.fromLegacyFailure(
                "lavatory_route_failed", 0);

        assertEquals(SkillOutcome.Status.BLOCKED, absentLava.status());
        assertEquals(SkillOutcome.FailureKind.RESOURCE_UNAVAILABLE, absentLava.failureKind());
        assertEquals(SkillOutcome.Status.RETRYABLE_FAILURE, missingLavaBucket.status());
        assertEquals(SkillOutcome.FailureKind.PRECONDITION, missingLavaBucket.failureKind());
        assertEquals(SkillOutcome.Status.RETRYABLE_FAILURE, actualHazard.status());
        assertEquals(SkillOutcome.FailureKind.SAFETY, actualHazard.failureKind());
        assertEquals(SkillOutcome.FailureKind.UNKNOWN, unrelatedWord.failureKind());
    }

    @Test
    void rejectsEveryContradictoryStatusAndFailureKindCombination() {
        for (SkillOutcome.Status status : SkillOutcome.Status.values()) {
            for (SkillOutcome.FailureKind kind : SkillOutcome.FailureKind.values()) {
                if (SkillOutcome.compatible(status, kind)) {
                    SkillOutcome outcome = new SkillOutcome(status, kind, "test", 0, java.util.Map.of());
                    assertEquals(status, outcome.status());
                    assertEquals(kind, outcome.failureKind());
                } else {
                    assertThrows(IllegalArgumentException.class,
                            () -> new SkillOutcome(status, kind, "test", 0, java.util.Map.of()),
                            status + ":" + kind);
                }
            }
        }
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
