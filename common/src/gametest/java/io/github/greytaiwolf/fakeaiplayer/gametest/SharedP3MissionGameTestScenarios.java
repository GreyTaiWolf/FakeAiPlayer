package io.github.greytaiwolf.fakeaiplayer.gametest;

import io.github.greytaiwolf.fakeaiplayer.entity.AIPlayerEntity;
import io.github.greytaiwolf.fakeaiplayer.runtime.TaskOrigin;
import io.github.greytaiwolf.fakeaiplayer.runtime.PauseOwner;
import io.github.greytaiwolf.fakeaiplayer.task.AbstractTask;
import io.github.greytaiwolf.fakeaiplayer.task.EquipLoadoutTask;
import io.github.greytaiwolf.fakeaiplayer.task.HoldTask;
import io.github.greytaiwolf.fakeaiplayer.task.TaskAssignmentResult;
import io.github.greytaiwolf.fakeaiplayer.task.TaskManager;
import io.github.greytaiwolf.fakeaiplayer.task.TaskState;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** Loader-neutral, world-backed contracts for the first Mission/Skill runtime slice. */
public final class SharedP3MissionGameTestScenarios {
    private static final int FEET_Y = 2;

    private SharedP3MissionGameTestScenarios() {
    }

    /** A temporary survival reflex must return control to the exact Mission it interrupted. */
    public static void reflexResumesExactMission(GameTestHelper helper) {
        run(helper, fixture -> {
            fixture.prepareFlat(FEET_Y, 7, 13, 7, 13);
            AIPlayerEntity bot = fixture.spawnBot(
                    "P3Resume01", new BlockPos(10, FEET_Y, 10));
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
