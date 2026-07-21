package io.github.greytaiwolf.fakeaiplayer.gametest;

import io.github.greytaiwolf.fakeaiplayer.FakeAiPlayer;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** NeoForge registration wrapper for the same loader-neutral scenarios used by Fabric. */
@GameTestHolder(FakeAiPlayer.MOD_ID)
@PrefixGameTestTemplate(false)
public final class FakeAiPlayerSharedP0P1GameTests {
    private static final String ARENA = "p0_arena";

    private FakeAiPlayerSharedP0P1GameTests() {
    }

    @GameTest(template = ARENA, batch = "fakeaiplayer_shared_bootstrap", timeoutTicks = 20)
    public static void loaderBootstrapAndWorldMutation(GameTestHelper helper) {
        SharedP0P1GameTestScenarios.loaderBootstrapAndWorldMutation(helper, "NeoForge");
    }

    @GameTest(template = ARENA, batch = "fakeaiplayer_shared_dry_path", timeoutTicks = 40)
    public static void dryPathDetoursAroundWater(GameTestHelper helper) {
        SharedP0P1GameTestScenarios.dryPathDetoursAroundWater(helper);
    }

    @GameTest(template = ARENA, batch = "fakeaiplayer_shared_dynamic_wall", timeoutTicks = 300)
    public static void activePathReplansAroundDynamicWall(GameTestHelper helper) {
        SharedP0P1GameTestScenarios.activePathReplansAroundDynamicWall(helper);
    }

    @GameTest(template = ARENA, batch = "fakeaiplayer_shared_cardinal_pose", timeoutTicks = 40)
    public static void interactionPoseUsesCurrentCardinalSide(GameTestHelper helper) {
        SharedP0P1GameTestScenarios.interactionPoseUsesCurrentCardinalSide(helper);
    }

    @GameTest(template = ARENA, batch = "fakeaiplayer_shared_five_block_pose", timeoutTicks = 40)
    public static void interactionPoseKeepsFiveBlockApproachShort(GameTestHelper helper) {
        SharedP0P1GameTestScenarios.interactionPoseKeepsFiveBlockApproachShort(helper);
    }

    @GameTest(template = ARENA, batch = "fakeaiplayer_shared_blocked_pose", timeoutTicks = 40)
    public static void interactionPoseFallsBackWhenNearSideBlocked(GameTestHelper helper) {
        SharedP0P1GameTestScenarios.interactionPoseFallsBackWhenNearSideBlocked(helper);
    }

    @GameTest(template = ARENA, batch = "fakeaiplayer_shared_structure_guard", timeoutTicks = 40)
    public static void placedLogStructureIsNotNaturalTree(GameTestHelper helper) {
        SharedP0P1GameTestScenarios.placedLogStructureIsNotNaturalTree(helper);
    }

    @GameTest(template = ARENA, batch = "fakeaiplayer_shared_attached_beam_guard", timeoutTicks = 40)
    public static void naturalTreeWithAttachedLowBeamIsRejected(GameTestHelper helper) {
        SharedP0P1GameTestScenarios.naturalTreeWithAttachedLowBeamIsRejected(helper);
    }

    @GameTest(template = ARENA, batch = "fakeaiplayer_shared_tree_topology", timeoutTicks = 40)
    public static void twoByTwoTreeWithBranchesRespectsExactSpecies(GameTestHelper helper) {
        SharedP0P1GameTestScenarios.twoByTwoTreeWithBranchesRespectsExactSpecies(helper);
    }

    @GameTest(template = ARENA, batch = "fakeaiplayer_shared_forced_l", timeoutTicks = 420)
    public static void navigationExecutesForcedLCorner(GameTestHelper helper) {
        SharedP0P1GameTestScenarios.navigationExecutesForcedLCorner(helper);
    }

    @GameTest(template = ARENA, batch = "fakeaiplayer_shared_tree_commit", timeoutTicks = 760)
    public static void gatherQuotaFinishesCommittedTree(GameTestHelper helper) {
        SharedP0P1GameTestScenarios.gatherQuotaFinishesCommittedTree(helper);
    }
}
