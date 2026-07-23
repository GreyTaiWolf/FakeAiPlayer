package io.github.greytaiwolf.fakeaiplayer.goal;

import io.github.greytaiwolf.fakeaiplayer.mission.SkillOutcome;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GoalResultTest {
    @Test
    void completedRequiresSatisfiedPredicate() {
        GoalEvaluation satisfied = GoalEvaluation.count(4, 4, Map.of(), "");
        GoalEvaluation partial = GoalEvaluation.count(2, 4, Map.of(), "missing");
        GoalEvaluation failed = GoalEvaluation.count(0, 4, Map.of(), "missing");
        GoalEvaluation unknown = GoalEvaluation.unknown("no_binding");

        assertEquals(GoalResult.Status.COMPLETED, GoalResult.classify(satisfied, false));
        assertEquals(GoalResult.Status.PARTIAL, GoalResult.classify(partial, false));
        assertEquals(GoalResult.Status.FAILED, GoalResult.classify(failed, false));
        assertEquals(GoalResult.Status.FAILED, GoalResult.classify(unknown, false));
    }

    @Test
    void cancellationAlwaysWinsOverFactsAndSkippedStepsCannotCreateCompletion() {
        GoalEvaluation satisfied = GoalEvaluation.count(4, 4, Map.of(), "");
        assertEquals(GoalResult.Status.CANCELLED, GoalResult.classify(satisfied, true));

        GoalEvaluation unmet = new GoalEvaluation(GoalEvaluation.State.UNSATISFIED, 3, 4,
                Map.of("skipped", "true"), List.of("missing_final_item"));
        assertEquals(GoalResult.Status.PARTIAL, GoalResult.classify(unmet, false));
    }

    @Test
    void blockedSkillRemainsBlockedAndKeepsTypedEvidence() {
        GoalEvaluation unmet = GoalEvaluation.count(0, 1, Map.of(), "missing_iron");
        SkillOutcome blocked = new SkillOutcome(
                SkillOutcome.Status.BLOCKED,
                SkillOutcome.FailureKind.RESOURCE_UNAVAILABLE,
                "no_ore_found",
                375,
                Map.of("searched_chunks", "12"));

        assertEquals(GoalResult.Status.BLOCKED,
                GoalResult.classify(unmet, false, blocked));

        GoalResult result = new GoalResult(
                7L,
                java.util.UUID.randomUUID(),
                new Goal.HaveItem(net.minecraft.world.item.Items.IRON_INGOT, 1),
                GoalResult.Status.BLOCKED,
                unmet,
                blocked.reason(),
                10,
                20,
                List.of(),
                null,
                blocked);
        assertEquals(blocked, result.terminalSkillOutcome().orElseThrow());
        assertEquals(375, result.terminalSkillOutcome().orElseThrow().progress());
        assertEquals("12", result.terminalSkillOutcome().orElseThrow()
                .evidence().get("searched_chunks"));
    }
}
