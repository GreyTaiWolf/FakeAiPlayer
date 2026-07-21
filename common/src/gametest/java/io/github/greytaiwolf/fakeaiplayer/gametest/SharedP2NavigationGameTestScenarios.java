package io.github.greytaiwolf.fakeaiplayer.gametest;

import io.github.greytaiwolf.fakeaiplayer.action.ActionResult;
import io.github.greytaiwolf.fakeaiplayer.entity.AIPlayerEntity;
import io.github.greytaiwolf.fakeaiplayer.pathfinding.FailureReason;
import io.github.greytaiwolf.fakeaiplayer.pathfinding.InteractionPosePlanner;
import io.github.greytaiwolf.fakeaiplayer.pathfinding.MoveType;
import io.github.greytaiwolf.fakeaiplayer.pathfinding.MultiGoalAStarPathfinder;
import io.github.greytaiwolf.fakeaiplayer.pathfinding.NavGoal;
import io.github.greytaiwolf.fakeaiplayer.pathfinding.NeighborEnumerator;
import io.github.greytaiwolf.fakeaiplayer.pathfinding.NavigationHandle;
import io.github.greytaiwolf.fakeaiplayer.pathfinding.NavigationOutcomePolicy;
import io.github.greytaiwolf.fakeaiplayer.pathfinding.NavigationPlanner;
import io.github.greytaiwolf.fakeaiplayer.pathfinding.NavigationRequest;
import io.github.greytaiwolf.fakeaiplayer.pathfinding.NavigationSearchBudget;
import io.github.greytaiwolf.fakeaiplayer.pathfinding.NavigationSearchMetrics;
import io.github.greytaiwolf.fakeaiplayer.pathfinding.NavigationState;
import io.github.greytaiwolf.fakeaiplayer.pathfinding.Node;
import io.github.greytaiwolf.fakeaiplayer.pathfinding.PathfindingResult;
import io.github.greytaiwolf.fakeaiplayer.pathfinding.Standability;
import io.github.greytaiwolf.fakeaiplayer.pathfinding.TraversalBounds;
import io.github.greytaiwolf.fakeaiplayer.pathfinding.TraversalPolicy;
import io.github.greytaiwolf.fakeaiplayer.task.TaskManager;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;

/** Loader-neutral P2 navigation contract scenarios. */
public final class SharedP2NavigationGameTestScenarios {
    private static final int FEET_Y = 2;

    private SharedP2NavigationGameTestScenarios() {
    }

    public static void goalVariantsResolveAuthoritativeEndpoints(GameTestHelper helper) {
        run(helper, fixture -> {
            fixture.prepareFlat(FEET_Y, 2, 18, 2, 18);
            BlockPos start = fixture.absolute(new BlockPos(4, FEET_Y, 10));
            BlockPos exact = fixture.absolute(new BlockPos(8, FEET_Y, 10));
            BlockPos nearCenter = fixture.absolute(new BlockPos(12, FEET_Y, 10));
            BlockPos interactionTarget = fixture.absolute(new BlockPos(16, FEET_Y, 10));
            BlockPos interactionStandA = fixture.absolute(new BlockPos(15, FEET_Y, 10));
            BlockPos interactionStandB = fixture.absolute(new BlockPos(16, FEET_Y, 11));

            List<NavGoal> goals = List.of(
                    NavGoal.exact(exact),
                    NavGoal.near(nearCenter, 2, 1),
                    NavGoal.interaction(
                            interactionTarget, Set.of(interactionStandA, interactionStandB)),
                    NavGoal.Composite.anyOf(List.of(
                            NavGoal.exact(interactionStandB), NavGoal.exact(exact))));
            for (NavGoal goal : goals) {
                PathfindingResult result = search(helper, start, goal, new NavigationSearchMetrics());
                require(result.success(), goal.identityKey() + " failed: " + result.reason());
                require(result.resolvedGoal() != null
                                && goal.accepts(helper.getLevel(), result.resolvedGoal()),
                        "Resolved endpoint is not accepted by " + goal.identityKey());
            }

            BlockPos blocked = fixture.absolute(new BlockPos(7, FEET_Y, 7));
            fixture.setAbsolute(blocked, Blocks.STONE.defaultBlockState());
            PathfindingResult rejected = search(
                    helper, start, NavGoal.exact(blocked), new NavigationSearchMetrics());
            require(!rejected.success()
                            && rejected.reason() == FailureReason.GOAL_NOT_STANDABLE,
                    "Exact goal snapped or used an invalid endpoint: " + rejected.reason());
        });
    }

