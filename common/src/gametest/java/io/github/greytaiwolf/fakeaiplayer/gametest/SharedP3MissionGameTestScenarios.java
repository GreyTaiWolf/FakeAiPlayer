package io.github.greytaiwolf.fakeaiplayer.gametest;

import io.github.greytaiwolf.fakeaiplayer.action.InventoryAction;
import io.github.greytaiwolf.fakeaiplayer.entity.AIPlayerEntity;
import io.github.greytaiwolf.fakeaiplayer.goal.Goal;
import io.github.greytaiwolf.fakeaiplayer.goal.GoalEvaluation;
import io.github.greytaiwolf.fakeaiplayer.goal.GoalExecutor;
import io.github.greytaiwolf.fakeaiplayer.goal.GoalResult;
import io.github.greytaiwolf.fakeaiplayer.mission.GoalSpec;
import io.github.greytaiwolf.fakeaiplayer.mission.MissionArbiter;
import io.github.greytaiwolf.fakeaiplayer.runtime.TaskOrigin;
import io.github.greytaiwolf.fakeaiplayer.runtime.PauseOwner;
import io.github.greytaiwolf.fakeaiplayer.task.AbstractTask;
import io.github.greytaiwolf.fakeaiplayer.task.CraftTask;
import io.github.greytaiwolf.fakeaiplayer.task.DigDownTask;
import io.github.greytaiwolf.fakeaiplayer.task.EquipLoadoutTask;
import io.github.greytaiwolf.fakeaiplayer.task.GatherQuotaTask;
import io.github.greytaiwolf.fakeaiplayer.task.HoldTask;
import io.github.greytaiwolf.fakeaiplayer.task.OreDigTask;
import io.github.greytaiwolf.fakeaiplayer.task.SmeltTask;
import io.github.greytaiwolf.fakeaiplayer.task.Task;
import io.github.greytaiwolf.fakeaiplayer.task.TaskAssignmentResult;
import io.github.greytaiwolf.fakeaiplayer.task.TaskManager;
import io.github.greytaiwolf.fakeaiplayer.task.TaskState;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;

/** Loader-neutral, world-backed contracts for the first Mission/Skill runtime slice. */
public final class SharedP3MissionGameTestScenarios {
    private static final int FEET_Y = 2;
    // Furnace + stone pickaxe need eleven cobblestone. The column is deliberately deeper so the
    // scenario proves those blocks came from DigDownTask instead of an arena-floor edge case.
    private static final int GOLDEN_FEET_Y = 14;
    private static final long GOLDEN_STALL_TICKS = 1_200L;

    private SharedP3MissionGameTestScenarios() {
    }

    /** A temporary survival reflex must return control to the exact Mission it interrupted. */
    public static void reflexResumesExactMission(GameTestHelper helper) {
        run(helper, fixture -> {
            fixture.prepareFlat(FEET_Y, 7, 13, 7, 13);
            AIPlayerEntity bot = fixture.spawnBot(
                    "P3Resume01", new BlockPos(10, FEET_Y, 10));
            HoldTask wrongDimension = new HoldTask();
            TaskAssignmentResult rejected = TaskManager.INSTANCE.assign(
                    bot,
                    wrongDimension,
                    new TaskOrigin(
                            TaskOrigin.Kind.MISSION,
                            UUID.randomUUID(),
                            null,
                            "p3_wrong_dimension_prestart",
                            GoalSpec.Source.AI_PROPOSAL,
                            50,
                            "minecraft:the_nether"));
            require(!rejected.started() && rejected.action() == MissionArbiter.Action.REJECT,
                    "Wrong-dimension Mission was not rejected before start: " + rejected);
            require(wrongDimension.state() == TaskState.PENDING
                            && TaskManager.INSTANCE.getActive(bot).isEmpty(),
                    "Wrong-dimension Mission produced start side effects");
            UUID missionId = UUID.randomUUID();
            HoldTask mission = new HoldTask();
            HoldTask reflex = new HoldTask();

            TaskAssignmentResult missionStart = TaskManager.INSTANCE.assign(
                    bot, mission, TaskOrigin.mission(missionId, "p3_resume_contract"));
            require(missionStart.started() && mission.state() == TaskState.RUNNING,
                    "Mission did not start: " + missionStart.reason());

            TaskAssignmentResult reflexStart = TaskManager.INSTANCE.assign(
                    bot,
                    reflex,
                    TaskOrigin.of(TaskOrigin.Kind.REFLEX, "p3_temporary_reflex"));
            require(reflexStart.started(), "Reflex did not preempt: " + reflexStart.reason());
            require(mission.state() == TaskState.PAUSED,
                    "Preempted Mission was not paused");
            require(TaskManager.INSTANCE.pausedDepth(bot) == 1,
                    "Expected one Mission frame, got " + TaskManager.INSTANCE.pausedDepth(bot));
            require(TaskManager.INSTANCE.isMissionAutomaticallyPaused(bot, missionId),
                    "Mission pause ownership was not queryable");

            TaskManager.INSTANCE.abort(bot);
            require(TaskManager.INSTANCE.getActive(bot).orElse(null) == mission,
                    "Aborting the reflex did not restore the exact Mission Task");
            require(mission.state() == TaskState.RUNNING,
                    "Restored Mission is not running: " + mission.state());
            require(TaskManager.INSTANCE.pausedDepth(bot) == 0,
                    "SYSTEM pause frame leaked after reflex abort");
            require(TaskManager.INSTANCE.activeOrigin(bot)
                            .map(origin -> missionId.equals(origin.missionId()))
                            .orElse(false),
                    "Restored Mission lost its origin identity");
            require(TaskManager.INSTANCE.consumeMissionInterruption(bot, missionId),
                    "Goal layer could not observe the completed interruption");
            require(!TaskManager.INSTANCE.consumeMissionInterruption(bot, missionId),
                    "Mission interruption latch was not single-consumer");
        });
    }

