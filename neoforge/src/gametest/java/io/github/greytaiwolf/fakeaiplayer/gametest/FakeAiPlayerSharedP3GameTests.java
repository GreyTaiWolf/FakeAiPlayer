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

    private FakeAiPlayerSharedP3GameTests() {
    }

    @GameTest(template = ARENA, batch = "fakeaiplayer_shared_p3_resume", timeoutTicks = 80)
    public static void reflexResumesExactMission(GameTestHelper helper) {
        SharedP3MissionGameTestScenarios.reflexResumesExactMission(helper);
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
}