    public static void multiGoalSingleSearchChoosesGlobalMinimum(GameTestHelper helper) {
        run(helper, fixture -> {
            fixture.prepareFlat(FEET_Y, 2, 18, 2, 18);
            BlockPos start = fixture.absolute(new BlockPos(4, FEET_Y, 10));
            BlockPos expensive = fixture.absolute(new BlockPos(9, FEET_Y, 10));
            BlockPos cheap = fixture.absolute(new BlockPos(4, FEET_Y, 16));
            for (int z = 6; z <= 14; z++) {
                BlockPos wall = fixture.absolute(new BlockPos(7, FEET_Y, z));
                fixture.setAbsolute(wall, Blocks.STONE.defaultBlockState());
                fixture.setAbsolute(wall.above(), Blocks.STONE.defaultBlockState());
            }
            LinkedHashSet<BlockPos> expensiveFirst = new LinkedHashSet<>();
            expensiveFirst.add(expensive);
            expensiveFirst.add(cheap);
            NavigationSearchMetrics metrics = new NavigationSearchMetrics();
            PathfindingResult multi = search(
                    helper, start,
                    NavGoal.interaction(
                            fixture.absolute(new BlockPos(18, FEET_Y, 10)), expensiveFirst),
                    metrics);
            PathfindingResult expensiveOnly = search(
                    helper, start, NavGoal.exact(expensive), new NavigationSearchMetrics());
            PathfindingResult cheapOnly = search(
                    helper, start, NavGoal.exact(cheap), new NavigationSearchMetrics());
            require(multi.success() && expensiveOnly.success() && cheapOnly.success(),
                    "Multi-goal baseline search failed");
            require(horizontalDistance(start, expensive) < horizontalDistance(start, cheap),
                    "Expensive endpoint is not geometrically nearer");
            require(expensiveOnly.pathCost() > cheapOnly.pathCost() + 1.0D,
                    "Wall did not make the nearer endpoint materially more expensive");
            require(multi.resolvedGoal().equals(cheap),
                    "Multi-goal search selected expensive candidate " + multi.resolvedGoal());
            require(multi.pathCost() <= Math.min(
                            expensiveOnly.pathCost(), cheapOnly.pathCost()) + 1.0E-6D,
                    "Multi-goal cost exceeded singleton best");
            require(metrics.snapshot().frontiersStarted() == 1,
                    "Interaction search created " + metrics.snapshot().frontiersStarted()
                            + " frontiers instead of one");
        });
    }

    public static void interactionPoseUsesOneFrontierAndCheapestStand(GameTestHelper helper) {
        run(helper, fixture -> {
            fixture.prepareFlat(FEET_Y, 2, 18, 2, 18);
            AIPlayerEntity bot = fixture.spawnBot("P2Pose", new BlockPos(3, FEET_Y, 10));
            BlockPos target = fixture.absolute(new BlockPos(15, FEET_Y, 10));
            fixture.setAbsolute(target, Blocks.OAK_LOG.defaultBlockState());
            InteractionPosePlanner.PlanningBudget budget =
                    InteractionPosePlanner.PlanningBudget.bounded(8, 250L);
            InteractionPosePlanner.InteractionPose pose = InteractionPosePlanner.plan(
                    bot, target, Set.of(), budget, 8).orElseThrow(
                    () -> new IllegalStateException("Production pose planner found no stand"));

            double singletonBest = Double.POSITIVE_INFINITY;
            for (int dx = -2; dx <= 2; dx++) {
                for (int dz = -2; dz <= 2; dz++) {
                    int ring = Math.max(Math.abs(dx), Math.abs(dz));
                    boolean plannerCandidate = ring == 2
                            || (ring == 1 && Math.abs(dx) + Math.abs(dz) == 1);
                    if (!plannerCandidate) {
                        continue;
                    }
                    BlockPos stand = target.offset(dx, 0, dz);
                    if (stand.equals(target)
                            || !Standability.isStandable(
                            helper.getLevel(), stand, TraversalPolicy.TASK_WALK_DRY)
                            || !InteractionPosePlanner.canInteractFrom(bot, stand, target, 5.0D)) {
                        continue;
                    }
                    PathfindingResult baseline = search(
                            helper, bot.blockPosition(), NavGoal.exact(stand),
                            new NavigationSearchMetrics());
                    if (baseline.success()) {
                        singletonBest = Math.min(singletonBest, baseline.pathCost());
                    }
                }
            }
            NavigationSearchMetrics.Snapshot metrics = budget.searchMetrics();
            require(metrics.frontiersStarted() == 1 && metrics.searchesCompleted() == 1,
                    "Pose planner used " + metrics.frontiersStarted() + " frontiers and "
                            + metrics.searchesCompleted() + " completed searches");
            require(Double.isFinite(singletonBest)
                            && pose.pathCost() <= singletonBest + 1.0E-6D,
                    "One-frontier pose cost exceeded singleton best: "
                            + pose.pathCost() + " > " + singletonBest);
        });
    }