    /** A reflex that fails during start must not publish an interruption that never took ownership. */
    public static void failedReflexStartDoesNotLatchMissionInterruption(GameTestHelper helper) {
        run(helper, fixture -> {
            fixture.prepareFlat(FEET_Y, 7, 13, 7, 13);
            AIPlayerEntity bot = fixture.spawnBot(
                    "P3StartFail01", new BlockPos(10, FEET_Y, 10));
            UUID missionId = UUID.randomUUID();
            HoldTask mission = new HoldTask();

            TaskAssignmentResult missionStart = TaskManager.INSTANCE.assign(
                    bot, mission, TaskOrigin.mission(missionId, "p3_failed_interrupt_contract"));
            require(missionStart.started() && mission.state() == TaskState.RUNNING,
                    "Mission did not start: " + missionStart.reason());

            expectFailure(() -> TaskManager.INSTANCE.assign(
                    bot,
                    new ThrowingStartTask(),
                    TaskOrigin.of(TaskOrigin.Kind.REFLEX, "p3_failed_reflex_start")));

            require(TaskManager.INSTANCE.getActive(bot).orElse(null) == mission,
                    "Failed reflex did not restore the exact Mission Task");
            require(mission.state() == TaskState.RUNNING,
                    "Failed reflex left Mission in " + mission.state());
            require(TaskManager.INSTANCE.pausedDepth(bot) == 0,
                    "Failed reflex leaked a pause frame");
            require(TaskManager.INSTANCE.activeOrigin(bot)
                            .map(origin -> missionId.equals(origin.missionId()))
                            .orElse(false),
                    "Failed reflex changed Mission ownership");
            require(!TaskManager.INSTANCE.consumeMissionInterruption(bot, missionId),
                    "Failed reflex published a false Mission interruption");

            require(TaskManager.INSTANCE.pauseFor(
                            bot, PauseOwner.SYSTEM, "p3_direct_pause_contract"),
                    "Direct pauseFor did not pause the restored Mission");
            require(TaskManager.INSTANCE.consumeMissionInterruption(bot, missionId),
                    "Direct pauseFor no longer publishes its real Mission interruption");
            require(TaskManager.INSTANCE.resumeSystemPause(bot)
                            && TaskManager.INSTANCE.getActive(bot).orElse(null) == mission,
                    "Direct pauseFor interruption did not resume the exact Mission");
        });
    }

