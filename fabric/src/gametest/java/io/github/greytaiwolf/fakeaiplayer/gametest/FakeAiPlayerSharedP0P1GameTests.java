package io.github.greytaiwolf.fakeaiplayer.gametest;

import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

/** Fabric registration wrapper for loader-neutral P0/P1 world scenarios. */
public final class FakeAiPlayerSharedP0P1GameTests implements FabricGameTest {
    private static final String ARENA = "fakeaiplayer:p0_arena";

    @GameTest(template = ARENA, batch = "fakeaiplayer_shared_bootstrap", timeoutTicks = 20)
    public void loaderBootstrapAndWorldMutation(GameTestHelper helper) {
        SharedP0P1GameTestScenarios.loaderBootstrapAndWorldMutation(helper, "Fabric");
    }

    @GameTest(template = ARENA, batch = "fakeaiplayer_shared_dry_path", timeoutTicks = 40)
    public void dryPathDetoursAroundWater(GameTestHelper helper) {
        SharedP0P1GameTestScenarios.dryPathDetoursAroundWater(helper);
    }

    @GameTest(template = ARENA, batch = "fakeaiplayer_shared_dynamic_wall", timeoutTicks = 300)
    public void activePathReplansAroundDynamicWall(GameTestHelper helper) {
        SharedP0P1GameTestScenarios.activePathReplansAroundDynamicWall(helper);
    }

    @GameTest(template = ARENA, batch = "fakeaiplayer_shared_cardinal_pose", timeoutTicks = 40)
    public void interactionPoseUsesCurrentCardinalSide(GameTestHelper helper) {
        SharedP0P1GameTestScenarios.interactionPoseUsesCurrentCardinalSide(helper);
    }

    @GameTest(template = ARENA, batch = "fakeaiplayer_shared_five_block_pose", timeoutTicks = 40)
    public void interactionPoseKeepsFiveBlockApproachShort(GameTestHelper helper) {
        SharedP0P1GameTestScenarios.interactionPoseKeepsFiveBlockApproachShort(helper);
    }

    @GameTest(template = ARENA, batch = "fakeaiplayer_shared_blocked_pose", timeoutTicks = 40)
    public void interactionPoseFallsBackWhenNearSideBlocked(GameTestHelper helper) {
        SharedP0P1GameTestScenarios.interactionPoseFallsBackWhenNearSideBlocked(helper);
    }

    @GameTest(template = ARENA, batch = "fakeaiplayer_shared_structure_guard", timeoutTicks = 40)
    public void placedLogStructureIsNotNaturalTree(GameTestHelper helper) {
        SharedP0P1GameTestScenarios.placedLogStructureIsNotNaturalTree(helper);
    }

    @GameTest(template = ARENA, batch = "fakeaiplayer_shared_attached_beam_guard", timeoutTicks = 40)
    public void naturalTreeWithAttachedLowBeamIsRejected(GameTestHelper helper) {
        SharedP0P1GameTestScenarios.naturalTreeWithAttachedLowBeamIsRejected(helper);
    }

    @GameTest(template = ARENA, batch = "fakeaiplayer_shared_tree_topology", timeoutTicks = 40)
    public void twoByTwoTreeWithBranchesRespectsExactSpecies(GameTestHelper helper) {
        SharedP0P1GameTestScenarios.twoByTwoTreeWithBranchesRespectsExactSpecies(helper);
    }

    @GameTest(template = ARENA, batch = "fakeaiplayer_shared_forced_l", timeoutTicks = 420)
    public void navigationExecutesForcedLCorner(GameTestHelper helper) {
        SharedP0P1GameTestScenarios.navigationExecutesForcedLCorner(helper);
    }

    @GameTest(template = ARENA, batch = "fakeaiplayer_shared_tree_commit", timeoutTicks = 760)
    public void gatherQuotaFinishesCommittedTree(GameTestHelper helper) {
        SharedP0P1GameTestScenarios.gatherQuotaFinishesCommittedTree(helper);
    }
}
