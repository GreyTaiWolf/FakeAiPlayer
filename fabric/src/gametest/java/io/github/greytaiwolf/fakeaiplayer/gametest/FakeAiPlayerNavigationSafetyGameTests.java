package io.github.greytaiwolf.fakeaiplayer.gametest;

import io.github.greytaiwolf.fakeaiplayer.action.ActionResult;
import io.github.greytaiwolf.fakeaiplayer.entity.AIPlayerEntity;
import io.github.greytaiwolf.fakeaiplayer.manager.AIPlayerManager;
import io.github.greytaiwolf.fakeaiplayer.pathfinding.AStarPathfinder;
import io.github.greytaiwolf.fakeaiplayer.pathfinding.Node;
import io.github.greytaiwolf.fakeaiplayer.pathfinding.PathfindingResult;
import io.github.greytaiwolf.fakeaiplayer.pathfinding.Standability;
import io.github.greytaiwolf.fakeaiplayer.pathfinding.TraversalPolicy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Live navigation regression contracts for route invalidation, player-sized collision sweeps and
 * first-contact water rescue.
 *
 * <p>The empty GameTest template is smaller than these routes. Every block write therefore passes
 * through {@link WorldFixture}, which restores the exact prior state on both success and asserted
 * failure.</p>
 */
public final class FakeAiPlayerNavigationSafetyGameTests implements FabricGameTest {
    private static final int SEARCH_NODES = 10_000;
    private static final long SEARCH_MILLIS = 250L;

    @GameTest(
            template = FabricGameTest.EMPTY_STRUCTURE,
            batch = "fakeaiplayer_nav_dynamic_wall",
            timeoutTicks = 230)
    public void activePathReplansAroundANewlyPlacedWall(GameTestHelper context) {
        // Method name deliberately describes a wall that appears after ActionPack owns a live
        // executor. Keeping the fixture long makes it impossible for the bot to reach the chosen
        // middle node before tick 4.
        WorldFixture fixture = new WorldFixture(context, "NavWall01");
        try {
            BlockPos start = context.absolutePos(new BlockPos(1, 2, 1));
            BlockPos goal = start.offset(11, 0, 0);
            fixture.prepareFlat(start, -2, 13, -5, 5);
            Standability.clearCache();

            PathfindingResult initial = dryPath(context, start, goal);
            require(initial.success() && initial.path().size() >= 7,
                    "initial straight route was unavailable: " + initial.reason());
            BlockPos blocked = initial.path().get(initial.path().size() / 2).pos();
            require(!blocked.equals(start) && !blocked.equals(goal),
                    "dynamic-wall node resolved to a route endpoint");

            AIPlayerEntity bot = fixture.spawn(start);
            ActionResult started = bot.getActionPack().startNonMutatingPathTo(goal);
            require(!started.isFailed(), "ActionPack rejected initial route: " + started.reason());
            DynamicWallState state = new DynamicWallState();

            context.runAtTickTime(4, () -> fixture.checked(() -> {
                require(!bot.getActionPack().isPathExecutorIdle(),
                        "path executor completed or vanished before wall injection");
                fixture.set(blocked, Blocks.STONE.defaultBlockState());
                fixture.set(blocked.above(), Blocks.STONE.defaultBlockState());
                state.wallPlaced = true;
            }));

            context.onEachTick(() -> fixture.checked(() -> {
                if (!state.wallPlaced) {
                    return;
                }
                AABB wallColumn = new AABB(blocked).minmax(new AABB(blocked.above()));
                require(!bot.getBoundingBox().intersects(wallColumn),
                        "player collision box entered the newly placed wall");
                require(context.getLevel().getBlockState(blocked).is(Blocks.STONE)
                                && context.getLevel().getBlockState(blocked.above()).is(Blocks.STONE),
                        "non-mutating navigation removed the injected wall");
                state.maxLateralOffset = Math.max(
                        state.maxLateralOffset,
                        Math.abs(bot.getZ() - (start.getZ() + 0.5D)));
                if (arrivedExactly(bot, goal) && bot.getActionPack().isPathExecutorIdle()) {
                    require(state.maxLateralOffset >= 0.45D,
                            "bot reached the goal without a measurable detour around the wall");
                    fixture.succeed();
                }
            }));

            context.runAtTickTime(215, () -> fixture.checked(() -> {
                throw new IllegalStateException(
                        "dynamic wall route did not safely replan and arrive; bot="
                                + compact(bot.blockPosition())
                                + ", executorIdle=" + bot.getActionPack().isPathExecutorIdle());
            }));
        } catch (RuntimeException | AssertionError failure) {
            fixture.fail(failure);
        }
    }

