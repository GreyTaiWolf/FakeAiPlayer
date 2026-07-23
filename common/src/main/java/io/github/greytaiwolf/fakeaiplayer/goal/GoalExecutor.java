package io.github.greytaiwolf.fakeaiplayer.goal;

import io.github.greytaiwolf.fakeaiplayer.AIBotConfig;
import io.github.greytaiwolf.fakeaiplayer.brain.BotReporter;
import io.github.greytaiwolf.fakeaiplayer.entity.AIPlayerEntity;
import io.github.greytaiwolf.fakeaiplayer.log.BotLog;
import io.github.greytaiwolf.fakeaiplayer.mission.CursorCheckpoint;
import io.github.greytaiwolf.fakeaiplayer.mission.GoalSpec;
import io.github.greytaiwolf.fakeaiplayer.mission.MissionArbiter;
import io.github.greytaiwolf.fakeaiplayer.mission.MissionLifecycle;
import io.github.greytaiwolf.fakeaiplayer.mission.MissionPlan;
import io.github.greytaiwolf.fakeaiplayer.mission.MissionPolicy;
import io.github.greytaiwolf.fakeaiplayer.mission.MissionState;
import io.github.greytaiwolf.fakeaiplayer.mission.PlanCursor;
import io.github.greytaiwolf.fakeaiplayer.mission.RecoveryLedger;
import io.github.greytaiwolf.fakeaiplayer.mission.SkillOutcome;
import io.github.greytaiwolf.fakeaiplayer.mission.SkillSpec;
import io.github.greytaiwolf.fakeaiplayer.task.BlueprintLoader;
import io.github.greytaiwolf.fakeaiplayer.task.BlueprintSchema;
import io.github.greytaiwolf.fakeaiplayer.task.BuildTask;
import io.github.greytaiwolf.fakeaiplayer.task.CraftTask;
import io.github.greytaiwolf.fakeaiplayer.task.DescendToYTask;
import io.github.greytaiwolf.fakeaiplayer.task.DigDownTask;
import io.github.greytaiwolf.fakeaiplayer.task.EquipLoadoutTask;
import io.github.greytaiwolf.fakeaiplayer.task.FarmTask;
import io.github.greytaiwolf.fakeaiplayer.task.GatherQuotaTask;
import io.github.greytaiwolf.fakeaiplayer.task.HuntTask;
import io.github.greytaiwolf.fakeaiplayer.task.MilkCowTask;
import io.github.greytaiwolf.fakeaiplayer.task.MineTask;
import io.github.greytaiwolf.fakeaiplayer.task.MoveTask;
import io.github.greytaiwolf.fakeaiplayer.task.OreDigTask;
import io.github.greytaiwolf.fakeaiplayer.task.PlaceStationsTask;
import io.github.greytaiwolf.fakeaiplayer.task.SmeltTask;
import io.github.greytaiwolf.fakeaiplayer.task.StockpileTask;
import io.github.greytaiwolf.fakeaiplayer.task.Task;
import io.github.greytaiwolf.fakeaiplayer.task.TaskManager;
import io.github.greytaiwolf.fakeaiplayer.task.TaskState;
import io.github.greytaiwolf.fakeaiplayer.task.TaskStatus;
import io.github.greytaiwolf.fakeaiplayer.runtime.TaskOrigin;
import io.github.greytaiwolf.fakeaiplayer.persist.MissionCheckpointCodec;
import io.github.greytaiwolf.fakeaiplayer.persist.MissionRecord;
import io.github.greytaiwolf.fakeaiplayer.persist.MissionRuntimeRecord;
import io.github.greytaiwolf.fakeaiplayer.persist.MissionSpec;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public final class GoalExecutor {
    public static final GoalExecutor INSTANCE = new GoalExecutor();
    private static final int MAX_CONSECUTIVE_NO_PROGRESS_RECOVERIES = 3;
    private static final int MAX_POSTCONDITION_RECOVERIES = 3;
    private static final int RUNTIME_CHECKPOINT_INTERVAL_TICKS = 200;
    private static final HexFormat HEX = HexFormat.of();

    private final Map<UUID, ActivePlan> activePlans = new ConcurrentHashMap<>();
    // P0 目标队列(对话式助手根基):复合指令"先搞吃的再挖铁"需要连续目标。原单 plan 模型下
    // 第二个目标会被拒/覆盖(prompt 甚至要求"一次一个,调完 STOP")。现在:活跃目标存在时新目标入队,
    // 当前目标完成/失败后自动出队衔接(像真人:手头干完接着办下一件,办不成说一声跳过)。
    private final Map<UUID, java.util.Deque<GoalRequest>> goalQueue = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> lastGoalFailTick = new ConcurrentHashMap<>(); // 优化2:goal 整体失败时刻,拦大脑随后手动逐格挖矿
    private final Map<UUID, Goal> userGoal = new ConcurrentHashMap<>(); // B:用户原始高层目标,防大脑把它降级成其前置子目标(挖钻石→做铁镐)
    private final Map<UUID, GoalResult> lastResults = new ConcurrentHashMap<>();
    private final AtomicLong resultSequence = new AtomicLong();
    private final AtomicLong submissionSequence = new AtomicLong();

    private GoalExecutor() {
    }

    public boolean submit(AIPlayerEntity bot, Goal goal) {
        return submit(bot, goal, GoalSpec.Source.LEGACY);
    }

    public boolean submit(AIPlayerEntity bot, Goal goal, GoalSpec.Source source) {
        GoalSpec.Source resolved = source == null ? GoalSpec.Source.LEGACY : source;
        return submit(bot, goal, resolved, GoalSpec.defaultPriority(resolved), null);
    }

    /** Submits a Goal with an explicit priority inside the Mission admission band. */
    public boolean submit(AIPlayerEntity bot,
                          Goal goal,
                          GoalSpec.Source source,
                          int priority) {
        return submit(bot, goal, source, priority, null);
    }

    private boolean submit(AIPlayerEntity bot,
                           Goal goal,
                           GoalSpec.Source source,
                           int priority,
                           RestoreSeed restore) {
        GoalSpec.Source resolvedSource = restore == null
                ? (source == null ? GoalSpec.Source.LEGACY : source)
                : restore.source();
        int resolvedPriority = restore == null ? priority : restore.priority();
        if (resolvedPriority < 0 || resolvedPriority > 100) {
            throw new IllegalArgumentException("goal_priority_out_of_range");
        }
        UUID missionId = restore == null ? UUID.randomUUID() : restore.missionId();
        int startedTick = restore == null ? bot.getServer().getTickCount() : restore.startedTick();
        BlueprintSchema verifiedBuildBlueprint = null;
        if (goal instanceof Goal.Build build) {
            try {
                verifiedBuildBlueprint = validateAndLoadBuildGoal(bot, build);
            } catch (IOException exception) {
                String detail = exception.getMessage() == null || exception.getMessage().isBlank()
                        ? exception.getClass().getSimpleName() : exception.getMessage();
                String reason = "build_admission_rejected:" + detail;
                BotLog.warn(io.github.greytaiwolf.fakeaiplayer.log.LogCategory.TASK, bot,
                        "build_goal_binding_rejected",
                        "blueprint", build.blueprint(),
                        "reason", detail,
                        "actual_dimension", bot.serverLevel().dimension().location());
                recordImmediateResult(
                        bot,
                        missionId,
                        goal,
                        startedTick,
                        GoalEvaluation.unknown(reason),
                        GoalResult.Status.BLOCKED,
                        reason,
                        blockedOutcome(reason, SkillOutcome.FailureKind.PRECONDITION, Map.of(
                                "phase", "build_admission",
                                "blueprint", build.blueprint(),
                                "actual_dimension",
                                bot.serverLevel().dimension().location().toString())));
                return false;
            }
        }
        GoalSpec incomingGoalSpec = LegacyMissionCompiler.goalSpec(
                goal,
                resolvedSource,
                resolvedPriority,
                bot.serverLevel().dimension().location().toString(),
                restore == null ? null : restore.policy());
        if (restore != null && restore.intentFingerprint() != null
                && !restore.intentFingerprint().equals(
                        MissionPlan.intentFingerprint(incomingGoalSpec))) {
            BotLog.warn(io.github.greytaiwolf.fakeaiplayer.log.LogCategory.LIFECYCLE, bot,
                    "mission_restore_isolated",
                    "mission_id", missionId,
                    "reason", "mission_intent_binding_mismatch");
            return false;
        }
        // GOALFIX-GF3:幂等——同一 bot 已有相同目标的活跃计划时,忽略重复 submit
        //(防大脑连点 mine_ore/achieve_goal 覆盖计划、打断进行中的步骤)。
        ActivePlan existing = activePlans.get(bot.getUUID());
        if (existing != null && existing.goal.equals(goal)) {
            if (upgradeAdmission(existing, resolvedSource, resolvedPriority)) {
                TaskManager.INSTANCE.upgradeMissionAdmission(
                        bot, existing.missionId, existing.missionPlan.goal());
            }
            BotLog.task(bot, "goal_submit_ignored", "goal", goal, "reason", "duplicate_active_plan");
            markDirty(bot);
            return true;
        }
        boolean replacesExisting = existing != null && MissionArbiter.decide(
                MissionArbiter.goalClaim(
                        existing.missionPlan.goal(), existing.missionId.toString()),
                MissionArbiter.goalClaim(incomingGoalSpec, "incoming_goal"),
                TaskManager.INSTANCE.hasPersistentPause(bot)).startsIncoming();
        // P0 队列:已有进行中的目标 → 新目标入队(去重),手头干完自动接续。复合指令/连续吩咐的根基。
        // 注意放在"前置降级拦截"之后判定才安全?不——降级拦截在下面,先让它检查:子目标仍要拦。
        java.util.Deque<GoalRequest> queued = goalQueue.computeIfAbsent(
                bot.getUUID(), k -> new java.util.concurrent.ConcurrentLinkedDeque<>());
        if (existing != null && !replacesExisting) {
            Goal ugQ = userGoal.get(bot.getUUID());
            if (ugQ != null && !ugQ.equals(goal) && isPrerequisiteOf(bot, goal, ugQ)) {
                BotLog.task(bot, "goal_downgrade_blocked", "sub", goal, "user", ugQ);
                report(bot, "这是当前目标的前置步骤,系统会自动完成,无需单独做。");
                return false;
            }
            GoalRequest duplicate = queued.stream()
                    .filter(request -> request.goal().equals(goal))
                    .findFirst()
                    .orElse(null);
            if (duplicate != null) {
                if (strongerAdmission(resolvedSource, resolvedPriority,
                        duplicate.source(), duplicate.priority())) {
                    queued.removeFirstOccurrence(duplicate);
                    queued.addLast(new GoalRequest(goal, resolvedSource, resolvedPriority,
                            duplicate.sequence()));
                    markDirty(bot);
                }
                BotLog.task(bot, "goal_submit_ignored", "goal", goal, "reason", "duplicate_queued");
                return true;
            }
            queued.addLast(new GoalRequest(goal, resolvedSource, resolvedPriority,
                    submissionSequence.incrementAndGet()));
            BotLog.task(bot, "goal_queued", "goal", goal, "behind", String.valueOf(existing.goal), "queue_size", queued.size());
            report(bot, "记下了,等手头这件干完就去办:" + goalLabel(goal));
            markDirty(bot);
            return true;
        }
        // B:保护用户原始目标——大脑不能把它降级成其前置子目标。实测:挖钻石失败后大脑 achieve_goal 做铁镐、
        // mine_ore 挖铁(都是挖钻石的前置)覆盖了目标,做完铁镐还误报"任务完成、最初要求是挖铁做镐"。
        Goal ug = userGoal.get(bot.getUUID());
        if (ug != null && !ug.equals(goal) && isPrerequisiteOf(bot, goal, ug)) {
            BotLog.task(bot, "goal_downgrade_blocked", "sub", goal, "user", ug);
            report(bot, "这是当前目标的前置步骤,系统会自动完成,无需单独做。要更换目标请直接告诉我。");
            return false;
        }
        GoalPredicate predicate = GoalPredicates.forGoal(goal);
        GoalSnapshotCollector.Context context = restore == null
                ? initialContext(bot, goal, verifiedBuildBlueprint)
                : restore.context();
        GoalEvaluation initialEvaluation = predicate.evaluate(GoalSnapshotCollector.collect(bot, goal, context));
        if (initialEvaluation.state() == GoalEvaluation.State.SATISFIED) {
            removeQueuedGoal(queued, goal);
            recordImmediateResult(bot, missionId, goal, startedTick, initialEvaluation,
                    GoalResult.Status.COMPLETED, "already_satisfied");
            return true;
        }
        GoalPlanner.GoalPlan plan = GoalPlanner.plan(bot, goal, context);
        if (!plan.success()) {
            String reason = "plan_failed:" + String.join(",", plan.unresolved());
            recordImmediateResult(bot, missionId, goal, startedTick, initialEvaluation,
                    GoalResult.Status.BLOCKED, reason,
                    blockedOutcome(reason, SkillOutcome.FailureKind.PRECONDITION, Map.of(
                            "phase", "planning",
                            "unresolved", String.join(",", plan.unresolved()))));
            BotLog.warn(io.github.greytaiwolf.fakeaiplayer.log.LogCategory.TASK, bot, "goal_plan_failed",
                    "goal", goal,
                    "unresolved", plan.unresolved());
            return false;
        }
        // replace 边界:A 活跃、B 已排队，随后同批 stop + B。只有 B 已成功规划后才从旧队列移除；
        // 若 replacement 规划失败，保留 B 让下一 tick 的常规 queue drain 再处理，不能静默丢目标。
        removeQueuedGoal(queued, goal);
        if (plan.steps().isEmpty()) {
            String reason = "empty_plan_unsatisfied";
            recordImmediateResult(bot, missionId, goal, startedTick, initialEvaluation,
                    GoalResult.Status.BLOCKED, reason,
                    blockedOutcome(reason, SkillOutcome.FailureKind.UNKNOWN, Map.of(
                            "phase", "planning",
                            "planner_version", LegacyMissionCompiler.PLANNER_VERSION)));
            return false;
        }
        int restoredPlanRevision = restore == null ? 0 : restore.planRevision();
        LegacyMissionCompiler.CompiledMission compiled = LegacyMissionCompiler.compile(
                missionId,
                restoredPlanRevision,
                goal,
                resolvedSource,
                resolvedPriority,
                bot.serverLevel().dimension().location().toString(),
                restore == null ? null : restore.policy(),
                plan.steps());
        if (restore != null && restore.planFingerprint() != null) {
            OptionalInt resolvedRevision = resolveRestoredPlanRevision(
                    restoredPlanRevision,
                    restore.planFingerprint(),
                    compiled.plan().fingerprint());
            if (resolvedRevision.isEmpty()) {
                BotLog.warn(io.github.greytaiwolf.fakeaiplayer.log.LogCategory.LIFECYCLE, bot,
                        "mission_restore_isolated",
                        "mission_id", missionId,
                        "reason", "mission_plan_revision_exhausted");
                return false;
            }
            if (resolvedRevision.getAsInt() != restoredPlanRevision) {
                // The checkpoint identifies the plan that produced its progress and budgets. The
                // authoritative world may legitimately require a different remaining plan after a
                // restart; retain all accounting, but make that divergence an explicit new revision.
                compiled = LegacyMissionCompiler.compile(
                        missionId,
                        resolvedRevision.getAsInt(),
                        goal,
                        resolvedSource,
                        resolvedPriority,
                        bot.serverLevel().dimension().location().toString(),
                        restore.policy(),
                        plan.steps());
                BotLog.task(bot, "mission_restore_replanned",
                        "mission_id", missionId,
                        "old_revision", restoredPlanRevision,
                        "new_revision", compiled.plan().revision(),
                        "old_fingerprint", restore.planFingerprint(),
                        "new_fingerprint", compiled.plan().fingerprint());
            }
        }
        ActivePlan active;
        try {
            int restoredCompletedSteps = restore == null ? 0 : restore.completedSteps();
            int restoredElapsedTicks = restore == null ? 0 : restore.elapsedMissionTicks();
            List<String> remainingStepLabels = compiled.skills().stream()
                    .map(skill -> skill.step().describe())
                    .toList();
            CursorCheckpoint restoredCursor = restore != null
                    && restore.cursorCheckpoint() != null
                    && restore.planRevision() == compiled.plan().revision()
                    && restore.planFingerprint().equals(compiled.plan().fingerprint())
                    ? restore.cursorCheckpoint() : null;
            active = new ActivePlan(missionId, startedTick, goal, predicate, context,
                    compiled.plan(), compiled.skills(),
                    totalSteps(restoredCompletedSteps, compiled.skills().size()),
                    alignedStepLabels(List.of(), restoredCompletedSteps, remainingStepLabels),
                    restore == null ? RecoveryLedger.Snapshot.empty() : restore.recovery(),
                    restoredCursor, restoredElapsedTicks,
                    restore != null && restore.replanAfterInterrupt());
        } catch (IllegalArgumentException invalidRecoverySnapshot) {
            BotLog.warn(io.github.greytaiwolf.fakeaiplayer.log.LogCategory.LIFECYCLE, bot,
                    "mission_runtime_budget_rejected",
                    "mission_id", missionId,
                    "reason", invalidRecoverySnapshot.getMessage());
            return false;
        }
        if (restore != null) {
            active.completedSteps = restore.completedSteps();
            active.elapsedMissionTicks = restore.elapsedMissionTicks();
        }
        active.lastEvaluationMatched = initialEvaluation.matched();
        // Phase A:进度快照初始化(开局基准),供 handleStepFailure 的进度赦免对比。
        net.minecraft.core.BlockPos sp0 = bot.blockPosition();
        MissionCheckpointCodec.ProgressSnapshot restoredProgress = restore == null
                ? null : restore.progress();
        active.snapSteps = restoredProgress == null
                ? active.completedSteps : restoredProgress.completedSteps();
        active.snapTargetCount = restoredProgress != null
                && restoredProgress.targetCount().isPresent()
                ? restoredProgress.targetCount().getAsInt()
                : goalTargetCount(bot, goal);
        MissionCheckpointCodec.Position restoredPosition = restoredProgress == null
                ? null : restoredProgress.position().orElse(null);
        active.snapX = restoredPosition == null ? sp0.getX() : restoredPosition.x();
        active.snapY = restoredPosition == null ? sp0.getY() : restoredPosition.y();
        active.snapZ = restoredPosition == null ? sp0.getZ() : restoredPosition.z();
        if (replacesExisting) {
            TaskManager.INSTANCE.cancelMissionTasks(
                    bot, existing.missionId, "superseded_by_higher_authority_goal");
            finishActive(bot, existing, evaluate(bot, existing),
                    "superseded_by_higher_authority_goal", true, false,
                    GoalResult.Status.CANCELLED);
        }
        activePlans.put(bot.getUUID(), active);
        // 工作记忆 episode 边界:新目标=新 episode,上一件事的排除项/轨迹作废。
        // (replan 不走这里——handleStepFailure 原地改 plan.steps,工作记忆跨 replan 存活,这正是设计。)
        io.github.greytaiwolf.fakeaiplayer.task.EpisodeMemory.INSTANCE.reset(bot.getUUID());
        userGoal.putIfAbsent(bot.getUUID(), goal); // B:首个目标记为"用户原始目标";后续前置子目标被上面拦下,换目标由用户消息清空
        BotLog.task(bot, "goal_plan", "goal", goal, "steps", plan.describeSteps());
        report(bot, "我会按 " + plan.steps().size() + " 步完成目标。");
        if (active.replanAfterInterrupt) {
            // A crash may happen after safety publishes the durable replan obligation but before
            // the safety frame unwinds. Never restart the stale child in that window; tickBot will
            // consume exactly one INTERRUPT_REPLAN budget and rebuild from the current world.
            active.transition(MissionState.SUSPENDED);
        } else {
            assignNext(bot, active);
        }
        markDirty(bot);
        return true;
    }

    public boolean tickBot(MinecraftServer server, AIPlayerEntity bot) {
        ActivePlan plan = activePlans.get(bot.getUUID());
        String actualDimension = bot.serverLevel().dimension().location().toString();
        if (plan != null && plan.missionPlan.goal().dimension() != null
                && !plan.missionPlan.goal().dimension().equals(actualDimension)) {
            String reason = plan.goal instanceof Goal.Build
                    ? "confirmed_build_dimension_mismatch" : "mission_bound_dimension_changed";
            plan.terminalOutcome = blockedOutcome(
                    reason,
                    SkillOutcome.FailureKind.WORLD_CHANGED,
                    Map.of("expected_dimension", plan.missionPlan.goal().dimension(),
                            "actual_dimension", actualDimension));
            TaskManager.INSTANCE.cancelMissionTasks(
                    bot, plan.missionId, reason);
            finishActive(bot, plan, evaluate(bot, plan),
                    reason, false, false, GoalResult.Status.BLOCKED);
            return false;
        }
        boolean missionInterrupted = plan != null
                && TaskManager.INSTANCE.consumeMissionInterruption(bot, plan.missionId);
        boolean missionCurrentlyPaused = plan != null
                && TaskManager.INSTANCE.isMissionAutomaticallyPaused(bot, plan.missionId);
        if (plan != null && (missionInterrupted || missionCurrentlyPaused) && !plan.state.terminal()) {
            switch (plan.missionPlan.goal().policy().interruptionPolicy()) {
                case CANCEL_ON_INTERRUPT -> {
                    TaskManager.INSTANCE.cancelMissionTasks(
                            bot, plan.missionId, "mission_policy_cancel_on_interrupt");
                    finishActive(bot, plan, evaluate(bot, plan),
                            "mission_policy_cancel_on_interrupt", true, false,
                            GoalResult.Status.CANCELLED);
                    return false;
                }
                case REPLAN_AFTER_SAFETY -> plan.replanAfterInterrupt = true;
                case RESUME_AFTER_SAFETY -> {
                }
            }
            if (plan.state == MissionState.RUNNING
                    || plan.state == MissionState.RECOVERING
                    || plan.state == MissionState.PLANNED) {
                plan.transition(MissionState.SUSPENDED);
            }
            if (missionInterrupted) {
                // Persist the policy obligation at the same stable cursor/attempt boundary as
                // the paused Task. REPLAN_AFTER_SAFETY must survive a crash before resume.
                markDirty(bot);
            }
        }
        PlanCursor.Snapshot advancedCursor = null;
        boolean persistAdvancedCursor = false;
        if (plan != null && shouldChargeMissionBudget(bot, plan)) {
            plan.elapsedMissionTicks = incrementMissionTicks(plan.elapsedMissionTicks);
            advancedCursor = plan.planCursor.advanceTo(plan.elapsedMissionTicks);
            persistAdvancedCursor = shouldPersistRuntimeCheckpoint(plan.elapsedMissionTicks);
        }
        if (plan != null
                && missionTimeBudgetExhausted(
                        plan.elapsedMissionTicks,
                        plan.missionPlan.goal().policy().timeBudgetTicks())) {
            GoalEvaluation evaluation = evaluate(bot, plan);
            TaskManager.INSTANCE.cancelMissionTasks(
                    bot, plan.missionId, "mission_time_budget_exhausted");
            finishActive(bot, plan, evaluation,
                    evaluation.state() == GoalEvaluation.State.SATISFIED
                            ? "postcondition_satisfied_at_time_budget"
                            : "mission_time_budget_exhausted",
                    false,
                    false,
                    evaluation.state() == GoalEvaluation.State.SATISFIED
                            ? null : GoalResult.Status.FAILED);
            return false;
        }
        // Global Mission budget wins before a Timeout node can install a Retry/AnyOf fallback.
        // Otherwise the fallback's onStart could mutate the world for one tick after expiry.
        if (advancedCursor != null) {
            if (reconcileCursorActivation(server, bot, plan, advancedCursor,
                    "mission_plan_clock_advanced")) {
                return true;
            }
            if (persistAdvancedCursor) {
                markDirty(bot);
            }
        }
        if (TaskManager.INSTANCE.isUserPaused(bot)) {
            if (plan != null && plan.state != MissionState.VERIFYING && !plan.state.terminal()) {
                plan.transition(MissionState.SUSPENDED);
            }
            return hasActivePlan(bot) || queuedGoalCount(bot) > 0 || TaskManager.INSTANCE.hasPaused(bot);
        }
        if (plan == null) {
            if (TaskManager.INSTANCE.getActive(bot).isEmpty()
                    && !TaskManager.INSTANCE.hasPaused(bot)
                    && !bot.getActionPack().hasActiveActions()) {
                return startNextQueuedIfIdle(bot);
            }
            return false;
        }
        Optional<Task> active = TaskManager.INSTANCE.getActive(bot);
        boolean resumedMissionChild = plan.state == MissionState.SUSPENDED
                && plan.currentTask != null
                && active.filter(task -> task == plan.currentTask).isPresent();
        boolean terminalMissionChildAfterInterrupt = plan.state == MissionState.SUSPENDED
                && plan.currentTask != null
                && active.isEmpty()
                && !TaskManager.INSTANCE.hasPaused(bot)
                && plan.currentTask.state() != TaskState.PAUSED;
        boolean restoredInterruptAwaitingReplan = plan.state == MissionState.SUSPENDED
                && plan.currentTask == null
                && active.isEmpty()
                && !TaskManager.INSTANCE.hasPaused(bot);
        if (plan.replanAfterInterrupt
                && (resumedMissionChild
                || terminalMissionChildAfterInterrupt
                || restoredInterruptAwaitingReplan)) {
            boolean verifiedChildProgress = false;
            SkillOutcome interruptedChildOutcome = null;
            if (terminalMissionChildAfterInterrupt) {
                TaskStatus interruptedStatus = TaskStatus.from(plan.currentTask);
                switch (interruptedStatus.state()) {
                    case COMPLETED -> {
                        // TaskManager ticks before GoalExecutor. A one-tick child can therefore
                        // finish immediately after the safety frame unwinds. Preserve its evidence,
                        // but still rebuild the remaining plan when policy requires it.
                        captureTaskEvidence(plan);
                        SkillOutcome verified = LegacySkillVerifier.verifySuccess(
                                bot,
                                plan.currentTask,
                                plan.currentSkillVerifier,
                                snapshotContext(plan));
                        if (verified.status() == SkillOutcome.Status.SUCCEEDED) {
                            plan.completedSteps++;
                            verifiedChildProgress = true;
                            plan.terminalOutcome = null;
                            if (plan.currentSkillSpec != null) {
                                plan.recoveryLedger.markVerifiedProgress(plan.currentSkillSpec);
                            }
                        } else {
                            plan.terminalOutcome = verified;
                            interruptedChildOutcome = verified;
                        }
                    }
                    case FAILED, CANCELLED, PENDING, RUNNING, PAUSED ->
                            interruptedChildOutcome = interruptedTerminalOutcome(interruptedStatus);
                }
            }
            GoalEvaluation interruptedEvaluation = evaluate(bot, plan);
            TaskManager.INSTANCE.cancelMissionTasks(
                    bot, plan.missionId, "mission_policy_replan_after_interrupt");
            if (interruptedEvaluation.state() == GoalEvaluation.State.SATISFIED) {
                finishActive(bot, plan, interruptedEvaluation,
                        "postcondition_satisfied_after_interrupt", false, false, null);
                return false;
            }
            if (interruptedChildOutcome != null) {
                plan.replanAfterInterrupt = false;
                handleStepFailure(server, bot, plan, interruptedChildOutcome);
                return true;
            }
            boolean verifiedProgress = verifiedChildProgress
                    || interruptedEvaluation.matched() > plan.lastEvaluationMatched;
            plan.lastEvaluationMatched = Math.max(
                    plan.lastEvaluationMatched, interruptedEvaluation.matched());
            Optional<String> recoveryDenied = reserveRecovery(
                    bot, plan, RecoveryLedger.RecoveryKind.INTERRUPT_REPLAN, verifiedProgress);
            if (recoveryDenied.isPresent()) {
                plan.replanAfterInterrupt = false;
                plan.terminalOutcome = blockedOutcome(
                        recoveryDenied.get(), SkillOutcome.FailureKind.SAFETY, Map.of());
                finishActive(bot, plan, interruptedEvaluation,
                        recoveryDenied.get(), false, true, GoalResult.Status.BLOCKED);
                return false;
            }
            GoalPlanner.GoalPlan fresh = GoalPlanner.plan(bot, plan.goal, snapshotContext(plan));
            if (!fresh.success() || fresh.steps().isEmpty()) {
                plan.replanAfterInterrupt = false;
                String terminalReason = "interrupt_replan_failed:"
                        + String.join(",", fresh.unresolved());
                plan.terminalOutcome = blockedOutcome(
                        terminalReason, SkillOutcome.FailureKind.SAFETY, Map.of());
                finishActive(bot, plan, interruptedEvaluation,
                        terminalReason, false, true, GoalResult.Status.BLOCKED);
                return false;
            }
            Optional<String> rebuildFailure = replacePlanSkills(plan, fresh.steps());
            if (rebuildFailure.isPresent()) {
                plan.replanAfterInterrupt = false;
                plan.terminalOutcome = blockedOutcome(
                        rebuildFailure.get(), SkillOutcome.FailureKind.SAFETY,
                        Map.of("phase", "interrupt_replan"));
                finishActive(bot, plan, interruptedEvaluation,
                        rebuildFailure.get(), false, true, GoalResult.Status.BLOCKED);
                return false;
            }
            clearCurrentSkill(plan);
            plan.terminalOutcome = null;
            plan.replanAfterInterrupt = false;
            plan.transition(MissionState.RECOVERING);
            assignNext(bot, plan);
            return true;
        }
        if (resumedMissionChild) {
            plan.transition(MissionState.RUNNING);
        } else if (terminalMissionChildAfterInterrupt) {
            // The child became terminal while the interrupt unwound; restore a legal lifecycle
            // state so the authoritative child outcome below can be consumed.
            plan.transition(MissionState.RUNNING);
        }
        if (active.isPresent()) {
            // A Mission submitted during a safety/background task is planned but owns no Task yet.
            // The arbiter keeps it queued here; once that work ends, the normal idle branch below
            // installs the first Skill without pretending the foreign Task completed it.
            if (plan.currentTask == null) {
                return true;
            }
            if (plan.currentTask != null && active.get() != plan.currentTask) {
                // FREEZE fix:有外来活跃任务时,先看我们的 step 是否被暂存进 paused 池。
                // 生存任务(战斗/逃跑/进食)抢占会把当前 step pauseFor 进 paused 池——这是临时抢占,
                // 打完会 resume,绝不能放弃整个目标(实测:刷怪→combat→goal_abandoned×12→从零重规划空转)。
                if (TaskManager.INSTANCE.hasPaused(bot)) {
                    return true;
                }
                Optional<TaskOrigin> foreignOrigin = TaskManager.INSTANCE.activeOrigin(bot);
                if (foreignOrigin.filter(GoalExecutor::temporaryInterrupt).isPresent()) {
                    return true;
                }
                // step 既不活跃也不在暂停池 = 被玩家显式指令真正替换 → 放弃目标让位。
                BotLog.task(bot, "goal_abandoned", "goal", plan.goal, "reason", "foreign_task_assigned");
                finishActive(bot, plan, evaluate(bot, plan), "foreign_task_assigned", false, true);
                return false;
            }
            return true;
        }
        // GOALFIX-GF1 P0-B:当前步被安全网暂停(生存任务抢占)→ 等待 resume,不要误判为步骤结束而跳步。
        if (TaskManager.INSTANCE.hasPaused(bot)) {
            return true;
        }
        if (plan.current == null) {
            assignNext(bot, plan);
            return true;
        }
        // Read the Mission-owned Task object, not the global last status. A safety/reflex Task can
        // start in the one-tick gap after this child completed and overwrite TaskManager.lastStatus.
        TaskStatus status = TaskStatus.from(plan.currentTask);
        if (status.state() == TaskState.COMPLETED) {
            captureTaskEvidence(plan);
            SkillOutcome outcome = LegacySkillVerifier.verifySuccess(
                    bot,
                    plan.currentTask,
                    plan.currentSkillVerifier,
                    snapshotContext(plan));
            BotLog.task(bot, "skill_outcome", "skill", currentSkillId(plan),
                    "status", outcome.status(), "failure_kind", outcome.failureKind(),
                    "reason", outcome.reason(), "progress", outcome.progress(),
                    "evidence", outcome.evidence());
            if (outcome.status() != SkillOutcome.Status.SUCCEEDED) {
                acceptSkillOutcome(server, bot, plan, outcome);
                return true;
            }
            acceptSkillOutcome(server, bot, plan, outcome);
            return true;
        }
        if (status.state() == TaskState.FAILED) {
            SkillOutcome outcome = SkillOutcome.fromLegacyFailure(
                    status.failureReason(), (int) Math.round(status.progress() * 1_000.0D));
            acceptSkillOutcome(server, bot, plan, outcome);
            return true;
        }
        if (status.state() == TaskState.CANCELLED) {
            SkillOutcome outcome = SkillOutcome.cancelled(
                    status.failureReason(), (int) Math.round(status.progress() * 1_000.0D));
            BotLog.task(bot, "skill_outcome", "skill", currentSkillId(plan),
                    "status", outcome.status(), "reason", outcome.reason());
            acceptSkillOutcome(server, bot, plan, outcome);
            return true;
        }
        // GOALFIX-GF1 P0-B:其它状态(如上一任务残留的 lastStatus)→ 防御性 no-op,
        // 步骤推进只由 COMPLETED 分支驱动,失败由 FAILED 分支驱动。
        return true;
    }

    public boolean hasActivePlan(AIPlayerEntity bot) {
        return activePlans.containsKey(bot.getUUID());
    }

    /** Exact active-goal probe used by deterministic runtime verification and diagnostics. */
    public boolean isActiveGoal(AIPlayerEntity bot, Goal goal) {
        ActivePlan plan = activePlans.get(bot.getUUID());
        return plan != null && plan.goal.equals(goal);
    }

    public MissionState activeMissionState(AIPlayerEntity bot) {
        ActivePlan plan = activePlans.get(bot.getUUID());
        return plan == null ? null : plan.state;
    }

    public Optional<GoalSpec> activeGoalSpec(AIPlayerEntity bot) {
        ActivePlan plan = activePlans.get(bot.getUUID());
        return plan == null ? Optional.empty() : Optional.of(plan.missionPlan.goal());
    }

    /** Delivers one edge-triggered event to a waiting composite Mission plan. */
    public boolean signalMissionEvent(AIPlayerEntity bot, String eventKey) {
        ActivePlan plan = activePlans.get(bot.getUUID());
        if (plan == null || plan.state.terminal()) {
            return false;
        }
        PlanCursor.Snapshot before = plan.planCursor.snapshot();
        if (before.state() == PlanCursor.State.SUCCEEDED
                || before.state() == PlanCursor.State.FAILED) {
            return false;
        }
        PlanCursor.Snapshot after = plan.planCursor.signalEvent(eventKey, plan.elapsedMissionTicks);
        boolean consumed = !before.equals(after);
        if (consumed) {
            if (plan.currentTask == null) {
                assignNext(bot, plan);
            } else {
                markDirty(bot);
            }
        }
        return consumed;
    }

    public void clear(AIPlayerEntity bot) {
        cancelAll(bot);
    }

    /** Detaches only the active mission. Queue promotion is deliberately a separate step. */
    public boolean cancelCurrent(AIPlayerEntity bot) {
        return cancelCurrent(bot, "intent_cancelled");
    }

    public boolean cancelCurrent(AIPlayerEntity bot, String reason) {
        UUID uuid = bot.getUUID();
        ActivePlan active = activePlans.get(uuid);
        boolean changed = active != null;
        if (active != null) {
            finishActive(bot, active, evaluate(bot, active), reason, true, false);
        }
        changed |= userGoal.remove(uuid) != null;
        changed |= lastGoalFailTick.remove(uuid) != null;
        if (changed) {
            io.github.greytaiwolf.fakeaiplayer.task.EpisodeMemory.INSTANCE.reset(uuid);
        }
        return changed;
    }

    public int clearQueue(AIPlayerEntity bot) {
        java.util.Deque<GoalRequest> queued = goalQueue.remove(bot.getUUID());
        int removed = queued == null ? 0 : queued.size();
        if (removed > 0) {
            markDirty(bot);
        }
        return removed;
    }

    public boolean cancelAll(AIPlayerEntity bot) {
        boolean changed = cancelCurrent(bot);
        return clearQueue(bot) > 0 || changed;
    }

    /** Death is a factual failure, not a user cancellation. Queue promotion waits for recovery to finish. */
    public boolean failCurrent(AIPlayerEntity bot, String reason) {
        ActivePlan active = activePlans.get(bot.getUUID());
        if (active == null) {
            return false;
        }
        finishActive(bot, active, evaluate(bot, active), reason, false, false, GoalResult.Status.FAILED);
        return true;
    }

    /** Drop every in-memory projection for a Bot without publishing a terminal result (server unload path). */
    public void unload(AIPlayerEntity bot) {
        UUID uuid = bot.getUUID();
        activePlans.remove(uuid);
        goalQueue.remove(uuid);
        lastGoalFailTick.remove(uuid);
        userGoal.remove(uuid);
        lastResults.remove(uuid);
        io.github.greytaiwolf.fakeaiplayer.task.EpisodeMemory.INSTANCE.reset(uuid);
    }

    public void clearAllRuntime() {
        activePlans.clear();
        goalQueue.clear();
        lastGoalFailTick.clear();
        userGoal.clear();
        lastResults.clear();
    }

    public int queuedGoalCount(AIPlayerEntity bot) {
        java.util.Deque<GoalRequest> queued = goalQueue.get(bot.getUUID());
        return queued == null ? 0 : queued.size();
    }

    public Optional<GoalResult> lastResult(AIPlayerEntity bot) {
        return Optional.ofNullable(lastResults.get(bot.getUUID()));
    }

    public Optional<GoalResult> resultAfter(AIPlayerEntity bot, long sequence) {
        GoalResult result = lastResults.get(bot.getUUID());
        return result != null && result.sequence() > sequence ? Optional.of(result) : Optional.empty();
    }

    public String resultSummary(GoalResult result) {
        return resultMessage(result.status(), result.evaluation(), result.reason());
    }

    public MissionRuntimeRecord captureRuntime(AIPlayerEntity bot) {
        ActivePlan active = activePlans.get(bot.getUUID());
        if (active != null) {
            captureTaskEvidence(active);
        }
        MissionRecord activeRecord = active == null ? null : new MissionRecord(
                active.missionId.toString(), MissionSpec.fromGoal(
                active.goal,
                active.missionPlan.goal().source(),
                active.missionPlan.goal().priority(),
                active.missionPlan.goal().policy()), checkpoint(active));
        java.util.Deque<GoalRequest> queued = goalQueue.get(bot.getUUID());
        List<MissionSpec> queue = queued == null ? List.of() : queued.stream()
                .map(request -> MissionSpec.fromGoal(
                        request.goal(), request.source(), request.priority()))
                .toList();
        boolean hasPersistableMission = activeRecord != null || !queue.isEmpty();
        return new MissionRuntimeRecord(activeRecord, queue,
                hasPersistableMission && TaskManager.INSTANCE.isUserPaused(bot));
    }

    public void restoreRuntime(AIPlayerEntity bot, MissionRuntimeRecord runtime) {
        if (runtime == null) {
            return;
        }
        MissionRecord activeRecord = runtime.active();
        boolean restorePaused = runtime.userPaused()
                && (activeRecord != null || !runtime.queue().isEmpty());
        if (restorePaused) {
            // Install the persistent owner before submit() can start a Skill with onStart side
            // effects. The arbiter will keep restored work planned until the user resumes.
            TaskManager.INSTANCE.pauseUserIntent(bot, "restore_persisted_pause");
        }
        if (activeRecord != null && activeRecord.spec() != null) {
            Optional<Goal> restored = activeRecord.spec().toGoal();
            if (restored.isPresent()) {
                try {
                    // Recovery itself re-reads and verifies the persisted generated blueprint.
                    // submit performs the same check again after seed construction, closing the
                    // restore-time file replacement window.
                    RestoreSeed seed = restoreSeed(bot, restored.get(), activeRecord);
                    submit(bot, restored.get(), seed.source(), seed.priority(), seed);
                } catch (IOException exception) {
                    BotLog.warn(io.github.greytaiwolf.fakeaiplayer.log.LogCategory.LIFECYCLE, bot,
                            "mission_restore_isolated", "type", activeRecord.spec().type(),
                            "reason", exception.getMessage());
                }
            } else if (restored.isEmpty()) {
                BotLog.warn(io.github.greytaiwolf.fakeaiplayer.log.LogCategory.LIFECYCLE, bot,
                        "mission_restore_isolated", "type", activeRecord.spec().type(), "reason", "invalid_spec");
            }
        }
        java.util.Deque<GoalRequest> restoredQueue = goalQueue.computeIfAbsent(
                bot.getUUID(), ignored -> new java.util.concurrent.ConcurrentLinkedDeque<>());
        for (MissionSpec spec : runtime.queue()) {
            Optional<Goal> restoredGoal = spec.toGoal();
            boolean currentBinding = spec.bindingValid();
            boolean legacyMigration = spec.legacyUnboundShape();
            if (restoredGoal.isPresent() && (currentBinding || legacyMigration)) {
                GoalSpec.Source restoredSource = currentBinding
                        ? spec.sourceOrRestored() : GoalSpec.Source.RESTORED;
                int restoredPriority = currentBinding
                        ? spec.priorityOrDefault()
                        : GoalSpec.defaultPriority(GoalSpec.Source.RESTORED);
                restoredQueue.addLast(new GoalRequest(
                        restoredGoal.get(),
                        restoredSource,
                        restoredPriority,
                        submissionSequence.incrementAndGet()));
            } else {
                BotLog.warn(io.github.greytaiwolf.fakeaiplayer.log.LogCategory.LIFECYCLE, bot,
                        "mission_queue_restore_isolated", "type", spec.type(),
                        "reason", restoredGoal.isEmpty()
                                ? "invalid_spec" : "mission_spec_binding_invalid");
            }
        }
        if (restoredQueue.isEmpty()) {
            goalQueue.remove(bot.getUUID(), restoredQueue);
        } else if (!hasActivePlan(bot)) {
            advanceQueue(bot);
        }
        if (restorePaused && !hasActivePlan(bot) && queuedGoalCount(bot) == 0) {
            // Every persisted entry was invalid; do not leave an otherwise idle bot user-locked.
            TaskManager.INSTANCE.resumeUserIntent(bot, "restore_persisted_pause_empty");
        }
    }

    private static Map<String, String> checkpoint(ActivePlan active) {
        // Persist only replay-safe evidence. The binding identifies the executable contract that
        // produced this accounting; restore still replans from authoritative world state and
        // advances the plan revision when the remaining contract legitimately changes.
        verifyStableCheckpointBoundary(active);
        Map<String, String> checkpoint = new java.util.LinkedHashMap<>();
        checkpoint.put("origin", encodePos(active.origin));
        checkpoint.put("started_tick", String.valueOf(active.startedTick));
        if (active.buildAnchor != null) {
            checkpoint.put("build_anchor", encodePos(active.buildAnchor));
            checkpoint.put("build_placed", String.valueOf(active.buildPlaced));
            checkpoint.put("build_skipped", String.valueOf(active.buildSkipped));
        }
        if (!active.boundContainers.isEmpty()) {
            checkpoint.put("containers", active.boundContainers.stream().map(GoalExecutor::encodePos).sorted()
                    .collect(java.util.stream.Collectors.joining(";")));
        }
        MissionCheckpointCodec.ProgressSnapshot progress = new MissionCheckpointCodec.ProgressSnapshot(
                active.snapSteps,
                OptionalInt.of(active.snapTargetCount),
                Optional.of(new MissionCheckpointCodec.Position(
                        active.snapX, active.snapY, active.snapZ)));
        return MissionCheckpointCodec.encode(checkpoint, new MissionCheckpointCodec.Checkpoint(
                active.missionId,
                active.missionPlan.revision(),
                active.missionPlan.fingerprint(),
                active.missionPlan.intentFingerprint(),
                contextFingerprint(active),
                active.completedSteps,
                active.elapsedMissionTicks,
                active.recoveryLedger.snapshot(),
                progress,
                active.replanAfterInterrupt,
                active.planCursor.checkpoint()));
    }

    /**
     * A READY cursor is durable only together with the attempt reservation for that exact
     * activation. Running and arbiter-deferred Tasks deliberately restore through the same
     * reservation path, so no JVM-local Task identity is persisted; every other mixed cursor/Task
     * state is rejected before it can become a V3 checkpoint. The sole exception is a durable
     * REPLAN_AFTER_SAFETY obligation: its cursor is intentionally discarded before execution and
     * therefore must not consume a new-plan attempt merely to survive another restart.
     */
    private static void verifyStableCheckpointBoundary(ActivePlan active) {
        PlanCursor.Snapshot cursor = active.planCursor.snapshot();
        if (cursor.state() == PlanCursor.State.READY) {
            if (cursor.readySkills().size() != 1) {
                throw new IllegalStateException("mission_checkpoint_ready_cardinality_invalid");
            }
            PlanCursor.ReadySkill ready = cursor.readySkills().getFirst();
            if (active.currentSkillLease != null) {
                if (!active.currentSkillLease.equals(ready.lease())
                        || active.currentTask == null
                        || active.pendingAttemptReservation) {
                    throw new IllegalStateException(
                            "mission_checkpoint_running_activation_mismatch");
                }
            } else if (active.currentTask != null
                    || !active.pendingAttemptReservation && !active.replanAfterInterrupt) {
                throw new IllegalStateException(
                        "mission_checkpoint_pending_activation_unreserved");
            }
            int attempts = active.recoveryLedger.attemptsFor(ready.spec());
            boolean reservedActivation = active.currentSkillLease != null
                    || active.pendingAttemptReservation;
            if (reservedActivation && (attempts < 1
                    || attempts > ready.spec().retryPolicy().maxAttempts())) {
                throw new IllegalStateException(
                        "mission_checkpoint_attempt_reservation_invalid");
            }
            return;
        }
        if (active.currentSkillLease != null
                || active.currentSkillSpec != null
                || active.currentTask != null
                || active.pendingAttemptReservation) {
            throw new IllegalStateException("mission_checkpoint_cursor_task_mismatch");
        }
    }

    private static RestoreSeed restoreSeed(AIPlayerEntity bot,
                                           Goal goal,
                                           MissionRecord record) throws IOException {
        Map<String, String> checkpoint = record.checkpoint() == null ? Map.of() : record.checkpoint();
        MissionCheckpointCodec.DecodeResult decoded = MissionCheckpointCodec.decode(checkpoint);
        if (!decoded.valid()) {
            throw new IOException("mission_checkpoint_" + decoded.status().name().toLowerCase(
                    java.util.Locale.ROOT) + ':' + decoded.reason());
        }
        MissionCheckpointCodec.Checkpoint runtime = decoded.checkpoint();
        MissionSpec persistedSpec = record.spec();
        if (runtime.bound()) {
            if (persistedSpec == null || !persistedSpec.bindingValid()) {
                throw new IOException("mission_spec_binding_invalid");
            }
        } else if (persistedSpec == null || !persistedSpec.legacyUnboundShape()) {
            // A current MissionSpec with a missing runtime payload is corruption, not a V0 save.
            // Never silently reset recovery accounting or admission authority via downgrade.
            throw new IOException("mission_checkpoint_downgrade_detected");
        }
        GoalSnapshotCollector.Context fallback = initialContext(bot, goal, null);
        boolean currentCheckpoint = decoded.version() == MissionCheckpointCodec.CURRENT_VERSION;
        BlockPos origin = currentCheckpoint
                ? decodeRequiredPos(checkpoint.get("origin"), "checkpoint_origin")
                : decodePos(checkpoint.get("origin")).orElse(fallback.origin());
        Set<BlockPos> containers = currentCheckpoint
                ? decodeCurrentContainers(checkpoint.get("containers"))
                : decodeLegacyContainers(checkpoint.get("containers"));
        // A Build goal may resume only at its confirmation-bound anchor. Never promote an old
        // auto-site checkpoint into a trusted binding during migration.
        BlockPos buildAnchor;
        int buildPlaced;
        int buildSkipped;
        if (currentCheckpoint) {
            verifyCurrentCheckpointShape(checkpoint, goal, !containers.isEmpty());
            // The old absolute value is deliberately parsed for canonicality but never reused:
            // server tick counters restart with the JVM.
            decodeRequiredNonNegative(checkpoint.get("started_tick"), "checkpoint_started_tick");
            if (goal instanceof Goal.Build build) {
                buildAnchor = decodeRequiredPos(
                        checkpoint.get("build_anchor"), "checkpoint_build_anchor");
                if (!build.anchor().equals(buildAnchor)) {
                    throw new IOException("checkpoint_build_anchor_mismatch");
                }
                buildPlaced = decodeRequiredNonNegative(
                        checkpoint.get("build_placed"), "checkpoint_build_placed");
                buildSkipped = decodeRequiredNonNegative(
                        checkpoint.get("build_skipped"), "checkpoint_build_skipped");
            } else {
                buildAnchor = null;
                buildPlaced = 0;
                buildSkipped = 0;
            }
        } else {
            buildAnchor = goal instanceof Goal.Build build
                    ? build.anchor()
                    : decodePos(checkpoint.get("build_anchor")).orElse(fallback.buildAnchor());
            buildPlaced = nonNegativeInt(checkpoint.get("build_placed"));
            buildSkipped = nonNegativeInt(checkpoint.get("build_skipped"));
        }
        BlueprintSchema blueprint = null;
        if (goal instanceof Goal.Build build && buildAnchor != null) {
            blueprint = validateAndLoadBuildGoal(bot, build);
        }
        GoalSnapshotCollector.Context context = new GoalSnapshotCollector.Context(
                origin, containers, blueprint, buildAnchor,
                buildPlaced, buildSkipped);
        if (currentCheckpoint && !runtime.contextFingerprint().equals(contextFingerprint(
                origin, containers, buildAnchor, buildPlaced, buildSkipped))) {
            throw new IOException("mission_checkpoint_context_mismatch");
        }
        UUID missionId;
        try {
            missionId = UUID.fromString(record.missionId());
        } catch (RuntimeException exception) {
            throw new IOException("mission_record_id_invalid", exception);
        }
        if (runtime.bound() && !missionId.equals(runtime.missionId())) {
            throw new IOException("mission_checkpoint_id_mismatch");
        }
        MissionPolicy restoredPolicy = null;
        if (runtime.bound() && persistedSpec.policyPresent()) {
            restoredPolicy = persistedSpec.persistedPolicy().orElseThrow(
                    () -> new IOException("mission_policy_invalid"));
        }
        // Server ticks restart with each JVM. Preserve the durable active-work budget separately,
        // but rebase result timestamps so a restored Mission can never finish before it started.
        int restoredStartedTick = bot.getServer().getTickCount();
        return new RestoreSeed(
                missionId,
                context,
                runtime.completedSteps(),
                restoredStartedTick,
                runtime.elapsedMissionTicks(),
                runtime.recovery(),
                runtime.progress(),
                runtime.bound() ? persistedSpec.sourceOrRestored() : GoalSpec.Source.RESTORED,
                runtime.bound() ? persistedSpec.priorityOrDefault()
                        : GoalSpec.defaultPriority(GoalSpec.Source.RESTORED),
                restoredPolicy,
                runtime.bound() ? runtime.planRevision() : 0,
                runtime.bound() ? runtime.planFingerprint() : null,
                runtime.bound() ? runtime.intentFingerprint() : null,
                runtime.current() && runtime.replanAfterInterrupt(),
                runtime.current() ? runtime.cursor() : null);
    }

    private static Set<BlockPos> decodeLegacyContainers(String encodedContainers) {
        Set<BlockPos> containers = new HashSet<>();
        if (encodedContainers != null && !encodedContainers.isBlank()) {
            for (String encoded : encodedContainers.split(";")) {
                decodePos(encoded).ifPresent(containers::add);
            }
        }
        return containers;
    }

    private static Set<BlockPos> decodeCurrentContainers(String encodedContainers)
            throws IOException {
        if (encodedContainers == null) {
            return Set.of();
        }
        if (encodedContainers.isBlank()) {
            throw new IOException("checkpoint_containers_invalid");
        }
        Set<BlockPos> containers = new HashSet<>();
        List<String> encoded = List.of(encodedContainers.split(";", -1));
        List<String> sorted = encoded.stream().sorted().toList();
        if (!encoded.equals(sorted)) {
            throw new IOException("checkpoint_containers_noncanonical");
        }
        for (String position : encoded) {
            BlockPos decoded = decodeRequiredPos(position, "checkpoint_container");
            if (!containers.add(decoded)) {
                throw new IOException("checkpoint_containers_duplicate");
            }
        }
        return Set.copyOf(containers);
    }

    private static void verifyCurrentCheckpointShape(Map<String, String> checkpoint,
                                                     Goal goal,
                                                     boolean hasContainers) throws IOException {
        Set<String> expected = new HashSet<>(Set.of(
                "origin",
                "started_tick",
                MissionCheckpointCodec.LEGACY_REVISION,
                MissionCheckpointCodec.ELAPSED_MISSION_TICKS,
                MissionCheckpointCodec.RUNTIME_BUDGET_V3));
        if (hasContainers) {
            expected.add("containers");
        }
        if (goal instanceof Goal.Build) {
            expected.add("build_anchor");
            expected.add("build_placed");
            expected.add("build_skipped");
        }
        if (!checkpoint.keySet().equals(expected)) {
            throw new IOException("mission_checkpoint_metadata_shape_invalid");
        }
    }

    private static int nonNegativeInt(String value) {
        try {
            return Math.max(0, Integer.parseInt(value));
        } catch (RuntimeException exception) {
            return 0;
        }
    }

    private static String encodePos(net.minecraft.core.BlockPos pos) {
        return pos.getX() + "," + pos.getY() + "," + pos.getZ();
    }

    /** Binds every persisted field that can change Goal evaluation or repair planning. */
    private static String contextFingerprint(BlockPos origin,
                                             Set<BlockPos> containers,
                                             BlockPos buildAnchor,
                                             int buildPlaced,
                                             int buildSkipped) {
        if (origin == null || containers == null || buildPlaced < 0 || buildSkipped < 0) {
            throw new IllegalArgumentException("mission_context_invalid");
        }
        StringBuilder canonical = new StringBuilder();
        appendContextField(canonical, "origin", encodePos(origin));
        List<String> sortedContainers = containers.stream().map(GoalExecutor::encodePos).sorted().toList();
        appendContextField(canonical, "containers.size", Integer.toString(sortedContainers.size()));
        sortedContainers.forEach(value -> appendContextField(canonical, "container", value));
        appendContextField(canonical, "build_anchor.present", Boolean.toString(buildAnchor != null));
        if (buildAnchor != null) {
            appendContextField(canonical, "build_anchor", encodePos(buildAnchor));
        }
        appendContextField(canonical, "build_placed", Integer.toString(buildPlaced));
        appendContextField(canonical, "build_skipped", Integer.toString(buildSkipped));
        try {
            return HEX.formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("sha256_unavailable", exception);
        }
    }

    private static String contextFingerprint(ActivePlan active) {
        return contextFingerprint(active.origin, active.boundContainers, active.buildAnchor,
                active.buildPlaced, active.buildSkipped);
    }

    private static void appendContextField(StringBuilder target, String key, String value) {
        target.append(key.length()).append(':').append(key).append(';')
                .append(value.length()).append(':').append(value).append(';');
    }

    private static Optional<net.minecraft.core.BlockPos> decodePos(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        try {
            String[] parts = value.split(",");
            if (parts.length != 3) {
                return Optional.empty();
            }
            return Optional.of(new net.minecraft.core.BlockPos(
                    Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), Integer.parseInt(parts[2])));
        } catch (RuntimeException ignored) {
            return Optional.empty();
        }
    }

    private static BlockPos decodeRequiredPos(String value, String field) throws IOException {
        BlockPos decoded = decodePos(value).orElseThrow(() -> new IOException(field + "_invalid"));
        if (!encodePos(decoded).equals(value)) {
            throw new IOException(field + "_noncanonical");
        }
        return decoded;
    }

    private static int decodeRequiredNonNegative(String value, String field) throws IOException {
        if (value == null || !value.matches("0|[1-9][0-9]*")) {
            throw new IOException(field + "_invalid");
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException exception) {
            throw new IOException(field + "_invalid", exception);
        }
    }

    public boolean startNextQueuedIfIdle(AIPlayerEntity bot) {
        if (hasActivePlan(bot)) {
            return false;
        }
        return advanceQueue(bot);
    }

    /** 诊断埋点:当前激活的顶层目标(无则 "none")。日志用,保留英文便于排查。 */
    public String describeActiveGoal(AIPlayerEntity bot) {
        ActivePlan plan = activePlans.get(bot.getUUID());
        return plan == null ? "none" : String.valueOf(plan.goal);
    }

    /** 面板任务链条:目标标题(中文)。物品 id 保留 minecraft:xxx,客户端再本地化成中文名。 */
    public String activeGoalTitle(AIPlayerEntity bot) {
        ActivePlan plan = activePlans.get(bot.getUUID());
        return plan == null ? "无目标" : goalLabel(plan.goal);
    }

    private static String goalLabel(Goal goal) {
        return switch (goal) {
            case Goal.HaveItem g -> "获取 " + io.github.greytaiwolf.fakeaiplayer.craft.ItemNames.cn(g.item()) + " ×" + g.count();
            case Goal.MineOre g -> "采矿 ×" + g.count();
            case Goal.HarvestCrop g -> "种收 " + io.github.greytaiwolf.fakeaiplayer.craft.ItemNames.cn(g.produce()) + " ×" + g.count();
            case Goal.Food g -> "备食物(熟食) ×" + g.cookedCount();
            case Goal.Armor g -> "武装(整套护甲+剑)";
            case Goal.Workstation g -> "搭建工作站";
            case Goal.Stockpile g -> "囤货 " + io.github.greytaiwolf.fakeaiplayer.craft.ItemNames.cn(g.item()) + " ×" + g.count();
            case Goal.HavePickaxeTier g -> "升级镐 (tier " + g.tier() + ")";
            case Goal.Build g -> "盖房子(" + g.blueprint() + ")";
        };
    }

    /** 诊断埋点:当前正在执行的步骤 + 进度 [第几步/总步数](无激活步则 "")。 */
    public String describeActiveStep(AIPlayerEntity bot) {
        ActivePlan plan = activePlans.get(bot.getUUID());
        if (plan == null || plan.current == null) {
            return "";
        }
        int idx = Math.min(plan.totalSteps, plan.completedSteps + 1);
        return plan.current.describe() + " [" + idx + "/" + plan.totalSteps + "]";
    }

    /** 面板任务链条:完整步骤描述列表(无激活计划则空)。 */
    public java.util.List<String> activeGoalSteps(AIPlayerEntity bot) {
        ActivePlan plan = activePlans.get(bot.getUUID());
        return plan == null ? java.util.List.of() : java.util.List.copyOf(plan.stepLabels);
    }

    /** 面板任务链条:当前所处步骤的 0 基下标。 */
    public int activeGoalCurrentIndex(AIPlayerEntity bot) {
        ActivePlan plan = activePlans.get(bot.getUUID());
        if (plan == null) {
            return 0;
        }
        return currentStepIndex(plan.totalSteps, queuedStepCount(plan), plan.current != null);
    }

    /** 面板任务链条:总步数。 */
    public int activeGoalTotalSteps(AIPlayerEntity bot) {
        ActivePlan plan = activePlans.get(bot.getUUID());
        return plan == null ? 0 : plan.totalSteps;
    }

    // P0 队列衔接:当前目标了结(完成/失败)后,自动开始队列里的下一个;规划失败的逐个跳过并说明。
    private boolean advanceQueue(AIPlayerEntity bot) {
        java.util.Deque<GoalRequest> queued = goalQueue.get(bot.getUUID());
        if (queued == null) {
            return false;
        }
        GoalRequest next;
        while ((next = highestPriorityRequest(queued)) != null) {
            queued.removeFirstOccurrence(next);
            report(bot, "接着办下一件:" + goalLabel(next.goal()));
            if (submit(bot, next.goal(), next.source(), next.priority())) {
                if (hasActivePlan(bot)) {
                    return true;
                }
                // 目标已经满足、没有创建 active plan：继续检查队列下一项。
            }
            // submit 失败(规划不成/被拦)已在内部 report 过原因,继续试队列里再下一个
        }
        goalQueue.remove(bot.getUUID(), queued);
        return false;
    }

    private static GoalRequest highestPriorityRequest(java.util.Deque<GoalRequest> queued) {
        return queued.stream()
                .max(java.util.Comparator.comparingInt(
                                (GoalRequest request) -> sourceAuthority(request.source()))
                        .thenComparingInt(GoalRequest::priority)
                        .thenComparing(java.util.Comparator.comparingLong(
                                GoalRequest::sequence).reversed()))
                .orElse(null);
    }

    private static boolean removeQueuedGoal(java.util.Deque<GoalRequest> queue, Goal goal) {
        GoalRequest match = queue.stream()
                .filter(request -> request.goal().equals(goal))
                .findFirst()
                .orElse(null);
        return match != null && queue.removeFirstOccurrence(match);
    }

    private static boolean upgradeAdmission(ActivePlan plan,
                                            GoalSpec.Source source,
                                            int priority) {
        GoalSpec current = plan.missionPlan.goal();
        if (!strongerAdmission(source, priority, current.source(), current.priority())) {
            return false;
        }
        if (plan.missionPlan.revision() == Integer.MAX_VALUE) {
            return false;
        }
        GoalSpec upgraded = new GoalSpec(
                current.type(), source, priority, current.successPredicate(), current.dimension(),
                current.policy(), current.attributes());
        MissionPlan upgradedPlan = new MissionPlan(
                plan.missionPlan.missionId(), plan.missionPlan.revision() + 1, upgraded,
                plan.missionPlan.root(), plan.missionPlan.plannerVersion());
        final PlanCursor upgradedCursor;
        final PlanCursor.ActivationLease upgradedLease;
        final LegacySkillVerifier.Session upgradedVerifier;
        try {
            upgradedCursor = plan.planCursor.rebindAdmission(upgradedPlan);
            if (plan.currentSkillLease == null) {
                upgradedLease = null;
            } else {
                List<PlanCursor.ReadySkill> matching = upgradedCursor.snapshot().readySkills().stream()
                        .filter(ready -> plan.currentSkillSpec != null
                                && ready.spec().equals(plan.currentSkillSpec)
                                && ready.spec().invocationId().equals(
                                plan.currentSkillLease.invocationId()))
                        .toList();
                if (matching.size() != 1) {
                    return false;
                }
                upgradedLease = matching.getFirst().lease();
            }
            upgradedVerifier = plan.currentSkillVerifier == null
                    ? null
                    : LegacySkillVerifier.rebindAdmission(
                    plan.currentSkillVerifier, upgraded);
        } catch (RuntimeException invalidRebind) {
            return false;
        }
        // Commit only after all dependent runtime identities were rebuilt successfully.
        plan.missionPlan = upgradedPlan;
        plan.planCursor = upgradedCursor;
        plan.currentSkillLease = upgradedLease;
        plan.currentSkillVerifier = upgradedVerifier;
        return true;
    }

    private static boolean strongerAdmission(GoalSpec.Source incomingSource,
                                              int incomingPriority,
                                              GoalSpec.Source currentSource,
                                              int currentPriority) {
        int incomingAuthority = sourceAuthority(incomingSource);
        int currentAuthority = sourceAuthority(currentSource);
        return incomingAuthority > currentAuthority
                || incomingAuthority == currentAuthority && incomingPriority > currentPriority;
    }

    private static int sourceAuthority(GoalSpec.Source source) {
        return MissionArbiter.sourceAuthority(source);
    }

    private void assignNext(AIPlayerEntity bot, ActivePlan plan) {
        PlanCursor.Snapshot cursor = plan.planCursor.advanceTo(plan.elapsedMissionTicks);
        if (cursor.state() == PlanCursor.State.FAILED) {
            SkillOutcome outcome = cursor.failure().orElseThrow().outcome();
            plan.terminalOutcome = outcome;
            GoalResult.Status status = switch (outcome.status()) {
                case BLOCKED -> GoalResult.Status.BLOCKED;
                case CANCELLED -> GoalResult.Status.CANCELLED;
                default -> GoalResult.Status.FAILED;
            };
            finishActive(bot, plan, evaluate(bot, plan), outcome.reason(),
                    outcome.status() == SkillOutcome.Status.CANCELLED, true, status);
            return;
        }
        if (cursor.state() == PlanCursor.State.SUCCEEDED) {
            plan.transition(MissionState.VERIFYING);
            GoalEvaluation evaluation = evaluate(bot, plan);
            if (evaluation.state() == GoalEvaluation.State.SATISFIED) {
                finishActive(bot, plan, evaluation, "postcondition_satisfied", false, true);
                return;
            }
            if (!(plan.goal instanceof Goal.Build) && bot.isAlive()) {
                boolean progress = evaluation.matched() > plan.lastEvaluationMatched;
                Optional<String> recoveryDenied = reserveRecovery(
                        bot, plan, RecoveryLedger.RecoveryKind.POSTCONDITION, progress);
                if (recoveryDenied.isPresent()) {
                    plan.terminalOutcome = blockedOutcome(
                            recoveryDenied.get(), SkillOutcome.FailureKind.WORLD_CHANGED,
                            Map.of("phase", "postcondition"));
                    finishActive(bot, plan, evaluation, recoveryDenied.get(), false, true,
                            GoalResult.Status.BLOCKED);
                    return;
                }
                GoalPlanner.GoalPlan fresh = GoalPlanner.plan(bot, plan.goal, snapshotContext(plan));
                String fingerprint = fresh.describeSteps();
                if (fresh.success() && !fresh.steps().isEmpty()
                        && (progress || !fingerprint.equals(plan.lastRepairFingerprint))) {
                    plan.lastEvaluationMatched = Math.max(plan.lastEvaluationMatched, evaluation.matched());
                    plan.lastRepairFingerprint = fingerprint;
                    BotLog.task(bot, "goal_postcondition_repair", "goal", plan.goal,
                            "matched", evaluation.matched(), "required", evaluation.required(),
                            "steps", fingerprint, "repair",
                            plan.recoveryLedger.postconditionRecoveriesConsumed());
                    Optional<String> rebuildFailure = replacePlanSkills(plan, fresh.steps());
                    if (rebuildFailure.isPresent()) {
                        plan.terminalOutcome = blockedOutcome(
                                rebuildFailure.get(), SkillOutcome.FailureKind.WORLD_CHANGED,
                                Map.of("phase", "postcondition_repair"));
                        finishActive(bot, plan, evaluation, rebuildFailure.get(),
                                false, true, GoalResult.Status.BLOCKED);
                        return;
                    }
                    clearCurrentSkill(plan);
                    plan.transition(MissionState.RECOVERING);
                    assignNext(bot, plan);
                    return;
                }
            }
            plan.terminalOutcome = blockedOutcome(
                    "postcondition_unsatisfied", SkillOutcome.FailureKind.WORLD_CHANGED,
                    Map.of("matched", String.valueOf(evaluation.matched()),
                            "required", String.valueOf(evaluation.required())));
            finishActive(bot, plan, evaluation, "postcondition_unsatisfied", false, true,
                    GoalResult.Status.BLOCKED);
            return;
        }
        if (cursor.state() == PlanCursor.State.WAITING
                || cursor.state() == PlanCursor.State.RUNNING) {
            BotLog.task(bot, "mission_plan_waiting",
                    "mission_id", plan.missionId,
                    "events", cursor.waitingEvents());
            markDirty(bot);
            return;
        }
        if (cursor.readySkills().size() != 1) {
            SkillOutcome outcome = new SkillOutcome(
                    SkillOutcome.Status.FATAL_FAILURE,
                    SkillOutcome.FailureKind.INTERNAL,
                    "mission_runtime_ready_cardinality_invalid",
                    0,
                    Map.of("ready_count", Integer.toString(cursor.readySkills().size())));
            plan.terminalOutcome = outcome;
            finishActive(bot, plan, evaluate(bot, plan), outcome.reason(), false, true,
                    GoalResult.Status.FAILED);
            return;
        }
        PlanCursor.ReadySkill ready = cursor.readySkills().getFirst();
        LegacyMissionCompiler.ExecutableSkill executable =
                plan.executableSkills.get(ready.spec().invocationId());
        if (executable == null || !executable.spec().equals(ready.spec())) {
            SkillOutcome outcome = new SkillOutcome(
                    SkillOutcome.Status.FATAL_FAILURE,
                    SkillOutcome.FailureKind.INTERNAL,
                    "mission_runtime_skill_adapter_missing",
                    0,
                    Map.of("skill", ready.spec().invocationId()));
            plan.terminalOutcome = outcome;
            finishActive(bot, plan, evaluate(bot, plan), outcome.reason(), false, true,
                    GoalResult.Status.FAILED);
            return;
        }
        GoalStep step = executable.step();
        plan.current = step;
        plan.currentSkillSpec = executable.spec();
        plan.currentSkillLease = ready.lease();
        plan.currentUsesPendingAttemptReservation = plan.pendingAttemptReservation;
        plan.pendingAttemptReservation = false;
        RecoveryLedger.AttemptDecision attempt = plan.currentUsesPendingAttemptReservation
                ? restoredAttemptDecision(plan.recoveryLedger, executable.spec())
                : plan.recoveryLedger.beginAttempt(executable.spec());
        if (!attempt.allowed()) {
            BotLog.task(bot, "mission_skill_attempt_denied",
                    "mission_id", plan.missionId,
                    "skill", executable.spec().invocationId(),
                    "capability", executable.spec().id(),
                    "attempts", attempt.attempt(),
                    "max_attempts", attempt.maxAttempts(),
                    "reason", attempt.reason());
            acceptSkillOutcome(bot.getServer(), bot, plan, attemptDeniedOutcome(attempt));
            return;
        }
        TaskOrigin skillOrigin = TaskOrigin.mission(
                plan.missionId,
                executable.spec().invocationId(),
                plan.missionPlan.goal());
        io.github.greytaiwolf.fakeaiplayer.task.TaskAssignmentResult preview =
                TaskManager.INSTANCE.previewAssignment(bot, skillOrigin);
        if (!preview.started()) {
            plan.pendingAttemptReservation = true;
            clearCurrentSkill(plan);
            BotLog.task(bot, "mission_skill_deferred_before_gate",
                    "mission_id", plan.missionId,
                    "skill", executable.spec().invocationId(),
                    "capability", executable.spec().id(),
                    "decision", preview.action(),
                    "reason", preview.reason());
            markDirty(bot);
            return;
        }
        LegacySkillVerifier.Preparation preparation = LegacySkillVerifier.beforeStart(
                bot, plan.missionPlan.goal(), plan.goal, executable, snapshotContext(plan));
        if (!preparation.allowed()) {
            SkillOutcome rejection = preparation.rejection();
            BotLog.task(bot, "mission_skill_gate_rejected",
                    "mission_id", plan.missionId,
                    "skill", executable.spec().invocationId(),
                    "capability", executable.spec().id(),
                    "status", rejection.status(),
                    "failure_kind", rejection.failureKind(),
                    "reason", rejection.reason());
            acceptSkillOutcome(bot.getServer(), bot, plan, rejection);
            return;
        }
        plan.currentSkillVerifier = preparation.session();
        Optional<Task> task = stepToTask(bot, step, plan);
        if (task.isEmpty()) {
            SkillOutcome outcome = new SkillOutcome(
                    SkillOutcome.Status.FATAL_FAILURE,
                    SkillOutcome.FailureKind.INTERNAL,
                    "unmapped_step:" + step.describe(),
                    0,
                    Map.of("skill", executable.spec().invocationId()));
            plan.terminalOutcome = outcome;
            finishActive(bot, plan, evaluate(bot, plan), outcome.reason(), false, true,
                    GoalResult.Status.FAILED);
            BotLog.warn(io.github.greytaiwolf.fakeaiplayer.log.LogCategory.TASK, bot, "goal_step_unmapped", "step", step.describe());
            return;
        }
        plan.currentTask = task.get();
        int done = Math.min(plan.totalSteps, plan.completedSteps + 1);
        BotLog.task(bot, "goal_step", "index", done, "total", plan.totalSteps, "step", step.describe());
        io.github.greytaiwolf.fakeaiplayer.task.TaskAssignmentResult assignment;
        try {
            assignment = TaskManager.INSTANCE.assign(
                    bot, task.get(), skillOrigin);
        } catch (RuntimeException startFailure) {
            SkillOutcome outcome = new SkillOutcome(
                    SkillOutcome.Status.FATAL_FAILURE,
                    SkillOutcome.FailureKind.INTERNAL,
                    "skill_start_failed:" + startFailure.getClass().getSimpleName(),
                    0,
                    Map.of("skill", executable.spec().invocationId(),
                            "capability", executable.spec().id()));
            BotLog.error(bot, "mission_skill_start_failed", startFailure,
                    "mission_id", plan.missionId, "skill", executable.spec().invocationId(),
                    "capability", executable.spec().id());
            BotLog.task(bot, "skill_outcome", "skill", executable.spec().invocationId(),
                    "capability", executable.spec().id(),
                    "status", outcome.status(), "failure_kind", outcome.failureKind(),
                    "reason", outcome.reason());
            acceptSkillOutcome(bot.getServer(), bot, plan, outcome);
            return;
        }
        if (!assignment.started()) {
            // The reservation belongs to this cursor activation, not to a JVM-local Task object.
            // Retain it across arbiter deferral (and across a restart while paused) so repeatedly
            // checking the same pending Skill neither refunds nor double-charges its attempt.
            plan.pendingAttemptReservation = true;
            clearCurrentSkill(plan);
            BotLog.task(bot, "mission_skill_deferred",
                    "mission_id", plan.missionId,
                    "skill", executable.spec().invocationId(),
                    "capability", executable.spec().id(),
                    "decision", assignment.action(),
                    "reason", assignment.reason());
            markDirty(bot);
            return;
        }
        BotLog.task(bot, "mission_skill_attempt_started",
                "mission_id", plan.missionId,
                "skill", executable.spec().invocationId(),
                "capability", executable.spec().id(),
                "attempt", attempt.attempt(),
                "max_attempts", attempt.maxAttempts());
        plan.transition(MissionState.RUNNING);
        markDirty(bot);
    }

    private static Optional<String> replacePlanSkills(ActivePlan plan, List<GoalStep> steps) {
        OptionalInt nextRevision = nextPlanRevision(plan.missionPlan.revision());
        if (nextRevision.isEmpty()) {
            return Optional.of("mission_plan_revision_exhausted");
        }
        LegacyMissionCompiler.CompiledMission compiled;
        int replacementTotal;
        try {
            compiled = LegacyMissionCompiler.compile(
                    plan.missionId,
                    nextRevision.getAsInt(),
                    plan.goal,
                    plan.missionPlan.goal().source(),
                    plan.missionPlan.goal().priority(),
                    plan.missionPlan.goal().dimension(),
                    plan.missionPlan.goal().policy(),
                    steps);
            replacementTotal = totalSteps(plan.completedSteps, compiled.skills().size());
        } catch (IllegalArgumentException invalidPlan) {
            return Optional.of("mission_plan_rebuild_invalid");
        }
        List<String> replacementLabels = alignedStepLabels(
                plan.stepLabels,
                plan.completedSteps,
                compiled.skills().stream().map(skill -> skill.step().describe()).toList());
        plan.missionPlan = compiled.plan();
        plan.executableSkills.clear();
        for (LegacyMissionCompiler.ExecutableSkill executable : compiled.skills()) {
            if (plan.executableSkills.putIfAbsent(
                    executable.spec().invocationId(), executable) != null) {
                return Optional.of("mission_plan_rebuild_duplicate_invocation");
            }
        }
        plan.planCursor = compiled.plan().cursor(plan.elapsedMissionTicks);
        plan.pendingAttemptReservation = false;
        plan.totalSteps = replacementTotal;
        plan.stepLabels.clear();
        plan.stepLabels.addAll(replacementLabels);
        return Optional.empty();
    }

    /** Reserves every plan rebuild through the single durable Mission recovery ledger. */
    private static Optional<String> reserveRecovery(AIPlayerEntity bot,
                                                    ActivePlan plan,
                                                    RecoveryLedger.RecoveryKind kind,
                                                    boolean verifiedProgress) {
        if (kind == RecoveryLedger.RecoveryKind.POSTCONDITION
                && plan.recoveryLedger.postconditionRecoveriesConsumed()
                >= MAX_POSTCONDITION_RECOVERIES) {
            return Optional.of("postcondition_recovery_limit_exhausted");
        }
        if (!verifiedProgress
                && plan.recoveryLedger.consecutiveNoProgressRecoveries()
                >= MAX_CONSECUTIVE_NO_PROGRESS_RECOVERIES) {
            return Optional.of("no_progress_recovery_limit_exhausted");
        }
        RecoveryLedger.RecoveryDecision decision = plan.recoveryLedger.consumeRecovery(
                kind, verifiedProgress);
        if (!decision.allowed()) {
            return Optional.of(decision.reason());
        }
        BotLog.task(bot, "mission_recovery_reserved",
                "mission_id", plan.missionId,
                "kind", kind,
                "consumed", decision.consumed(),
                "budget", decision.budget(),
                "no_progress", decision.consecutiveNoProgress(),
                "postcondition", decision.postconditionConsumed());
        return Optional.empty();
    }

    // Phase A 进度信号:目标产物当前库存计数(HaveItem/Stockpile 用其物品,MineOre 用矿石掉落)。
    private static int goalTargetCount(AIPlayerEntity bot, Goal goal) {
        if (goal instanceof Goal.HaveItem hi) {
            return io.github.greytaiwolf.fakeaiplayer.action.HarvestCore.countInventoryItems(bot, java.util.Set.of(hi.item()));
        }
        if (goal instanceof Goal.Stockpile sp) {
            return io.github.greytaiwolf.fakeaiplayer.action.HarvestCore.countInventoryItems(bot, java.util.Set.of(sp.item()));
        }
        if (goal instanceof Goal.MineOre mo) {
            return io.github.greytaiwolf.fakeaiplayer.action.HarvestCore.countInventoryItems(bot,
                    io.github.greytaiwolf.fakeaiplayer.action.HarvestCore.expectedDropsFor(mo.ores()));
        }
        return 0;
    }

    /**
     * Routes one authoritative adapter result through the plan cursor before Mission-level
     * recovery. Retry/AnyOf/Checkpoint semantics therefore run in the same production path as
     * legacy Tasks, and only an unhandled root failure reaches whole-plan replanning.
     */
    private void acceptSkillOutcome(MinecraftServer server,
                                    AIPlayerEntity bot,
                                    ActivePlan plan,
                                    SkillOutcome outcome) {
        if (plan.currentSkillLease == null || plan.currentSkillSpec == null) {
            SkillOutcome invalid = new SkillOutcome(
                    SkillOutcome.Status.FATAL_FAILURE,
                    SkillOutcome.FailureKind.INTERNAL,
                    "mission_skill_lease_missing",
                    outcome == null ? 0 : outcome.progress(),
                    Map.of("phase", "skill_completion"));
            plan.terminalOutcome = invalid;
            finishActive(bot, plan, evaluate(bot, plan), invalid.reason(), false, true,
                    GoalResult.Status.FAILED);
            return;
        }
        SkillOutcome resolved = outcome == null ? new SkillOutcome(
                SkillOutcome.Status.FATAL_FAILURE,
                SkillOutcome.FailureKind.INTERNAL,
                "mission_skill_outcome_missing", 0, Map.of()) : outcome;
        captureTaskEvidence(plan);

        boolean cookFinalOfFood = plan.goal instanceof Goal.Food
                && plan.current != null && plan.current.kind() == GoalStep.Kind.COOK_FOOD;
        boolean foodGoalBestEffort = plan.goal instanceof Goal.Food;
        boolean skip = bestEffortSkippable(resolved) && !cookFinalOfFood && plan.current != null
                && (foodGoalBestEffort
                || plan.current.kind() == GoalStep.Kind.HUNT
                || plan.current.kind() == GoalStep.Kind.COOK_FOOD
                || plan.current.kind() == GoalStep.Kind.STOCKPILE);
        PlanCursor.ActivationLease completedLease = plan.currentSkillLease;
        SkillOutcome cursorOutcome = skip
                ? SkillOutcome.succeeded(resolved.progress(), Map.of(
                "best_effort_skip", "true", "skip_reason", resolved.reason()))
                : resolved;
        PlanCursor.Completion completion = plan.planCursor.tryCompleteSkill(
                completedLease, cursorOutcome, plan.elapsedMissionTicks);
        PlanCursor.Snapshot cursor = completion.snapshot();
        if (!completion.accepted()) {
            BotLog.task(bot, "mission_skill_callback_lease_rejected",
                    "mission_id", plan.missionId,
                    "skill", completedLease.invocationId(),
                    "activation_attempt", completedLease.activationAttempt(),
                    "cursor_state", cursor.state());
            reconcileCursorActivation(server, bot, plan, cursor,
                    "mission_skill_callback_lease_rejected");
            return;
        }
        if (skip && cursor.state() != PlanCursor.State.FAILED) {
            plan.skippedSteps.add(new GoalResult.SkippedStep(plan.current.describe(), resolved.reason()));
            plan.completedSteps++;
            BotLog.task(bot, "goal_step_skipped_besteffort",
                    "step", plan.current.describe(), "reason", resolved.reason());
        } else if (resolved.status() == SkillOutcome.Status.SUCCEEDED
                && cursor.state() != PlanCursor.State.FAILED) {
            BotLog.task(bot, "goal_step_completed", "step", plan.current.describe());
            plan.completedSteps++;
            plan.recoveryLedger.markVerifiedProgress(plan.currentSkillSpec);
        }

        if (cursor.state() == PlanCursor.State.FAILED) {
            handleStepFailure(server, bot, plan, cursor.failure().orElseThrow().outcome());
            return;
        }
        plan.terminalOutcome = null;
        clearCurrentSkill(plan);
        assignNext(bot, plan);
    }

    /**
     * Keeps the Task owner and the composite cursor on the same activation. Timeout can move a
     * Retry/AnyOf cursor to a fresh READY leaf without making the root fail; the old Task must be
     * detached before any checkpoint captures that new cursor state.
     */
    private boolean reconcileCursorActivation(MinecraftServer server,
                                              AIPlayerEntity bot,
                                              ActivePlan plan,
                                              PlanCursor.Snapshot cursor,
                                              String reason) {
        PlanCursor.ActivationLease runningLease = plan.currentSkillLease;
        if (runningLease == null) {
            if (plan.currentTask == null
                    && cursor.state() != PlanCursor.State.WAITING
                    && cursor.state() != PlanCursor.State.RUNNING) {
                assignNext(bot, plan);
                return true;
            }
            return false;
        }
        boolean stillReady = cursor.readySkills().stream()
                .anyMatch(ready -> ready.lease().equals(runningLease));
        if (stillReady) {
            return false;
        }
        TaskManager.INSTANCE.cancelMissionTasks(
                bot, plan.missionId, reason + ':' + runningLease.invocationId());
        if (cursor.state() == PlanCursor.State.FAILED) {
            handleStepFailure(server, bot, plan, cursor.failure().orElseThrow().outcome());
            return true;
        }
        BotLog.task(bot, "mission_cursor_activation_changed",
                "mission_id", plan.missionId,
                "old_skill", runningLease.invocationId(),
                "old_attempt", runningLease.activationAttempt(),
                "cursor_state", cursor.state(),
                "reason", reason);
        clearCurrentSkill(plan);
        plan.pendingAttemptReservation = false;
        plan.terminalOutcome = null;
        assignNext(bot, plan);
        return true;
    }

    private static void clearCurrentSkill(ActivePlan plan) {
        plan.current = null;
        plan.currentSkillSpec = null;
        plan.currentSkillLease = null;
        plan.currentSkillVerifier = null;
        plan.currentTask = null;
        plan.currentUsesPendingAttemptReservation = false;
    }

    private void handleStepFailure(MinecraftServer server,
                                   AIPlayerEntity bot,
                                   ActivePlan plan,
                                   SkillOutcome outcome) {
        String reason = outcome.reason();
        plan.terminalOutcome = outcome;
        plan.transition(MissionState.RECOVERING);
        BotLog.task(bot, "skill_outcome",
                "skill", currentSkillId(plan),
                "capability", plan.currentSkillSpec == null ? "unknown" : plan.currentSkillSpec.id(),
                "status", outcome.status(),
                "failure_kind", outcome.failureKind(),
                "reason", reason,
                "progress", outcome.progress());
        boolean placedBuildingCells = plan.currentTask instanceof BuildTask build
                && build.placedBlocks() > 0;
        captureTaskEvidence(plan);
        // Phase A 进度感知预算(断点恢复核心):有进展→清零"连续无进展"计数,产出区被瞬时打断
        // (骷髅/卡顿)不与原地空转同罪。进展=完成新步 || 挖到更多目标物 || 下潜更深 || 横向位移≥8格
        //(位移信号对 ore_dig strip-mining 前进尤其关键——它是 real_diamond 主导失败面)。只认单向增量防往返误判。
        net.minecraft.core.BlockPos bp = bot.blockPosition();
        int curTarget = goalTargetCount(bot, plan.goal);
        long hMoved2 = (long) (bp.getX() - plan.snapX) * (bp.getX() - plan.snapX)
                     + (long) (bp.getZ() - plan.snapZ) * (bp.getZ() - plan.snapZ);
        boolean madeProgress = placedBuildingCells
                || plan.completedSteps > plan.snapSteps
                || curTarget > plan.snapTargetCount
                || bp.getY() < plan.snapY
                || hMoved2 >= 64;
        if (madeProgress && plan.currentSkillSpec != null) {
            plan.recoveryLedger.markVerifiedProgress(plan.currentSkillSpec);
        }
        plan.snapSteps = plan.completedSteps;
        plan.snapTargetCount = curTarget;
        plan.snapX = bp.getX();
        plan.snapY = bp.getY();
        plan.snapZ = bp.getZ();
        if (outcome.terminalFailure()) {
            GoalResult.Status forced = switch (outcome.status()) {
                case BLOCKED -> GoalResult.Status.BLOCKED;
                case CANCELLED -> GoalResult.Status.CANCELLED;
                case FATAL_FAILURE -> GoalResult.Status.FAILED;
                default -> throw new IllegalStateException("non_terminal_skill_outcome");
            };
            finishActive(bot, plan, evaluate(bot, plan),
                    reason.isBlank() ? "skill_terminal_failure" : reason,
                    outcome.status() == SkillOutcome.Status.CANCELLED,
                    true, forced);
            return;
        }
        SkillSpec.RetryPolicy retryPolicy = plan.currentSkillSpec == null
                ? SkillSpec.RetryPolicy.standard()
                : plan.currentSkillSpec.retryPolicy();
        int attemptsSoFar = plan.currentSkillSpec == null
                ? retryPolicy.maxAttempts()
                : plan.recoveryLedger.attemptsFor(plan.currentSkillSpec);
        if (!retryPolicy.mayReplan(outcome, attemptsSoFar)) {
            plan.terminalOutcome = blockedOutcome(
                    reason.isBlank() ? "skill_attempt_budget_exhausted" : reason,
                    outcome.failureKind(), outcome.progress(), outcome.evidence());
            finishActive(bot, plan, evaluate(bot, plan),
                    plan.terminalOutcome.reason(), false, true, GoalResult.Status.BLOCKED);
            return;
        }
        if (!AIBotConfig.get().goal().replanOnFailureEnabled()) {
            plan.terminalOutcome = blockedOutcome(
                    "skill_replanning_disabled:" + reason,
                    outcome.failureKind(), outcome.progress(), outcome.evidence());
            finishActive(bot, plan, evaluate(bot, plan), plan.terminalOutcome.reason(),
                    false, true, GoalResult.Status.BLOCKED);
            return;
        }
        Optional<String> recoveryDenied = reserveRecovery(
                bot, plan, RecoveryLedger.RecoveryKind.SKILL_FAILURE, madeProgress);
        if (recoveryDenied.isPresent()) {
            plan.terminalOutcome = blockedOutcome(
                    recoveryDenied.get(), outcome.failureKind(), outcome.progress(), outcome.evidence());
            finishActive(bot, plan, evaluate(bot, plan), recoveryDenied.get(), false, true,
                    GoalResult.Status.BLOCKED);
            return;
        }
        GoalPlanner.GoalPlan fresh = GoalPlanner.plan(bot, plan.goal, snapshotContext(plan));
        BotLog.task(bot, "goal_replan", "goal", plan.goal, "reason", reason, "steps", fresh.describeSteps(), "unresolved", fresh.unresolved());
        if (!fresh.success() || fresh.steps().isEmpty()) {
            String terminalReason = fresh.success()
                    ? "replan_empty" : "replan_failed:" + String.join(",", fresh.unresolved());
            plan.terminalOutcome = blockedOutcome(
                    terminalReason, outcome.failureKind(), outcome.progress(), outcome.evidence());
            finishActive(bot, plan, evaluate(bot, plan), terminalReason,
                    false, true, GoalResult.Status.BLOCKED);
            return;
        }
        // 防呆:若重规划的第一步与刚失败的步骤完全相同,且失败是"硬卡死"类(挖不动/卡住/超时),
        // 重试只会原样再失败一次(实测#9 的 replan 风暴根因)。直接判失败,交大脑/玩家换思路。
        if (plan.current != null && plan.current.equals(fresh.steps().get(0))
                && isHardFailure(outcome) && !madeProgress) {
            String terminalReason = "replan_same_step:" + reason;
            plan.terminalOutcome = blockedOutcome(
                    terminalReason, outcome.failureKind(), outcome.progress(), outcome.evidence());
            finishActive(bot, plan, evaluate(bot, plan), terminalReason,
                    false, true, GoalResult.Status.BLOCKED);
            return;
        }
        Optional<String> rebuildFailure = replacePlanSkills(plan, fresh.steps());
        if (rebuildFailure.isPresent()) {
            plan.terminalOutcome = blockedOutcome(
                    rebuildFailure.get(), outcome.failureKind(), outcome.progress(),
                    Map.of("phase", "skill_failure_replan",
                            "previous_reason", outcome.reason()));
            finishActive(bot, plan, evaluate(bot, plan), rebuildFailure.get(),
                    false, true, GoalResult.Status.BLOCKED);
            return;
        }
        clearCurrentSkill(plan);
        plan.terminalOutcome = null;
        report(bot, "遇到问题,我重新规划了一次。");
        assignNext(bot, plan);
    }

    // 优化2:目标最近(withinTicks 内)是否整体失败过——供 ActionDispatcher 拦截大脑失败后的手动逐格挖矿。
    public boolean recentlyFailed(AIPlayerEntity bot, int withinTicks) {
        Integer t = lastGoalFailTick.get(bot.getUUID());
        return t != null && bot.getServer().getTickCount() - t < withinTicks;
    }

    // B:用户发来新消息时清空原始目标记忆(允许用户正常更换目标);由 BrainCoordinator 在收到用户消息时调用。
    public void clearUserGoal(AIPlayerEntity bot) {
        userGoal.remove(bot.getUUID());
    }

    // B:sub 是否是 parent(用户原始目标)的前置——sub 的产物落在 parent 计划某一步的产出里。
    // 覆盖主 case:做铁镐(HaveItem)/挖铁(MineOre)都是挖钻石计划里的前置步骤,会被拦下。
    private boolean isPrerequisiteOf(AIPlayerEntity bot, Goal sub, Goal parent) {
        GoalPlanner.GoalPlan parentPlan = GoalPlanner.plan(bot, parent);
        Set<Item> items = new HashSet<>();
        Set<Block> ores = new HashSet<>();
        for (GoalStep s : parentPlan.steps()) {
            if (s.item() != null) {
                items.add(s.item());
            }
            if (s.kind() == GoalStep.Kind.MINE_ORE) {
                ores.addAll(s.ores());
            }
        }
        if (sub instanceof Goal.HaveItem hi) {
            return items.contains(hi.item());
        }
        if (sub instanceof Goal.MineOre mo) {
            for (Block b : mo.ores()) {
                if (ores.contains(b)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static Optional<Task> stepToTask(AIPlayerEntity bot, GoalStep step, ActivePlan plan) {
        return switch (step.kind()) {
            case GATHER -> Optional.of(new GatherQuotaTask(
                    step.item(), step.count(), false, GatherQuotaTask.QuotaMode.DELTA_FROM_BASELINE));
            case GATHER_EXACT -> Optional.of(new GatherQuotaTask(
                    step.item(), step.count(), true, GatherQuotaTask.QuotaMode.DELTA_FROM_BASELINE));
            // DIGDOWN(实测#8):MINE 步改用 DigDownTask——站着就近垂直下挖,不定位/不寻路,
            // 永不"够不到/走不过去"空转。取代旧的 OreSeekTask.digBlocks(它会锁定垂直够不到的石头 stuck)。
            case MINE -> Optional.of(new DigDownTask(step.block(), step.count()));
            case MINE_EXACT -> Optional.of(new DigDownTask(step.block(), step.count(), true));
            // OREDIG(实测#10):MINE_ORE 步改用 OreDigTask(BlockMiner 控制式直挖隧道),
            // 取代 OreSeekTask——后者"A*接近被埋矿"在 #6/#8/#10 连续 stuck。
            case MINE_ORE -> Optional.of(new OreDigTask(step.ores(), step.count()));
            case CRAFT -> Optional.of(new CraftTask(step.item(), step.count()));
            case SMELT -> Optional.of(new SmeltTask(step.input(), step.output(), step.count()));
            case MOVE -> Optional.of(new MoveTask(bot, step.pos()));
            case MOVE_NON_MUTATING -> Optional.of(MoveTask.nonMutating(bot, step.pos()));
            // P3:FARM 步 → 数量受限的 FarmTask(就地开垦/播种/等熟/收割,收够 count 个产出即完成)。
            case FARM -> Optional.of(new FarmTask(bot.blockPosition(), 4, step.input(), step.block(),
                    true, false, step.item(), step.count()));
            // 第4层:HUNT 步 → HuntTask 猎杀动物取生肉(备粮)。
            case HUNT -> Optional.of(new HuntTask(step.count()));
            // P0 食物闭环:COOK_FOOD 步 → SmeltTask cookAll 模式,把背包生肉逐种烤成熟肉。
            case COOK_FOOD -> Optional.of(new SmeltTask(step.count()));
            // 蛋糕链:MILK_COW 步 → MilkCowTask 用空桶挤 count 桶牛奶。
            case MILK_COW -> Optional.of(new MilkCowTask(step.count()));
            case EQUIP_LOADOUT -> Optional.of(new EquipLoadoutTask());
            // Phase2:PLACE_STATIONS 步 → 摆好工作台/熔炉/箱子。
            case PLACE_STATIONS -> Optional.of(new PlaceStationsTask());
            // Phase3:STOCKPILE 步 → 把背包资源存进附近箱子(存所有非工具)。
            case STOCKPILE -> Optional.of(new StockpileTask(true));
            // 挖深层矿:DESCEND_TO_Y 步 → 连续挖竖井下到矿层。
            case DESCEND_TO_Y -> Optional.of(new DescendToYTask(step.pos().getY()));
            case MAKE_OBSIDIAN -> Optional.of(new io.github.greytaiwolf.fakeaiplayer.task.CreateObsidianTask(step.count()));
            // 盖房:BUILD 步 → BuildTask(自动选址 autoSite + 整地 flatten,真实起伏地形也能落成);材料已由规划期备齐;
            // 蓝图读取失败(被删/坏档)→ empty,assignNext 按"步骤无法执行"收尾。
            // flatten=true:真实地形罕有现成平地,lenient 选址选最平点 + FLATTEN 挖高填低整平(治 real_build no_flat_site)。
            case BUILD -> {
                try {
                    if (!(plan.goal instanceof Goal.Build build)
                            || !build.blueprint().equals(step.tag())) {
                        yield Optional.empty();
                    }
                    BlockPos anchor = plan.buildAnchor;
                    yield Optional.of(new BuildTask(
                            loadBuildBlueprint(build),
                            anchor,
                            anchor == null,
                            anchor == null,
                            build.blueprintDigest(),
                            build.dimension()));
                } catch (IOException e) {
                    yield Optional.empty();
                }
            }
        };
    }

    private GoalEvaluation evaluate(AIPlayerEntity bot, ActivePlan plan) {
        GoalSnapshot snapshot = GoalSnapshotCollector.collect(bot, plan.goal, snapshotContext(plan));
        plan.lastStructure = snapshot.structure().orElse(null);
        return plan.predicate.evaluate(snapshot);
    }

    private static GoalSnapshotCollector.Context snapshotContext(ActivePlan plan) {
        return new GoalSnapshotCollector.Context(
                plan.origin,
                plan.boundContainers,
                plan.blueprint,
                plan.buildAnchor,
                plan.buildPlaced,
                plan.buildSkipped);
    }

    private static void captureTaskEvidence(ActivePlan plan) {
        if (plan.currentTask instanceof BuildTask build) {
            plan.blueprint = build.blueprint();
            plan.buildAnchor = build.anchor();
            plan.buildPlaced = build.placedBlocks();
            plan.buildSkipped = build.skippedBlocks();
        } else if (plan.currentTask instanceof StockpileTask stockpile) {
            plan.boundContainers.addAll(stockpile.depositedContainers());
        } else if (plan.currentTask instanceof PlaceStationsTask stations && !stations.placedPositions().isEmpty()) {
            plan.origin = stations.placedPositions().iterator().next();
        }
    }

    private static String currentSkillId(ActivePlan plan) {
        return plan.currentSkillSpec == null ? "unknown" : plan.currentSkillSpec.invocationId();
    }

    private static GoalSnapshotCollector.Context initialContext(AIPlayerEntity bot,
                                                                Goal goal,
                                                                BlueprintSchema verifiedBlueprint) {
        if (goal instanceof Goal.Build build && build.anchor() != null) {
            return new GoalSnapshotCollector.Context(
                    build.anchor(), Set.of(), verifiedBlueprint, build.anchor(), 0, 0);
        }
        if (goal instanceof Goal.Stockpile) {
            net.minecraft.core.BlockPos base = io.github.greytaiwolf.fakeaiplayer.memory.BotMemoryStore.INSTANCE
                    .of(bot.getUUID())
                    .placeIn(bot.serverLevel(), "base")
                    .orElse(bot.blockPosition());
            return GoalSnapshotCollector.Context.at(base);
        }
        return GoalSnapshotCollector.Context.at(bot.blockPosition());
    }

    private static BlueprintSchema validateAndLoadBuildGoal(AIPlayerEntity bot,
                                                            Goal.Build build) throws IOException {
        boolean generated = build.isGeneratedReference();
        if (!hasTrustedBuildBinding(build)) {
            if (!generated || !BlueprintLoader.isGeneratedName(build.blueprint())) {
                // Goal.Build is the resumable AI/mission path and accepts only immutable
                // blueprints emitted by the projection-confirmation service. Explicit player
                // task commands use BuildTask directly and do not reopen this legacy preset path.
                throw new IOException("build_goal_requires_confirmed_generated_blueprint");
            }
            // Old mission JSON still decodes so unrelated snapshot data remains compatible, but
            // no Goal.Build may execute without the confirmation-time location/content binding.
            throw new IOException("build_blueprint_binding_incomplete");
        }
        if (build.dimension() != null
                && !build.dimension().equals(bot.serverLevel().dimension().location().toString())) {
            throw new IOException("build_goal_wrong_dimension: expected=" + build.dimension()
                    + " actual=" + bot.serverLevel().dimension().location());
        }
        return loadBuildBlueprint(build);
    }

    static boolean hasTrustedBuildBinding(Goal.Build build) {
        return build != null
                && build.isGeneratedReference()
                && BlueprintLoader.isGeneratedName(build.blueprint())
                && build.hasCompleteConfirmedBinding();
    }

    private static BlueprintSchema loadBuildBlueprint(Goal.Build build) throws IOException {
        return BlueprintLoader.loadVerified(build.blueprint(), build.blueprintDigest());
    }

    private void finishActive(AIPlayerEntity bot,
                              ActivePlan plan,
                              GoalEvaluation evaluation,
                              String reason,
                              boolean cancelled,
                              boolean advanceQueue) {
        finishActive(bot, plan, evaluation, reason, cancelled, advanceQueue, null);
    }

    private void finishActive(AIPlayerEntity bot,
                              ActivePlan plan,
                              GoalEvaluation evaluation,
                              String reason,
                              boolean cancelled,
                              boolean advanceQueue,
                              GoalResult.Status forcedStatus) {
        if (activePlans.get(bot.getUUID()) != plan) {
            return;
        }
        GoalResult.Status status = cancelled
                ? GoalResult.Status.CANCELLED
                : evaluation.state() == GoalEvaluation.State.SATISFIED
                ? GoalResult.Status.COMPLETED
                : forcedStatus == null
                ? GoalResult.classify(evaluation, false, plan.terminalOutcome)
                : forcedStatus;
        MissionState terminalState = switch (status) {
            case COMPLETED -> MissionState.SUCCEEDED;
            case PARTIAL, BLOCKED -> MissionState.BLOCKED;
            case FAILED -> MissionState.FAILED;
            case CANCELLED -> MissionState.CANCELLED;
        };
        if (terminalState == MissionState.SUCCEEDED && plan.state != MissionState.VERIFYING) {
            plan.transition(MissionState.VERIFYING);
        }
        plan.transition(terminalState);
        if (!activePlans.remove(bot.getUUID(), plan)) {
            return;
        }
        SkillOutcome publishedOutcome = status == GoalResult.Status.COMPLETED
                ? null : plan.terminalOutcome;
        GoalResult result = new GoalResult(
                resultSequence.incrementAndGet(),
                plan.missionId,
                plan.goal,
                status,
                evaluation,
                reason,
                plan.startedTick,
                bot.getServer().getTickCount(),
                plan.skippedSteps,
                plan.lastStructure,
                publishedOutcome);
        publishResult(bot, result);
        userGoal.remove(bot.getUUID());
        if (status == GoalResult.Status.FAILED
                || status == GoalResult.Status.PARTIAL
                || status == GoalResult.Status.BLOCKED) {
            lastGoalFailTick.put(bot.getUUID(), bot.getServer().getTickCount());
        }
        if (advanceQueue) {
            advanceQueue(bot);
        }
    }

    private void recordImmediateResult(AIPlayerEntity bot,
                                       UUID missionId,
                                       Goal goal,
                                       int startedTick,
                                       GoalEvaluation evaluation,
                                       GoalResult.Status status,
                                       String reason) {
        recordImmediateResult(bot, missionId, goal, startedTick, evaluation, status, reason, null);
    }

    private void recordImmediateResult(AIPlayerEntity bot,
                                       UUID missionId,
                                       Goal goal,
                                       int startedTick,
                                       GoalEvaluation evaluation,
                                       GoalResult.Status status,
                                       String reason,
                                       SkillOutcome terminalOutcome) {
        publishResult(bot, new GoalResult(
                resultSequence.incrementAndGet(), missionId, goal, status, evaluation, reason,
                startedTick, bot.getServer().getTickCount(), List.of(), null, terminalOutcome));
    }

    private void publishResult(AIPlayerEntity bot, GoalResult result) {
        lastResults.put(bot.getUUID(), result);
        BotLog.task(bot, "goal_result",
                "sequence", result.sequence(),
                "mission_id", result.missionId(),
                "goal", result.goal(),
                "status", result.status(),
                "matched", result.evaluation().matched(),
                "required", result.evaluation().required(),
                "reason", result.reason(),
                "skipped", result.skippedSteps().size(),
                "evidence", result.evaluation().evidence(),
                "skill_outcome", result.terminalSkillOutcome()
                        .map(outcome -> outcome.status().name()).orElse("none"),
                "failure_kind", result.terminalSkillOutcome()
                        .map(outcome -> outcome.failureKind().name()).orElse("NONE"),
                "skill_evidence", result.terminalSkillOutcome()
                        .map(SkillOutcome::evidence).orElse(Map.of()));
        if (result.status() == GoalResult.Status.COMPLETED) {
            io.github.greytaiwolf.fakeaiplayer.memory.EpisodeLog.INSTANCE.record(bot,
                    io.github.greytaiwolf.fakeaiplayer.memory.EpisodeLog.Type.GOAL_DONE, bot.blockPosition(), goalLabel(result.goal()));
        } else if (result.status() != GoalResult.Status.CANCELLED) {
            io.github.greytaiwolf.fakeaiplayer.memory.EpisodeLog.INSTANCE.record(bot,
                    io.github.greytaiwolf.fakeaiplayer.memory.EpisodeLog.Type.GOAL_FAILED, bot.blockPosition(), goalLabel(result.goal()));
        }
        String message = resultMessage(result.status(), result.evaluation(), result.reason());
        reportTerminal(bot, message, result.status());
        markDirty(bot);
    }

    private static void markDirty(AIPlayerEntity bot) {
        io.github.greytaiwolf.fakeaiplayer.persist.BotPersistence.INSTANCE.markDirty(bot.getServer());
    }

    private static String resultMessage(GoalResult.Status status, GoalEvaluation evaluation, String reason) {
        return switch (status) {
            case COMPLETED -> "目标已验收完成。";
            case PARTIAL -> "目标只完成了一部分(" + evaluation.matched() + "/" + evaluation.required() + "):"
                    + String.join(",", evaluation.unmet());
            case BLOCKED -> "目标被阻塞:"
                    + (reason == null || reason.isBlank()
                    ? String.join(",", evaluation.unmet()) : reason);
            case FAILED -> "目标未通过最终验收:" + (evaluation.unmet().isEmpty() ? reason : String.join(",", evaluation.unmet()));
            case CANCELLED -> "目标已取消。";
        };
    }

    private static void reportTerminal(AIPlayerEntity bot, String text, GoalResult.Status status) {
        BotReporter.INSTANCE.onGoalResult(bot, status, text);
    }

    private static void report(AIPlayerEntity bot, String text) {
        BotReporter.INSTANCE.onGoalMessage(bot, text);
    }

    private static SkillOutcome blockedOutcome(String reason,
                                               SkillOutcome.FailureKind kind,
                                               Map<String, String> evidence) {
        return blockedOutcome(reason, kind, 0, evidence);
    }

    private static SkillOutcome blockedOutcome(String reason,
                                               SkillOutcome.FailureKind kind,
                                               int progress,
                                               Map<String, String> evidence) {
        SkillOutcome.FailureKind resolved = kind == null || kind == SkillOutcome.FailureKind.NONE
                ? SkillOutcome.FailureKind.UNKNOWN : kind;
        return new SkillOutcome(
                SkillOutcome.Status.BLOCKED,
                resolved,
                reason == null || reason.isBlank() ? "mission_blocked" : reason,
                progress,
                evidence);
    }

    /** Keeps TaskManager-before-GoalExecutor terminal races inside the typed Skill contract. */
    static SkillOutcome interruptedTerminalOutcome(TaskStatus status) {
        if (status == null) {
            throw new IllegalArgumentException("interrupted_task_status_missing");
        }
        int progress = (int) Math.round(status.progress() * 1_000.0D);
        return switch (status.state()) {
            case FAILED -> SkillOutcome.fromLegacyFailure(status.failureReason(), progress);
            case CANCELLED -> SkillOutcome.cancelled(status.failureReason(), progress);
            case PENDING, RUNNING, PAUSED -> new SkillOutcome(
                    SkillOutcome.Status.FATAL_FAILURE,
                    SkillOutcome.FailureKind.INTERNAL,
                    "interrupted_mission_child_lost_ownership:"
                            + status.state().name().toLowerCase(java.util.Locale.ROOT),
                    progress,
                    Map.of("task", status.name()));
            case COMPLETED -> throw new IllegalArgumentException(
                    "completed_interrupted_task_requires_authoritative_verifier");
        };
    }

    // 硬卡死类失败:原样重试只会再失败(挖不动/卡住/超时/够不到)。区别于"缺料/缺镐"这类重规划能补的。
    private static boolean isHardFailure(SkillOutcome outcome) {
        if (outcome == null) {
            return false;
        }
        return outcome.failureKind() == SkillOutcome.FailureKind.PATH_UNREACHABLE
                || outcome.failureKind() == SkillOutcome.FailureKind.TIMEOUT
                || outcome.reason().contains("binding_curse")
                || outcome.reason().contains("locked_armor_slot");
    }

    private static boolean temporaryInterrupt(TaskOrigin origin) {
        return origin.safety()
                || origin.kind() == TaskOrigin.Kind.REFLEX
                || origin.kind() == TaskOrigin.Kind.VERIFY
                || origin.kind() == TaskOrigin.Kind.SYSTEM_BACKGROUND;
    }

    private static boolean shouldChargeMissionBudget(AIPlayerEntity bot, ActivePlan plan) {
        if (plan.replanAfterInterrupt
                || TaskManager.INSTANCE.hasPersistentPause(bot)
                || TaskManager.INSTANCE.hasNavigationSafetyLease(bot)
                || TaskManager.INSTANCE.isMissionAutomaticallyPaused(bot, plan.missionId)) {
            return false;
        }
        Optional<TaskOrigin> origin = TaskManager.INSTANCE.activeOrigin(bot);
        if (origin.isPresent()) {
            return plan.missionId.equals(origin.get().missionId());
        }
        return !TaskManager.INSTANCE.hasPaused(bot)
                && !bot.getActionPack().hasActiveActions();
    }

    static boolean missionTimeBudgetExhausted(int elapsedMissionTicks, int timeBudgetTicks) {
        return elapsedMissionTicks >= timeBudgetTicks;
    }

    static int incrementMissionTicks(int elapsedMissionTicks) {
        if (elapsedMissionTicks < 0) {
            throw new IllegalArgumentException("mission_elapsed_ticks_negative");
        }
        return elapsedMissionTicks == Integer.MAX_VALUE
                ? Integer.MAX_VALUE : elapsedMissionTicks + 1;
    }

    static boolean shouldPersistRuntimeCheckpoint(int elapsedMissionTicks) {
        return elapsedMissionTicks > 0
                && elapsedMissionTicks % RUNTIME_CHECKPOINT_INTERVAL_TICKS == 0;
    }

    static int totalSteps(int completedSteps, int remainingSteps) {
        if (completedSteps < 0 || remainingSteps < 0
                || completedSteps > MissionCheckpointCodec.MAX_COMPLETED_STEPS
                || remainingSteps > MissionCheckpointCodec.MAX_COMPLETED_STEPS
                || completedSteps > MissionCheckpointCodec.MAX_COMPLETED_STEPS - remainingSteps) {
            throw new IllegalArgumentException("mission_total_steps_invalid");
        }
        return completedSteps + remainingSteps;
    }

    /**
     * Produces the UI's global step timeline after restore/replan. Persisted checkpoints do not
     * carry historical labels, so missing completed entries are represented explicitly instead
     * of shifting the remaining plan back to index zero.
     */
    static List<String> alignedStepLabels(List<String> previousLabels,
                                          int completedSteps,
                                          List<String> remainingLabels) {
        if (previousLabels == null || remainingLabels == null) {
            throw new IllegalArgumentException("mission_step_labels_missing");
        }
        int total = totalSteps(completedSteps, remainingLabels.size());
        List<String> aligned = new ArrayList<>(total);
        for (int index = 0; index < completedSteps; index++) {
            String historical = index < previousLabels.size() ? previousLabels.get(index) : null;
            aligned.add(historical == null || historical.isBlank()
                    ? "已完成步骤 " + (index + 1)
                    : historical);
        }
        for (String remaining : remainingLabels) {
            if (remaining == null || remaining.isBlank()) {
                throw new IllegalArgumentException("mission_step_label_blank");
            }
            aligned.add(remaining);
        }
        return List.copyOf(aligned);
    }

    /** Returns a valid 0-based UI index both while a Skill runs and while assignment is deferred. */
    static int currentStepIndex(int totalSteps, int queuedSteps, boolean hasCurrentStep) {
        if (totalSteps < 0 || queuedSteps < 0 || queuedSteps > totalSteps) {
            throw new IllegalArgumentException("mission_step_index_invalid");
        }
        if (totalSteps == 0) {
            return 0;
        }
        int processed = totalSteps - queuedSteps - (hasCurrentStep ? 1 : 0);
        return Math.max(0, Math.min(processed, totalSteps - 1));
    }

    private static int queuedStepCount(ActivePlan plan) {
        int current = plan.current == null ? 0 : 1;
        return Math.max(0, plan.totalSteps - plan.completedSteps - current);
    }

    /**
     * Reuses the checkpoint revision when its plan is unchanged. A new revision is required only
     * when authoritative replanning produces a different fingerprint.
     */
    static OptionalInt resolveRestoredPlanRevision(int checkpointRevision,
                                                   String checkpointFingerprint,
                                                   String compiledFingerprint) {
        if (checkpointRevision < 0) {
            throw new IllegalArgumentException("mission_plan_revision_negative");
        }
        if (checkpointFingerprint == null || compiledFingerprint == null) {
            throw new IllegalArgumentException("mission_plan_fingerprint_missing");
        }
        return checkpointFingerprint.equals(compiledFingerprint)
                ? OptionalInt.of(checkpointRevision)
                : nextPlanRevision(checkpointRevision);
    }

    static OptionalInt nextPlanRevision(int currentRevision) {
        if (currentRevision < 0) {
            throw new IllegalArgumentException("mission_plan_revision_negative");
        }
        return currentRevision == Integer.MAX_VALUE
                ? OptionalInt.empty() : OptionalInt.of(currentRevision + 1);
    }

    private static RecoveryLedger.AttemptDecision restoredAttemptDecision(
            RecoveryLedger ledger,
            SkillSpec skill) {
        int reserved = ledger.attemptsFor(skill);
        int maximum = skill.retryPolicy().maxAttempts();
        if (reserved < 1 || reserved > maximum) {
            return new RecoveryLedger.AttemptDecision(
                    false,
                    RecoveryLedger.fingerprint(skill),
                    Math.max(0, reserved),
                    maximum,
                    "restored_skill_attempt_reservation_invalid");
        }
        return new RecoveryLedger.AttemptDecision(
                true,
                RecoveryLedger.fingerprint(skill),
                reserved,
                maximum,
                "restored_attempt_reservation_reused");
    }

    static SkillOutcome attemptDeniedOutcome(RecoveryLedger.AttemptDecision attempt) {
        if (attempt == null || attempt.allowed()) {
            throw new IllegalArgumentException("denied_attempt_required");
        }
        boolean capacityFailure = "skill_attempt_tracking_capacity_exhausted"
                .equals(attempt.reason());
        return new SkillOutcome(
                capacityFailure ? SkillOutcome.Status.FATAL_FAILURE : SkillOutcome.Status.BLOCKED,
                capacityFailure ? SkillOutcome.FailureKind.INTERNAL
                        : SkillOutcome.FailureKind.PRECONDITION,
                attempt.reason(),
                0,
                Map.of("attempts", String.valueOf(attempt.attempt()),
                        "max_attempts", String.valueOf(attempt.maxAttempts())));
    }

    static boolean bestEffortSkippable(SkillOutcome outcome) {
        return outcome != null
                && (outcome.status() == SkillOutcome.Status.RETRYABLE_FAILURE
                || outcome.status() == SkillOutcome.Status.PREEMPTED);
    }

    // P1:目标失败时给出可执行的中文引导,避免大脑收到原始 reason 后用 move 乱走探索而遇险。
    private static String humanGoalFailure(String reason) {
        String r = reason == null ? "" : reason;
        if (r.contains("no_resource_after_explore")) {
            // EXPLORE 已定向走出去几片区域都找过(非"原地没找到"),如实区分,免得大脑再让 move 乱走重试。
            return "我已经走出去好几片区域找过了,还是没找到需要的资源,暂时无法继续。我会待在原地,不乱走也不空手挖。";
        }
        if (r.contains("no_resource_nearby") || r.contains("no_reachable") || r.contains("no_ore_found")) {
            return "我在较大范围内都没找到可用的树木/石头/矿石,暂时无法继续。我会待在原地,不乱走也不空手挖。";
        }
        if (r.startsWith("need_better_tool") || r.startsWith("need_pickaxe")) {
            return "我还缺合适的镐,正在自动准备;若准备不出来我会停下,不会空手硬挖。";
        }
        return "目标失败:" + (r.isBlank() ? "步骤失败" : r);
    }

    private static final class ActivePlan {
        private UUID missionId;
        private final int startedTick;
        private final Goal goal;
        private final GoalPredicate predicate;
        private MissionPlan missionPlan;
        private MissionState state = MissionState.PLANNED;
        private net.minecraft.core.BlockPos origin;
        private final Set<net.minecraft.core.BlockPos> boundContainers = new HashSet<>();
        private final Map<String, LegacyMissionCompiler.ExecutableSkill> executableSkills;
        private PlanCursor planCursor;
        private final java.util.List<String> stepLabels;
        private final List<GoalResult.SkippedStep> skippedSteps = new ArrayList<>();
        private GoalStep current;
        private SkillSpec currentSkillSpec;
        private PlanCursor.ActivationLease currentSkillLease;
        private LegacySkillVerifier.Session currentSkillVerifier;
        private Task currentTask;
        private boolean pendingAttemptReservation;
        private boolean currentUsesPendingAttemptReservation;
        private SkillOutcome terminalOutcome;
        private BlueprintSchema blueprint;
        private net.minecraft.core.BlockPos buildAnchor;
        private int buildPlaced;
        private int buildSkipped;
        private StructureReport lastStructure;
        private int totalSteps;
        private final RecoveryLedger recoveryLedger;
        private int lastEvaluationMatched;
        private String lastRepairFingerprint = "";
        // Phase A 韧性·进度感知预算(断点恢复):
        private int completedSteps;    // 累计完成步数(单调增)
        private int elapsedMissionTicks; // 只计 Mission 真正拥有执行权的 tick；暂停/安全抢占不消耗预算
        private int snapSteps;         // 上次 replan 时 completedSteps 快照
        private int snapTargetCount;   // 上次 replan 时目标产物库存计数
        private int snapX, snapY, snapZ; // 上次 replan 时 bot 坐标(横向位移/下潜=进展判据)
        private boolean replanAfterInterrupt;

        private ActivePlan(UUID missionId,
                           int startedTick,
                           Goal goal,
                           GoalPredicate predicate,
                           GoalSnapshotCollector.Context context,
                           MissionPlan missionPlan,
                           List<LegacyMissionCompiler.ExecutableSkill> executableSkills,
                           int totalSteps,
                           java.util.List<String> stepLabels,
                           RecoveryLedger.Snapshot restoredRecovery,
                           CursorCheckpoint restoredCursor,
                           int cursorStartTick,
                           boolean restoredReplanAfterInterrupt) {
            if (stepLabels == null || stepLabels.size() != totalSteps) {
                throw new IllegalArgumentException("mission_step_labels_misaligned");
            }
            if (executableSkills == null || executableSkills.isEmpty()) {
                throw new IllegalArgumentException("mission_executable_skills_missing");
            }
            this.missionId = missionId;
            this.startedTick = startedTick;
            this.goal = goal;
            this.predicate = predicate;
            this.missionPlan = missionPlan;
            this.origin = context.origin();
            this.boundContainers.addAll(context.boundContainers());
            this.blueprint = context.blueprint();
            this.buildAnchor = context.buildAnchor();
            this.buildPlaced = context.buildPlaced();
            this.buildSkipped = context.buildSkipped();
            Map<String, LegacyMissionCompiler.ExecutableSkill> byInvocation = new LinkedHashMap<>();
            for (LegacyMissionCompiler.ExecutableSkill executable : executableSkills) {
                if (byInvocation.putIfAbsent(executable.spec().invocationId(), executable) != null) {
                    throw new IllegalArgumentException("duplicate_executable_skill_invocation");
                }
            }
            this.executableSkills = byInvocation;
            this.planCursor = restoredCursor == null
                    ? missionPlan.cursor(cursorStartTick)
                    : missionPlan.cursor(restoredCursor);
            this.totalSteps = totalSteps;
            this.stepLabels = new ArrayList<>(stepLabels);
            this.recoveryLedger = new RecoveryLedger(
                    missionPlan.goal().policy().recoveryBudget(), restoredRecovery);
            this.replanAfterInterrupt = restoredReplanAfterInterrupt;
            if (restoredCursor != null) {
                PlanCursor.Snapshot restoredSnapshot = this.planCursor.snapshot();
                if (restoredSnapshot.state() == PlanCursor.State.READY) {
                    if (restoredSnapshot.readySkills().size() != 1) {
                        throw new IllegalArgumentException(
                                "restored_skill_ready_cardinality_invalid");
                    }
                    SkillSpec restoredSkill = restoredSnapshot.readySkills().getFirst().spec();
                    int reserved = this.recoveryLedger.attemptsFor(restoredSkill);
                    if (reserved >= 1
                            && reserved <= restoredSkill.retryPolicy().maxAttempts()) {
                        this.pendingAttemptReservation = true;
                    } else if (!restoredReplanAfterInterrupt) {
                        throw new IllegalArgumentException(
                                "restored_skill_attempt_reservation_invalid");
                    }
                }
            }
        }

        private void transition(MissionState next) {
            state = MissionLifecycle.transition(state, next);
        }
    }

    private record GoalRequest(Goal goal,
                               GoalSpec.Source source,
                               int priority,
                               long sequence) {
        private GoalRequest {
            if (goal == null) {
                throw new IllegalArgumentException("queued_goal_missing");
            }
            source = source == null ? GoalSpec.Source.LEGACY : source;
            if (priority < 0 || priority > 100) {
                throw new IllegalArgumentException("queued_goal_priority_out_of_range");
            }
            if (sequence < 0) {
                throw new IllegalArgumentException("queued_goal_sequence_negative");
            }
        }
    }

    private record RestoreSeed(UUID missionId,
                               GoalSnapshotCollector.Context context,
                               int completedSteps,
                               int startedTick,
                               int elapsedMissionTicks,
                               RecoveryLedger.Snapshot recovery,
                               MissionCheckpointCodec.ProgressSnapshot progress,
                               GoalSpec.Source source,
                               int priority,
                               MissionPolicy policy,
                               int planRevision,
                               String planFingerprint,
                               String intentFingerprint,
                               boolean replanAfterInterrupt,
                               CursorCheckpoint cursorCheckpoint) {
        private RestoreSeed {
            source = source == null ? GoalSpec.Source.RESTORED : source;
            if (priority < 0 || priority > 100 || planRevision < 0) {
                throw new IllegalArgumentException("invalid_mission_restore_admission");
            }
            if (missionId == null || context == null || recovery == null || progress == null) {
                throw new IllegalArgumentException("incomplete_mission_restore_seed");
            }
            if (planFingerprint != null && !planFingerprint.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("invalid_mission_restore_fingerprint");
            }
            if (intentFingerprint != null && !intentFingerprint.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("invalid_mission_restore_intent_fingerprint");
            }
            if ((planFingerprint == null) != (intentFingerprint == null)) {
                throw new IllegalArgumentException("incomplete_mission_restore_binding");
            }
            if (cursorCheckpoint != null && (planFingerprint == null
                    || !missionId.equals(cursorCheckpoint.missionId())
                    || planRevision != cursorCheckpoint.planRevision()
                    || !planFingerprint.equals(cursorCheckpoint.planFingerprint())
                    || elapsedMissionTicks != cursorCheckpoint.tick())) {
                throw new IllegalArgumentException("invalid_mission_restore_cursor_binding");
            }
        }
    }
}
