package io.github.greytaiwolf.fakeaiplayer.gametest;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import io.github.greytaiwolf.fakeaiplayer.action.InventoryAction;
import io.github.greytaiwolf.fakeaiplayer.coordination.Job;
import io.github.greytaiwolf.fakeaiplayer.coordination.TaskBoard;
import io.github.greytaiwolf.fakeaiplayer.goal.Goal;
import io.github.greytaiwolf.fakeaiplayer.goal.GoalExecutor;
import io.github.greytaiwolf.fakeaiplayer.goal.GoalResult;
import io.github.greytaiwolf.fakeaiplayer.manager.AIPlayerManager;
import io.github.greytaiwolf.fakeaiplayer.mode.CapabilityPolicy;
import io.github.greytaiwolf.fakeaiplayer.mode.CapabilityRuntime;
import io.github.greytaiwolf.fakeaiplayer.mode.OperatingProfile;
import io.github.greytaiwolf.fakeaiplayer.mode.PrivilegedCapability;
import io.github.greytaiwolf.fakeaiplayer.persist.BotPersistence;
import io.github.greytaiwolf.fakeaiplayer.persist.MissionRecord;
import io.github.greytaiwolf.fakeaiplayer.persist.MissionRuntimeRecord;
import io.github.greytaiwolf.fakeaiplayer.runtime.IntentController;
import io.github.greytaiwolf.fakeaiplayer.task.TaskManager;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;

/** Two-process restart probe used by the external evidence harness. */
public final class AIBotRestartHarnessCommand {
    private static final String PROBE_JOB_KIND = "restart_checkpoint_probe";
    private static final String CHECKPOINT_ORIGIN = "origin";
    private static final String CHECKPOINT_STARTED_TICK = "started_tick";
    private static final String CHECKPOINT_ELAPSED_MISSION_TICKS = "elapsed_mission_ticks";
    private static final String CHECKPOINT_REVISION = "revision";
    private static final AtomicBoolean TICKER_REGISTERED = new AtomicBoolean();
    private static volatile UUID checkpointWatchBot;

    private AIBotRestartHarnessCommand() {
    }

    public static LiteralArgumentBuilder<CommandSourceStack> build() {
        registerTickerOnce();
        return literal("harness")
                .then(literal("restart-stage")
                        .then(argument("name", StringArgumentType.word())
                                .executes(context -> stage(
                                        context.getSource(), StringArgumentType.getString(context, "name")))))
                .then(literal("restart-stage-check")
                        .then(argument("name", StringArgumentType.word())
                                .executes(context -> stageCheckpoint(
                                        context.getSource(), StringArgumentType.getString(context, "name")))))
                .then(literal("restart-check")
                        .then(argument("name", StringArgumentType.word())
                                .executes(context -> check(
                                        context.getSource(), StringArgumentType.getString(context, "name")))))
                .then(literal("restart-progress")
                        .then(argument("name", StringArgumentType.word())
                                .executes(context -> checkProgress(
                                        context.getSource(), StringArgumentType.getString(context, "name")))))
                .then(literal("profile-check")
                        .then(argument("name", StringArgumentType.word())
                                .then(argument("profile", StringArgumentType.word())
                                        .executes(context -> checkProfile(
                                                context.getSource(),
                                                StringArgumentType.getString(context, "name"),
                                                StringArgumentType.getString(context, "profile"))))));
    }