    @GameTest(
            template = FabricGameTest.EMPTY_STRUCTURE,
            batch = "fakeaiplayer_nav_player_collision",
            timeoutTicks = 250)
    public void oneBlockDoorRejectsDiagonalCornerCutButRemainsNavigable(GameTestHelper context) {
        WorldFixture fixture = new WorldFixture(context, "NavCollision01");
        try {
            BlockPos start = context.absolutePos(new BlockPos(1, 2, 1));
            BlockPos goal = start.offset(10, 0, 6);
            BlockPos doorway = start.offset(5, 0, 3);
            fixture.prepareFlat(start, -2, 12, -4, 10);

            // A two-block-high barrier with one one-block-wide opening. A center-point-only sweep
            // is tempted to enter the opening diagonally past either neighboring wall column;
            // a 0.6 x 1.8 player sweep must line up and enter cardinally.
            for (int dz = -3; dz <= 9; dz++) {
                BlockPos wall = start.offset(5, 0, dz);
                if (wall.equals(doorway)) {
                    continue;
                }
                fixture.set(wall, Blocks.STONE.defaultBlockState());
                fixture.set(wall.above(), Blocks.STONE.defaultBlockState());
            }
            Standability.clearCache();

            PathfindingResult planned = dryPath(context, start, goal);
            require(planned.success(), "one-block doorway route failed: " + planned.reason());
            require(planned.path().stream().map(Node::pos).anyMatch(doorway::equals),
                    "planner bypassed the required one-block doorway");
            assertNoDiagonalCornerCut(context, planned.path());

            AIPlayerEntity bot = fixture.spawn(start);
            ActionResult started = bot.getActionPack().startNonMutatingPathTo(goal);
            require(!started.isFailed(), "ActionPack rejected doorway route: " + started.reason());
            DoorwayState state = new DoorwayState();

            context.onEachTick(() -> fixture.checked(() -> {
                require(context.getLevel().noBlockCollision(
                                bot, bot.getBoundingBox().deflate(1.0E-5D)),
                        "player collision box overlapped a wall while traversing the doorway");
                if (bot.blockPosition().getX() == doorway.getX()
                        && bot.blockPosition().getZ() == doorway.getZ()) {
                    state.enteredDoorway = true;
                }
                if (arrivedExactly(bot, goal) && bot.getActionPack().isPathExecutorIdle()) {
                    require(state.enteredDoorway,
                            "bot reached the far side without occupying the required doorway cell");
                    fixture.succeed();
                }
            }));

            context.runAtTickTime(235, () -> fixture.checked(() -> {
                throw new IllegalStateException(
                        "player-sized doorway route did not arrive; bot="
                                + compact(bot.blockPosition())
                                + ", enteredDoor=" + state.enteredDoorway
                                + ", executorIdle=" + bot.getActionPack().isPathExecutorIdle());
            }));
        } catch (RuntimeException | AssertionError failure) {
            fixture.fail(failure);
        }
    }