    public static void blockWritesInvalidateCachedRoutes(GameTestHelper helper) {
        run(helper, fixture -> {
            fixture.prepareFlat(FEET_Y, 2, 18, 2, 18);
            BlockPos start = fixture.absolute(new BlockPos(3, FEET_Y, 10));
            BlockPos goal = fixture.absolute(new BlockPos(15, FEET_Y, 10));
            for (int z = 7; z <= 13; z++) {
                BlockPos wall = fixture.absolute(new BlockPos(9, FEET_Y, z));
                fixture.setAbsolute(wall, Blocks.STONE.defaultBlockState());
                fixture.setAbsolute(wall.above(), Blocks.STONE.defaultBlockState());
            }
            PathfindingResult detour = cachedSearch(helper, start, NavGoal.exact(goal));
            require(detour.success() && !detour.cacheHit(),
                    "Initial detour search failed or unexpectedly hit cache");

            for (int z = 7; z <= 13; z++) {
                BlockPos wall = fixture.absolute(new BlockPos(9, FEET_Y, z));
                fixture.setAbsolute(wall, Blocks.AIR.defaultBlockState());
                fixture.setAbsolute(wall.above(), Blocks.AIR.defaultBlockState());
            }
            PathfindingResult direct = cachedSearch(helper, start, NavGoal.exact(goal));
            require(direct.success() && !direct.cacheHit(),
                    "World write reused the stale detour cache");
            require(direct.worldVersion() > detour.worldVersion(),
                    "Successful Level#setBlock did not advance world revision");
            require(direct.pathCost() + 1.0D < detour.pathCost(),
                    "Removed wall did not produce a shorter fresh route");

            PathfindingResult cached = cachedSearch(helper, start, NavGoal.exact(goal));
            require(cached.success() && cached.cacheHit() && cached.nodesExplored() == 0,
                    "Fresh unchanged route did not produce a zero-work cache hit");
        });
    }

    public static void traversalPoliciesPreservePermissions(GameTestHelper helper) {
        runAsync(helper, fixture -> {
            fixture.prepareFlat(FEET_Y, 2, 18, 7, 13);
            AIPlayerEntity bot = fixture.spawnBot("P2Policy", new BlockPos(3, FEET_Y, 10));
            BlockPos start = bot.blockPosition();
            BlockPos goal = fixture.absolute(new BlockPos(16, FEET_Y, 10));
            BlockPos gate = fixture.absolute(new BlockPos(9, FEET_Y, 10));
            Predicate<BlockPos> corridor = position -> position.getY() == start.getY()
                    && position.getZ() == start.getZ()
                    && position.getX() >= start.getX()
                    && position.getX() <= goal.getX();

            helper.runAtTickTime(1, () -> fixture.checked(() -> {
                fixture.setAbsolute(gate, Blocks.WATER.defaultBlockState());
                NavigationRequest dryWater = NavigationRequest.walk(
                        NavGoal.exact(goal), "p2_dry_water")
                        .withConstraint(Set.of(), corridor, "p2_policy_corridor");
                NavigationRequest wet = NavigationRequest.water(
                        NavGoal.exact(goal), "p2_water")
                        .withConstraint(Set.of(), corridor, "p2_policy_corridor");
                PathfindingResult dryWaterPlan = NavigationPlanner.plan(
                        bot, dryWater, true).result();
                PathfindingResult wetPlan = NavigationPlanner.plan(bot, wet, true).result();
                require(!dryWaterPlan.success()
                                && dryWaterPlan.reason() == FailureReason.GOAL_UNREACHABLE,
                        "Dry policy crossed water or misclassified it: "
                                + dryWaterPlan.reason());
                require(wetPlan.success()
                                && wetPlan.path().stream().anyMatch(
                                node -> node.pos().equals(gate)),
                        "Water-capable policy did not use the only water corridor");
                require(wetPlan.path().stream().noneMatch(node ->
                                node.moveType() == MoveType.DIG_THROUGH
                                        || node.moveType() == MoveType.PILLAR_UP),
                        "Water policy gained a world-edit move");

                fixture.setAbsolute(gate, Blocks.STONE.defaultBlockState());
                fixture.setAbsolute(gate.above(), Blocks.STONE.defaultBlockState());
                var gateBefore = helper.getLevel().getBlockState(gate);
                var headBefore = helper.getLevel().getBlockState(gate.above());
                NeighborEnumerator virtualDigEnumerator = new NeighborEnumerator(
                        true, true, TraversalPolicy.TASK_MUTATING_DRY);
                List<?> liveBlockedTransitions = virtualDigEnumerator.getNeighbors(
                        gate, helper.getLevel());
                List<?> virtualDigTransitions = virtualDigEnumerator.getNeighbors(
                        new Node(gate, 0.0D, 0.0D, MoveType.DIG_THROUGH, null),
                        helper.getLevel());
                require(!Standability.isStandableFresh(
                                helper.getLevel(), gate, TraversalPolicy.TASK_MUTATING_DRY),
                        "A reblocked executed DIG source must fail live standability");
                require(!hasMoveType(liveBlockedTransitions, MoveType.WALK),
                        "A live blocked column unexpectedly exposed a walk exit");
                require(hasMoveType(virtualDigTransitions, MoveType.WALK),
                        "A pending DIG column did not expose its post-dig walk exit");
                require(!hasMoveType(virtualDigTransitions, MoveType.PILLAR_UP),
                        "A pending DIG column leaked virtual clearance into PILLAR_UP");
                NavigationRequest dryWall = NavigationRequest.walk(
                        NavGoal.exact(goal), "p2_dry_wall")
                        .withConstraint(Set.of(), corridor, "p2_policy_corridor");
                NavigationRequest mutating = NavigationRequest.mutating(
                        NavGoal.exact(goal), false, "p2_mutating_wall")
                        .withConstraint(Set.of(), corridor, "p2_policy_corridor");
                PathfindingResult dryWallPlan = NavigationPlanner.plan(
                        bot, dryWall, true).result();
                PathfindingResult mutatingPlan = NavigationPlanner.plan(
                        bot, mutating, true).result();
                require(!dryWallPlan.success()
                                && dryWallPlan.reason() == FailureReason.GOAL_UNREACHABLE,
                        "Walk-only policy crossed a solid wall: " + dryWallPlan.reason());
                require(mutatingPlan.success()
                                && mutatingPlan.path().stream().anyMatch(
                                node -> node.moveType() == MoveType.DIG_THROUGH),
                        "Mutating policy ended as " + mutatingPlan.reason()
                                + " with moves " + mutatingPlan.path().stream()
                                .map(node -> node.moveType()).toList());
                require(helper.getLevel().getBlockState(gate).equals(gateBefore)
                                && helper.getLevel().getBlockState(gate.above()).equals(headBefore),
                        "Planning mutated the world before execution");
                fixture.succeed();
            }));
        });
    }