    /** Goal completion means equipment is in authoritative slots, not merely in inventory. */
    public static void equipLoadoutUsesAuthoritativeSlots(GameTestHelper helper) {
        runAsync(helper, fixture -> {
            fixture.prepareFlat(FEET_Y, 7, 13, 7, 13);
            AIPlayerEntity bot = fixture.spawnBot(
                    "P3Armor01", new BlockPos(10, FEET_Y, 10));
            bot.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.GOLDEN_HELMET));
            require(bot.getInventory().add(new ItemStack(Items.IRON_HELMET)),
                    "Could not seed iron helmet");
            require(bot.getInventory().add(new ItemStack(Items.IRON_CHESTPLATE)),
                    "Could not seed iron chestplate");
            require(bot.getInventory().add(new ItemStack(Items.IRON_LEGGINGS)),
                    "Could not seed iron leggings");
            require(bot.getInventory().add(new ItemStack(Items.IRON_BOOTS)),
                    "Could not seed iron boots");
            require(bot.getInventory().add(new ItemStack(Items.IRON_AXE)),
                    "Could not seed competing iron axe");
            require(bot.getInventory().add(new ItemStack(Items.IRON_SWORD)),
                    "Could not seed iron sword");

            EquipLoadoutTask task = new EquipLoadoutTask();
            TaskAssignmentResult assignment = TaskManager.INSTANCE.assign(
                    bot,
                    task,
                    TaskOrigin.of(TaskOrigin.Kind.VERIFY, "p3_authoritative_loadout"));
            require(assignment.started(), "Equip Skill did not start: " + assignment.reason());

            AIPlayerEntity offhandBot = fixture.spawnBot(
                    "P3Offhand01", new BlockPos(12, FEET_Y, 10));
            offhandBot.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.IRON_HELMET));
            offhandBot.setItemSlot(EquipmentSlot.CHEST, new ItemStack(Items.IRON_CHESTPLATE));
            offhandBot.setItemSlot(EquipmentSlot.LEGS, new ItemStack(Items.IRON_LEGGINGS));
            offhandBot.setItemSlot(EquipmentSlot.FEET, new ItemStack(Items.IRON_BOOTS));
            offhandBot.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_AXE));
            offhandBot.setItemSlot(EquipmentSlot.OFFHAND, new ItemStack(Items.IRON_SWORD));
            EquipLoadoutTask offhandTask = new EquipLoadoutTask();
            TaskAssignmentResult offhandAssignment = TaskManager.INSTANCE.assign(
                    offhandBot,
                    offhandTask,
                    TaskOrigin.of(TaskOrigin.Kind.VERIFY, "p3_offhand_sword"));
            require(offhandAssignment.started(),
                    "Offhand Equip Skill did not start: " + offhandAssignment.reason());

            helper.onEachTick(() -> fixture.checked(() -> {
                if (task.state() == TaskState.FAILED || task.state() == TaskState.CANCELLED) {
                    throw new IllegalStateException(
                            "Equip Skill ended as " + task.state() + ": " + task.failureReason());
                }
                if (offhandTask.state() == TaskState.FAILED
                        || offhandTask.state() == TaskState.CANCELLED) {
                    throw new IllegalStateException(
                            "Offhand Equip Skill ended as " + offhandTask.state()
                                    + ": " + offhandTask.failureReason());
                }
                if (task.state() != TaskState.COMPLETED
                        || offhandTask.state() != TaskState.COMPLETED) {
                    return;
                }
                require(bot.getItemBySlot(EquipmentSlot.HEAD).is(Items.IRON_HELMET),
                        "Equal-defense golden helmet was not upgraded to iron");
                require(bot.getItemBySlot(EquipmentSlot.CHEST).is(Items.IRON_CHESTPLATE),
                        "Iron chestplate is not equipped");
                require(bot.getItemBySlot(EquipmentSlot.LEGS).is(Items.IRON_LEGGINGS),
                        "Iron leggings are not equipped");
                require(bot.getItemBySlot(EquipmentSlot.FEET).is(Items.IRON_BOOTS),
                        "Iron boots are not equipped");
                require(bot.getItemBySlot(EquipmentSlot.MAINHAND).is(Items.IRON_SWORD),
                        "Iron sword is owned but not equipped in the main hand");
                require(EquipLoadoutTask.ready(bot),
                        "Authoritative loadout verifier disagreed with equipment slots");
                require(offhandBot.getItemBySlot(EquipmentSlot.MAINHAND).is(Items.IRON_SWORD),
                        "Offhand sword was not moved into the main hand");
                require(offhandBot.getItemBySlot(EquipmentSlot.OFFHAND).is(Items.IRON_AXE),
                        "Offhand sword swap lost the previous main-hand axe");
                require(EquipLoadoutTask.ready(offhandBot),
                        "Offhand loadout did not pass authoritative verification");
                fixture.succeed();
            }));

            helper.runAtTickTime(60, () -> fixture.checked(() -> {
                throw new IllegalStateException(
                        "Equip Skills timed out; inventory=" + task.state()
                                + ":" + task.failureReason()
                                + ", offhand=" + offhandTask.state()
                                + ":" + offhandTask.failureReason());
            }));
        });
    }

    /** Throwing Task hooks must not strand work between the active slot and pause stack. */
    public static void pauseResumeFailuresAreTransactional(GameTestHelper helper) {
        run(helper, fixture -> {
            fixture.prepareFlat(FEET_Y, 7, 13, 7, 13);
            AIPlayerEntity bot = fixture.spawnBot(
                    "P3Txn01", new BlockPos(10, FEET_Y, 10));

            ThrowingTransitionTask pauseFailure = new ThrowingTransitionTask(true, false);
            require(TaskManager.INSTANCE.assign(
                            bot,
                            pauseFailure,
                            TaskOrigin.mission(UUID.randomUUID(), "p3_pause_failure"))
                    .started(), "Pause-failure fixture did not start");
            expectFailure(() -> TaskManager.INSTANCE.pauseFor(
                    bot, PauseOwner.SYSTEM, "p3_injected_pause_failure"));
            require(TaskManager.INSTANCE.getActive(bot).orElse(null) == pauseFailure,
                    "Throwing pause removed the active Task");
            require(pauseFailure.state() == TaskState.RUNNING,
                    "Throwing pause left Task state as " + pauseFailure.state());
            require(TaskManager.INSTANCE.pausedDepth(bot) == 0,
                    "Throwing pause leaked an execution frame");

            TaskManager.INSTANCE.resetToIdle(bot);
            ThrowingTransitionTask resumeFailure = new ThrowingTransitionTask(false, true);
            require(TaskManager.INSTANCE.assign(
                            bot,
                            resumeFailure,
                            TaskOrigin.mission(UUID.randomUUID(), "p3_resume_failure"))
                    .started(), "Resume-failure fixture did not start");
            require(TaskManager.INSTANCE.pauseFor(
                            bot, PauseOwner.USER, "p3_prepare_resume_failure"),
                    "Resume-failure fixture did not pause");
            expectFailure(() -> TaskManager.INSTANCE.resumeOwnedPause(bot, PauseOwner.USER));
            require(TaskManager.INSTANCE.getActive(bot).isEmpty(),
                    "Throwing resume installed a half-resumed active Task");
            require(resumeFailure.state() == TaskState.PAUSED,
                    "Throwing resume left Task state as " + resumeFailure.state());
            require(TaskManager.INSTANCE.pausedDepth(bot) == 1,
                    "Throwing resume consumed the preserved frame");
            require(TaskManager.INSTANCE.isUserPaused(bot),
                    "Throwing resume released the persistent USER lock");
        });
    }

    /** A broken Skill tick must become an owned failure instead of escaping the server loop. */
    public static void taskTickFailureIsContained(GameTestHelper helper) {
        runAsync(helper, fixture -> {
            fixture.prepareFlat(FEET_Y, 7, 13, 7, 13);
            AIPlayerEntity bot = fixture.spawnBot(
                    "P3TickFail01", new BlockPos(10, FEET_Y, 10));
            ThrowingTickTask task = new ThrowingTickTask();
            require(TaskManager.INSTANCE.assign(
                            bot,
                            task,
                            TaskOrigin.mission(UUID.randomUUID(), "p3_tick_failure"))
                    .started(), "Tick-failure fixture did not start");

            helper.onEachTick(() -> fixture.checked(() -> {
                if (task.state() != TaskState.FAILED) {
                    return;
                }
                require(task.failureReason().equals("tick_failed:injected_tick_failure"),
                        "Tick failure lost its structured reason: " + task.failureReason());
                require(TaskManager.INSTANCE.getActive(bot).isEmpty(),
                        "Failed tick Task remained in the active slot");
                fixture.succeed();
            }));
            helper.runAtTickTime(40, () -> fixture.checked(() -> {
                throw new IllegalStateException(
                        "Tick failure was not contained; state=" + task.state());
            }));
        });
    }

    /**
     * An empty survival inventory must reach an iron ingot through the real Mission executor:
     * fell world trees, craft both pickaxe tiers and a furnace, mine stone and iron, then smelt.
     * The arena controls resource placement but never injects a tool, station, fuel or product.
     */
    public static void goldenSurvivalChainStartsFromEmptyInventory(GameTestHelper helper) {
        runAsync(helper, fixture -> {
            List<BlockPos> stoneCells = prepareGoldenArena(fixture);
            List<BlockPos> treeLogs = new ArrayList<>();
            // DigDownTask opens its staircase northward first. Keeping the trees in the southern
            // half means every legitimate post-felling work pose still has ample stone ahead.
            treeLogs.addAll(placeOakTree(fixture, new BlockPos(5, GOLDEN_FEET_Y, 15), 4));
            treeLogs.addAll(placeOakTree(fixture, new BlockPos(15, GOLDEN_FEET_Y, 15), 4));
            BlockPos ironOre = fixture.absolute(new BlockPos(10, GOLDEN_FEET_Y, 10));
            fixture.setAbsolute(ironOre, Blocks.IRON_ORE.defaultBlockState());

            AIPlayerEntity bot = fixture.spawnBot(
                    "P3Golden01", new BlockPos(10, GOLDEN_FEET_Y, 14));
            clearInventory(bot);
            bot.setHealth(bot.getMaxHealth());
            bot.getFoodData().setFoodLevel(20);
            bot.getFoodData().setSaturation(5.0F);

            require(bot.getInventory().isEmpty(),
                    "Golden-chain bot did not start with an empty inventory");
            require(noGoldenChainSupplies(bot),
                    "Golden-chain fixture injected a tool, furnace, fuel or product");

            Goal goal = new Goal.HaveItem(Items.IRON_INGOT, 1);
            long resultSequence = GoalExecutor.INSTANCE.lastResult(bot)
                    .map(GoalResult::sequence)
                    .orElse(0L);
            int initialStoneCount = countBlocks(helper, stoneCells, Blocks.STONE);
            int initialLogCount = countBlocks(helper, treeLogs, Blocks.OAK_LOG);
            require(initialLogCount == 8,
                    "Golden-chain fixture has " + initialLogCount + " logs instead of 8");
            require(initialStoneCount >= 11,
                    "Golden-chain fixture does not contain enough stone");
            require(GoalExecutor.INSTANCE.submit(bot, goal, GoalSpec.Source.PLAYER_COMMAND),
                    "Golden-chain Mission submission failed");

            GoalSpec submitted = GoalExecutor.INSTANCE.activeGoalSpec(bot)
                    .orElseThrow(() -> new IllegalStateException(
                            "Golden-chain Mission has no active GoalSpec"));
            require(submitted.source() == GoalSpec.Source.PLAYER_COMMAND,
                    "Golden-chain GoalSpec lost its player-command source");
            require("minecraft:overworld".equals(submitted.dimension()),
                    "Golden-chain GoalSpec bound the wrong dimension: " + submitted.dimension());

            GoldenChainState state = new GoldenChainState(helper.getTick());
            helper.onEachTick(() -> fixture.checked(() -> {
                long tick = helper.getTick();
                observeGoldenChain(helper, bot, treeLogs, ironOre, state, tick);

                GoalResult result = GoalExecutor.INSTANCE.resultAfter(bot, resultSequence)
                        .orElse(null);
                if (result == null) {
                    require(tick - state.lastProgressTick <= GOLDEN_STALL_TICKS,
                            "Golden chain made no observable progress for "
                                    + GOLDEN_STALL_TICKS + " ticks; "
                                    + goldenDiagnostic(bot, state));
                    return;
                }
                require(result.goal().equals(goal),
                        "Golden chain published a result for a different goal: " + result.goal());
                require(result.status() == GoalResult.Status.COMPLETED,
                        "Golden chain ended as " + result.status() + ": " + result.reason());
                require(result.evaluation().state() == GoalEvaluation.State.SATISFIED,
                        "Completed golden chain has unsatisfied evaluation: "
                                + result.evaluation());
                require(result.skippedSteps().isEmpty(),
                        "Golden chain silently skipped steps: " + result.skippedSteps());
                require(state.missionId != null && state.missionId.equals(result.missionId()),
                        "Golden chain result lost the Mission identity observed by its Skills");
                require(!GoalExecutor.INSTANCE.hasActivePlan(bot),
                        "Completed golden chain left an active Mission");
                require(GoalExecutor.INSTANCE.queuedGoalCount(bot) == 0,
                        "Completed golden chain left queued Missions");
                require(TaskManager.INSTANCE.getActive(bot).isEmpty()
                                && TaskManager.INSTANCE.pausedDepth(bot) == 0,
                        "Completed golden chain leaked active or paused Tasks");
                require(!bot.getActionPack().hasActiveActions(),
                        "Completed golden chain leaked a movement, mining or pickup controller");
                require(bot.isAlive(), "Golden-chain bot died before completion");
                require(InventoryAction.countItem(bot, Items.IRON_INGOT) >= 1,
                        "Completed golden chain has no authoritative iron ingot");
                require(InventoryAction.countItem(bot, Items.CRAFTING_TABLE) >= 1,
                        "Golden chain did not retain its crafted crafting table");
                require(InventoryAction.countItem(bot, Items.WOODEN_PICKAXE) >= 1,
                        "Golden chain did not craft a wooden pickaxe");
                require(InventoryAction.countItem(bot, Items.STONE_PICKAXE) >= 1,
                        "Golden chain did not craft a stone pickaxe");
                require(initialLogCount - countBlocks(helper, treeLogs, Blocks.OAK_LOG) >= 3,
                        "Golden chain did not harvest the minimum wood needed for its stations and tools");
                require(initialStoneCount - countBlocks(helper, stoneCells, Blocks.STONE) >= 11,
                        "Golden chain did not mine the 8 furnace + 3 stone-pickaxe blocks");
                require(!helper.getLevel().getBlockState(ironOre).is(Blocks.IRON_ORE),
                        "Golden chain completed without mining the fixture iron ore");
                require(state.sawRawIron,
                        "Golden chain smelted without an observed natural raw-iron pickup");
                require(hasFurnace(helper, fixture),
                        "Golden chain produced iron without placing its crafted furnace");
                require(state.sawGather && state.sawCraft && state.sawDigDown
                                && state.sawOreDig && state.sawSmelt,
                        "Golden chain missed a real Skill: " + state.milestones());
                require(state.gatherTick < state.craftTick
                                && state.craftTick < state.digDownTick
                                && state.digDownTick < state.oreDigTick
                                && state.oreDigTick < state.smeltTick,
                        "Golden-chain Skills ran out of order: " + state.milestones());
                require(state.woodenPickaxeTick >= 0
                                && state.woodenPickaxeTick <= state.digDownTick,
                        "Wooden pickaxe was not observed before or when stone mining started");
                require(state.stonePickaxeTick >= 0
                                && state.stonePickaxeTick <= state.oreDigTick,
                        "Stone pickaxe was not observed before or when iron mining started");
                fixture.succeed();
            }));

            helper.runAtTickTime(5_800, () -> fixture.checked(() -> {
                throw new IllegalStateException(
                        "Golden chain timed out; " + goldenDiagnostic(bot, state));
            }));
        });
    }

    private static List<BlockPos> prepareGoldenArena(SharedGameTestFixture fixture) {
        List<BlockPos> stoneCells = new ArrayList<>();
        for (int x = 2; x <= 18; x++) {
            for (int z = 2; z <= 18; z++) {
                for (int y = 1; y < GOLDEN_FEET_Y; y++) {
                    BlockPos relative = new BlockPos(x, y, z);
                    fixture.setRelative(relative, Blocks.STONE.defaultBlockState());
                    stoneCells.add(fixture.absolute(relative));
                }
                fixture.setRelative(
                        new BlockPos(x, GOLDEN_FEET_Y, z), Blocks.AIR.defaultBlockState());
                fixture.setRelative(
                        new BlockPos(x, GOLDEN_FEET_Y + 1, z), Blocks.AIR.defaultBlockState());
            }
        }
        return List.copyOf(stoneCells);
    }

    private static List<BlockPos> placeOakTree(
            SharedGameTestFixture fixture, BlockPos relativeRoot, int height) {
        fixture.setRelative(relativeRoot.below(), Blocks.DIRT.defaultBlockState());
        List<BlockPos> logs = new ArrayList<>(height);
        for (int dy = 0; dy < height; dy++) {
            BlockPos relativeLog = relativeRoot.above(dy);
            fixture.setRelative(relativeLog, Blocks.OAK_LOG.defaultBlockState());
            logs.add(fixture.absolute(relativeLog));
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
                            new BlockPos(relativeRoot.getX() + dx, canopyY + dy,
                                    relativeRoot.getZ() + dz),
                            Blocks.OAK_LEAVES.defaultBlockState()
                                    .setValue(LeavesBlock.PERSISTENT, false)
                                    .setValue(LeavesBlock.DISTANCE, Math.max(1, distance)));
                }
            }
        }
        return List.copyOf(logs);
    }

    private static void observeGoldenChain(
            GameTestHelper helper,
            AIPlayerEntity bot,
            List<BlockPos> treeLogs,
            BlockPos ironOre,
            GoldenChainState state,
            long tick) {
        Task active = TaskManager.INSTANCE.getActive(bot).orElse(null);
        boolean missionSkill = active instanceof GatherQuotaTask
                || active instanceof CraftTask
                || active instanceof DigDownTask
                || active instanceof OreDigTask
                || active instanceof SmeltTask;
        if (missionSkill) {
            TaskOrigin origin = TaskManager.INSTANCE.activeOrigin(bot)
                    .orElseThrow(() -> new IllegalStateException(
                            "Golden-chain Skill has no TaskOrigin: " + active.name()));
            require(origin.kind() == TaskOrigin.Kind.MISSION && origin.missionId() != null,
                    "Golden-chain Skill is not Mission-owned: " + origin);
            require(origin.missionSource() == GoalSpec.Source.PLAYER_COMMAND,
                    "Golden-chain Skill lost its player-command source: " + origin);
            require(Integer.valueOf(90).equals(origin.goalPriority()),
                    "Golden-chain Skill lost its priority 90 admission metadata: " + origin);
            if (state.missionId == null) {
                state.missionId = origin.missionId();
            } else {
                require(state.missionId.equals(origin.missionId()),
                        "Golden-chain Skill changed Mission identity");
            }
        }
        if (active instanceof GatherQuotaTask && !state.sawGather) {
            state.sawGather = true;
            state.gatherTick = tick;
        } else if (active instanceof CraftTask && !state.sawCraft) {
            state.sawCraft = true;
            state.craftTick = tick;
        } else if (active instanceof DigDownTask && !state.sawDigDown) {
            state.sawDigDown = true;
            state.digDownTick = tick;
        } else if (active instanceof OreDigTask && !state.sawOreDig) {
            state.sawOreDig = true;
            state.oreDigTick = tick;
        } else if (active instanceof SmeltTask && !state.sawSmelt) {
            state.sawSmelt = true;
            state.smeltTick = tick;
        }
        if (state.woodenPickaxeTick < 0
                && InventoryAction.countItem(bot, Items.WOODEN_PICKAXE) > 0) {
            state.woodenPickaxeTick = tick;
        }
        if (state.stonePickaxeTick < 0
                && InventoryAction.countItem(bot, Items.STONE_PICKAXE) > 0) {
            state.stonePickaxeTick = tick;
        }
        if (!state.sawRawIron && InventoryAction.countItem(bot, Items.RAW_IRON) > 0) {
            state.sawRawIron = true;
        }

        String fingerprint = (active == null ? "idle" : active.name())
                + ':' + GoalExecutor.INSTANCE.activeGoalCurrentIndex(bot)
                + ':' + bot.blockPosition().toShortString()
                + ':' + (active == null ? 0 : Math.round(active.progress() * 1_000.0D))
                + ':' + InventoryAction.countItem(bot, Items.OAK_LOG)
                + ':' + InventoryAction.countItem(bot, Items.OAK_PLANKS)
                + ':' + InventoryAction.countItem(bot, Items.STICK)
                + ':' + InventoryAction.countItem(bot, Items.COBBLESTONE)
                + ':' + InventoryAction.countItem(bot, Items.RAW_IRON)
                + ':' + InventoryAction.countItem(bot, Items.IRON_INGOT)
                + ':' + countBlocks(helper, treeLogs, Blocks.OAK_LOG)
                + ':' + helper.getLevel().getBlockState(ironOre).is(Blocks.IRON_ORE);
        if (!fingerprint.equals(state.fingerprint)) {
            state.fingerprint = fingerprint;
            state.lastProgressTick = tick;
        }
    }

    private static int countBlocks(
            GameTestHelper helper, List<BlockPos> positions, net.minecraft.world.level.block.Block block) {
        int count = 0;
        for (BlockPos position : positions) {
            if (helper.getLevel().getBlockState(position).is(block)) {
                count++;
            }
        }
        return count;
    }

    private static boolean hasFurnace(
            GameTestHelper helper, SharedGameTestFixture fixture) {
        BlockPos min = fixture.absolute(new BlockPos(1, 1, 1));
        BlockPos max = fixture.absolute(new BlockPos(19, GOLDEN_FEET_Y + 2, 19));
        return BlockPos.betweenClosedStream(min, max)
                .anyMatch(position -> helper.getLevel().getBlockState(position).is(Blocks.FURNACE));
    }

    private static void clearInventory(AIPlayerEntity bot) {
        bot.getInventory().clearContent();
        bot.getInventory().setChanged();
        bot.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
        bot.setItemSlot(EquipmentSlot.OFFHAND, ItemStack.EMPTY);
        bot.setItemSlot(EquipmentSlot.HEAD, ItemStack.EMPTY);
        bot.setItemSlot(EquipmentSlot.CHEST, ItemStack.EMPTY);
        bot.setItemSlot(EquipmentSlot.LEGS, ItemStack.EMPTY);
        bot.setItemSlot(EquipmentSlot.FEET, ItemStack.EMPTY);
    }

    private static boolean noGoldenChainSupplies(AIPlayerEntity bot) {
        return InventoryAction.countItem(bot, Items.WOODEN_PICKAXE) == 0
                && InventoryAction.countItem(bot, Items.STONE_PICKAXE) == 0
                && InventoryAction.countItem(bot, Items.CRAFTING_TABLE) == 0
                && InventoryAction.countItem(bot, Items.FURNACE) == 0
                && InventoryAction.countItem(bot, Items.COAL) == 0
                && InventoryAction.countItem(bot, Items.CHARCOAL) == 0
                && InventoryAction.countItem(bot, Items.RAW_IRON) == 0
                && InventoryAction.countItem(bot, Items.IRON_INGOT) == 0;
    }

    private static String goldenDiagnostic(AIPlayerEntity bot, GoldenChainState state) {
        Task task = TaskManager.INSTANCE.getActive(bot).orElse(null);
        return "pos=" + bot.blockPosition().toShortString()
                + ", mission=" + GoalExecutor.INSTANCE.activeMissionState(bot)
                + ", step=" + GoalExecutor.INSTANCE.describeActiveStep(bot)
                + ", task=" + (task == null ? "idle" : task.name() + '/' + task.state())
                + ", inv=[log=" + InventoryAction.countItem(bot, Items.OAK_LOG)
                + ",cobble=" + InventoryAction.countItem(bot, Items.COBBLESTONE)
                + ",raw=" + InventoryAction.countItem(bot, Items.RAW_IRON)
                + ",ingot=" + InventoryAction.countItem(bot, Items.IRON_INGOT) + "]"
                + ", milestones=" + state.milestones();
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

    private static void expectFailure(Runnable action) {
        try {
            action.run();
        } catch (IllegalStateException expected) {
            return;
        }
        throw new IllegalStateException("Expected injected transition failure");
    }

    @FunctionalInterface
    private interface Scenario {
        void run(SharedGameTestFixture fixture);
    }

    private static final class GoldenChainState {
        private long lastProgressTick;
        private String fingerprint = "";
        private UUID missionId;
        private boolean sawGather;
        private boolean sawCraft;
        private boolean sawDigDown;
        private boolean sawOreDig;
        private boolean sawRawIron;
        private boolean sawSmelt;
        private long gatherTick = -1L;
        private long craftTick = -1L;
        private long digDownTick = -1L;
        private long oreDigTick = -1L;
        private long smeltTick = -1L;
        private long woodenPickaxeTick = -1L;
        private long stonePickaxeTick = -1L;

        private GoldenChainState(long startedTick) {
            this.lastProgressTick = startedTick;
        }

        private String milestones() {
            return "gather=" + gatherTick
                    + ",craft=" + craftTick
                    + ",dig=" + digDownTick
                    + ",ore=" + oreDigTick
                    + ",smelt=" + smeltTick
                    + ",wood_pick=" + woodenPickaxeTick
                    + ",stone_pick=" + stonePickaxeTick;
        }
    }

    private static final class ThrowingTransitionTask extends AbstractTask {
        private final boolean throwOnPause;
        private final boolean throwOnResume;

        private ThrowingTransitionTask(boolean throwOnPause, boolean throwOnResume) {
            this.throwOnPause = throwOnPause;
            this.throwOnResume = throwOnResume;
        }

        @Override
        public String name() {
            return "throwing_transition";
        }

        @Override
        public String describe() {
            return "Injected pause/resume transition failure";
        }

        @Override
        public double progress() {
            return 0.0D;
        }

        @Override
        protected void onStart(AIPlayerEntity bot) {
        }

        @Override
        protected void onTick(AIPlayerEntity bot) {
        }

        @Override
        protected void onPause(AIPlayerEntity bot) {
            if (throwOnPause) {
                throw new IllegalStateException("injected_pause_failure");
            }
            super.onPause(bot);
        }

        @Override
        protected void onResume(AIPlayerEntity bot) {
            if (throwOnResume) {
                throw new IllegalStateException("injected_resume_failure");
            }
        }
    }

    private static final class ThrowingStartTask extends AbstractTask {
        @Override
        public String name() {
            return "throwing_start";
        }

        @Override
        public String describe() {
            return "Injected Task start failure";
        }

        @Override
        public double progress() {
            return 0.0D;
        }

        @Override
        protected void onStart(AIPlayerEntity bot) {
            throw new IllegalStateException("injected_start_failure");
        }

        @Override
        protected void onTick(AIPlayerEntity bot) {
        }
    }

    private static final class ThrowingTickTask extends AbstractTask {
        @Override
        public String name() {
            return "throwing_tick";
        }

        @Override
        public String describe() {
            return "Injected Task tick failure";
        }

        @Override
        public double progress() {
            return 0.0D;
        }

        @Override
        protected void onStart(AIPlayerEntity bot) {
        }

        @Override
        protected void onTick(AIPlayerEntity bot) {
            throw new IllegalStateException("injected_tick_failure");
        }
    }
}