    @GameTest(
            template = FabricGameTest.EMPTY_STRUCTURE,
            batch = "fakeaiplayer_nav_accidental_water",
            timeoutTicks = 210)
    public void ordinaryPathCancelsOnFirstWaterContactAndStaysOutAfterRescue(GameTestHelper context) {
        WorldFixture fixture = new WorldFixture(context, "NavWater01");
        try {
            BlockPos start = context.absolutePos(new BlockPos(1, 3, 1));
            BlockPos goal = start.offset(11, 0, 0);
            BlockPos poolCenter = start.offset(5, -1, 0);
            fixture.prepareFlat(start, -2, 13, -5, 5);
            Standability.clearCache();

            AIPlayerEntity bot = fixture.spawn(start);
            ActionResult started = bot.getActionPack().startNonMutatingPathTo(goal);
            require(!started.isFailed(), "ordinary route failed before water injection: " + started.reason());
            WaterState state = new WaterState();

            context.runAtTickTime(5, () -> fixture.checked(() -> {
                require(!bot.getActionPack().isPathExecutorIdle(),
                        "ordinary path ended before accidental-water injection");
                // Recess the water one block below the dry floor. The surrounding floor contains
                // source updates while still giving NavSafety a reachable one-block-high bank.
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        BlockPos water = poolCenter.offset(dx, 0, dz);
                        fixture.set(water.below(), Blocks.STONE.defaultBlockState());
                        fixture.set(water, Blocks.WATER.defaultBlockState());
                    }
                }
                bot.setDeltaMovement(Vec3.ZERO);
                bot.teleportTo(
                        context.getLevel(),
                        poolCenter.getX() + 0.5D,
                        poolCenter.getY(),
                        poolCenter.getZ() + 0.5D,
                        Collections.emptySet(),
                        bot.getYRot(),
                        bot.getXRot(),
                        true);
                state.injected = true;
            }));

            context.onEachTick(() -> fixture.checked(() -> {
                if (!state.injected) {
                    return;
                }
                boolean inPoolWater = inPoolWater(context, bot, poolCenter);
                if (inPoolWater || bot.isInWater()) {
                    state.sawWater = true;
                    if (state.firstWaterTick < 0L) {
                        state.firstWaterTick = context.getTick();
                    }
                    if (state.exitedTick >= 0L) {
                        throw new IllegalStateException("bot re-entered the same pool after reaching dry ground");
                    }
                }

                if (!state.pathCancelled
                        && state.firstWaterTick >= 0L
                        && bot.getActionPack().isPathExecutorIdle()) {
                    state.pathCancelled = true;
                    require(context.getTick() - state.firstWaterTick <= 3L,
                            "ordinary path was not cancelled on first water contact");
                }

                if (state.sawWater && state.exitedTick < 0L
                        && !inPoolWater && !bot.isInWater() && bot.onGround()) {
                    state.exitedTick = context.getTick();
                    require(state.pathCancelled,
                            "bot reached shore before the unsafe ordinary route was cancelled");
                }

                if (state.exitedTick >= 0L && context.getTick() - state.exitedTick >= 25L) {
                    require(state.sawWater, "water fixture never registered contact");
                    require(state.pathCancelled, "ordinary route survived accidental water contact");
                    fixture.succeed();
                }
            }));