    private static int stage(CommandSourceStack source, String name) {
        var bot = AIPlayerManager.INSTANCE.getByName(name).orElse(null);
        if (bot == null) {
            source.sendFailure(Component.literal("[FakeAiPlayer Harness] restart-stage FAIL no_bot"));
            return 0;
        }
        bot.getInventory().clearContent();
        bot.getInventory().setChanged();
        InventoryAction.giveItem(bot, new ItemStack(Items.OAK_PLANKS, 2));
        InventoryAction.giveItem(bot, new ItemStack(Items.COAL, 1));

        // Two deterministic pure-crafting steps: planks -> sticks, then sticks + coal -> torches.
        // The end-tick watcher pauses immediately after the first GoalStep advances revision.
        boolean activeSubmitted = GoalExecutor.INSTANCE.submit(bot, new Goal.HaveItem(Items.TORCH, 4));
        boolean queuedSubmitted = GoalExecutor.INSTANCE.submit(bot, new Goal.HaveItem(Items.CRAFTING_TABLE, 1));
        checkpointWatchBot = activeSubmitted ? bot.getUUID() : null;
        TaskBoard.INSTANCE.clear();
        boolean ok = activeSubmitted
                && queuedSubmitted
                && GoalExecutor.INSTANCE.hasActivePlan(bot)
                && GoalExecutor.INSTANCE.queuedGoalCount(bot) == 1
                && !TaskManager.INSTANCE.isUserPaused(bot)
                && InventoryAction.countItem(bot, Items.TORCH) == 0;
        source.sendSuccess(() -> Component.literal("[FakeAiPlayer Harness] restart-stage "
                + (ok ? "STARTED" : "FAIL")
                + " active=" + GoalExecutor.INSTANCE.hasActivePlan(bot)
                + " queue=" + GoalExecutor.INSTANCE.queuedGoalCount(bot)), false);
        return ok ? 1 : 0;
    }

    private static void registerTickerOnce() {
        if (TICKER_REGISTERED.compareAndSet(false, true)) {
            // Registered after the production END_SERVER_TICK handler. This observes the exact tick
            // where GoalExecutor records the first completed step and pauses before the new task can
            // tick, eliminating a polling race with short failure paths.
            ServerTickEvents.END_SERVER_TICK.register(AIBotRestartHarnessCommand::captureFirstProgressTick);
        }
    }

    private static void captureFirstProgressTick(MinecraftServer server) {
        UUID watched = checkpointWatchBot;
        if (watched == null) {
            return;
        }
        var bot = AIPlayerManager.INSTANCE.getByUuid(watched).orElse(null);
        if (bot == null) {
            checkpointWatchBot = null;
            return;
        }
        MissionRecord active = GoalExecutor.INSTANCE.captureRuntime(bot).active();
        if (active == null) {
            checkpointWatchBot = null;
            return;
        }
        if (parseNonNegative(active.checkpoint().get(CHECKPOINT_REVISION)) < 1) {
            return;
        }
        IntentController.INSTANCE.pause(
                bot, IntentController.ControlOrigin.SYSTEM, "restart_probe_first_progress_tick");
        checkpointWatchBot = null;
    }