    public static void searchLimitIsInconclusiveNotUnreachable(GameTestHelper helper) {
        run(helper, fixture -> {
            fixture.prepareFlat(FEET_Y, 2, 18, 7, 13);
            AIPlayerEntity bot = fixture.spawnBot("P2Limits", new BlockPos(3, FEET_Y, 10));
            BlockPos start = bot.blockPosition();
            BlockPos goal = fixture.absolute(new BlockPos(16, FEET_Y, 10));
            Predicate<BlockPos> corridor = position -> position.getY() == start.getY()
                    && position.getZ() == start.getZ();
            NavigationRequest limited = NavigationRequest.walk(
                    NavGoal.exact(goal), "p2_search_limit")
                    .withConstraint(Set.of(), corridor, "p2_limit_corridor")
                    .withBudget(1, 250L);
            PathfindingResult limit = NavigationPlanner.plan(bot, limited, true).result();
            require(!limit.success() && limit.reason() == FailureReason.SEARCH_LIMIT,
                    "Bounded frontier did not classify SEARCH_LIMIT: " + limit.reason());

            BlockPos wall = fixture.absolute(new BlockPos(4, FEET_Y, 10));
            fixture.setAbsolute(wall, Blocks.STONE.defaultBlockState());
            fixture.setAbsolute(wall.above(), Blocks.STONE.defaultBlockState());
            NavigationRequest exhaustive = NavigationRequest.walk(
                    NavGoal.exact(goal), "p2_unreachable")
                    .withConstraint(Set.of(), corridor, "p2_limit_corridor")
                    .withBudget(1_000, 250L);
            PathfindingResult unreachable = NavigationPlanner.plan(
                    bot, exhaustive, true).result();
            require(!unreachable.success()
                            && unreachable.reason() == FailureReason.GOAL_UNREACHABLE,
                    "Exhausted frontier did not prove GOAL_UNREACHABLE: "
                            + unreachable.reason());
        });
    }

    public static void handleLifecycleIsRequestScoped(GameTestHelper helper) {
        run(helper, fixture -> {
            fixture.prepareFlat(FEET_Y, 2, 18, 2, 18);
            AIPlayerEntity bot = fixture.spawnBot("P2Handle", new BlockPos(4, FEET_Y, 10));
            int searchesBefore = NavigationSearchBudget.snapshot(
                    bot.getServer(), bot.getUUID()).botSearches();
            NavigationHandle immediate = bot.getActionPack().navigate(
                    NavigationRequest.walk(
                            NavGoal.exact(bot.blockPosition()), "p2_handle_immediate"));
            require(immediate.state() == NavigationState.ARRIVED
                            && immediate.result().isPresent(),
                    "Immediate handle did not retain ARRIVED result");
            require(NavigationSearchBudget.snapshot(
                            bot.getServer(), bot.getUUID()).botSearches() == searchesBefore,
                    "Already-satisfied request consumed a search permit");

            BlockPos firstGoal = fixture.absolute(new BlockPos(17, FEET_Y, 10));
            NavigationHandle first = bot.getActionPack().navigate(
                    NavigationRequest.walk(NavGoal.exact(firstGoal), "p2_handle_first"));
            require(first.state() == NavigationState.FOLLOWING,
                    "First long request did not start following");

            BlockPos impossible = fixture.absolute(new BlockPos(10, FEET_Y, 6));
            fixture.setAbsolute(impossible, Blocks.STONE.defaultBlockState());
            NavigationHandle rejected = bot.getActionPack().navigate(
                    NavigationRequest.walk(NavGoal.exact(impossible), "p2_handle_rejected"));
            require(rejected.state() == NavigationState.BLOCKED,
                    "Invalid replacement was not BLOCKED: " + rejected.state());
            require(first.state() == NavigationState.FOLLOWING,
                    "Failed replacement disturbed active handle");
            require(bot.getActionPack().navigationHandle().orElseThrow() == first
                            && bot.getActionPack().navigationSnapshot().requestId()
                            == first.requestId(),
                    "Failed replacement relabeled the active request");

            BlockPos secondGoal = fixture.absolute(new BlockPos(17, FEET_Y, 14));
            NavigationHandle second = bot.getActionPack().navigate(
                    NavigationRequest.walk(NavGoal.exact(secondGoal), "p2_handle_second"));
            require(first.state() == NavigationState.PREEMPTED,
                    "Successful replacement did not PREEMPT first handle");
            require(second.state() == NavigationState.FOLLOWING,
                    "Second handle did not become active");
            require(!bot.getActionPack().cancelNavigation(first, "stale_cancel"),
                    "Old handle cancelled its successor");
            require(bot.getActionPack().cancelNavigation(second, "test_cancel"),
                    "Active handle could not be cancelled");
            require(!bot.getActionPack().cancelNavigation(second, "duplicate_cancel"),
                    "Terminal cancellation was not idempotent");
            require(second.state() == NavigationState.CANCELLED,
                    "Explicit cancellation did not publish CANCELLED");
            require(immediate.result().orElseThrow().state() == NavigationState.ARRIVED,
                    "Later requests overwrote old terminal result");
        });
    }

