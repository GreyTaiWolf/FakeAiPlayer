package io.github.greytaiwolf.fakeaiplayer.mode;

import io.github.greytaiwolf.fakeaiplayer.goal.StructureVerifier;
import io.github.greytaiwolf.fakeaiplayer.task.BlueprintSchema;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import net.minecraft.core.BlockPos;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Locks down the small, reviewed set of production adapters that may invoke privileged primitives. */
class PrivilegedBoundarySourceTest {
    private static final Path MAIN = Path.of(System.getProperty(
            "fakeaiplayer.mainSourceDir",
            "src/main/java/io/github/greytaiwolf/fakeaiplayer"));
    private static final Pattern DIRECT_TELEPORT = Pattern.compile("\\.teleportTo\\s*\\(");

    @Test
    void directTeleportsStayInsideReviewedAdapters() throws IOException {
        Set<String> expected = Set.of(
                "action/ActionPack.java",
                "manager/AIPlayerManager.java",
                "mode/FakePlayerMotion.java",
                "network/AIBotServerNetworking.java",
                "task/DangerWatcher.java",
                "task/DigDownTask.java",
                "task/GatherQuotaTask.java",
                "task/HuntTask.java",
                "task/NavSafetyNet.java");
        Map<String, String> matches = matchingSources(DIRECT_TELEPORT);
        assertEquals(expected, matches.keySet(),
                "A new direct teleport requires an explicit capability or lifecycle-adapter review");

        for (Map.Entry<String, String> entry : matches.entrySet()) {
            if (entry.getKey().equals("manager/AIPlayerManager.java")
                    || entry.getKey().equals("mode/FakePlayerMotion.java")) {
                continue;
            }
            assertTrue(entry.getValue().contains("CapabilityRuntime"), entry.getKey());
            assertTrue(entry.getValue().contains("EMERGENCY_TELEPORT")
                    || entry.getValue().contains("MANUAL_TELEPORT"), entry.getKey());
        }

        String actionPack = read("action/ActionPack.java");
        int snapMethod = actionPack.indexOf("boolean snapPlayerToNearestStandable");
        int currentStandable = actionPack.indexOf("Standability.isStandable(world, current)", snapMethod);
        int emergencyGate = actionPack.indexOf("CapabilityRuntime.decide", currentStandable);
        assertTrue(snapMethod >= 0 && currentStandable > snapMethod && emergencyGate > currentStandable,
                "ordinary pathfinding from a valid start must run before the emergency-teleport gate");
        assertTrue(actionPack.contains("return startPathTo(goal, false, false, true);"),
                "construction-safe navigation must disable both pillar placement and dig fallback");
        assertTrue(actionPack.contains(
                        "return startPathTo(goal, false, false, false, TraversalPolicy.TASK_WALK_DRY);"),
                "ordinary navigation must be dry and non-mutating by default");
        assertTrue(actionPack.contains("public ActionResult startMutatingPathTo"),
                "world-mutating navigation must remain an explicit reviewed API");
        assertTrue(actionPack.contains("!currentStartStandable\n                && hasActiveActions()"),
                "an invalid-start replacement must not teleport an existing controller");
        assertTrue(occurrences(actionPack, "replacement_path_start_invalid") >= 2,
                "walk-only and explicit mutating navigation must share the atomic start guard");

        String pathExecutor = read("pathfinding/PathExecutor.java");
        assertTrue(pathExecutor.contains("allowPillarOnReplan && hasPlaceableBlock"));
        assertTrue(pathExecutor.contains("canPillar,\n                    allowDigOnReplan"),
                "construction-safe replans must preserve the non-mutating policy");

        String moveTask = read("task/MoveTask.java");
        assertTrue(moveTask.contains("public static MoveTask nonMutating"));
        assertTrue(moveTask.contains("startNonMutatingPathTo(target)"));
        assertTrue(moveTask.indexOf("if (!worldMutationAllowed)")
                        < moveTask.indexOf("digging = true;"),
                "initial, relay and idle fallbacks must fail before entering DigNav");
        String goalPlanner = read("goal/GoalPlanner.java");
        assertTrue(goalPlanner.contains("GoalStep.moveNonMutating(buildStagingPoint"));
        String goalExecutor = read("goal/GoalExecutor.java");
        assertTrue(goalExecutor.contains(
                        "case MOVE_NON_MUTATING -> Optional.of(MoveTask.nonMutating"),
                "confirmed staging must retain its non-mutating policy at task dispatch");
        String gather = read("task/GatherQuotaTask.java");
        assertTrue(gather.contains("getBlockState(tree).is(BlockTags.LOGS)"));
        assertTrue(gather.contains("gather_tree_mutating_approach_refused"),
                "tree gathering must not silently promote a blocked work route into wall digging");
    }

