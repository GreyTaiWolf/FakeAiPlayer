package io.github.greytaiwolf.fakeaiplayer.gametest;

import io.github.greytaiwolf.fakeaiplayer.action.ActionResult;
import io.github.greytaiwolf.fakeaiplayer.action.BuildAction;
import io.github.greytaiwolf.fakeaiplayer.action.HarvestCore;
import io.github.greytaiwolf.fakeaiplayer.entity.AIPlayerEntity;
import io.github.greytaiwolf.fakeaiplayer.pathfinding.AStarPathfinder;
import io.github.greytaiwolf.fakeaiplayer.pathfinding.InteractionPosePlanner;
import io.github.greytaiwolf.fakeaiplayer.pathfinding.NavigationSnapshot;
import io.github.greytaiwolf.fakeaiplayer.pathfinding.NavigationState;
import io.github.greytaiwolf.fakeaiplayer.pathfinding.Node;
import io.github.greytaiwolf.fakeaiplayer.pathfinding.PathfindingResult;
import io.github.greytaiwolf.fakeaiplayer.pathfinding.Standability;
import io.github.greytaiwolf.fakeaiplayer.pathfinding.TraversalPolicy;
import io.github.greytaiwolf.fakeaiplayer.platform.PlatformServices;
import io.github.greytaiwolf.fakeaiplayer.runtime.PauseOwner;
import io.github.greytaiwolf.fakeaiplayer.runtime.TaskOrigin;
import io.github.greytaiwolf.fakeaiplayer.task.EpisodeMemory;
import io.github.greytaiwolf.fakeaiplayer.task.GatherQuotaTask;
import io.github.greytaiwolf.fakeaiplayer.task.HoldTask;
import io.github.greytaiwolf.fakeaiplayer.task.TaskManager;
import io.github.greytaiwolf.fakeaiplayer.task.TaskState;
import io.github.greytaiwolf.fakeaiplayer.task.tree.TreeDetector;
import io.github.greytaiwolf.fakeaiplayer.task.tree.TreeFellingSession;
import io.github.greytaiwolf.fakeaiplayer.task.tree.TreeSnapshot;
import io.github.greytaiwolf.fakeaiplayer.task.tree.PlayerPlacedLogLedger;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Shared test bodies; loader projects contain only registration annotations and expected names. */
public final class SharedP0P1GameTestScenarios {
    private static final int FEET_Y = 2;
    private static final BlockPos CENTER = new BlockPos(10, FEET_Y, 10);

    private SharedP0P1GameTestScenarios() {
    }

    public static void loaderBootstrapAndWorldMutation(
            GameTestHelper helper, String expectedLoader) {
        run(helper, fixture -> {
            require(expectedLoader.equals(PlatformServices.loaderName()),
                    "Expected loader " + expectedLoader + " but got "
                            + PlatformServices.loaderName());
            require(PlayerPlacedLogLedger.INSTANCE.allowsAutomaticFelling(),
                    "Fresh GameTest world did not establish a trusted empty placement baseline");
            BlockPos marker = new BlockPos(10, 1, 10);
            fixture.setRelative(marker, Blocks.IRON_BLOCK.defaultBlockState());
            require(helper.getLevel().getBlockState(fixture.absolute(marker)).is(Blocks.IRON_BLOCK),
                    "Shared GameTest world mutation was not visible");
        });
    }

    public static void dryPathDetoursAroundWater(GameTestHelper helper) {
        run(helper, fixture -> {
            fixture.prepareFlat(FEET_Y, 2, 18, 5, 15);
            BlockPos start = fixture.absolute(new BlockPos(4, FEET_Y, 10));
            BlockPos goal = fixture.absolute(new BlockPos(16, FEET_Y, 10));

            for (int x = 9; x <= 11; x++) {
                for (int z = 9; z <= 11; z++) {
                    fixture.setRelative(
                            new BlockPos(x, FEET_Y, z), Blocks.WATER.defaultBlockState());
                }
            }

            PathfindingResult result = new AStarPathfinder(
                    helper.getLevel(),
                    start,
                    goal,
                    10_000,
                    250L,
                    TraversalPolicy.TASK_WALK_DRY,
                    1.0D).findPath(true);
            require(result.success(), "Dry path failed: " + result.reason());
            require(!result.path().isEmpty(), "Dry path succeeded with no nodes");
            require(result.path().stream().map(Node::pos)
                            .noneMatch(pos -> helper.getLevel().getFluidState(pos).is(FluidTags.WATER)),
                    "Dry traversal included a water node");
            require(result.path().stream().map(Node::pos)
                            .anyMatch(pos -> pos.getZ() != start.getZ()),
                    "Dry path did not measurably detour around the three-wide water barrier");
        });
    }