    public static void relayIsPrefixOfProvenDetour(GameTestHelper helper) {
        run(helper, fixture -> {
            fixture.prepareFlat(FEET_Y, 2, 18, 2, 18);
            AIPlayerEntity bot = fixture.spawnBot("P2Detour", new BlockPos(4, FEET_Y, 10));
            BlockPos goal = fixture.absolute(new BlockPos(9, FEET_Y, 10));
            for (int z = 6; z <= 14; z++) {
                BlockPos wall = fixture.absolute(new BlockPos(7, FEET_Y, z));
                fixture.setAbsolute(wall, Blocks.STONE.defaultBlockState());
                fixture.setAbsolute(wall.above(), Blocks.STONE.defaultBlockState());
            }
            NavigationRequest request = NavigationRequest.walk(
                    NavGoal.exact(goal), "p2_proven_relay").withSegmentation(8);
            PathfindingResult complete = search(
                    helper, bot.blockPosition(), request.goal(),
                    new NavigationSearchMetrics());
            NavigationPlanner.PlanningOutcome segmented = NavigationPlanner.plan(
                    bot, request, true);
            require(complete.success() && segmented.result().success(),
                    "Detour baseline or segmented planning failed");
            require(complete.path().size() > request.segmentLength() + 1,
                    "Near detour did not exceed the execution horizon");
            require(segmented.segmented()
                            && segmented.path().size() == request.segmentLength() + 1,
                    "Long detour did not return one bounded execution segment");
            for (int index = 0; index < segmented.path().size(); index++) {
                require(segmented.path().get(index).pos().equals(
                                complete.path().get(index).pos()),
                        "Relay segment diverged from the proven complete route at " + index);
            }
        });
    }

    public static void dynamicObstacleReplansToAlternateResolvedGoal(GameTestHelper helper) {
        runAsync(helper, fixture -> {
            fixture.prepareFlat(FEET_Y, 2, 18, 2, 18);
            AIPlayerEntity bot = fixture.spawnBot("P2AltGoal", new BlockPos(3, FEET_Y, 10));
            BlockPos standA = fixture.absolute(new BlockPos(15, FEET_Y, 10));
            BlockPos standB = fixture.absolute(new BlockPos(15, FEET_Y, 15));
            NavGoal.Interaction goal = new NavGoal.Interaction(
                    fixture.absolute(new BlockPos(17, FEET_Y, 12)), Set.of(standA, standB));
            NavigationHandle[] handleRef = new NavigationHandle[1];
            BlockPos[] initiallyResolved = new BlockPos[1];
            long[] requestId = new long[1];
            boolean[] blocked = new boolean[1];

            helper.runAtTickTime(1, () -> fixture.checked(() -> {
                NavigationHandle handle = bot.getActionPack().navigate(
                        NavigationRequest.walk(goal, "p2_alternate_goal"));
                require(handle.state() == NavigationState.FOLLOWING,
                        "Alternate-goal request did not start: "
                                + handle.state() + '/' + handle.reason());
                handleRef[0] = handle;
                initiallyResolved[0] = handle.resolvedGoal();
                requestId[0] = handle.requestId();
            }));
            helper.runAtTickTime(5, () -> fixture.checked(() -> {
                require(initiallyResolved[0] != null,
                        "Alternate-goal endpoint was not published");
                fixture.setAbsolute(initiallyResolved[0], Blocks.STONE.defaultBlockState());
                fixture.setAbsolute(
                        initiallyResolved[0].above(), Blocks.STONE.defaultBlockState());
                blocked[0] = true;
            }));
            helper.onEachTick(() -> fixture.checked(() -> {
                NavigationHandle handle = handleRef[0];
                if (handle == null || initiallyResolved[0] == null) {
                    return;
                }
                if (!blocked[0]) {
                    return;
                }
                require(!bot.blockPosition().equals(initiallyResolved[0]),
                        "Bot entered the dynamically blocked endpoint");
                require(helper.getLevel().getBlockState(
                                initiallyResolved[0]).is(Blocks.STONE),
                        "Dynamic obstacle was unexpectedly mutated");
                if (handle.state() == NavigationState.ARRIVED) {
                    require(handle.requestId() == requestId[0],
                            "Dynamic replan changed logical request id");
                    require(handle.routeRevision() >= 1,
                            "Dynamic obstacle did not increment route revision");
                    require(!initiallyResolved[0].equals(handle.resolvedGoal()),
                            "Dynamic obstacle did not select alternate endpoint");
                    require(goal.accepts(helper.getLevel(), bot.blockPosition()),
                            "Bot arrived outside interaction goal");
                    fixture.succeed();
                }
                require(!handle.terminal() || handle.state() == NavigationState.ARRIVED,
                        "Alternate-goal replan terminated as " + handle.state()
                                + ": " + handle.reason());
            }));
        });
    }