    @Test
    void resourceAndEntityDiscoveryUsesObservableBoundary() throws IOException {
        for (String relative : Set.of(
                "action/HarvestCore.java",
                "brain/ToolRegistry.java",
                "goal/GoalSnapshotCollector.java",
                "log/DiagnosticLogger.java",
                "mining/OreProspector.java",
                "mining/OreScan.java",
                "perception/PerceptionCollector.java",
                "perception/focus/FocusResolver.java",
                "task/ContainerTask.java",
                "task/CraftTask.java",
                "task/DangerWatcher.java",
                "task/FarmTask.java",
                "task/FishTask.java",
                "task/OreDigTask.java",
                "task/RecoverDropsTask.java",
                "task/ResupplyTask.java",
                "task/SiteFinder.java",
                "task/SleepTask.java",
                "task/SmeltTask.java",
                "task/StockpileTask.java",
                "task/StripMineTask.java")) {
            assertTrue(read(relative).contains("ObservableWorldQuery"), relative);
        }

        String prospector = read("mining/OreProspector.java");
        assertTrue(prospector.contains("nearestObservable"));
        assertTrue(prospector.contains("private static BlockPos nearestRaw"));
        assertTrue(prospector.indexOf("ObservableWorldQuery.canObserveBlock(bot, pos)")
                < prospector.indexOf("BlockState state = world.getBlockState(pos)"));

        String oreScan = read("mining/OreScan.java");
        assertFalse(oreScan.contains("veinFrom(World"), "raw world-only vein scans must not be public");
        assertTrue(oreScan.contains("veinFrom(AIPlayerEntity"));
    }