    /** A live non-mutating request must replan around a wall that appears on its route. */
    public static void activePathReplansAroundDynamicWall(GameTestHelper helper) {
        runAsync(helper, fixture -> {
            fixture.prepareFlat(FEET_Y, 2, 18, 5, 15);
            BlockPos start = fixture.absolute(new BlockPos(4, FEET_Y, 10));
            BlockPos goal = fixture.absolute(new BlockPos(16, FEET_Y, 10));
            PathfindingResult initial = new AStarPathfinder(
                    helper.getLevel(),
                    start,
                    goal,
                    10_000,
                    250L,
                    TraversalPolicy.TASK_WALK_DRY,
                    1.0D).findPath(true);
            require(initial.success() && initial.path().size() >= 9,
                    "Initial dynamic-wall route failed: " + initial.reason());
            BlockPos blocked = initial.path().get(initial.path().size() / 2).pos();
            require(!blocked.equals(start) && !blocked.equals(goal),
                    "Dynamic-wall node resolved to a route endpoint");
            BlockPos longerAllowedSide = blocked.south();
            fixture.setAbsolute(longerAllowedSide, Blocks.STONE.defaultBlockState());
            fixture.setAbsolute(longerAllowedSide.above(), Blocks.STONE.defaultBlockState());

            AIPlayerEntity bot = fixture.spawnBot(
                    "SharedNavWall01", new BlockPos(4, FEET_Y, 10));
            // The first path is straight and satisfies the skill invariant. After the wall appears,
            // the executor must retain that invariant and detour only on the permitted side.
            ActionResult started = bot.getActionPack().startPlannedNonMutatingPath(
                    goal,
                    initial.path(),
                    Set.of(),
                    feet -> feet.getZ() >= start.getZ());
            require(!started.isFailed(),
                    "ActionPack rejected dynamic-wall route: " + started.reason());
            NavigationSnapshot initialNavigation = bot.getActionPack().navigationSnapshot();
            long requestId = initialNavigation.requestId();
            require(initialNavigation.start().equals(start)
                            && initialNavigation.requestedGoal().equals(goal)
                            && initialNavigation.traversalPolicy()
                                    == TraversalPolicy.TASK_WALK_DRY,
                    "Navigation snapshot lost start, goal or traversal policy");
            require(initialNavigation.pathLength() >= 9
                            && initialNavigation.pathCost() > 0.0D
                            && initialNavigation.remainingPathCost() > 0.0D
                            && initialNavigation.remainingPathCost()
                                    <= initialNavigation.pathCost(),
                    "Navigation snapshot did not expose its reviewed route cost");
            DynamicWallState state = new DynamicWallState();

            helper.runAtTickTime(4, () -> fixture.checked(() -> {
                NavigationSnapshot navigation = bot.getActionPack().navigationSnapshot();
                require(navigation.requestId() == requestId
                                && navigation.state() == NavigationState.FOLLOWING,
                        "Navigation ended or was replaced before wall injection: "
                                + navigation.state());
                require(!sameColumn(bot.blockPosition(), blocked),
                        "Bot reached the selected wall cell before injection");
                fixture.setAbsolute(blocked, Blocks.STONE.defaultBlockState());
                fixture.setAbsolute(blocked.above(), Blocks.STONE.defaultBlockState());
                Standability.clearCache();
                state.wallPlaced = true;
            }));

            helper.onEachTick(() -> fixture.checked(() -> {
                NavigationSnapshot navigation = bot.getActionPack().navigationSnapshot();
                require(navigation.requestId() == requestId,
                        "Dynamic wall replaced request " + requestId
                                + " with " + navigation.requestId());
                if (!state.wallPlaced) {
                    return;
                }

                require(helper.getLevel().getBlockState(blocked).is(Blocks.STONE)
                                && helper.getLevel().getBlockState(blocked.above()).is(Blocks.STONE),
                        "Non-mutating navigation damaged the injected wall");
                require(helper.getLevel().getBlockState(longerAllowedSide).is(Blocks.STONE)
                                && helper.getLevel().getBlockState(
                                longerAllowedSide.above()).is(Blocks.STONE),
                        "Non-mutating navigation damaged the asymmetric detour wall");
                AABB wallColumn = new AABB(blocked).minmax(new AABB(blocked.above()));
                require(!bot.getBoundingBox().intersects(wallColumn),
                        "Bot collision box entered the injected wall");
                require(bot.blockPosition().getZ() >= start.getZ(),
                        "Dynamic replan discarded the skill-owned path constraint");
                state.sawReplan |= navigation.replanCount() > 0;
                state.maxLateralOffset = Math.max(
                        state.maxLateralOffset,
                        Math.abs(bot.getZ() - (start.getZ() + 0.5D)));

                if (navigation.state() == NavigationState.ARRIVED) {
                    require(state.sawReplan,
                            "Bot arrived without reporting an in-request replan");
                    require(navigation.replanCount() >= 1,
                            "Terminal navigation snapshot lost its replan count");
                    require(navigation.remainingNodes() == 0
                                    && navigation.remainingPathCost() == 0.0D,
                            "ARRIVED retained unfinished route cost");
                    require(!navigation.lastReplanReason().isBlank(),
                            "Terminal navigation snapshot lost its replan reason");
                    require(state.maxLateralOffset >= 0.45D,
                            "Bot did not measurably detour around the dynamic wall");
                    require(sameColumn(bot.blockPosition(), goal),
                            "ARRIVED was published away from the dynamic-wall goal");
                    require(bot.getActionPack().isPathExecutorIdle(),
                            "ARRIVED was published while the replanned executor was active");
                    fixture.succeed();
                    return;
                }
                require(navigation.state() != NavigationState.FAILED
                                && navigation.state() != NavigationState.CANCELLED
                                && navigation.state() != NavigationState.PREEMPTED,
                        "Dynamic-wall navigation ended as " + navigation.state()
                                + ": " + navigation.reason());
            }));

            helper.runAtTickTime(280, () -> fixture.checked(() -> {
                NavigationSnapshot navigation = bot.getActionPack().navigationSnapshot();
                throw new IllegalStateException(
                        "Dynamic-wall route timed out; bot=" + compact(bot.blockPosition())
                                + ", state=" + navigation.state()
                                + ", request=" + navigation.requestId()
                                + ", replans=" + navigation.replanCount()
                                + ", remaining=" + navigation.remainingNodes()
                                + ", reason=" + navigation.reason());
            }));
        });
    }

    public static void interactionPoseUsesCurrentCardinalSide(GameTestHelper helper) {
        run(helper, fixture -> {
            fixture.prepareFlat(FEET_Y, 4, 16, 4, 16);
            fixture.setRelative(CENTER, Blocks.OAK_LOG.defaultBlockState());
            AIPlayerEntity bot = fixture.spawnBot(
                    "PoseCardinal01", CENTER.relative(Direction.EAST));
            BlockPos absoluteTarget = fixture.absolute(CENTER);

            for (Direction direction : Direction.Plane.HORIZONTAL) {
                BlockPos relativeStand = CENTER.relative(direction);
                BlockPos stand = fixture.absolute(relativeStand);
                bot.setDeltaMovement(Vec3.ZERO);
                bot.teleportTo(
                        helper.getLevel(),
                        stand.getX() + 0.5D,
                        stand.getY(),
                        stand.getZ() + 0.5D,
                        Set.of(),
                        0.0F,
                        0.0F,
                        true);

                InteractionPosePlanner.InteractionPose pose =
                        InteractionPosePlanner.plan(bot, absoluteTarget)
                                .orElseThrow(() -> new IllegalStateException(
                                        "No interaction pose from " + direction));
                require(pose.currentPosition(),
                        "Planner moved away from an already valid " + direction + " pose");
                require(pose.stand().equals(stand),
                        "Planner selected " + pose.stand() + " instead of current " + stand);
                require(pose.pathCost() == 0.0D && pose.path().isEmpty(),
                        "Current interaction pose unexpectedly carried navigation cost");
                require(pose.face() == direction,
                        "Expected target face " + direction + " but got " + pose.face());
            }

            // Reach and line of sight do not make an unsafe current column a valid work pose.
            // This catches the fast-path regression where pose planning skipped dry standability.
            BlockPos unsafeRelative = CENTER.relative(Direction.EAST);
            BlockPos unsafe = fixture.absolute(unsafeRelative);
            fixture.setRelative(unsafeRelative.below(), Blocks.WATER.defaultBlockState());
            Standability.clearCache();
            bot.setDeltaMovement(Vec3.ZERO);
            bot.teleportTo(
                    helper.getLevel(),
                    unsafe.getX() + 0.5D,
                    unsafe.getY(),
                    unsafe.getZ() + 0.5D,
                    Set.of(),
                    0.0F,
                    0.0F,
                    true);
            require(InteractionPosePlanner.plan(bot, absoluteTarget).isEmpty(),
                    "Planner fabricated a path from a computationally snapped wet start");

            fixture.setRelative(CENTER.below(), Blocks.DIRT.defaultBlockState());
            for (int dy = 1; dy < 4; dy++) {
                fixture.setRelative(CENTER.above(dy), Blocks.OAK_LOG.defaultBlockState());
            }
            for (Direction direction : Direction.Plane.HORIZONTAL) {
                fixture.setRelative(
                        CENTER.above(3).relative(direction),
                        Blocks.OAK_LEAVES.defaultBlockState()
                                .setValue(LeavesBlock.PERSISTENT, false)
                                .setValue(LeavesBlock.DISTANCE, 1));
            }
            TreeSnapshot wetTree = TreeDetector.detect(
                    helper.getLevel(), absoluteTarget, Set.of(Blocks.OAK_LOG));
            require(wetTree.natural(), "Wet-pose fixture did not create a natural tree");
            TreeFellingSession wetSession = TreeFellingSession.fromSnapshot(
                            bot, wetTree, Set.of(Blocks.OAK_LOG))
                    .orElseThrow(() -> new IllegalStateException(
                            "Wet-pose natural tree did not create a felling session"));
            for (int tick = 0; tick < 3; tick++) {
                require(wetSession.tick(bot) == TreeFellingSession.Status.RUNNING,
                        "Unsafe current pose terminally failed the tree before safety recovery");
                require(bot.getActionPack().isMiningIdle()
                                && bot.getActionPack().isPathExecutorIdle(),
                        "Tree session started mining/navigation from an unsafe wet pose on tick "
                                + tick);
            }
            wetSession.cancel(bot);
        });
    }