            context.runAtTickTime(195, () -> fixture.checked(() -> {
                throw new IllegalStateException(
                        "water rescue did not finish and remain dry; bot="
                                + compact(bot.blockPosition())
                                + ", sawWater=" + state.sawWater
                                + ", cancelled=" + state.pathCancelled
                                + ", exitedTick=" + state.exitedTick);
            }));
        } catch (RuntimeException | AssertionError failure) {
            fixture.fail(failure);
        }
    }

    private static PathfindingResult dryPath(GameTestHelper context, BlockPos start, BlockPos goal) {
        return new AStarPathfinder(
                context.getLevel(), start, goal, SEARCH_NODES, SEARCH_MILLIS,
                TraversalPolicy.TASK_WALK_DRY, 1.0D).findPath(true);
    }

    private static void assertNoDiagonalCornerCut(GameTestHelper context, List<Node> path) {
        for (int index = 1; index < path.size(); index++) {
            BlockPos from = path.get(index - 1).pos();
            BlockPos to = path.get(index).pos();
            int dx = to.getX() - from.getX();
            int dz = to.getZ() - from.getZ();
            if (Math.abs(dx) != 1 || Math.abs(dz) != 1 || to.getY() != from.getY()) {
                continue;
            }
            BlockPos xFlank = from.offset(dx, 0, 0);
            BlockPos zFlank = from.offset(0, 0, dz);
            require(passableColumn(context, xFlank) && passableColumn(context, zFlank),
                    "planner emitted a diagonal corner cut from "
                            + compact(from) + " to " + compact(to));
        }
    }

    private static boolean passableColumn(GameTestHelper context, BlockPos feet) {
        return context.getLevel().getBlockState(feet)
                        .getCollisionShape(context.getLevel(), feet).isEmpty()
                && context.getLevel().getBlockState(feet.above())
                        .getCollisionShape(context.getLevel(), feet.above()).isEmpty();
    }

    private static boolean arrivedExactly(AIPlayerEntity bot, BlockPos goal) {
        BlockPos current = bot.blockPosition();
        return current.getX() == goal.getX()
                && current.getZ() == goal.getZ()
                && Math.abs(current.getY() - goal.getY()) <= 1;
    }

    private static boolean inPoolWater(
            GameTestHelper context, AIPlayerEntity bot, BlockPos poolCenter) {
        BlockPos current = bot.blockPosition();
        return Math.abs(current.getX() - poolCenter.getX()) <= 1
                && Math.abs(current.getZ() - poolCenter.getZ()) <= 1
                && context.getLevel().getFluidState(current).is(FluidTags.WATER);
    }

    private static String compact(BlockPos pos) {
        return pos.getX() + "," + pos.getY() + "," + pos.getZ();
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }

    private static final class DynamicWallState {
        private boolean wallPlaced;
        private double maxLateralOffset;
    }

    private static final class DoorwayState {
        private boolean enteredDoorway;
    }

    private static final class WaterState {
        private boolean injected;
        private boolean sawWater;
        private boolean pathCancelled;
        private long firstWaterTick = -1L;
        private long exitedTick = -1L;
    }

    private static final class WorldFixture {
        private final GameTestHelper context;
        private final String botName;
        private final Map<BlockPos, BlockState> originalBlocks = new LinkedHashMap<>();
        private AIPlayerEntity bot;
        private boolean finished;

        private WorldFixture(GameTestHelper context, String botName) {
            this.context = context;
            this.botName = botName;
        }

        private AIPlayerEntity spawn(BlockPos position) {
            bot = AIPlayerManager.INSTANCE.spawn(
                            context.getLevel().getServer(),
                            botName,
                            context.getLevel(),
                            Vec3.atBottomCenterOf(position),
                            0.0F,
                            0.0F,
                            GameType.SURVIVAL,
                            null)
                    .orElseThrow(() -> new IllegalStateException(
                            "could not spawn navigation GameTest bot " + botName));
            return bot;
        }

        private void prepareFlat(
                BlockPos origin, int minDx, int maxDx, int minDz, int maxDz) {
            for (int dx = minDx; dx <= maxDx; dx++) {
                for (int dz = minDz; dz <= maxDz; dz++) {
                    BlockPos feet = origin.offset(dx, 0, dz);
                    set(feet.below(), Blocks.STONE.defaultBlockState());
                    set(feet, Blocks.AIR.defaultBlockState());
                    set(feet.above(), Blocks.AIR.defaultBlockState());
                }
            }
        }

        private void set(BlockPos position, BlockState state) {
            BlockPos immutable = position.immutable();
            originalBlocks.putIfAbsent(immutable, context.getLevel().getBlockState(immutable));
            context.getLevel().setBlockAndUpdate(immutable, state);
        }

        private void checked(Runnable assertion) {
            if (finished) {
                return;
            }
            try {
                assertion.run();
            } catch (RuntimeException | AssertionError failure) {
                fail(failure);
            }
        }

        private void succeed() {
            if (finished) {
                return;
            }
            finished = true;
            cleanup();
            context.succeed();
        }

        private void fail(Throwable failure) {
            if (finished) {
                return;
            }
            finished = true;
            cleanup();
            String message = failure.getMessage() == null ? failure.toString() : failure.getMessage();
            context.fail(message);
        }

        private void cleanup() {
            if (bot != null) {
                bot.getActionPack().stopAll();
                AIPlayerManager.INSTANCE.despawn(context.getLevel().getServer(), botName);
                bot = null;
            }
            List<Map.Entry<BlockPos, BlockState>> restore =
                    new ArrayList<>(originalBlocks.entrySet());
            Collections.reverse(restore);
            for (Map.Entry<BlockPos, BlockState> entry : restore) {
                context.getLevel().setBlockAndUpdate(entry.getKey(), entry.getValue());
            }
            originalBlocks.clear();
            Standability.clearCache();
        }
    }
}