    private static int stageCheckpoint(CommandSourceStack source, String name) {
        var bot = AIPlayerManager.INSTANCE.getByName(name).orElse(null);
        if (bot == null) {
            source.sendFailure(Component.literal("[FakeAiPlayer Harness] restart-stage-check FAIL no_bot"));
            return 0;
        }
        MissionRuntimeRecord beforePause = GoalExecutor.INSTANCE.captureRuntime(bot);
        MissionRecord active = beforePause.active();
        if (active == null || !GoalExecutor.INSTANCE.hasActivePlan(bot)) {
            source.sendFailure(Component.literal("[FakeAiPlayer Harness] restart-stage-check FAIL mission_not_active"));
            return 0;
        }
        int revision = parseNonNegative(active.checkpoint().get(CHECKPOINT_REVISION));
        if (revision < 1) {
            source.sendSuccess(() -> Component.literal("[FakeAiPlayer Harness] restart-stage-check WAITING revision="
                    + revision + " step=" + GoalExecutor.INSTANCE.describeActiveStep(bot)), false);
            return 1;
        }

        IntentController.INSTANCE.pause(bot, IntentController.ControlOrigin.SYSTEM, "restart_probe_checkpoint");
        MissionRuntimeRecord pausedRuntime = GoalExecutor.INSTANCE.captureRuntime(bot);
        MissionRecord pausedActive = pausedRuntime.active();
        Map<String, String> checkpoint = pausedActive == null ? Map.of() : pausedActive.checkpoint();
        boolean exactShape = checkpoint.keySet().equals(Set.of(
                CHECKPOINT_ORIGIN,
                CHECKPOINT_STARTED_TICK,
                CHECKPOINT_ELAPSED_MISSION_TICKS,
                CHECKPOINT_REVISION));
        boolean nonDefaultCheckpoint = exactShape
                && parseNonNegative(checkpoint.get(CHECKPOINT_REVISION)) >= 1
                && checkpoint.get(CHECKPOINT_ORIGIN) != null
                && !checkpoint.get(CHECKPOINT_ORIGIN).isBlank()
                && parseNonNegative(checkpoint.get(CHECKPOINT_STARTED_TICK)) > 0
                && parseNonNegative(checkpoint.get(CHECKPOINT_ELAPSED_MISSION_TICKS)) > 0;
        boolean missionShape = pausedActive != null
                && "have_item".equals(pausedActive.spec().type())
                && "minecraft:torch".equals(pausedActive.spec().params().get("item"))
                && "4".equals(pausedActive.spec().params().get("count"))
                && pausedRuntime.queue().size() == 1
                && "have_item".equals(pausedRuntime.queue().getFirst().type())
                && "minecraft:crafting_table".equals(pausedRuntime.queue().getFirst().params().get("item"))
                && "1".equals(pausedRuntime.queue().getFirst().params().get("count"))
                && pausedRuntime.userPaused()
                && InventoryAction.countItem(bot, Items.TORCH) == 0;
        if (!nonDefaultCheckpoint || !missionShape) {
            source.sendFailure(Component.literal("[FakeAiPlayer Harness] restart-stage-check FAIL"
                    + " checkpoint_non_default=" + nonDefaultCheckpoint
                    + " mission_shape=" + missionShape
                    + " checkpoint=" + checkpoint));
            return 0;
        }

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("mission_id", pausedActive.missionId());
        expected.put("checkpoint_origin", checkpoint.get(CHECKPOINT_ORIGIN));
        expected.put("checkpoint_started_tick", checkpoint.get(CHECKPOINT_STARTED_TICK));
        expected.put("checkpoint_elapsed_mission_ticks", checkpoint.get(CHECKPOINT_ELAPSED_MISSION_TICKS));
        expected.put("checkpoint_revision", checkpoint.get(CHECKPOINT_REVISION));
        expected.put("checkpoint_size", String.valueOf(checkpoint.size()));
        expected.put("queue_count", String.valueOf(pausedRuntime.queue().size()));
        TaskBoard.INSTANCE.clear();
        TaskBoard.INSTANCE.postGlobal(PROBE_JOB_KIND, expected, "worker");
        Optional<Job> claimed = TaskBoard.INSTANCE.claimNext(bot, AIPlayerManager.INSTANCE.roles(bot));
        boolean leaseClaimed = claimed.isPresent()
                && claimed.get().status() == Job.Status.CLAIMED
                && bot.getUUID().equals(claimed.get().claimant())
                && TaskBoard.INSTANCE.runtimeSessionId().equals(claimed.get().leaseSessionId())
                && claimed.get().leaseId() != null;
        BotPersistence.INSTANCE.saveAll(source.getServer());
        boolean exactAtSave = pausedActive.checkpoint().equals(
                GoalExecutor.INSTANCE.captureRuntime(bot).active().checkpoint());
        boolean ok = leaseClaimed && exactAtSave && BotPersistence.INSTANCE.lastSaveSucceeded();
        source.sendSuccess(() -> Component.literal("[FakeAiPlayer Harness] restart-stage-check "
                + (ok ? "PASS" : "FAIL")
                + " checkpoint_non_default=" + nonDefaultCheckpoint
                + " checkpoint_exact_at_save=" + exactAtSave
                + " lease_claimed=" + leaseClaimed
                + " mission_id=" + pausedActive.missionId()
                + " revision=" + checkpoint.get(CHECKPOINT_REVISION)
                + " elapsed_mission_ticks=" + checkpoint.get(CHECKPOINT_ELAPSED_MISSION_TICKS)
                + " origin=" + checkpoint.get(CHECKPOINT_ORIGIN)
                + " started_tick=" + checkpoint.get(CHECKPOINT_STARTED_TICK)), false);
        return ok ? 1 : 0;
    }