    /** A visible tree five cells ahead must keep the work pose on the near side. */
    public static void interactionPoseKeepsFiveBlockApproachShort(GameTestHelper helper) {
        run(helper, fixture -> {
            fixture.prepareFlat(FEET_Y, 3, 18, 4, 16);
            fixture.setRelative(CENTER, Blocks.OAK_LOG.defaultBlockState());
            BlockPos relativeStart = CENTER.relative(Direction.EAST, 5);
            AIPlayerEntity bot = fixture.spawnBot("PoseFiveAhead01", relativeStart);
            BlockPos target = fixture.absolute(CENTER);
            BlockPos start = fixture.absolute(relativeStart);

            InteractionPosePlanner.InteractionPose pose =
                    InteractionPosePlanner.plan(bot, target)
                            .orElseThrow(() -> new IllegalStateException(
                                    "No work pose for a visible tree five cells ahead"));
            require(!pose.currentPosition(),
                    "Five-cell approach was incorrectly treated as already in reach");
            require(pose.stand().getX() > target.getX(),
                    "Planner walked around the visible tree instead of keeping its near side: "
                            + compact(pose.stand()));
            double shortestReviewedCost = Math.max(
                    0.0D,
                    Math.abs(start.getX() - target.getX()) - 2.0D);
            require(pose.pathCost() <= shortestReviewedCost + 2.0D,
                    "Five-cell tree route exceeded shortest reviewed cost + 2; cost="
                            + pose.pathCost());
            require(InteractionPosePlanner.canInteractFrom(
                            bot,
                            pose.stand(),
                            target,
                            bot.getAttributeValue(
                                    net.minecraft.world.entity.ai.attributes.Attributes
                                            .BLOCK_INTERACTION_RANGE) + 0.5D),
                    "Selected near-side pose cannot see and reach the visible tree");
        });
    }

    public static void interactionPoseFallsBackWhenNearSideBlocked(GameTestHelper helper) {
        run(helper, fixture -> {
            fixture.prepareFlat(FEET_Y, 3, 18, 3, 17);
            fixture.setRelative(CENTER, Blocks.OAK_LOG.defaultBlockState());
            BlockPos blockedRelative = CENTER.relative(Direction.EAST);
            fixture.setRelative(blockedRelative, Blocks.STONE.defaultBlockState());
            fixture.setRelative(blockedRelative.above(), Blocks.STONE.defaultBlockState());

            AIPlayerEntity bot = fixture.spawnBot(
                    "PoseBlocked01", CENTER.relative(Direction.EAST, 4));
            BlockPos absoluteTarget = fixture.absolute(CENTER);
            BlockPos blocked = fixture.absolute(blockedRelative);
            InteractionPosePlanner.InteractionPose pose =
                    InteractionPosePlanner.plan(bot, absoluteTarget)
                            .orElseThrow(() -> new IllegalStateException(
                                    "No fallback interaction pose around blocked near side"));

            require(!pose.currentPosition(),
                    "Occluded start was incorrectly accepted as a direct interaction pose");
            require(!pose.stand().equals(blocked),
                    "Planner selected the occupied near-side column");
            require(Standability.isStandable(
                            helper.getLevel(), pose.stand(), TraversalPolicy.TASK_WALK_DRY),
                    "Fallback pose is not standable: " + pose.stand());
            require(!pose.path().isEmpty() && pose.pathCost() > 0.0D,
                    "Fallback pose did not include a real navigation path");
            require(pose.pathCost() <= 12.0D,
                    "Fallback route was unexpectedly expensive: " + pose.pathCost());
            require(InteractionPosePlanner.canInteractFrom(
                            bot,
                            pose.stand(),
                            absoluteTarget,
                            bot.getAttributeValue(
                                    net.minecraft.world.entity.ai.attributes.Attributes
                                    .BLOCK_INTERACTION_RANGE) + 0.5D),
                    "Fallback pose cannot actually see and reach the target");

            // Isolate one exact work stand whose unconstrained shortest path crosses a leaf. The
            // only ground-supported route is strictly longer. A planner that runs unconstrained
            // A* and merely post-filters its one winning path will return empty here; the P1
            // planner must apply the support predicate during expansion and find the alternative.
            fixture.setRelative(CENTER, Blocks.AIR.defaultBlockState());
            fixture.setRelative(blockedRelative, Blocks.AIR.defaultBlockState());
            fixture.setRelative(blockedRelative.above(), Blocks.AIR.defaultBlockState());
            BlockPos secondTargetRelative = new BlockPos(6, FEET_Y, 10);
            BlockPos allowedStandRelative = new BlockPos(8, FEET_Y, 10);
            BlockPos leafSupportedFeetRelative = new BlockPos(11, FEET_Y, 10);
            fixture.setRelative(secondTargetRelative, Blocks.OAK_LOG.defaultBlockState());
            fixture.setRelative(
                    leafSupportedFeetRelative.below(),
                    Blocks.OAK_LEAVES.defaultBlockState()
                            .setValue(LeavesBlock.PERSISTENT, false)
                            .setValue(LeavesBlock.DISTANCE, 1));
            BlockPos secondTarget = fixture.absolute(secondTargetRelative);
            BlockPos allowedStand = fixture.absolute(allowedStandRelative);
            Set<BlockPos> excludedStands = new java.util.HashSet<>();
            for (int dx = -2; dx <= 2; dx++) {
                for (int dy = -2; dy <= 2; dy++) {
                    for (int dz = -2; dz <= 2; dz++) {
                        excludedStands.add(secondTarget.offset(dx, dy, dz));
                    }
                }
            }
            excludedStands.remove(allowedStand);
            java.util.function.Predicate<BlockPos> independentSupport = feet ->
                    !helper.getLevel().getBlockState(feet.below()).is(BlockTags.LEAVES);

            PathfindingResult unconstrainedShortcut = new AStarPathfinder(
                    helper.getLevel(), bot.blockPosition(), allowedStand,
                    10_000, 250L, TraversalPolicy.TASK_WALK_DRY, 1.0D).findPath(true);
            require(unconstrainedShortcut.success()
                            && unconstrainedShortcut.path().stream().anyMatch(node ->
                            helper.getLevel().getBlockState(node.pos().below())
                                    .is(BlockTags.LEAVES)),
                    "Leaf fixture did not make the forbidden shortcut uniquely cheaper");

            InteractionPosePlanner.InteractionPose groundPose = InteractionPosePlanner.plan(
                            bot,
                            secondTarget,
                            excludedStands,
                            InteractionPosePlanner.PlanningBudget.bounded(8, 250L),
                            8,
                            Set.of(),
                            independentSupport,
                            candidate -> independentSupport.test(candidate.stand())
                                    && candidate.path().stream()
                                    .allMatch(node -> independentSupport.test(node.pos())))
                    .orElseThrow(() -> new IllegalStateException(
                            "Rejected leaf shortcut hid the legal ground route"));
            require(groundPose.stand().equals(allowedStand),
                    "Isolated support test selected an unreviewed work stand");
            require(groundPose.path().stream().noneMatch(node ->
                            helper.getLevel().getBlockState(node.pos().below())
                                    .is(BlockTags.LEAVES)),
                    "Constrained pose path still used leaf support");
            require(groundPose.pathCost() > unconstrainedShortcut.path()
                            .get(unconstrainedShortcut.path().size() - 1).gCost(),
                    "Ground alternative was not strictly costlier than the leaf shortcut");
        });
    }