    public static void staleInteractionTargetIsClassified(GameTestHelper helper) {
        runAsync(helper, fixture -> {
            fixture.prepareFlat(FEET_Y, 2, 18, 2, 18);
            AIPlayerEntity bot = fixture.spawnBot("P2Stale", new BlockPos(3, FEET_Y, 10));
            BlockPos target = fixture.absolute(new BlockPos(17, FEET_Y, 10));
            BlockPos stand = fixture.absolute(new BlockPos(16, FEET_Y, 10));
            fixture.setAbsolute(target, Blocks.OAK_LOG.defaultBlockState());
            NavGoal.Interaction goal = new NavGoal.Interaction(
                    target, Set.of(stand), helper.getLevel().getBlockState(target).toString());
            NavigationHandle handle = bot.getActionPack().navigate(
                    NavigationRequest.walk(goal, "p2_stale_interaction"));
            helper.runAtTickTime(3, () -> fixture.checked(() -> {
                fixture.setAbsolute(target, Blocks.SPRUCE_LOG.defaultBlockState());
            }));
            helper.onEachTick(() -> fixture.checked(() -> {
                if (!handle.terminal()) {
                    return;
                }
                require(handle.state() == NavigationState.STALE_WORLD,
                        "Changed interaction target ended as " + handle.state());
                require(handle.result().isPresent()
                                && !NavigationOutcomePolicy.mayRecordUnreachable(
                                handle.result().orElseThrow()),
                        "STALE_WORLD became unreachable memory evidence");
                fixture.succeed();
            }));
        });
    }

    public static void followRingTracksMovingEntity(GameTestHelper helper) {
        runAsync(helper, fixture -> {
            fixture.prepareFlat(FEET_Y, 2, 18, 2, 18);
            AIPlayerEntity follower = fixture.spawnBot("P2Follower", new BlockPos(3, FEET_Y, 10));
            AIPlayerEntity target = fixture.spawnBot("P2Target", new BlockPos(16, FEET_Y, 10));
            TaskManager.INSTANCE.pauseUserIntent(follower, "p2_follow_fixture");
            TaskManager.INSTANCE.pauseUserIntent(target, "p2_follow_fixture");
            NavGoal.FollowRing ring = new NavGoal.FollowRing(
                    target.getUUID(), target.blockPosition(), 0, 1);
            NavigationHandle[] handleRef = new NavigationHandle[1];
            long[] requestId = new long[1];
            BlockPos followerStart = follower.blockPosition();
            helper.runAtTickTime(1, () -> fixture.checked(() -> {
                NavigationHandle handle = follower.getActionPack().navigate(
                        NavigationRequest.walk(
                                ring, "p2_follow_ring").withSegmentation(8));
                require(handle.state() == NavigationState.FOLLOWING,
                        "Follow request did not start: "
                                + handle.state() + '/' + handle.reason());
                require(handle.resolvedGoal() != null
                                && !ring.accepts(helper.getLevel(), handle.resolvedGoal()),
                        "Long dynamic follow request did not begin with a relay");
                handleRef[0] = handle;
                requestId[0] = handle.requestId();
            }));
            for (int tick = 5; tick <= 54; tick++) {
                int scheduledTick = tick;
                helper.runAtTickTime(scheduledTick, () -> fixture.checked(() -> {
                    int z = 7 + ((scheduledTick - 5) % 8);
                    BlockPos moving = fixture.absolute(new BlockPos(16, FEET_Y, z));
                    target.teleportTo(
                            helper.getLevel(), moving.getX() + 0.5D, moving.getY(),
                            moving.getZ() + 0.5D, java.util.Collections.emptySet(),
                            target.getYRot(), target.getXRot(), true);
                }));
            }
            helper.runAtTickTime(44, () -> fixture.checked(() -> {
                NavigationHandle handle = handleRef[0];
                require(handle != null, "Follow handle was not installed");
                require(!handle.terminal(),
                        "Dynamic follow terminated as "
                                + handle.state() + '/' + handle.reason());
                require(follower.blockPosition().distSqr(followerStart) >= 1.0D,
                        "Continuously moving target stalled the follower between replan cadences");
            }));
            helper.runAtTickTime(55, () -> fixture.checked(() -> {
                BlockPos settled = fixture.absolute(new BlockPos(3, FEET_Y, 15));
                target.teleportTo(
                        helper.getLevel(), settled.getX() + 0.5D, settled.getY(),
                        settled.getZ() + 0.5D, java.util.Collections.emptySet(),
                        target.getYRot(), target.getXRot(), true);
            }));
            helper.onEachTick(() -> fixture.checked(() -> {
                NavigationHandle handle = handleRef[0];
                if (handle == null) {
                    return;
                }
                if (handle.state() == NavigationState.ARRIVED) {
                    require(handle.requestId() == requestId[0],
                            "Follow movement created a new logical request");
                    require(ring.accepts(helper.getLevel(), follower.blockPosition()),
                            "Follower arrived outside live target ring");
                    require(handle.routeRevision() >= 1,
                            "Moving entity did not revise route");
                    fixture.succeed();
                }
                require(!handle.terminal() || handle.state() == NavigationState.ARRIVED,
                        "Follow ring terminated as " + handle.state() + ": " + handle.reason());
            }));
        });
    }

