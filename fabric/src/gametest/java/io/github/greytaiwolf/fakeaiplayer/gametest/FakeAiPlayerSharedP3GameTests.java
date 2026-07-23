package io.github.greytaiwolf.fakeaiplayer.gametest;

import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

/** Fabric registration wrapper for loader-neutral P3 Mission/Skill scenarios. */
public final class FakeAiPlayerSharedP3GameTests implements FabricGameTest {
    private static final String ARENA = "fakeaiplayer:p0_arena";

    @GameTest(template = ARENA, batch = "fakeaiplayer_shared_p3_resume", timeoutTicks = 80)
    public void reflexResumesExactMission(GameTestHelper helper) {
        SharedP3MissionGameTestScenarios.reflexResumesExactMission(helper);
    }

    @GameTest(template = ARENA, batch = "fakeaiplayer_shared_p3_equip", timeoutTicks = 80)
    public void equipLoadoutUsesAuthoritativeSlots(GameTestHelper helper) {
        SharedP3MissionGameTestScenarios.equipLoadoutUsesAuthoritativeSlots(helper);
    }

    @GameTest(template = ARENA, batch = "fakeaiplayer_shared_p3_transactions", timeoutTicks = 80)
    public void pauseResumeFailuresAreTransactional(GameTestHelper helper) {
        SharedP3MissionGameTestScenarios.pauseResumeFailuresAreTransactional(helper);
    }

    @GameTest(template = ARENA, batch = "fakeaiplayer_shared_p3_tick_failure", timeoutTicks = 80)
    public void taskTickFailureIsContained(GameTestHelper helper) {
        SharedP3MissionGameTestScenarios.taskTickFailureIsContained(helper);
    }
}