    /** Player-built log geometry must never be promoted into a whole-tree work unit. */
    public static void placedLogStructureIsNotNaturalTree(GameTestHelper helper) {
        run(helper, fixture -> {
            fixture.prepareFlat(FEET_Y, 5, 16, 5, 15);
            BlockPos relativeRoot = new BlockPos(9, FEET_Y, 10);
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    fixture.setRelative(
                            relativeRoot.offset(dx, -1, dz),
                            Blocks.STONE.defaultBlockState());
                }
            }

            List<BlockPos> relativeLogs = List.of(
                    relativeRoot,
                    relativeRoot.above(),
                    relativeRoot.above(2),
                    relativeRoot.above(2).relative(Direction.EAST),
                    relativeRoot.above(2).relative(Direction.EAST, 2));
            for (BlockPos log : relativeLogs) {
                fixture.setRelative(log, Blocks.OAK_LOG.defaultBlockState());
            }
            List<BlockPos> relativeLeaves = List.of(
                    relativeRoot.above(3).relative(Direction.EAST),
                    relativeRoot.above(3).relative(Direction.EAST, 2));
            for (BlockPos leaf : relativeLeaves) {
                fixture.setRelative(
                        leaf,
                        Blocks.OAK_LEAVES.defaultBlockState()
                                .setValue(LeavesBlock.PERSISTENT, true)
                                .setValue(LeavesBlock.DISTANCE, 7));
            }

            AIPlayerEntity bot = fixture.spawnBot(
                    "StructureGuard01", relativeRoot.relative(Direction.WEST, 2));
            BlockPos absoluteRoot = fixture.absolute(relativeRoot);
            TreeSnapshot snapshot = TreeDetector.detect(
                    helper.getLevel(), absoluteRoot, Set.of(Blocks.OAK_LOG));

            require(!snapshot.truncated(),
                    "Small placed log structure was unexpectedly truncated");
            require(snapshot.logs().size() == relativeLogs.size(),
                    "Placed structure detector saw " + snapshot.logs().size()
                            + " logs instead of " + relativeLogs.size());
            require(!snapshot.natural(),
                    "Stone-founded structure with persistent leaves was classified as natural");
            require(TreeFellingSession.fromSnapshot(
                            bot, snapshot, Set.of(Blocks.OAK_LOG)).isEmpty(),
                    "Placed structure produced a destructive TreeFellingSession");

            for (BlockPos log : relativeLogs) {
                require(helper.getLevel().getBlockState(fixture.absolute(log)).is(Blocks.OAK_LOG),
                        "Tree guard changed placed log " + compact(fixture.absolute(log)));
            }
            for (BlockPos leaf : relativeLeaves) {
                var state = helper.getLevel().getBlockState(fixture.absolute(leaf));
                require(state.is(Blocks.OAK_LEAVES)
                                && state.getValue(LeavesBlock.PERSISTENT),
                        "Tree guard changed persistent leaf " + compact(fixture.absolute(leaf)));
            }
            require(TreeDetector.supportsWholeTreeDetection(Blocks.OAK_LOG.defaultBlockState()),
                    "Ordinary overworld logs were excluded from whole-tree detection");
            require(!TreeDetector.supportsWholeTreeDetection(
                            Blocks.STRIPPED_OAK_LOG.defaultBlockState())
                            && !TreeDetector.supportsWholeTreeDetection(
                            Blocks.CRIMSON_STEM.defaultBlockState())
                            && !TreeDetector.supportsWholeTreeDetection(
                            Blocks.WARPED_STEM.defaultBlockState())
                            && !TreeDetector.supportsWholeTreeDetection(
                            Blocks.BAMBOO_BLOCK.defaultBlockState()),
                    "A log-like family without overworld root+leaf topology entered tree felling");

            // Natural leaf proximity alone cannot turn a short player column into a tree.
            for (BlockPos log : relativeLogs) {
                fixture.setRelative(log, Blocks.AIR.defaultBlockState());
            }
            for (BlockPos leaf : relativeLeaves) {
                fixture.setRelative(leaf, Blocks.AIR.defaultBlockState());
            }
            fixture.setRelative(relativeRoot.below(), Blocks.DIRT.defaultBlockState());
            fixture.setRelative(relativeRoot, Blocks.OAK_LOG.defaultBlockState());
            fixture.setRelative(relativeRoot.above(), Blocks.OAK_LOG.defaultBlockState());
            List<BlockPos> shortCanopy = List.of(
                    relativeRoot.above(2).relative(Direction.EAST),
                    relativeRoot.above(2).relative(Direction.WEST));
            for (BlockPos leaf : shortCanopy) {
                fixture.setRelative(
                        leaf,
                        Blocks.OAK_LEAVES.defaultBlockState()
                                .setValue(LeavesBlock.PERSISTENT, false)
                                .setValue(LeavesBlock.DISTANCE, 1));
            }
            TreeSnapshot shortPillar = TreeDetector.detect(
                    helper.getLevel(), absoluteRoot, Set.of(Blocks.OAK_LOG));
            require(!shortPillar.natural()
                            && TreeFellingSession.fromSnapshot(
                            bot, shortPillar, Set.of(Blocks.OAK_LOG)).isEmpty(),
                    "Two-log player pillar borrowed nearby natural-leaf evidence");

            // A tree that cannot be completed from reviewed dry ground poses is rejected before
            // the first irreversible cut, never after its base has become a floating remainder.
            fixture.setRelative(relativeRoot, Blocks.AIR.defaultBlockState());
            fixture.setRelative(relativeRoot.above(), Blocks.AIR.defaultBlockState());
            for (BlockPos leaf : shortCanopy) {
                fixture.setRelative(leaf, Blocks.AIR.defaultBlockState());
            }
            List<BlockPos> tallLogs = new ArrayList<>();
            for (int dy = 0; dy < 8; dy++) {
                BlockPos log = relativeRoot.above(dy);
                fixture.setRelative(log, Blocks.OAK_LOG.defaultBlockState());
                tallLogs.add(fixture.absolute(log));
            }
            List<BlockPos> tallCanopy = List.of(
                    relativeRoot.above(9).relative(Direction.EAST),
                    relativeRoot.above(9).relative(Direction.WEST));
            for (BlockPos leaf : tallCanopy) {
                fixture.setRelative(
                        leaf,
                        Blocks.OAK_LEAVES.defaultBlockState()
                                .setValue(LeavesBlock.PERSISTENT, false)
                                .setValue(LeavesBlock.DISTANCE, 2));
            }
            BlockPos tallStand = fixture.absolute(
                    relativeRoot.relative(Direction.WEST, 3));
            bot.setDeltaMovement(Vec3.ZERO);
            bot.teleportTo(
                    helper.getLevel(),
                    tallStand.getX() + 0.5D,
                    tallStand.getY(),
                    tallStand.getZ() + 0.5D,
                    Set.of(),
                    0.0F,
                    0.0F,
                    true);
            TreeSnapshot tallTree = TreeDetector.detect(
                    helper.getLevel(), absoluteRoot, Set.of(Blocks.OAK_LOG));
            TreeFellingSession tallSession = TreeFellingSession.fromSnapshot(
                            bot, tallTree, Set.of(Blocks.OAK_LOG))
                    .orElseThrow(() -> new IllegalStateException(
                            "Tall natural fixture did not create a guarded session"));
            TreeFellingSession.Status tallStatus = TreeFellingSession.Status.RUNNING;
            for (int tick = 0; tick < 24 && tallStatus == TreeFellingSession.Status.RUNNING; tick++) {
                tallStatus = tallSession.tick(bot);
            }
            require(tallStatus == TreeFellingSession.Status.NEEDS_VERTICAL_ACCESS
                            || tallStatus == TreeFellingSession.Status.RETRYABLE_BLOCKED
                            || tallStatus == TreeFellingSession.Status.PLANNING_BUDGET_EXHAUSTED,
                    "Tall tree was not rejected by intact-tree preflight: " + tallStatus);
            require(tallSession.diagnostic().logsBroken() == 0,
                    "Tall tree preflight broke a base log before proving completion");
            for (BlockPos log : tallLogs) {
                require(helper.getLevel().getBlockState(log).is(Blocks.OAK_LOG),
                        "Tall preflight changed log " + compact(log));
            }

            // Positive player-placement evidence is authoritative even when an otherwise complete
            // pillar sits under another oak's natural canopy.
            for (int dy = 0; dy < 8; dy++) {
                fixture.setRelative(relativeRoot.above(dy), Blocks.AIR.defaultBlockState());
            }
            for (BlockPos leaf : tallCanopy) {
                fixture.setRelative(leaf, Blocks.AIR.defaultBlockState());
            }
            List<BlockPos> borrowedPillar = new ArrayList<>();
            bot.setItemInHand(
                    InteractionHand.MAIN_HAND, new ItemStack(Items.OAK_LOG, 1));
            BlockPos trackedRoot = fixture.absolute(relativeRoot);
            ActionResult placement = BuildAction.placeBlock(
                    bot,
                    trackedRoot.below(),
                    Direction.UP,
                    InteractionHand.MAIN_HAND,
                    "minecraft:oak_log",
                    null,
                    Map.of());
            require(placement.isSuccess(),
                    "Reviewed BlockItem placement failed at " + compact(trackedRoot)
                            + ": " + placement.reason());
            require(helper.getLevel().getBlockState(trackedRoot).is(Blocks.OAK_LOG),
                    "BlockItem placement did not create oak log " + compact(trackedRoot));
            require(PlayerPlacedLogLedger.INSTANCE.isKnownPlayerPlaced(
                            helper.getLevel(), trackedRoot),
                    "Loader did not apply BlockItem placement provenance at "
                            + compact(trackedRoot));
            require(bot.getItemInHand(InteractionHand.MAIN_HAND).isEmpty(),
                    "Survival BlockItem placement did not consume the held log");
            borrowedPillar.add(trackedRoot);
            for (int dy = 1; dy < 4; dy++) {
                BlockPos log = relativeRoot.above(dy);
                BlockPos absoluteLog = fixture.absolute(log);
                fixture.setRelative(log, Blocks.OAK_LOG.defaultBlockState());
                borrowedPillar.add(absoluteLog);
            }
            BlockPos foreignRoot = relativeRoot.relative(Direction.EAST, 4);
            placeOakTree(fixture, foreignRoot, 4);
            TreeSnapshot borrowedCanopy = TreeDetector.detect(
                    helper.getLevel(), absoluteRoot, Set.of(Blocks.OAK_LOG));
            require(borrowedCanopy.logs().equals(Set.copyOf(borrowedPillar)),
                    "Borrowed-canopy fixture merged disconnected oak components");
            require(!borrowedCanopy.natural()
                            && TreeFellingSession.fromSnapshot(
                            bot, borrowedCanopy, Set.of(Blocks.OAK_LOG)).isEmpty(),
                    "Tracked four-log player pillar borrowed a natural oak canopy");
            TreeSnapshot foreignNaturalTree = TreeDetector.detect(
                    helper.getLevel(), fixture.absolute(foreignRoot), Set.of(Blocks.OAK_LOG));
            require(foreignNaturalTree.natural(),
                    "Placement evidence on a disconnected pillar poisoned the real oak");
            for (BlockPos log : borrowedPillar) {
                require(helper.getLevel().getBlockState(log).is(Blocks.OAK_LOG),
                        "Borrowed-canopy guard changed player log " + compact(log));
            }
        });
    }

    /** A low player-built beam touching a natural trunk makes the whole component unsafe. */
    public static void naturalTreeWithAttachedLowBeamIsRejected(GameTestHelper helper) {
        run(helper, fixture -> {
            fixture.prepareFlat(FEET_Y, 5, 17, 5, 15);
            BlockPos relativeRoot = new BlockPos(9, FEET_Y, 10);
            List<BlockPos> absoluteTreeLogs = placeOakTree(fixture, relativeRoot, 4);
            var horizontalBeamState = Blocks.OAK_LOG.defaultBlockState()
                    .setValue(RotatedPillarBlock.AXIS, Direction.Axis.X);
            List<BlockPos> relativeBeamLogs = List.of(
                    relativeRoot.above(2).relative(Direction.EAST),
                    relativeRoot.above(2).relative(Direction.EAST, 2),
                    relativeRoot.above(2).relative(Direction.EAST, 3));
            for (BlockPos beamLog : relativeBeamLogs) {
                fixture.setPlayerPlacedRelative(beamLog, horizontalBeamState);
            }

            AIPlayerEntity bot = fixture.spawnBot(
                    "BeamGuard01", relativeRoot.relative(Direction.WEST, 2));
            BlockPos absoluteRoot = fixture.absolute(relativeRoot);
            Set<BlockPos> expectedComponent = java.util.stream.Stream.concat(
                            absoluteTreeLogs.stream(),
                            relativeBeamLogs.stream().map(fixture::absolute))
                    .collect(java.util.stream.Collectors.toUnmodifiableSet());
            TreeSnapshot snapshot = TreeDetector.detect(
                    helper.getLevel(), absoluteRoot, Set.of(Blocks.OAK_LOG));

            require(!snapshot.truncated(),
                    "Small tree-plus-beam component was unexpectedly truncated");
            require(snapshot.logs().equals(expectedComponent),
                    "Detector did not review the complete tree-plus-beam component; expected="
                            + expectedComponent.size() + ", actual=" + snapshot.logs().size());
            require(!snapshot.natural(),
                    "Natural oak with an attached low horizontal player beam was accepted");
            require(TreeFellingSession.fromSnapshot(
                            bot, snapshot, Set.of(Blocks.OAK_LOG)).isEmpty(),
                    "Attached player beam produced a destructive TreeFellingSession");

            for (BlockPos treeLog : absoluteTreeLogs) {
                require(helper.getLevel().getBlockState(treeLog)
                                .equals(Blocks.OAK_LOG.defaultBlockState()),
                        "Tree guard changed natural trunk log " + compact(treeLog));
            }
            for (BlockPos beamLog : relativeBeamLogs) {
                BlockPos absoluteBeamLog = fixture.absolute(beamLog);
                require(helper.getLevel().getBlockState(absoluteBeamLog)
                                .equals(horizontalBeamState),
                        "Tree guard changed attached player beam " + compact(absoluteBeamLog));
            }

            // Also reject a second dirt-founded support column attached to a one-wide natural
            // trunk. A real large vanilla trunk must present a complete 2x2 root footprint.
            for (BlockPos beamLog : relativeBeamLogs) {
                fixture.setRelative(beamLog, Blocks.AIR.defaultBlockState());
            }
            BlockPos relativeSupport = relativeRoot.relative(Direction.WEST);
            fixture.setRelative(relativeSupport.below(), Blocks.DIRT.defaultBlockState());
            fixture.setRelative(relativeSupport, Blocks.OAK_LOG.defaultBlockState());
            fixture.setRelative(relativeSupport.above(), Blocks.OAK_LOG.defaultBlockState());
            TreeSnapshot supportSnapshot = TreeDetector.detect(
                    helper.getLevel(), absoluteRoot, Set.of(Blocks.OAK_LOG));
            require(supportSnapshot.logs().size() == absoluteTreeLogs.size() + 2,
                    "Detector did not review the complete tree-plus-support component");
            require(!supportSnapshot.natural(),
                    "Natural oak with an attached partial root column was accepted");
            require(TreeFellingSession.fromSnapshot(
                            bot, supportSnapshot, Set.of(Blocks.OAK_LOG)).isEmpty(),
                    "Attached partial root column produced a destructive felling session");
            require(helper.getLevel().getBlockState(fixture.absolute(relativeSupport))
                            .is(Blocks.OAK_LOG)
                            && helper.getLevel().getBlockState(
                            fixture.absolute(relativeSupport.above())).is(Blocks.OAK_LOG),
                    "Tree guard changed the attached partial root column");

            // A same-species vertical extension is geometrically indistinguishable from a taller
            // vanilla trunk. Persistent BlockItem provenance is the hard safety boundary.
            fixture.setRelative(relativeSupport, Blocks.AIR.defaultBlockState());
            fixture.setRelative(relativeSupport.above(), Blocks.AIR.defaultBlockState());
            BlockPos relativeTopExtension = relativeRoot.above(4);
            fixture.setPlayerPlacedRelative(
                    relativeTopExtension, Blocks.OAK_LOG.defaultBlockState());
            TreeSnapshot extendedSnapshot = TreeDetector.detect(
                    helper.getLevel(), absoluteRoot, Set.of(Blocks.OAK_LOG));
            require(!extendedSnapshot.natural(),
                    "Tracked same-species trunk extension was classified as natural");
            require(TreeFellingSession.fromSnapshot(
                            bot, extendedSnapshot, Set.of(Blocks.OAK_LOG)).isEmpty(),
                    "Tracked same-species trunk extension produced a felling session");
            require(helper.getLevel().getBlockState(
                            fixture.absolute(relativeTopExtension)).is(Blocks.OAK_LOG),
                    "Placement-ledger guard changed the tracked trunk extension");
        });
    }

    /** A grounded 2x2 oak with diagonal branches is one tree; touching spruce is not. */
    public static void twoByTwoTreeWithBranchesRespectsExactSpecies(GameTestHelper helper) {
        run(helper, fixture -> {
            fixture.prepareFlat(FEET_Y, 4, 17, 4, 16);
            BlockPos relativeRoot = new BlockPos(10, FEET_Y, 10);
            List<BlockPos> relativeOakLogs = new ArrayList<>();
            for (int dx = 0; dx <= 1; dx++) {
                for (int dz = 0; dz <= 1; dz++) {
                    BlockPos trunkRoot = relativeRoot.offset(dx, 0, dz);
                    fixture.setRelative(trunkRoot.below(), Blocks.DIRT.defaultBlockState());
                    for (int dy = 0; dy < 4; dy++) {
                        BlockPos log = trunkRoot.above(dy);
                        fixture.setRelative(log, Blocks.OAK_LOG.defaultBlockState());
                        relativeOakLogs.add(log);
                    }
                }
            }
            List<BlockPos> diagonalBranches = List.of(
                    relativeRoot.offset(-1, 4, -1),
                    relativeRoot.offset(2, 4, 2));
            for (BlockPos branch : diagonalBranches) {
                fixture.setRelative(branch, Blocks.OAK_LOG.defaultBlockState());
                relativeOakLogs.add(branch);
            }

            BlockPos relativeSpruce = relativeRoot.relative(Direction.WEST);
            fixture.setRelative(relativeSpruce.below(), Blocks.DIRT.defaultBlockState());
            fixture.setRelative(relativeSpruce, Blocks.SPRUCE_LOG.defaultBlockState());
            fixture.setRelative(relativeSpruce.above(), Blocks.SPRUCE_LOG.defaultBlockState());

            List<BlockPos> relativeLeaves = List.of(
                    relativeRoot.offset(-2, 4, -1),
                    relativeRoot.offset(-1, 5, -2),
                    relativeRoot.offset(0, 5, -1),
                    relativeRoot.offset(1, 5, 2),
                    relativeRoot.offset(2, 5, 1),
                    relativeRoot.offset(3, 4, 2));
            for (BlockPos leaf : relativeLeaves) {
                fixture.setRelative(
                        leaf,
                        Blocks.OAK_LEAVES.defaultBlockState()
                                .setValue(LeavesBlock.PERSISTENT, false)
                                .setValue(LeavesBlock.DISTANCE, 2));
            }

            AIPlayerEntity bot = fixture.spawnBot(
                    "TwoByTwoTree01", relativeRoot.relative(Direction.NORTH, 2));
            BlockPos absoluteRoot = fixture.absolute(relativeRoot);
            BlockPos absoluteSpruce = fixture.absolute(relativeSpruce);
            Set<BlockPos> expectedOakLogs = relativeOakLogs.stream()
                    .map(fixture::absolute)
                    .collect(java.util.stream.Collectors.toUnmodifiableSet());
            TreeSnapshot snapshot = TreeDetector.detect(
                    helper.getLevel(), absoluteRoot, Set.of(Blocks.OAK_LOG, Blocks.SPRUCE_LOG));

            require(snapshot.natural(),
                    "Grounded 2x2 oak with diagonal branches was not classified as natural");
            require(!snapshot.truncated(),
                    "Grounded 2x2 oak snapshot was unexpectedly truncated");
            require(snapshot.id().root().equals(absoluteRoot),
                    "2x2 detector selected the wrong root: " + compact(snapshot.id().root()));
            require(snapshot.logs().equals(expectedOakLogs),
                    "Exact oak snapshot differed from expected component; expected="
                            + expectedOakLogs.size() + ", actual=" + snapshot.logs().size());
            require(!snapshot.logs().contains(absoluteSpruce)
                            && !snapshot.logs().contains(absoluteSpruce.above()),
                    "Exact oak detector absorbed adjacent spruce logs");
            require(InteractionPosePlanner.canInteractFromCurrent(bot, absoluteRoot),
                    "Ground-level 2x2 tree root was not reachable from open terrain");

            TreeFellingSession session = TreeFellingSession.fromSnapshot(
                            bot, snapshot, Set.of(Blocks.OAK_LOG, Blocks.SPRUCE_LOG))
                    .orElseThrow(() -> new IllegalStateException(
                            "Natural 2x2 oak did not create a TreeFellingSession"));
            require(session.committedTree().id().equals(snapshot.id()),
                    "TreeFellingSession changed the detected TreeId");
            require(session.remainingLogs().equals(expectedOakLogs),
                    "TreeFellingSession did not commit the exact oak component");
            require(session.tick(bot) == TreeFellingSession.Status.RUNNING,
                    "Ground-accessible 2x2 session failed during its first planning tick");
            require(helper.getLevel().getBlockState(absoluteSpruce).is(Blocks.SPRUCE_LOG)
                            && helper.getLevel().getBlockState(absoluteSpruce.above())
                                    .is(Blocks.SPRUCE_LOG),
                    "Exact oak session changed adjacent spruce logs");
            BlockPos replacedCommittedLog = absoluteRoot.above();
            fixture.setAbsolute(
                    replacedCommittedLog, Blocks.SPRUCE_LOG.defaultBlockState());
            require(session.tick(bot) == TreeFellingSession.Status.STALE_TREE,
                    "Broad log quota accepted a different species at a committed oak position");
            require(helper.getLevel().getBlockState(replacedCommittedLog).is(Blocks.SPRUCE_LOG),
                    "Stale-tree guard destroyed the replacement log");
        });
    }

    /** Executes the real movement controller through a sealed one-cell-wide L corridor. */
    public static void navigationExecutesForcedLCorner(GameTestHelper helper) {
        runAsync(helper, fixture -> {
            fixture.prepareFlat(FEET_Y, 3, 14, 3, 15);
            BlockPos startRelative = new BlockPos(5, FEET_Y, 6);
            BlockPos cornerRelative = new BlockPos(11, FEET_Y, 6);
            BlockPos goalRelative = new BlockPos(11, FEET_Y, 13);

            for (int x = 4; x <= 12; x++) {
                for (int z = 5; z <= 14; z++) {
                    boolean horizontalLeg = z == startRelative.getZ()
                            && x >= startRelative.getX() && x <= cornerRelative.getX();
                    boolean verticalLeg = x == cornerRelative.getX()
                            && z >= cornerRelative.getZ() && z <= goalRelative.getZ();
                    if (!horizontalLeg && !verticalLeg) {
                        fixture.setRelative(
                                new BlockPos(x, FEET_Y, z), Blocks.STONE.defaultBlockState());
                        fixture.setRelative(
                                new BlockPos(x, FEET_Y + 1, z), Blocks.STONE.defaultBlockState());
                    }
                }
            }

            AIPlayerEntity bot = fixture.spawnBot("ForcedCorner01", startRelative);
            BlockPos corner = fixture.absolute(cornerRelative);
            BlockPos goal = fixture.absolute(goalRelative);
            ActionResult started = bot.getActionPack().startNonMutatingPathTo(goal);
            require(!started.isFailed(),
                    "ActionPack rejected forced L route: " + started.reason());
            long requestId = bot.getActionPack().navigationSnapshot().requestId();
            ForcedCornerState state = new ForcedCornerState();

            helper.onEachTick(() -> fixture.checked(() -> {
                NavigationSnapshot navigation = bot.getActionPack().navigationSnapshot();
                require(navigation.requestId() == requestId,
                        "Forced L navigation was replaced by request " + navigation.requestId());
                state.sawFollowing |= navigation.state() == NavigationState.FOLLOWING;
                BlockPos current = bot.blockPosition();
                if (sameColumn(current, corner)) {
                    state.sawCorner = true;
                }
                if (navigation.state() == NavigationState.ARRIVED) {
                    require(state.sawFollowing,
                            "Navigation jumped directly to ARRIVED without following the path");
                    require(state.sawCorner,
                            "PathExecutor skipped the mandatory L-corner cell " + compact(corner));
                    require(sameColumn(current, goal),
                            "ARRIVED was published away from goal; bot=" + compact(current));
                    require(bot.getActionPack().isPathExecutorIdle(),
                            "ARRIVED was published while PathExecutor still owned movement");
                    require(goal.equals(navigation.resolvedGoal()),
                            "Resolved goal changed during the forced L route");
                    fixture.succeed();
                    return;
                }
                require(navigation.state() != NavigationState.FAILED
                                && navigation.state() != NavigationState.CANCELLED
                                && navigation.state() != NavigationState.PREEMPTED,
                        "Forced L navigation ended as " + navigation.state()
                                + ": " + navigation.reason());
            }));

            helper.runAtTickTime(390, () -> fixture.checked(() -> {
                NavigationSnapshot navigation = bot.getActionPack().navigationSnapshot();
                throw new IllegalStateException(
                        "Forced L route timed out; bot=" + compact(bot.blockPosition())
                                + ", state=" + navigation.state()
                                + ", node=" + compact(navigation.currentNode())
                                + ", remaining=" + navigation.remainingNodes()
                                + ", reason=" + navigation.reason());
            }));
        });
    }

    /** Quota completion and a safety pause must not discard a committed natural tree. */
    public static void gatherQuotaFinishesCommittedTree(GameTestHelper helper) {
        runAsync(helper, fixture -> {
            fixture.prepareFlat(FEET_Y, 3, 18, 4, 16);
            BlockPos firstRootRelative = new BlockPos(8, FEET_Y, 10);
            BlockPos secondRootRelative = new BlockPos(15, FEET_Y, 10);
            List<BlockPos> firstLogs = placeOakTree(fixture, firstRootRelative, 4);
            List<BlockPos> secondLogs = placeOakTree(fixture, secondRootRelative, 4);
            BlockPos firstRoot = fixture.absolute(firstRootRelative);

            AIPlayerEntity bot = fixture.spawnBot(
                    "TreeCommit01", firstRootRelative.relative(Direction.WEST));
            require(bot.getInventory().add(new ItemStack(Items.DIAMOND_AXE)),
                    "Could not equip the GameTest bot with an axe");
            GatherQuotaTask task = new GatherQuotaTask(Items.OAK_LOG, 1, true);
            TaskManager.INSTANCE.assign(
                    bot,
                    task,
                    TaskOrigin.of(TaskOrigin.Kind.VERIFY, "shared_gametest_tree_commit"));
            HoldTask safetyHold = new HoldTask();
            CommittedTreeState state = new CommittedTreeState();

            helper.onEachTick(() -> fixture.checked(() -> {
                TreeFellingSession.Diagnostic diagnostic = task.treeFellingDiagnostic();
                if (diagnostic != null) {
                    state.committed = true;
                    state.discoveredLogs = diagnostic.discoveredLogs();
                    if (state.committedTreeId == null) {
                        state.committedTreeId = diagnostic.tree();
                    } else {
                        require(state.committedTreeId.equals(diagnostic.tree()),
                                "Tree commitment changed across pause/resume from "
                                        + compact(state.committedTreeId.root()) + " to "
                                        + compact(diagnostic.tree().root()));
                    }
                    if (state.resumed) {
                        state.sawSameTreeAfterResume = true;
                    }
                    require(firstRoot.equals(diagnostic.tree().root()),
                            "Gather task committed the wrong tree root: "
                                    + compact(diagnostic.tree().root()));
                    require(diagnostic.discoveredLogs() == firstLogs.size(),
                            "Committed component contained " + diagnostic.discoveredLogs()
                                    + " logs instead of " + firstLogs.size());
                    if (!state.quotaInjected) {
                        require(diagnostic.remainingLogs() == firstLogs.size(),
                                "Tree changed before the committed quota probe could start");
                        // Make the quota transition deterministic. In normal play the first item
                        // entity is picked up while later logs are still being cut; injecting the
                        // same authoritative inventory change here avoids depending on pickup
                        // delay or entity-tick ordering on either loader.
                        require(bot.getInventory().add(new ItemStack(Items.OAK_LOG)),
                                "Could not inject the committed-tree quota probe");
                        state.quotaInjected = true;
                    }
                    if (!state.pauseStarted) {
                        state.excludedBeforePause = EpisodeMemory.INSTANCE
                                .excludedCount(bot.getUUID());
                        state.pauseStartTick = helper.getTick();
                        require(TaskManager.INSTANCE.pauseFor(
                                        bot,
                                        PauseOwner.SAFETY,
                                        "shared_gametest_tree_interrupt"),
                                "TaskManager rejected the committed-tree safety pause");
                        TaskManager.INSTANCE.assign(
                                bot,
                                safetyHold,
                                TaskOrigin.safety("shared_gametest_tree_interrupt"));
                        state.pauseStarted = true;
                        require(task.state() == TaskState.PAUSED,
                                "Committed gather did not enter PAUSED state");
                    }
                    if (HarvestCore.countInventoryItems(bot, Set.of(Items.OAK_LOG)) >= 1
                            && diagnostic.remainingLogs() > 0) {
                        state.sawQuotaBeforeCompletion = true;
                    }
                }

                if (state.pauseStarted && !state.resumed) {
                    require(EpisodeMemory.INSTANCE.excludedCount(bot.getUUID())
                                    == state.excludedBeforePause,
                            "Safety pause incorrectly added an unreachable tree exclusion");
                }
                if (state.pauseStarted && !state.resumed) {
                    require(diagnostic != null
                                    && state.committedTreeId.equals(diagnostic.tree()),
                            "Committed TreeId disappeared while the task was safety-paused");
                    // Automatic owners such as SAFETY live on the execution-stack frame;
                    // isPausedBy() intentionally reports only persistent USER/INVENTORY locks.
                    // The later resumeSafetyPause() call proves that this frame has SAFETY
                    // ownership instead of merely observing an implementation-private lock.
                    require(task.state() == TaskState.PAUSED
                                    && TaskManager.INSTANCE.pausedDepth(bot) == 1,
                            "Committed gather lost its expected SAFETY pause frame");
                    require(TaskManager.INSTANCE.getActive(bot).orElse(null) == safetyHold
                                    && TaskManager.INSTANCE.activeOrigin(bot)
                                    .map(TaskOrigin::safety)
                                    .orElse(false),
                            "The bounded safety task did not retain active ownership");
                    state.pauseObservedTicks++;
                    if (helper.getTick() - state.pauseStartTick >= 10L) {
                        TaskManager.INSTANCE.abort(bot);
                        require(TaskManager.INSTANCE.resumeSafetyPause(bot),
                                "TaskManager failed to resume the SAFETY pause");
                        state.resumed = true;
                        require(task.state() == TaskState.RUNNING
                                        && TaskManager.INSTANCE.getActive(bot).orElse(null) == task,
                                "Resumed gather did not regain active task ownership");
                    }
                }

                for (BlockPos secondLog : secondLogs) {
                    require(helper.getLevel().getBlockState(secondLog).is(Blocks.OAK_LOG),
                            "Committed felling crossed into the disconnected second tree at "
                                    + compact(secondLog));
                }

                if (task.state() == TaskState.FAILED || task.state() == TaskState.CANCELLED) {
                    throw new IllegalStateException(
                            "Gather task terminated as " + task.state()
                                    + ": " + task.failureReason());
                }
                if (task.state() != TaskState.COMPLETED
                        || TaskManager.INSTANCE.getActive(bot).isPresent()) {
                    return;
                }

                require(state.committed,
                        "Gather task never created a TreeFellingSession for the natural oak");
                require(state.discoveredLogs == firstLogs.size(),
                        "Tree commitment diagnostic was lost before validating all four logs");
                require(state.quotaInjected && state.sawQuotaBeforeCompletion,
                        "Exact quota was not observed while committed logs still remained");
                require(state.pauseStarted && state.resumed && state.pauseObservedTicks >= 10
                                && state.sawSameTreeAfterResume,
                        "Committed tree did not survive the required 10-tick safety pause");
                require(EpisodeMemory.INSTANCE.excludedCount(bot.getUUID())
                                == state.excludedBeforePause,
                        "Pause/resume polluted EpisodeMemory exclusions");
                for (BlockPos firstLog : firstLogs) {
                    require(helper.getLevel().getBlockState(firstLog).isAir(),
                            "Quota completed with a floating committed log at "
                                    + compact(firstLog));
                }
                require(HarvestCore.countInventoryItems(bot, Set.of(Items.OAK_LOG)) >= 1,
                        "Gather task completed without its exact oak-log quota");
                require(HarvestCore.nearestDropAnyOf(bot, Set.of(Items.OAK_LOG), 8.0D)
                                .isEmpty(),
                        "Committed-tree batch pickup left an observable oak-log drop behind");
                fixture.succeed();
            }));

            helper.runAtTickTime(740, () -> fixture.checked(() -> {
                TreeFellingSession.Diagnostic diagnostic = task.treeFellingDiagnostic();
                throw new IllegalStateException(
                        "Committed-tree gather timed out; task=" + task.state()
                                + ", description=" + task.describe()
                                + ", committed=" + state.committed
                                + ", quotaInjected=" + state.quotaInjected
                                + ", earlyQuota=" + state.sawQuotaBeforeCompletion
                                + ", pauseStarted=" + state.pauseStarted
                                + ", resumed=" + state.resumed
                                + ", pausedTicks=" + state.pauseObservedTicks
                                + ", excluded="
                                + EpisodeMemory.INSTANCE.excludedCount(bot.getUUID())
                                + ", remaining="
                                + (diagnostic == null ? "n/a" : diagnostic.remainingLogs())
                                + ", reason=" + task.failureReason());
            }));
        });
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

    private static List<BlockPos> placeOakTree(
            SharedGameTestFixture fixture, BlockPos relativeRoot, int height) {
        // TreeDetector deliberately requires a natural root substrate so player-built log
        // columns and tree houses cannot be promoted into whole-tree commitments.
        fixture.setRelative(relativeRoot.below(), Blocks.DIRT.defaultBlockState());
        List<BlockPos> absoluteLogs = new ArrayList<>(height);
        for (int dy = 0; dy < height; dy++) {
            BlockPos relativeLog = relativeRoot.above(dy);
            fixture.setRelative(relativeLog, Blocks.OAK_LOG.defaultBlockState());
            absoluteLogs.add(fixture.absolute(relativeLog));
        }
        int canopyY = relativeRoot.getY() + height - 1;
        for (int dy = 0; dy <= 1; dy++) {
            int radius = dy == 0 ? 2 : 1;
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (dy == 0 && dx == 0 && dz == 0) {
                        continue;
                    }
                    int distance = Math.min(7, Math.abs(dx) + Math.abs(dz) + dy);
                    fixture.setRelative(
                            new BlockPos(
                                    relativeRoot.getX() + dx,
                                    canopyY + dy,
                                    relativeRoot.getZ() + dz),
                            Blocks.OAK_LEAVES.defaultBlockState()
                                    .setValue(LeavesBlock.PERSISTENT, false)
                                    .setValue(LeavesBlock.DISTANCE, Math.max(1, distance)));
                }
            }
        }
        return List.copyOf(absoluteLogs);
    }

    private static boolean sameColumn(BlockPos first, BlockPos second) {
        return first != null && second != null
                && first.getX() == second.getX()
                && first.getZ() == second.getZ()
                && Math.abs(first.getY() - second.getY()) <= 1;
    }

    private static String compact(BlockPos position) {
        return position == null
                ? "n/a"
                : position.getX() + "," + position.getY() + "," + position.getZ();
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }

    @FunctionalInterface
    private interface Scenario {
        void run(SharedGameTestFixture fixture);
    }

    private static final class ForcedCornerState {
        private boolean sawFollowing;
        private boolean sawCorner;
    }

    private static final class DynamicWallState {
        private boolean wallPlaced;
        private boolean sawReplan;
        private double maxLateralOffset;
    }

    private static final class CommittedTreeState {
        private boolean committed;
        private boolean quotaInjected;
        private boolean sawQuotaBeforeCompletion;
        private boolean pauseStarted;
        private boolean resumed;
        private boolean sawSameTreeAfterResume;
        private int discoveredLogs;
        private int excludedBeforePause;
        private int pauseObservedTicks;
        private long pauseStartTick;
        private TreeSnapshot.TreeId committedTreeId;
    }
}
