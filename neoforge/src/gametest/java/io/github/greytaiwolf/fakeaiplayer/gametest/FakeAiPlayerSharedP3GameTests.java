package io.github.greytaiwolf.fakeaiplayer.gametest;

import io.github.greytaiwolf.fakeaiplayer.FakeAiPlayer;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** NeoForge registration wrapper for the same P3 scenarios used by Fabric. */
@GameTestHolder(FakeAiPlayer.MOD_ID)
@PrefixGameTestTemplate(false)
public final class FakeAiPlayerSharedP3GameTests {
    private static final String ARENA = "p0_arena";
    private static final String GOLDEN_ARENA = "p3_mission_arena";

    private FakeAiPlayerSharedP3GameTests() {
    }

    @GameTest(template = ARENA, batch = "fakeaiplayer_shared_p3_resume", timeoutTicks = 80)
    public static void reflexResumesExactMission(GameTestHelper helper) {
        SharedP3MissionGameTestScenarios.reflexResumesExactMission(helper);
    }

    @GameTest(template = ARENA, batch = "fakeaiplayer_shared_p3_failed_start", timeoutTicks = 80)
    public static void failedReflexStartDoesNotLatchMissionInterruption(GameTestHelper helper) {
        SharedP3MissionGameTestScenarios.failedReflexStartDoesNotLatchMissionInterruption(helper);
    }

    @GameTest(template = ARENA, batch = "fakeaiplayer_shared_p3_equip", timeoutTicks = 80)
    public static void equipLoadoutUsesAuthoritativeSlots(GameTestHelper helper) {
        SharedP3MissionGameTestScenarios.equipLoadoutUsesAuthoritativeSlots(helper);
    }

    @GameTest(template = ARENA, batch = "fakeaiplayer_shared_p3_transactions", timeoutTicks = 80)
    public static void pauseResumeFailuresAreTransactional(GameTestHelper helper) {
        SharedP3MissionGameTestScenarios.pauseResumeFailuresAreTransactional(helper);
    }

    @GameTest(template = ARENA, batch = "fakeaiplayer_shared_p3_tick_failure", timeoutTicks = 80)
    public static void taskTickFailureIsContained(GameTestHelper helper) {
        SharedP3MissionGameTestScenarios.taskTickFailureIsContained(helper);
    }

    @GameTest(template = GOLDEN_ARENA, batch = "fakeaiplayer_shared_p3_golden_chain", timeoutTicks = 6000)
    public static void goldenSurvivalChainStartsFromEmptyInventory(GameTestHelper helper) {
        SharedP3MissionGameTestScenarios.goldenSurvivalChainStartsFromEmptyInventory(helper);
    }
}