    private static int check(CommandSourceStack source, String name) {
        var bot = AIPlayerManager.INSTANCE.getByName(name).orElse(null);
        Optional<Job> probeJob = probeJob();
        MissionRuntimeRecord restored = bot == null ? MissionRuntimeRecord.empty()
                : GoalExecutor.INSTANCE.captureRuntime(bot);
        MissionRecord active = restored.active();
        Map<String, String> expectedCheckpoint = probeJob.map(AIBotRestartHarnessCommand::expectedCheckpoint)
                .orElse(Map.of());
        boolean checkpointExact = active != null && active.checkpoint().equals(expectedCheckpoint);
        boolean missionIdExact = active != null && probeJob.isPresent()
                && active.missionId().equals(probeJob.get().params().get("mission_id"));
        boolean missionShape = active != null
                && "have_item".equals(active.spec().type())
                && "minecraft:torch".equals(active.spec().params().get("item"))
                && "4".equals(active.spec().params().get("count"));
        boolean queueExact = probeJob.isPresent()
                && String.valueOf(restored.queue().size()).equals(probeJob.get().params().get("queue_count"))
                && restored.queue().size() == 1
                && "have_item".equals(restored.queue().getFirst().type())
                && "minecraft:crafting_table".equals(restored.queue().getFirst().params().get("item"))
                && "1".equals(restored.queue().getFirst().params().get("count"));
        boolean missionRestored = bot != null
                && GoalExecutor.INSTANCE.hasActivePlan(bot)
                && missionIdExact
                && missionShape
                && queueExact
                && restored.userPaused()
                && TaskManager.INSTANCE.isUserPaused(bot);
        boolean staleLeaseReopened = probeJob.isPresent()
                && probeJob.get().status() == Job.Status.OPEN
                && probeJob.get().claimant() == null
                && probeJob.get().leaseSessionId() == null
                && probeJob.get().leaseId() == null
                && "recovered_stale_claim".equals(probeJob.get().failureReason());
        boolean restoredExactly = missionRestored && checkpointExact && staleLeaseReopened;
        boolean resumed = restoredExactly
                && IntentController.INSTANCE.resume(
                        bot, IntentController.ControlOrigin.SYSTEM, "restart_probe_resume")
                && !TaskManager.INSTANCE.isUserPaused(bot);
        boolean ok = restoredExactly && resumed;
        source.sendSuccess(() -> Component.literal("[FakeAiPlayer Harness] persistence_restart "
                + (ok ? "RESTORE_PASS" : "RESTORE_FAIL")
                + " checkpoint_exact=" + checkpointExact
                + " mission_id_exact=" + missionIdExact
                + " mission_shape=" + missionShape
                + " queue_exact=" + queueExact
                + " paused_restored=" + restored.userPaused()
                + " stale_job_reopened=" + staleLeaseReopened
                + " resumed=" + resumed
                + " expected_checkpoint=" + expectedCheckpoint
                + " actual_checkpoint=" + (active == null ? Map.of() : active.checkpoint())), false);
        return ok ? 1 : 0;
    }

    private static int checkProgress(CommandSourceStack source, String name) {
        var bot = AIPlayerManager.INSTANCE.getByName(name).orElse(null);
        Optional<Job> probeJob = probeJob();
        if (bot == null || probeJob.isEmpty()) {
            source.sendFailure(Component.literal("[FakeAiPlayer Harness] restart-progress FAIL missing_bot_or_probe_job"));
            return 0;
        }
        String expectedMissionId = probeJob.get().params().get("mission_id");
        Optional<GoalResult> terminal = GoalExecutor.INSTANCE.lastResult(bot)
                .filter(result -> result.missionId().toString().equals(expectedMissionId));
        int torches = InventoryAction.countItem(bot, Items.TORCH);
        boolean staleLeaseStillOpen = probeJob.get().status() == Job.Status.OPEN
                && probeJob.get().claimant() == null
                && probeJob.get().leaseSessionId() == null
                && probeJob.get().leaseId() == null;
        if (terminal.isPresent()) {
            GoalResult result = terminal.get();
            boolean progressed = result.status() == GoalResult.Status.COMPLETED
                    && torches >= 4
                    && !TaskManager.INSTANCE.isUserPaused(bot)
                    && staleLeaseStillOpen;
            source.sendSuccess(() -> Component.literal("[FakeAiPlayer Harness] restart-progress "
                    + (progressed ? "PASS" : "FAIL")
                    + " mission_id=" + expectedMissionId
                    + " terminal=" + result.status()
                    + " evidence=" + result.evaluation().matched() + "/" + result.evaluation().required()
                    + " torch=" + torches
                    + " stale_job_open=" + staleLeaseStillOpen
                    + " paused=" + TaskManager.INSTANCE.isUserPaused(bot)), false);
            return progressed ? 1 : 0;
        }
        MissionRecord active = GoalExecutor.INSTANCE.captureRuntime(bot).active();
        boolean expectedStillActive = active != null && active.missionId().equals(expectedMissionId);
        if (!expectedStillActive || TaskManager.INSTANCE.isUserPaused(bot)) {
            source.sendFailure(Component.literal("[FakeAiPlayer Harness] restart-progress FAIL mission_lost_or_paused"
                    + " expected_mission_id=" + expectedMissionId
                    + " actual_mission_id=" + (active == null ? "none" : active.missionId())
                    + " paused=" + TaskManager.INSTANCE.isUserPaused(bot)));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("[FakeAiPlayer Harness] restart-progress WAITING"
                + " mission_id=" + expectedMissionId
                + " revision=" + active.checkpoint().get(CHECKPOINT_REVISION)
                + " step=" + GoalExecutor.INSTANCE.describeActiveStep(bot)
                + " torch=" + torches), false);
        return 1;
    }

