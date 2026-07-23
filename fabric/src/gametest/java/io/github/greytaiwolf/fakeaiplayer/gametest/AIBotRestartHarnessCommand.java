package io.github.greytaiwolf.fakeaiplayer.gametest;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import io.github.greytaiwolf.fakeaiplayer.action.InventoryAction;
import io.github.greytaiwolf.fakeaiplayer.coordination.Job;
import io.github.greytaiwolf.fakeaiplayer.coordination.TaskBoard;
import io.github.greytaiwolf.fakeaiplayer.goal.Goal;
import io.github.greytaiwolf.fakeaiplayer.goal.GoalExecutor;
import io.github.greytaiwolf.fakeaiplayer.goal.GoalResult;
import io.github.greytaiwolf.fakeaiplayer.mission.CursorCheckpoint;
import io.github.greytaiwolf.fakeaiplayer.mission.GoalSpec;
import io.github.greytaiwolf.fakeaiplayer.manager.AIPlayerManager;
import io.github.greytaiwolf.fakeaiplayer.mode.CapabilityPolicy;
import io.github.greytaiwolf.fakeaiplayer.mode.CapabilityRuntime;
import io.github.greytaiwolf.fakeaiplayer.mode.OperatingProfile;
import io.github.greytaiwolf.fakeaiplayer.mode.PrivilegedCapability;
import io.github.greytaiwolf.fakeaiplayer.persist.BotPersistence;
import io.github.greytaiwolf.fakeaiplayer.persist.MissionCheckpointCodec;
import io.github.greytaiwolf.fakeaiplayer.persist.MissionRecord;
import io.github.greytaiwolf.fakeaiplayer.persist.MissionRuntimeRecord;
import io.github.greytaiwolf.fakeaiplayer.runtime.IntentController;
import io.github.greytaiwolf.fakeaiplayer.task.CraftTask;
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
    private static final String CHECKPOINT_RUNTIME_BUDGET =
            MissionCheckpointCodec.RUNTIME_BUDGET_V3;
    private static final AtomicBoolean TICKER_REGISTERED = new AtomicBoolean();
    private static volatile UUID checkpointWatchBot;
    private static volatile boolean recoveryFaultInjected;
    private static volatile boolean recoveryFaultObserved;

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
        bot.clearFire();
        bot.setHealth(bot.getMaxHealth());
        recoveryFaultInjected = false;
        recoveryFaultObserved = false;
        InventoryAction.giveItem(bot, new ItemStack(Items.OAK_PLANKS, 2));
        InventoryAction.giveItem(bot, new ItemStack(Items.COAL, 1));

        // Two deterministic pure-crafting steps: planks -> sticks, then sticks + coal -> torches.
        // The end-tick watcher injects one bounded SurvivalGuard failure, heals the bot after the
        // Mission consumes a real recovery, then pauses after the first verified GoalStep.
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
            // Registered after the production END_SERVER_TICK handler. This gives the probe a
            // deterministic boundary both for injecting a one-tick failure and for pausing before
            // the next task can tick after verified progress.
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
        if (!recoveryFaultInjected) {
            boolean crafting = TaskManager.INSTANCE.getActive(bot)
                    .filter(CraftTask.class::isInstance)
                    .isPresent();
            if (!crafting) {
                return;
            }
            // TaskManager runs before this callback. On the next production tick SurvivalGuard
            // aborts the still-running CraftTask with guard_on_fire. Ingredients stay untouched,
            // so GoalExecutor can reserve a recovery and immediately rebuild the same safe work.
            bot.setHealth(Math.min(9.0F, bot.getMaxHealth()));
            bot.igniteForSeconds(3.0F);
            recoveryFaultInjected = true;
            return;
        }
        MissionRecord active = GoalExecutor.INSTANCE.captureRuntime(bot).active();
        if (active == null) {
            bot.clearFire();
            bot.setHealth(bot.getMaxHealth());
            checkpointWatchBot = null;
            return;
        }
        MissionCheckpointCodec.DecodeResult decoded =
                MissionCheckpointCodec.decode(active.checkpoint());
        if (!decoded.valid()
                || decoded.checkpoint().recovery().recoveriesConsumed() < 1) {
            // Keep the fault bounded but deterministic even if a non-critical task tick is
            // temporarily skipped by TPS guarding; do not let the probe burn the bot down.
            bot.setHealth(Math.min(9.0F, bot.getMaxHealth()));
            bot.igniteForSeconds(1.0F);
            return;
        }
        if (!recoveryFaultObserved) {
            bot.clearFire();
            bot.setHealth(bot.getMaxHealth());
            recoveryFaultObserved = true;
        }
        if (parseNonNegative(active.checkpoint().get(CHECKPOINT_REVISION)) < 1
                || decoded.checkpoint().completedSteps() < 1
                || InventoryAction.countItem(bot, Items.STICK) < 4
                || InventoryAction.countItem(bot, Items.TORCH) > 0) {
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
        MissionCheckpointCodec.DecodeResult activeCheckpoint =
                MissionCheckpointCodec.decode(active.checkpoint());
        int activeRecoveries = activeCheckpoint.valid()
                ? activeCheckpoint.checkpoint().recovery().recoveriesConsumed() : 0;
        boolean firstStepVerified = activeCheckpoint.valid()
                && activeCheckpoint.checkpoint().completedSteps() == 1
                && advancedCraftCursor(activeCheckpoint.checkpoint().cursor())
                && InventoryAction.countItem(bot, Items.STICK) >= 4
                && InventoryAction.countItem(bot, Items.TORCH) == 0;
        if (revision < 1 || activeRecoveries < 1 || !firstStepVerified) {
            source.sendSuccess(() -> Component.literal("[FakeAiPlayer Harness] restart-stage-check WAITING revision="
                    + revision
                    + " recoveries_consumed=" + activeRecoveries
                    + " first_step_verified=" + firstStepVerified
                    + " step=" + GoalExecutor.INSTANCE.describeActiveStep(bot)), false);
            return 1;
        }

        IntentController.INSTANCE.pause(bot, IntentController.ControlOrigin.SYSTEM, "restart_probe_checkpoint");
        MissionRuntimeRecord pausedRuntime = GoalExecutor.INSTANCE.captureRuntime(bot);
        MissionRecord pausedActive = pausedRuntime.active();
        Map<String, String> checkpoint = pausedActive == null ? Map.of() : pausedActive.checkpoint();
        MissionCheckpointCodec.DecodeResult decodedCheckpoint =
                MissionCheckpointCodec.decode(checkpoint);
        CursorCheckpoint cursor = decodedCheckpoint.valid()
                ? decodedCheckpoint.checkpoint().cursor() : null;
        boolean cursorAdvanced = advancedCraftCursor(cursor);
        boolean exactShape = checkpoint.keySet().equals(Set.of(
                CHECKPOINT_ORIGIN,
                CHECKPOINT_STARTED_TICK,
                CHECKPOINT_ELAPSED_MISSION_TICKS,
                CHECKPOINT_REVISION,
                CHECKPOINT_RUNTIME_BUDGET));
        boolean nonDefaultCheckpoint = exactShape
                && decodedCheckpoint.valid()
                && decodedCheckpoint.version() == MissionCheckpointCodec.CURRENT_VERSION
                && parseNonNegative(checkpoint.get(CHECKPOINT_REVISION)) >= 1
                && checkpoint.get(CHECKPOINT_ORIGIN) != null
                && !checkpoint.get(CHECKPOINT_ORIGIN).isBlank()
                && parseNonNegative(checkpoint.get(CHECKPOINT_STARTED_TICK)) > 0
                && parseNonNegative(checkpoint.get(CHECKPOINT_ELAPSED_MISSION_TICKS)) > 0
                && checkpoint.get(CHECKPOINT_RUNTIME_BUDGET) != null
                && !checkpoint.get(CHECKPOINT_RUNTIME_BUDGET).isBlank()
                && decodedCheckpoint.checkpoint().bound()
                && decodedCheckpoint.checkpoint().missionId().toString()
                .equals(pausedActive.missionId())
                && decodedCheckpoint.checkpoint().planRevision() >= 1
                && validFingerprint(decodedCheckpoint.checkpoint().planFingerprint())
                && validFingerprint(decodedCheckpoint.checkpoint().intentFingerprint())
                && validFingerprint(decodedCheckpoint.checkpoint().contextFingerprint())
                && decodedCheckpoint.checkpoint().current()
                && decodedCheckpoint.checkpoint().cursor() != null
                && decodedCheckpoint.checkpoint().cursor().tick()
                == decodedCheckpoint.checkpoint().elapsedMissionTicks()
                && decodedCheckpoint.checkpoint().completedSteps() == 1
                && cursorAdvanced
                && !decodedCheckpoint.checkpoint().recovery().attemptsBySkill().isEmpty()
                && decodedCheckpoint.checkpoint().recovery().recoveriesConsumed() >= 1;
        boolean missionShape = pausedActive != null
                && "have_item".equals(pausedActive.spec().type())
                && "minecraft:torch".equals(pausedActive.spec().params().get("item"))
                && "4".equals(pausedActive.spec().params().get("count"))
                && pausedActive.spec().sourceOrRestored() == GoalSpec.Source.LEGACY
                && pausedActive.spec().priorityOrDefault()
                == GoalSpec.defaultPriority(GoalSpec.Source.LEGACY)
                && pausedActive.spec().policyPresent()
                && pausedActive.spec().persistedPolicy().isPresent()
                && pausedActive.spec().bindingValid()
                && pausedRuntime.queue().size() == 1
                && "have_item".equals(pausedRuntime.queue().getFirst().type())
                && "minecraft:crafting_table".equals(pausedRuntime.queue().getFirst().params().get("item"))
                && "1".equals(pausedRuntime.queue().getFirst().params().get("count"))
                && pausedRuntime.queue().getFirst().sourceOrRestored() == GoalSpec.Source.LEGACY
                && pausedRuntime.queue().getFirst().priorityOrDefault()
                == GoalSpec.defaultPriority(GoalSpec.Source.LEGACY)
                && pausedRuntime.queue().getFirst().bindingValid()
                && pausedRuntime.userPaused()
                && InventoryAction.countItem(bot, Items.STICK) >= 4
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
        expected.put("checkpoint_runtime_budget_v3", checkpoint.get(CHECKPOINT_RUNTIME_BUDGET));
        expected.put("bound_mission_id",
                decodedCheckpoint.checkpoint().missionId().toString());
        expected.put("bound_plan_revision", String.valueOf(
                decodedCheckpoint.checkpoint().planRevision()));
        expected.put("bound_plan_fingerprint",
                decodedCheckpoint.checkpoint().planFingerprint());
        expected.put("bound_intent_fingerprint",
                decodedCheckpoint.checkpoint().intentFingerprint());
        expected.put("bound_context_fingerprint",
                decodedCheckpoint.checkpoint().contextFingerprint());
        expected.put("recoveries_consumed", String.valueOf(
                decodedCheckpoint.checkpoint().recovery().recoveriesConsumed()));
        expected.put("consecutive_no_progress_recoveries", String.valueOf(
                decodedCheckpoint.checkpoint().recovery().consecutiveNoProgressRecoveries()));
        expected.put("replan_after_interrupt", String.valueOf(
                decodedCheckpoint.checkpoint().replanAfterInterrupt()));
        expected.put("checkpoint_size", String.valueOf(checkpoint.size()));
        expected.put("queue_count", String.valueOf(pausedRuntime.queue().size()));
        expected.put("mission_source", pausedActive.spec().sourceOrRestored().name());
        expected.put("mission_priority", String.valueOf(
                pausedActive.spec().priorityOrDefault()));
        expected.put("mission_spec_binding", pausedActive.spec().binding());
        expected.put("queue_spec_binding", pausedRuntime.queue().getFirst().binding());
        expected.put("policy_risk", pausedActive.spec().policy().riskLevel());
        expected.put("policy_mutation", pausedActive.spec().policy().mutationScope());
        expected.put("policy_time_budget", String.valueOf(
                pausedActive.spec().policy().timeBudgetTicks()));
        expected.put("policy_recovery_budget", String.valueOf(
                pausedActive.spec().policy().recoveryBudget()));
        expected.put("policy_interruption", pausedActive.spec().policy().interruptionPolicy());
        TaskBoard.INSTANCE.clear();
        String probeOnlyRole = "restart_harness_probe_only";
        TaskBoard.INSTANCE.postGlobal(PROBE_JOB_KIND, expected, probeOnlyRole);
        Optional<Job> claimed = TaskBoard.INSTANCE.claimNext(bot, java.util.Set.of(probeOnlyRole));
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
                + " completed_steps=" + decodedCheckpoint.checkpoint().completedSteps()
                + " cursor_advanced=" + cursorAdvanced
                + " elapsed_mission_ticks=" + checkpoint.get(CHECKPOINT_ELAPSED_MISSION_TICKS)
                + " recoveries_consumed="
                + decodedCheckpoint.checkpoint().recovery().recoveriesConsumed()
                + " bound_plan_revision=" + decodedCheckpoint.checkpoint().planRevision()
                + " bound_plan_fingerprint=" + decodedCheckpoint.checkpoint().planFingerprint()
                + " bound_intent_fingerprint=" + decodedCheckpoint.checkpoint().intentFingerprint()
                + " runtime_budget_v3=" + checkpoint.get(CHECKPOINT_RUNTIME_BUDGET)
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
        MissionCheckpointCodec.DecodeResult expectedRuntime =
                MissionCheckpointCodec.decode(expectedCheckpoint);
        MissionCheckpointCodec.DecodeResult restoredCheckpoint = active == null
                ? MissionCheckpointCodec.decode(Map.of())
                : MissionCheckpointCodec.decode(active.checkpoint());
        int expectedRecoveries = probeJob
                .map(job -> parseNonNegative(job.params().get("recoveries_consumed")))
                .orElse(0);
        int expectedNoProgress = probeJob
                .map(job -> parseNonNegative(
                        job.params().get("consecutive_no_progress_recoveries")))
                .orElse(0);
        boolean runtimeAccountingExact = expectedRuntime.valid()
                && expectedRuntime.version() == MissionCheckpointCodec.CURRENT_VERSION
                && restoredCheckpoint.valid()
                && restoredCheckpoint.version() == MissionCheckpointCodec.CURRENT_VERSION
                && restoredCheckpoint.checkpoint().completedSteps()
                == expectedRuntime.checkpoint().completedSteps()
                && restoredCheckpoint.checkpoint().elapsedMissionTicks()
                == expectedRuntime.checkpoint().elapsedMissionTicks()
                && restoredCheckpoint.checkpoint().progress().equals(
                expectedRuntime.checkpoint().progress())
                && restoredCheckpoint.checkpoint().recovery().equals(
                expectedRuntime.checkpoint().recovery())
                && restoredCheckpoint.checkpoint().contextFingerprint().equals(
                expectedRuntime.checkpoint().contextFingerprint())
                && restoredCheckpoint.checkpoint().replanAfterInterrupt()
                == expectedRuntime.checkpoint().replanAfterInterrupt();
        boolean samePlanFingerprint = expectedRuntime.valid()
                && restoredCheckpoint.valid()
                && expectedRuntime.checkpoint().planFingerprint().equals(
                restoredCheckpoint.checkpoint().planFingerprint());
        boolean planRecompiled = expectedRuntime.valid()
                && restoredCheckpoint.valid()
                && !samePlanFingerprint;
        boolean planRevisionValid = expectedRuntime.valid()
                && restoredCheckpoint.valid()
                && (samePlanFingerprint
                ? restoredCheckpoint.checkpoint().planRevision()
                == expectedRuntime.checkpoint().planRevision()
                : expectedRuntime.checkpoint().planRevision() < Integer.MAX_VALUE
                && restoredCheckpoint.checkpoint().planRevision()
                == expectedRuntime.checkpoint().planRevision() + 1);
        boolean cursorContinuityValid = expectedRuntime.valid()
                && restoredCheckpoint.valid()
                && expectedRuntime.checkpoint().cursor() != null
                && restoredCheckpoint.checkpoint().cursor() != null
                && (samePlanFingerprint
                ? restoredCheckpoint.checkpoint().cursor().equals(
                expectedRuntime.checkpoint().cursor())
                : planRevisionValid
                && restoredCheckpoint.checkpoint().cursor().missionId().equals(
                expectedRuntime.checkpoint().missionId())
                && restoredCheckpoint.checkpoint().cursor().planRevision()
                == restoredCheckpoint.checkpoint().planRevision()
                && restoredCheckpoint.checkpoint().cursor().planFingerprint().equals(
                restoredCheckpoint.checkpoint().planFingerprint())
                && restoredCheckpoint.checkpoint().cursor().tick()
                == expectedRuntime.checkpoint().cursor().tick());
        // An authoritative restart replan may remove a completed prefix and therefore cannot
        // preserve the old tree paths byte-for-byte. In that case accounting remains exact while
        // the fresh cursor must be bound to the single +1 plan revision at the same Mission tick.
        boolean runtimeStateExact = runtimeAccountingExact
                && planRevisionValid
                && cursorContinuityValid;
        boolean recoveryBudgetExact = runtimeAccountingExact
                && expectedRecoveries >= 1
                && expectedRuntime.checkpoint().recovery().recoveriesConsumed()
                == expectedRecoveries
                && expectedRuntime.checkpoint().recovery().consecutiveNoProgressRecoveries()
                == expectedNoProgress;
        boolean checkpointMetadataExact = active != null
                && checkpointMetadata(active.checkpoint()).equals(
                checkpointMetadata(expectedCheckpoint));
        boolean planProvenanceValid = runtimeStateExact
                && expectedRuntime.checkpoint().bound()
                && restoredCheckpoint.checkpoint().bound()
                && validFingerprint(expectedRuntime.checkpoint().planFingerprint())
                && validFingerprint(restoredCheckpoint.checkpoint().planFingerprint())
                && validFingerprint(expectedRuntime.checkpoint().intentFingerprint())
                && expectedRuntime.checkpoint().intentFingerprint().equals(
                restoredCheckpoint.checkpoint().intentFingerprint())
                && validFingerprint(expectedRuntime.checkpoint().contextFingerprint())
                && expectedRuntime.checkpoint().contextFingerprint().equals(
                restoredCheckpoint.checkpoint().contextFingerprint())
                && expectedRuntime.checkpoint().missionId().equals(
                restoredCheckpoint.checkpoint().missionId())
                && probeJob.isPresent()
                && expectedRuntime.checkpoint().missionId().toString().equals(
                probeJob.get().params().get("bound_mission_id"))
                && String.valueOf(expectedRuntime.checkpoint().planRevision()).equals(
                probeJob.get().params().get("bound_plan_revision"))
                && expectedRuntime.checkpoint().planFingerprint().equals(
                probeJob.get().params().get("bound_plan_fingerprint"))
                && expectedRuntime.checkpoint().intentFingerprint().equals(
                probeJob.get().params().get("bound_intent_fingerprint"))
                && expectedRuntime.checkpoint().contextFingerprint().equals(
                probeJob.get().params().get("bound_context_fingerprint"))
                && String.valueOf(expectedRuntime.checkpoint().replanAfterInterrupt()).equals(
                probeJob.get().params().get("replan_after_interrupt"))
                && planRevisionValid;
        boolean missionIdExact = active != null && probeJob.isPresent()
                && active.missionId().equals(probeJob.get().params().get("mission_id"))
                && restoredCheckpoint.valid()
                && restoredCheckpoint.version() == MissionCheckpointCodec.CURRENT_VERSION
                && restoredCheckpoint.checkpoint().bound()
                && active.missionId().equals(
                restoredCheckpoint.checkpoint().missionId().toString());
        boolean policyExact = active != null && probeJob.isPresent()
                && active.spec().sourceOrRestored().name().equals(
                probeJob.get().params().get("mission_source"))
                && String.valueOf(active.spec().priorityOrDefault()).equals(
                probeJob.get().params().get("mission_priority"))
                && active.spec().policyPresent()
                && active.spec().persistedPolicy().isPresent()
                && active.spec().policy().riskLevel().equals(
                probeJob.get().params().get("policy_risk"))
                && active.spec().policy().mutationScope().equals(
                probeJob.get().params().get("policy_mutation"))
                && String.valueOf(active.spec().policy().timeBudgetTicks()).equals(
                probeJob.get().params().get("policy_time_budget"))
                && String.valueOf(active.spec().policy().recoveryBudget()).equals(
                probeJob.get().params().get("policy_recovery_budget"))
                && active.spec().policy().interruptionPolicy().equals(
                probeJob.get().params().get("policy_interruption"))
                && active.spec().bindingValid()
                && active.spec().binding().equals(
                probeJob.get().params().get("mission_spec_binding"));
        boolean missionShape = active != null
                && "have_item".equals(active.spec().type())
                && "minecraft:torch".equals(active.spec().params().get("item"))
                && "4".equals(active.spec().params().get("count"))
                && active.spec().sourceOrRestored() == GoalSpec.Source.LEGACY
                && active.spec().priorityOrDefault()
                == GoalSpec.defaultPriority(GoalSpec.Source.LEGACY);
        boolean queueExact = probeJob.isPresent()
                && String.valueOf(restored.queue().size()).equals(probeJob.get().params().get("queue_count"))
                && restored.queue().size() == 1
                && "have_item".equals(restored.queue().getFirst().type())
                && "minecraft:crafting_table".equals(restored.queue().getFirst().params().get("item"))
                && "1".equals(restored.queue().getFirst().params().get("count"))
                && restored.queue().getFirst().sourceOrRestored() == GoalSpec.Source.LEGACY
                && restored.queue().getFirst().priorityOrDefault()
                == GoalSpec.defaultPriority(GoalSpec.Source.LEGACY)
                && restored.queue().getFirst().bindingValid()
                && restored.queue().getFirst().binding().equals(
                probeJob.get().params().get("queue_spec_binding"));
        boolean missionRestored = bot != null
                && GoalExecutor.INSTANCE.hasActivePlan(bot)
                && missionIdExact
                && policyExact
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
        boolean canonicalRestorePersisted = BotPersistence.INSTANCE.lastSaveSucceeded();
        boolean restoredExactly = missionRestored
                && checkpointMetadataExact
                && runtimeStateExact
                && recoveryBudgetExact
                && planProvenanceValid
                && staleLeaseReopened
                && canonicalRestorePersisted;
        // Queue restoration was already proved above. Remove the deliberately under-provisioned
        // crafting-table goal before resuming so it cannot synchronously publish BLOCKED and
        // overwrite the single terminal-result slot for the torch Mission under test.
        int isolatedQueuedGoals = restoredExactly
                ? GoalExecutor.INSTANCE.clearQueue(bot) : 0;
        boolean queueIsolated = isolatedQueuedGoals == 1;
        boolean resumed = restoredExactly
                && queueIsolated
                && IntentController.INSTANCE.resume(
                        bot, IntentController.ControlOrigin.SYSTEM, "restart_probe_resume")
                && !TaskManager.INSTANCE.isUserPaused(bot);
        boolean ok = restoredExactly && resumed;
        source.sendSuccess(() -> Component.literal("[FakeAiPlayer Harness] persistence_restart "
                + (ok ? "RESTORE_PASS" : "RESTORE_FAIL")
                + " checkpoint_metadata_exact=" + checkpointMetadataExact
                + " runtime_state_exact=" + runtimeStateExact
                + " recovery_budget_exact=" + recoveryBudgetExact
                + " plan_provenance_valid=" + planProvenanceValid
                + " plan_recompiled=" + planRecompiled
                + " cursor_continuity_valid=" + cursorContinuityValid
                + " recoveries_consumed="
                + (restoredCheckpoint.valid()
                ? restoredCheckpoint.checkpoint().recovery().recoveriesConsumed() : -1)
                + " mission_id_exact=" + missionIdExact
                + " policy_exact=" + policyExact
                + " mission_shape=" + missionShape
                + " queue_exact=" + queueExact
                + " paused_restored=" + restored.userPaused()
                + " stale_job_reopened=" + staleLeaseReopened
                + " canonical_restore_persisted=" + canonicalRestorePersisted
                + " queue_isolated_after_restore_proof=" + queueIsolated
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
        checkpoint.put(CHECKPOINT_RUNTIME_BUDGET,
                job.params().get("checkpoint_runtime_budget_v3"));
        int expectedSize = parseNonNegative(job.params().get("checkpoint_size"));
        return expectedSize == checkpoint.size() && !checkpoint.containsValue(null)
                ? Map.copyOf(checkpoint)
                : Map.of();
    }

    private static Map<String, String> checkpointMetadata(Map<String, String> checkpoint) {
        Map<String, String> metadata = new LinkedHashMap<>(
                checkpoint == null ? Map.of() : checkpoint);
        metadata.keySet().removeIf(key -> key != null && key.startsWith("runtime_budget_v"));
        // JVM-local server ticks are deliberately rebased on restore; the durable active-work
        // clock lives inside the V3 payload and must remain exact instead.
        metadata.remove(CHECKPOINT_STARTED_TICK);
        return Map.copyOf(metadata);
    }

    private static boolean advancedCraftCursor(CursorCheckpoint cursor) {
        return cursor != null
                && cursor.nodeStates().get("root") != null
                && cursor.nodeStates().get("root").childIndex() == 1
                && cursor.nodeStates().get("root/0") != null
                && cursor.nodeStates().get("root/0").phase()
                == CursorCheckpoint.NodePhase.SUCCEEDED
                && cursor.nodeStates().get("root/1") != null
                && cursor.nodeStates().get("root/1").phase()
                == CursorCheckpoint.NodePhase.ACTIVE
                && cursor.activationCounts().getOrDefault(
                "step.0.legacy.craft", 0) >= 1
                && cursor.activationCounts().getOrDefault(
                "step.1.legacy.craft", 0) >= 1;
    }

    private static boolean validFingerprint(String fingerprint) {
        return fingerprint != null && fingerprint.matches("[0-9a-f]{64}");
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