    public static void fleeGoalReachesSafeBoundedCell(GameTestHelper helper) {
        runAsync(helper, fixture -> {
            fixture.prepareFlat(FEET_Y, 2, 18, 2, 18);
            AIPlayerEntity bot = fixture.spawnBot("P2Flee", new BlockPos(10, FEET_Y, 10));
            BlockPos start = bot.blockPosition();
            NavGoal.Flee flee = new NavGoal.Flee(Set.of(start), 6);
            NavigationRequest request = NavigationRequest.escape(
                    flee, TraversalBounds.around(start, 8, 2), "p2_flee");
            NavigationHandle handle = bot.getActionPack().navigate(request);
            helper.onEachTick(() -> fixture.checked(() -> {
                if (handle.state() == NavigationState.ARRIVED) {
                    require(flee.accepts(helper.getLevel(), bot.blockPosition()),
                            "Flee handle arrived before minimum separation");
                    require(handle.request().traversalPolicy() == TraversalPolicy.ESCAPE_DRY_OPEN
                                    && !handle.request().allowDig()
                                    && !handle.request().allowPillar(),
                            "Flee request gained world-edit permission");
                    fixture.succeed();
                }
                require(!handle.terminal() || handle.state() == NavigationState.ARRIVED,
                        "Flee request terminated as " + handle.state() + ": " + handle.reason());
            }));
        });
    }

    public static void longRouteUsesRelaysUnderSameHandle(GameTestHelper helper) {
        runAsync(helper, fixture -> {
            fixture.prepareFlat(FEET_Y, 2, 12, 5, 15);
            for (int x = 2; x <= 12; x++) {
                for (int z = 5; z <= 15; z++) {
                    boolean horizontalLeg = z == 6 && x >= 3 && x <= 11;
                    boolean verticalLeg = x == 11 && z >= 6 && z <= 14;
                    if (horizontalLeg || verticalLeg) {
                        continue;
                    }
                    BlockPos wall = fixture.absolute(new BlockPos(x, FEET_Y, z));
                    fixture.setAbsolute(wall, Blocks.STONE.defaultBlockState());
                    fixture.setAbsolute(wall.above(), Blocks.STONE.defaultBlockState());
                }
            }
            AIPlayerEntity bot = fixture.spawnBot("P2Relay", new BlockPos(3, FEET_Y, 6));
            BlockPos goal = fixture.absolute(new BlockPos(11, FEET_Y, 14));
            PathfindingResult baseline = search(
                    helper, bot.blockPosition(), NavGoal.exact(goal),
                    new NavigationSearchMetrics());
            require(baseline.success(), "L-corridor singleton baseline failed: " + baseline.reason());
            require(baseline.path().size() == 17 && baseline.pathCost() > 16.0D,
                    "L-corridor did not put its turn on the relay boundary");
            NavigationRequest request = NavigationRequest.walk(
                    NavGoal.exact(goal), "p2_segmented").withSegmentation(8);
            NavigationHandle handle = bot.getActionPack().navigate(request);
            long requestId = handle.requestId();
            require(handle.resolvedGoal() != null && !handle.resolvedGoal().equals(goal),
                    "Long route did not expose an initial relay endpoint");
            helper.onEachTick(() -> fixture.checked(() -> {
                if (handle.state() == NavigationState.ARRIVED) {
                    require(handle.requestId() == requestId,
                            "Segment relay replaced the logical handle");
                    require(handle.routeRevision() >= 1,
                            "Long route never published a relay revision");
                    require(bot.blockPosition().getX() == goal.getX()
                                    && bot.blockPosition().getZ() == goal.getZ(),
                            "Segmented exact route ended at " + bot.blockPosition());
                    require(handle.result().isPresent()
                                    && Math.abs(handle.result().orElseThrow().pathCost()
                                    - baseline.pathCost()) <= 1.0E-6D,
                            "Segmented logical cost lost the boundary turn penalty");
                    NavigationSearchMetrics.Snapshot metrics = request.searchMetrics().snapshot();
                    require(metrics.frontiersStarted() == 1
                                    && metrics.searchesCompleted() == 1,
                            "Proven suffix started another A* frontier: " + metrics);
                    fixture.succeed();
                }
                require(!handle.terminal() || handle.state() == NavigationState.ARRIVED,
                        "Segmented route terminated as " + handle.state()
                                + ": " + handle.reason());
            }));
        });
    }

