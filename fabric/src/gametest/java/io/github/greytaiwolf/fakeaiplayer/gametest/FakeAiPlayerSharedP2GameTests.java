package io.github.greytaiwolf.fakeaiplayer.gametest;

import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

/** Fabric registration wrapper for loader-neutral P2 navigation scenarios. */
public final class FakeAiPlayerSharedP2GameTests implements FabricGameTest {
    private static final String ARENA = "fakeaiplayer:p0_arena";

    @GameTest(template = ARENA, batch = "fakeaiplayer_shared_p2_goal_variants", timeoutTicks = 80)
    public void goalVariantsResolveAuthoritativeEndpoints(GameTestHelper helper) {
        SharedP2NavigationGameTestScenarios.goalVariantsResolveAuthoritativeEndpoints(helper);
    }

    @GameTest(template = ARENA, batch = "fakeaiplayer_shared_p2_single_search", timeoutTicks = 80)
    public void multiGoalSingleSearchChoosesGlobalMinimum(GameTestHelper helper) {
        SharedP2NavigationGameTestScenarios.multiGoalSingleSearchChoosesGlobalMinimum(helper);
    }

    @GameTest(template = ARENA, batch = "fakeaiplayer_shared_p2_pose_frontier", timeoutTicks = 100)
    public void interactionPoseUsesOneFrontierAndCheapestStand(GameTestHelper helper) {
        SharedP2NavigationGameTestScenarios.interactionPoseUsesOneFrontierAndCheapestStand(helper);
    }

    @GameTest(template = ARENA, batch = "fakeaiplayer_shared_p2_policies", timeoutTicks = 120)
    public void traversalPoliciesPreservePermissions(GameTestHelper helper) {
        SharedP2NavigationGameTestScenarios.traversalPoliciesPreservePermissions(helper);
    }

    @GameTest(template = ARENA, batch = "fakeaiplayer_shared_p2_limits", timeoutTicks = 100)
    public void searchLimitIsInconclusiveNotUnreachable(GameTestHelper helper) {
        SharedP2NavigationGameTestScenarios.searchLimitIsInconclusiveNotUnreachable(helper);
    }

    @GameTest(template = ARENA, batch = "fakeaiplayer_shared_p2_cache_revision", timeoutTicks = 100)
    public void blockWritesInvalidateCachedRoutes(GameTestHelper helper) {
        SharedP2NavigationGameTestScenarios.blockWritesInvalidateCachedRoutes(helper);
    }

    @GameTest(template = ARENA, batch = "fakeaiplayer_shared_p2_handle_lifecycle", timeoutTicks = 120)
    public void handleLifecycleIsRequestScoped(GameTestHelper helper) {
        SharedP2NavigationGameTestScenarios.handleLifecycleIsRequestScoped(helper);
    }

    @GameTest(template = ARENA, batch = "fakeaiplayer_shared_p2_proven_relay", timeoutTicks = 100)
    public void relayIsPrefixOfProvenDetour(GameTestHelper helper) {
        SharedP2NavigationGameTestScenarios.relayIsPrefixOfProvenDetour(helper);
    }

    @GameTest(template = ARENA, batch = "fakeaiplayer_shared_p2_alternate_goal", timeoutTicks = 360)
    public void dynamicObstacleReplansToAlternateResolvedGoal(GameTestHelper helper) {
        SharedP2NavigationGameTestScenarios.dynamicObstacleReplansToAlternateResolvedGoal(helper);
    }

    @GameTest(template = ARENA, batch = "fakeaiplayer_shared_p2_stale_world", timeoutTicks = 160)
    public void staleInteractionTargetIsClassified(GameTestHelper helper) {
        SharedP2NavigationGameTestScenarios.staleInteractionTargetIsClassified(helper);
    }

    @GameTest(template = ARENA, batch = "fakeaiplayer_shared_p2_follow_ring", timeoutTicks = 420)
    public void followRingTracksMovingEntity(GameTestHelper helper) {
        SharedP2NavigationGameTestScenarios.followRingTracksMovingEntity(helper);
    }

    @GameTest(template = ARENA, batch = "fakeaiplayer_shared_p2_flee", timeoutTicks = 320)
    public void fleeGoalReachesSafeBoundedCell(GameTestHelper helper) {
        SharedP2NavigationGameTestScenarios.fleeGoalReachesSafeBoundedCell(helper);
    }

    @GameTest(template = ARENA, batch = "fakeaiplayer_shared_p2_segmented", timeoutTicks = 520)
    public void longRouteUsesRelaysUnderSameHandle(GameTestHelper helper) {
        SharedP2NavigationGameTestScenarios.longRouteUsesRelaysUnderSameHandle(helper);
    }

    @GameTest(template = ARENA, batch = "fakeaiplayer_shared_p2_legacy_adapter", timeoutTicks = 360)
    public void legacyActionPackMirrorsHandle(GameTestHelper helper) {
        SharedP2NavigationGameTestScenarios.legacyActionPackMirrorsHandle(helper);
    }
}