    @Test
    void directMutationFallbacksKeepReachAndVisibilityGuards() throws IOException {
        String build = read("action/BuildAction.java");
        assertTrue(build.contains("target_out_of_reach"));
        assertTrue(build.contains("target_not_visible"));
        assertTrue(build.contains("ObservableWorldQuery.canObserveBlock"));
        assertTrue(build.contains("ObservableWorldQuery.canObserveCell"));
        assertTrue(build.contains("isUnobstructed(placementState, pos, CollisionContext.of(player))"),
                "direct placement fallback must respect entity/world collision");
        assertTrue(build.contains("player.pick(reach, 1.0F, false)"));
        assertTrue(build.contains("hit.getDirection() != face"));
        assertTrue(build.contains("OperatingProfile.STRICT_SURVIVAL"),
                "strict mode must not use direct setBlockState placement fallback");

        String buildTask = read("task/BuildTask.java");
        assertTrue(buildTask.contains("StructureVerifier.verify"));
        assertTrue(buildTask.contains("structure_incomplete"),
                "a best-effort blueprint must not report completion without exact verification");
        assertEquals(3, occurrences(buildTask, "isObservableStandable(bot, candidate)"),
                "general, directional and exterior work-pose scans must use one reviewed boundary");
        assertEquals(1, occurrences(buildTask, "Standability.isStandable"),
                "raw standability must stay inside the observable work-pose adapter");
        assertTrue(buildTask.contains("hasCompletedPlanSupport(candidate.below())"),
                "an occluded work deck exception must be tied to a completed immutable plan cell");
        assertTrue(buildTask.contains("hasUsablePlacementRayFrom"));
        assertTrue(buildTask.contains("ClipContext.Block.OUTLINE"));
        assertTrue(buildTask.contains("hit.getDirection() == face"),
                "work-pose planning must prove the same clicked face used by BuildAction");
        assertTrue(buildTask.contains("expectedPlacementAxis"),
                "pillar/log work poses must preserve the reviewed axis state");
        assertTrue(buildTask.contains("startNonMutatingPathTo"),
                "reviewed construction routes must not add pillars or dig unrelated cells");
        assertTrue(buildTask.contains("prerequisite_not_satisfied"),
                "reviewed dependencies must remain exact runtime preconditions");
        assertTrue(buildTask.contains("reviewed_placement_failed"),
                "reviewed plans must fail closed instead of building unsupported child cells");
        assertTrue(buildTask.contains("foundation_support_not_inspectable"));
        assertTrue(buildTask.contains("support.isFaceSturdy(world, supportPos, Direction.UP)"),
                "confirmed foundations must recheck dry sturdy support at execution time");
        String previewService = read("building/preview/BuildingPreviewService.java");
        assertTrue(previewService.contains("support.isFaceSturdy(world, supportPos, Direction.UP)"),
                "confirmation must reject foundation cells over cliffs or fluids");
        String actionPack = read("action/ActionPack.java");
        assertTrue(actionPack.contains(
                        "result.path(), resolvedGoal, exactGoal, canPillar, allowDigFallback"),
                "construction path policy must be preserved by the executor");
        String pathExecutor = read("pathfinding/PathExecutor.java");
        assertTrue(pathExecutor.contains("allowPillarOnReplan && hasPlaceableBlock"));
        assertTrue(pathExecutor.contains("allowDigOnReplan"),
                "a non-mutating construction path must remain non-mutating after replan");
        String walker = read("action/WalkToController.java");
        assertTrue(walker.contains("hasReachedRequiredColumn(player.blockPosition())"),
                "exact construction paths must not finish from an adjacent horizontal cell");
        assertFalse(StructureVerifier.matches(
                        null,
                        BlockPos.ZERO,
                        new BlueprintSchema.BlockPlacement(0, 0, 0, "invalid id", null)),
                "invalid blueprint IDs must fail closed before touching world state");

        String container = read("task/ContainerTask.java");
        assertTrue(container.contains("getEyePosition().distanceToSqr(containerPos.getCenter()) > REACH_SQUARED"));
        assertTrue(container.contains("ObservableWorldQuery.canObserveBlock(bot, containerPos)"));

        assertTrue(matchingSources(Pattern.compile("setTimeOfDay\\s*\\(")).isEmpty(),
                "sleep tasks must respect the server's vanilla sleep quorum");
    }

    @Test
    void productionSourceSetDoesNotContainVerificationHarness() throws IOException {
        assertFalse(Files.exists(MAIN.resolve("command/AIBotTestSubcommand.java")));
        assertFalse(Files.exists(MAIN.resolve("command/AIBotVerifySubcommand.java")));
    }

    private static Map<String, String> matchingSources(Pattern pattern) throws IOException {
        Map<String, String> result = new LinkedHashMap<>();
        try (var paths = Files.walk(MAIN)) {
            for (Path path : paths.filter(file -> file.toString().endsWith(".java")).sorted().toList()) {
                String source = Files.readString(path);
                if (pattern.matcher(source).find()) {
                    result.put(MAIN.relativize(path).toString().replace('\\', '/'), source);
                }
            }
        }
        return result;
    }

    private static String read(String relative) throws IOException {
        return Files.readString(MAIN.resolve(relative));
    }

    private static int occurrences(String source, String needle) {
        return source.split(Pattern.quote(needle), -1).length - 1;
    }
}