    public static void legacyActionPackMirrorsHandle(GameTestHelper helper) {
        runAsync(helper, fixture -> {
            fixture.prepareFlat(FEET_Y, 2, 18, 7, 13);
            AIPlayerEntity bot = fixture.spawnBot("P2Legacy", new BlockPos(4, FEET_Y, 10));
            BlockPos goal = fixture.absolute(new BlockPos(16, FEET_Y, 10));
            fixture.setAbsolute(goal, Blocks.STONE.defaultBlockState());
            fixture.setAbsolute(goal.above(), Blocks.STONE.defaultBlockState());
            ActionResult start = bot.getActionPack().startNonMutatingPathTo(goal);
            require(start.isInProgress(), "Legacy path did not start");
            NavigationHandle handle = bot.getActionPack().navigationHandle().orElseThrow();
            require(handle.requestId() == bot.getActionPack().navigationSnapshot().requestId(),
                    "Legacy snapshot and handle request ids diverged");
            helper.onEachTick(() -> fixture.checked(() -> {
                if (bot.getActionPack().navigationSnapshot().state() == NavigationState.ARRIVED) {
                    require(handle.state() == NavigationState.ARRIVED
                                    && handle.result().isPresent(),
                            "Legacy adapter did not retain handle outcome");
                    require(handle.requestedGoal().accepts(
                                    helper.getLevel(),
                                    handle.result().orElseThrow().resolvedGoal()),
                            "Legacy handle published an endpoint rejected by its semantic goal");
                    require(!goal.equals(handle.result().orElseThrow().resolvedGoal()),
                            "Legacy endpoint fixture did not exercise standable-cell snapping");
                    require(bot.getActionPack().isPathExecutorIdle()
                                    && bot.getActionPack().activePathGoal() == null,
                            "Legacy executor remained active after ARRIVED");
                    fixture.succeed();
                }
                require(bot.getActionPack().navigationSnapshot().state()
                                != NavigationState.FAILED,
                        "Legacy adapter failed: "
                                + bot.getActionPack().navigationSnapshot().reason());
            }));
        });
    }

    private static PathfindingResult search(GameTestHelper helper,
                                            BlockPos start,
                                            NavGoal goal,
                                            NavigationSearchMetrics metrics) {
        return new MultiGoalAStarPathfinder(
                helper.getLevel(), start, goal, 10_000, 250L,
                TraversalPolicy.TASK_WALK_DRY, metrics).findPath(true);
    }

    private static PathfindingResult cachedSearch(GameTestHelper helper,
                                                  BlockPos start,
                                                  NavGoal goal) {
        return new MultiGoalAStarPathfinder(
                helper.getLevel(), start, goal, 10_000, 250L,
                TraversalPolicy.TASK_WALK_DRY,
                new NavigationSearchMetrics()).findPath(false);
    }

    private static void run(GameTestHelper helper, Scenario scenario) {
        SharedGameTestFixture fixture = new SharedGameTestFixture(helper);
        fixture.checked(() -> scenario.run(fixture));
        fixture.succeed();
    }

    private static void runAsync(GameTestHelper helper, Scenario scenario) {
        SharedGameTestFixture fixture = new SharedGameTestFixture(helper);
        fixture.checked(() -> scenario.run(fixture));
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }

    private static boolean hasMoveType(List<?> candidates, MoveType expected) {
        // NeighborCandidate is deliberately package-private; keep the production API closed and
        // inspect only its stable record accessor from this loader-neutral regression scenario.
        for (Object candidate : candidates) {
            try {
                var accessor = candidate.getClass().getDeclaredMethod("moveType");
                accessor.setAccessible(true);
                if (accessor.invoke(candidate) == expected) {
                    return true;
                }
            } catch (ReflectiveOperationException exception) {
                throw new IllegalStateException("Cannot inspect navigation candidate", exception);
            }
        }
        return false;
    }

    private static double horizontalDistance(BlockPos first, BlockPos second) {
        return Math.hypot(first.getX() - second.getX(), first.getZ() - second.getZ());
    }

    @FunctionalInterface
    private interface Scenario {
        void run(SharedGameTestFixture fixture);
    }
}