    private static Optional<Job> probeJob() {
        return TaskBoard.INSTANCE.snapshot().stream()
                .filter(job -> PROBE_JOB_KIND.equals(job.kind()))
                .findFirst();
    }

    private static Map<String, String> expectedCheckpoint(Job job) {
        Map<String, String> checkpoint = new LinkedHashMap<>();
        checkpoint.put(CHECKPOINT_ORIGIN, job.params().get("checkpoint_origin"));
        checkpoint.put(CHECKPOINT_STARTED_TICK, job.params().get("checkpoint_started_tick"));
        checkpoint.put(CHECKPOINT_ELAPSED_MISSION_TICKS,
                job.params().get("checkpoint_elapsed_mission_ticks"));
        checkpoint.put(CHECKPOINT_REVISION, job.params().get("checkpoint_revision"));
        int expectedSize = parseNonNegative(job.params().get("checkpoint_size"));
        return expectedSize == checkpoint.size() && !checkpoint.containsValue(null)
                ? Map.copyOf(checkpoint)
                : Map.of();
    }

    private static int parseNonNegative(String value) {
        try {
            return Math.max(0, Integer.parseInt(value));
        } catch (RuntimeException ignored) {
            return 0;
        }
    }

    private static int checkProfile(CommandSourceStack source, String name, String expectedValue) {
        var bot = AIPlayerManager.INSTANCE.getByName(name).orElse(null);
        OperatingProfile expected = OperatingProfile.parse(expectedValue).orElse(null);
        if (bot == null || expected == null) {
            source.sendFailure(Component.literal("[FakeAiPlayer Harness] capability_profile FAIL invalid_input"));
            return 0;
        }

        var config = io.github.greytaiwolf.fakeaiplayer.AIBotConfig.get();
        AtomicInteger sideEffects = new AtomicInteger();
        int expectedExecutions = 0;
        boolean decisionsMatch = true;
        for (PrivilegedCapability capability : PrivilegedCapability.values()) {
            boolean expectedAllowed = CapabilityPolicy.decide(
                    expected, config.operatorCapabilities(), capability).allowed();
            boolean executed = CapabilityRuntime.run(
                    bot, capability, "harness_profile_check", sideEffects::incrementAndGet);
            decisionsMatch &= executed == expectedAllowed;
            if (expectedAllowed) {
                expectedExecutions++;
            }
        }
        boolean ok = config.profile() == expected
                && decisionsMatch
                && sideEffects.get() == expectedExecutions;
        int expectedExecutionCount = expectedExecutions;
        source.sendSuccess(() -> Component.literal("[FakeAiPlayer Harness] capability_profile "
                + (ok ? "PASS" : "FAIL")
                + " profile=" + config.profile().configValue()
                + " executed=" + sideEffects.get()
                + " expected=" + expectedExecutionCount), false);
        return ok ? 1 : 0;
    }
}
