package io.github.greytaiwolf.fakeaiplayer.gametest;

import io.github.greytaiwolf.fakeaiplayer.FakeAiPlayer;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** NeoForge registration wrapper for the same loader-neutral P2 scenarios used by Fabric. */
@GameTestHolder(FakeAiPlayer.MOD_ID)
@PrefixGameTestTemplate(false)
public final class FakeAiPlayerSharedP2GameTests {
    private static final String ARENA = "p0_arena";

    private FakeAiPlayerSharedP2GameTests() {
    }

    @GameTest(template = ARENA, batch = "fakeaiplayer_shared_p2_goal_variants", timeoutTicks = 80)
    public static void goalVariantsResolveAuthoritativeEndpoints(GameTestHelper helper) {
        SharedP2NavigationGameTestScenarios.goalVariantsResolveAuthoritativeEndpoints(helper);
    }

    @GameTest(template = ARENA, batch = "fakeaiplayer_shared_p2_single_search", timeoutTicks = 80)
    public static void multiGoalSingleSearchChoosesGlobalMinimum(GameTestHelper helper) {
        SharedP2NavigationGameTestScenarios.multiGoalSingleSearchChoosesGlobalMinimum(helper);
    }

    @GameTest(template = ARENA, batch = "fakeaiplayer_shared_p2_pose_frontier", timeoutTicks = 100)
    public static void interactionPoseUsesOneFrontierAndCheapestStand(GameTestHelper helper) {
        SharedP2NavigationGameTestScenarios.interactionPoseUsesOneFrontierAndCheapestStand(helper);
    }

    @GameTest(template = ARENA, batch = "fakeaiplayer_shared_p2_policies", timeoutTicks = 120)
    public static void traversalPoliciesPreservePermissions(GameTestHelper helper) {
        SharedP2NavigationGameTestScenarios.traversalPoliciesPreservePermissions(helper);
    }

    @GameTest(template = ARENA, batch = "fakeaiplayer_shared_p2_limits", timeoutTicks = 100)
    public static void searchLimitIsInconclusiveNotUnreachable(GameTestHelper helper) {
        SharedP2NavigationGameTestScenarios.searchLimitIsInconclusiveNotUnreachable(helper);
    }

    @GameTest(template = ARENA, batch = "fakeaiplayer_shared_p2_cache_revision", timeoutTicks = 100)
    public static void blockWritesInvalidateCachedRoutes(GameTestHelper helper) {
        SharedP2NavigationGameTestScenarios.blockWritesInvalidateCachedRoutes(helper);
    }

    @GameTest(template = ARENA, batch = "fakeaiplayer_shared_p2_handle_lifecycle", timeoutTicks = 120)
    public static void handleLifecycleIsRequestScoped(GameTestHelper helper) {
        SharedP2NavigationGameTestScenarios.handleLifecycleIsRequestScoped(helper);
    }

    @GameTest(template = ARENA, batch = "fakeaiplayer_shared_p2_proven_relay", timeoutTicks = 100)
    public static void relayIsPrefixOfProvenDetour(GameTestHelper helper) {
        SharedP2NavigationGameTestScenarios.relayIsPrefixOfProvenDetour(helper);
    }

    @GameTest(template = ARENA, batch = "fakeaiplayer_shared_p2_alternate_goal", timeoutTicks = 360)
    public static void dynamicObstacleReplansToAlternateResolvedGoal(GameTestHelper helper) {
        SharedP2NavigationGameTestScenarios.dynamicObstacleReplansToAlternateResolvedGoal(helper);
    }

    @GameTest(template = ARENA, batch = "fakeaiplayer_shared_p2_stale_world", timeoutTicks = 160)
    public static void staleInteractionTargetIsClassified(GameTestHelper helper) {
        SharedP2NavigationGameTestScenarios.staleInteractionTargetIsClassified(helper);
    }

    @GameTest(template = ARENA, batch = "fakeaiplayer_shared_p2_follow_ring", timeoutTicks = 420)
    public static void followRingTracksMovingEntity(GameTestHelper helper) {
        SharedP2NavigationGameTestScenarios.followRingTracksMovingEntity(helper);
    }

    @GameTest(template = ARENA, batch = "fakeaiplayer_shared_p2_flee", timeoutTicks = 320)
    public static void fleeGoalReachesSafeBoundedCell(GameTestHelper helper) {
        SharedP2NavigationGameTestScenarios.fleeGoalReachesSafeBoundedCell(helper);
    }

    @GameTest(template = ARENA, batch = "fakeaiplayer_shared_p2_segmented", timeoutTicks = 520)
    public static void longRouteUsesRelaysUnderSameHandle(GameTestHelper helper) {
        SharedP2NavigationGameTestScenarios.longRouteUsesRelaysUnderSameHandle(helper);
    }

    @GameTest(template = ARENA, batch = "fakeaiplayer_shared_p2_legacy_adapter", timeoutTicks = 360)
    public static void legacyActionPackMirrorsHandle(GameTestHelper helper) {
        SharedP2NavigationGameTestScenarios.legacyActionPackMirrorsHandle(helper);
    }
}
