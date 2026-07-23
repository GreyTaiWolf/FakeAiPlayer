package io.github.greytaiwolf.fakeaiplayer.task;

import io.github.greytaiwolf.fakeaiplayer.brain.BotReporter;
import io.github.greytaiwolf.fakeaiplayer.entity.AIPlayerEntity;
import io.github.greytaiwolf.fakeaiplayer.log.BotLog;
import io.github.greytaiwolf.fakeaiplayer.manager.AIPlayerManager;
import io.github.greytaiwolf.fakeaiplayer.mission.GoalSpec;
import io.github.greytaiwolf.fakeaiplayer.mission.MissionArbiter;
import io.github.greytaiwolf.fakeaiplayer.observe.BotProfiler;
import io.github.greytaiwolf.fakeaiplayer.observe.TpsGuard;
import io.github.greytaiwolf.fakeaiplayer.runtime.ExecutionStack;
import io.github.greytaiwolf.fakeaiplayer.runtime.PauseOwner;
import io.github.greytaiwolf.fakeaiplayer.runtime.TaskOrigin;
import net.minecraft.server.MinecraftServer;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.ArrayList;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

public final class TaskManager {
    public static final TaskManager INSTANCE = new TaskManager();

    private final Map<UUID, Task> active = new ConcurrentHashMap<>();
    private final Map<UUID, TaskOrigin> activeOrigins = new ConcurrentHashMap<>();
    private final Map<UUID, ExecutionStack<Task>> executionStacks = new ConcurrentHashMap<>();
    private final Map<UUID, Set<PauseOwner>> pauseLocks = new ConcurrentHashMap<>();
    private final Map<UUID, TaskStatus> lastStatus = new ConcurrentHashMap<>();
    private final Map<UUID, FailureRecord> lastFailure = new ConcurrentHashMap<>();
    private final Map<UUID, FailureRecord> pendingFailure = new ConcurrentHashMap<>();
    private final Map<UUID, String> navigationSafetyLeases = new ConcurrentHashMap<>();
    private final Map<UUID, Set<UUID>> missionInterruptions = new ConcurrentHashMap<>();
    private final Set<UUID> runtimeRecoveryLocks = ConcurrentHashMap.newKeySet();
    private volatile boolean runtimeRecoveryMode;

    private TaskManager() {
    }

    public TaskAssignmentResult assign(AIPlayerEntity bot, Task task, TaskOrigin origin) {
        if (bot == null || task == null || origin == null) {
            throw new IllegalArgumentException("task_assignment_incomplete");
        }
        UUID uuid = bot.getUUID();
        // Resolve the stable identity before any incumbent work is paused. A malformed Task whose
        // name accessor throws must fail without entering the assignment transaction at all.
        String incomingTaskName = task.name();
        TaskOrigin currentOrigin = currentOwnerOrigin(uuid);
        MissionArbiter.Decision decision = previewDecision(bot, origin, currentOrigin);
        if (!decision.startsIncoming()) {
            BotLog.task(bot, "task_assignment_deferred",
                    "incoming", incomingTaskName,
                    "incoming_origin", origin.kind(),
                    "active_origin", currentOrigin == null ? "none" : currentOrigin.kind(),
                    "decision", decision.action(),
                    "reason", decision.reason());
            return new TaskAssignmentResult(false, decision.action(), decision.reason());
        }
        io.github.greytaiwolf.fakeaiplayer.coordination.IdleCoordinator.INSTANCE
                .cancelAmbient(bot, "task_assigned");
        UUID preemptedFrameId = null;
        UUID preemptedMissionId = null;
        if (decision.action() == MissionArbiter.Action.PREEMPT && active.containsKey(uuid)) {
            PauseOwner pauseOwner = origin.safety() ? PauseOwner.SAFETY : PauseOwner.SYSTEM;
            pauseFor(bot, pauseOwner, "arbiter:" + decision.reason(), false);
            ExecutionStack<Task> stack = executionStacks.get(uuid);
            preemptedFrameId = stack == null
                    ? null : stack.peek().map(ExecutionStack.Frame::frameId).orElse(null);
            if (pauseOwner.automaticResumeAllowed() && currentOrigin != null) {
                preemptedMissionId = currentOrigin.missionId();
            }
        } else if (decision.action() == MissionArbiter.Action.REPLACE) {
            io.github.greytaiwolf.fakeaiplayer.coordination.IdleCoordinator.INSTANCE
                    .cancelClaimedJob(bot, "replaced_by:" + origin.kind());
            cancelIntentTasks(bot, "replaced_by:" + origin.kind());
        }
        if (origin.safety() && decision.action() != MissionArbiter.Action.PREEMPT) {
            bot.getActionPack().cancelActivePathForSafety(
                    "safety_task_assign:" + origin.reason());
        }
        bot.getActionPack().stopAll();
        active.put(uuid, task);
        activeOrigins.put(uuid, origin);
        try {
            task.start(bot);
        } catch (RuntimeException startFailure) {
            active.remove(uuid, task);
            activeOrigins.remove(uuid, origin);
            try {
                task.abort(bot);
                if (task instanceof AbstractTask abstractTask && task.state() == TaskState.FAILED) {
                    String message = startFailure.getMessage() == null
                            ? startFailure.getClass().getSimpleName()
                            : startFailure.getMessage();
                    abstractTask.failureReason = "start_failed:" + message;
                }
            } catch (RuntimeException cleanupFailure) {
                startFailure.addSuppressed(cleanupFailure);
            }
            try {
                bot.getActionPack().stopAll();
            } catch (RuntimeException cleanupFailure) {
                startFailure.addSuppressed(cleanupFailure);
            }
            UUID rollbackFrameId = preemptedFrameId;
            FailedStartResolution resolution = settleFailedStart(
                    () -> rollbackFailedPreemption(bot, rollbackFrameId),
                    () -> TaskStatus.from(task),
                    startFailure);
            // A failed interrupt must not overwrite the restored owner's RUNNING status or leak a
            // bot-global pending failure that the old Mission/Brain would consume as its own.
            TaskStatus failed = resolution.failedStatus();
            if (!resolution.restoredPreemptedWork() && failed != null) {
                lastStatus.put(uuid, failed);
                if (failed.state() == TaskState.FAILED) {
                    recordFailure(bot, incomingTaskName, failed.failureReason(),
                            bot.getServer().getTickCount());
                }
                BotReporter.INSTANCE.onStatus(bot.getServer(), bot, failed);
            }
            BotLog.error(bot, "task_start_failed", startFailure, "name", incomingTaskName);
            throw startFailure;
        }
        publishMissionInterruption(bot, preemptedMissionId);
        AssignmentDiagnostics diagnostics = captureCommittedStatus(task, incomingTaskName);
        TaskStatus status = diagnostics.status();
        lastStatus.put(uuid, status);
        RuntimeException diagnosticFailure = diagnostics.failure();
        diagnosticFailure = isolatePostStartDiagnostic(diagnosticFailure,
                () -> BotReporter.INSTANCE.onAssigned(bot, status));
        diagnosticFailure = isolatePostStartDiagnostic(diagnosticFailure,
                () -> BotLog.task(bot, "task_assigned",
                        "name", incomingTaskName,
                        "params", status.description(),
                        "origin", origin.kind(),
                        "origin_reason", origin.reason(),
                        "arbiter", decision.action(),
                        "arbiter_reason", decision.reason()));
        if (diagnosticFailure != null) {
            try {
                BotLog.error(bot, "task_assignment_diagnostics_failed", diagnosticFailure,
                        "name", incomingTaskName);
            } catch (RuntimeException loggingFailure) {
                if (loggingFailure != diagnosticFailure) {
                    diagnosticFailure.addSuppressed(loggingFailure);
                }
            }
        }
        return new TaskAssignmentResult(true, decision.action(), decision.reason());
    }

    /**
     * Side-effect-free preview of the exact central ownership gate. Callers may use this before
     * reading mutable world preconditions, but {@link #assign} always rechecks atomically before
     * installation.
     */
    public TaskAssignmentResult previewAssignment(AIPlayerEntity bot, TaskOrigin origin) {
        if (bot == null || origin == null) {
            throw new IllegalArgumentException("task_assignment_preview_incomplete");
        }
        MissionArbiter.Decision decision = previewDecision(
                bot, origin, currentOwnerOrigin(bot.getUUID()));
        return new TaskAssignmentResult(
                decision.startsIncoming(), decision.action(), decision.reason());
    }

    private MissionArbiter.Decision previewDecision(AIPlayerEntity bot,
                                                     TaskOrigin incoming,
                                                     TaskOrigin current) {
        String dimensionFailure = missionDimensionFailure(
                incoming, bot.serverLevel().dimension().location().toString());
        if (dimensionFailure != null) {
            return new MissionArbiter.Decision(
                    MissionArbiter.Action.REJECT, dimensionFailure);
        }
        if (runtimeRecoveryGateBlocks(
                runtimeRecoveryMode,
                runtimeRecoveryLocks.contains(bot.getUUID()),
                incoming)) {
            return new MissionArbiter.Decision(
                    MissionArbiter.Action.DEFER,
                    "runtime_partial_restore_read_only");
        }
        String navigationLease = navigationSafetyLeases.get(bot.getUUID());
        if (navigationLease != null && !incoming.safety()) {
            return new MissionArbiter.Decision(
                    MissionArbiter.Action.DEFER,
                    "navigation_safety_active:" + navigationLease);
        }
        return MissionArbiter.decide(
                current == null ? null : workClaim(current),
                workClaim(incoming),
                hasPersistentPause(bot));
    }

    private TaskOrigin currentOwnerOrigin(UUID botId) {
        TaskOrigin current = activeOrigins.get(botId);
        if (current != null) {
            return current;
        }
        ExecutionStack<Task> stack = executionStacks.get(botId);
        return stack == null ? null : stack.peek().map(ExecutionStack.Frame::origin).orElse(null);
    }

    static MissionArbiter.WorkClaim workClaim(TaskOrigin origin) {
        MissionArbiter.WorkKind kind = switch (origin.kind()) {
            case SAFETY -> MissionArbiter.WorkKind.SAFETY;
            case REFLEX -> MissionArbiter.WorkKind.REFLEX;
            case PLAYER_COMMAND, PLAYER_PANEL -> MissionArbiter.WorkKind.PLAYER;
            case VERIFY -> MissionArbiter.WorkKind.VERIFY;
            case MISSION -> origin.playerMission()
                    ? MissionArbiter.WorkKind.PLAYER_MISSION : MissionArbiter.WorkKind.MISSION;
            case LLM_TOOL, LLM_SKILL -> MissionArbiter.WorkKind.AI_DIRECT;
            case JOB -> MissionArbiter.WorkKind.JOB;
            case SYSTEM_BACKGROUND -> MissionArbiter.WorkKind.BACKGROUND;
        };
        String owner = origin.missionId() != null
                ? "mission:" + origin.missionId()
                : origin.jobId() != null
                ? "job:" + origin.jobId()
                : origin.kind().name().toLowerCase(java.util.Locale.ROOT) + ':' + origin.reason();
        int priority = switch (origin.kind()) {
            case SAFETY -> safetyPriority(origin.reason());
            case REFLEX -> 750;
            case PLAYER_COMMAND, PLAYER_PANEL -> 900;
            case VERIFY -> 800;
            // Goal priority is relative within the Mission band. Keeping the whole band at or
            // below 700 ensures routine REFLEX (750), VERIFY (800), and SAFETY (850+) retain the
            // ability to pause and later resume even a player-confirmed Mission.
            case MISSION -> origin.goalPriority() == null
                    ? 700 : 600 + origin.goalPriority();
            case LLM_TOOL, LLM_SKILL -> 600;
            case JOB -> 400;
            case SYSTEM_BACKGROUND -> 200;
        };
        boolean resumable = switch (origin.kind()) {
            case MISSION, LLM_SKILL, JOB, REFLEX, SYSTEM_BACKGROUND -> true;
            default -> false;
        };
        return new MissionArbiter.WorkClaim(
                kind,
                owner,
                priority,
                resumable,
                origin.kind() == TaskOrigin.Kind.MISSION ? origin.missionSource() : null);
    }

    static boolean runtimeRecoveryLockBypass(TaskOrigin origin) {
        return origin != null && origin.safety();
    }

    static boolean runtimeRecoveryGateBlocks(boolean globalMode,
                                             boolean botLocked,
                                             TaskOrigin origin) {
        return (globalMode || botLocked) && !runtimeRecoveryLockBypass(origin);
    }

    private static int safetyPriority(String reason) {
        String normalized = reason == null ? "" : reason.toLowerCase(java.util.Locale.ROOT);
        if (normalized.contains("lava") || normalized.contains("drown")) {
            return 1_000;
        }
        if (normalized.contains("emergency") || normalized.contains("entomb")) {
            return 980;
        }
        if (normalized.contains("low_hp") || normalized.contains("critical")
                || normalized.contains("threat")) {
            return 950;
        }
        if (normalized.contains("recover_drops")) {
            return 850;
        }
        return 900;
    }

    private static boolean rollbackFailedPreemption(AIPlayerEntity bot, UUID preemptedFrameId) {
        if (preemptedFrameId == null) {
            return false;
        }
        return INSTANCE.resumeTop(bot, frame -> frame.frameId().equals(preemptedFrameId));
    }

    /**
     * Settles the state-owning half of a failed assignment before inspecting the incoming Task.
     *
     * <p>A Task that throws from {@code start()} is not trusted to have working diagnostic
     * accessors either. Reading {@link TaskStatus#from(Task)} before restoring a preempted owner
     * could therefore strand that owner in the execution stack. Once restoration succeeds the
     * failed Task is deliberately not inspected at all, so its failure cannot overwrite the
     * restored owner's status or failure channel.</p>
     */
    static FailedStartResolution settleFailedStart(BooleanSupplier rollback,
                                                    Supplier<TaskStatus> failedStatus,
                                                    RuntimeException startFailure) {
        if (rollback == null || failedStatus == null || startFailure == null) {
            throw new IllegalArgumentException("failed_start_transaction_incomplete");
        }
        boolean restoredPreemptedWork = false;
        try {
            restoredPreemptedWork = rollback.getAsBoolean();
        } catch (RuntimeException rollbackFailure) {
            startFailure.addSuppressed(rollbackFailure);
        }
        if (restoredPreemptedWork) {
            return new FailedStartResolution(true, null);
        }
        try {
            return new FailedStartResolution(false, failedStatus.get());
        } catch (RuntimeException diagnosticFailure) {
            startFailure.addSuppressed(diagnosticFailure);
            return new FailedStartResolution(false, null);
        }
    }

    record FailedStartResolution(boolean restoredPreemptedWork, TaskStatus failedStatus) {
    }

    /** Builds a non-throwing status snapshot after ownership has already been committed. */
    static AssignmentDiagnostics captureCommittedStatus(Task task, String stableName) {
        try {
            return new AssignmentDiagnostics(TaskStatus.from(task), null);
        } catch (RuntimeException diagnosticFailure) {
            String detail = diagnosticFailure.getMessage();
            String description = "Task active; diagnostics unavailable"
                    + (detail == null || detail.isBlank() ? "" : ": " + detail);
            return new AssignmentDiagnostics(new TaskStatus(
                    stableName,
                    description,
                    TaskState.RUNNING,
                    0.0D,
                    "",
                    0), diagnosticFailure);
        }
    }

    /** Runs one non-critical observer without allowing it to roll back a committed owner. */
    static RuntimeException isolatePostStartDiagnostic(RuntimeException previous,
                                                       Runnable diagnostic) {
        try {
            diagnostic.run();
            return previous;
        } catch (RuntimeException failure) {
            if (previous == null) {
                return failure;
            }
            if (failure != previous) {
                previous.addSuppressed(failure);
            }
            return previous;
        }
    }

    record AssignmentDiagnostics(TaskStatus status, RuntimeException failure) {
    }

    public void abort(AIPlayerEntity bot) {
        UUID uuid = bot.getUUID();
        Task current = active.remove(uuid);
        TaskOrigin origin = activeOrigins.remove(uuid);
        if (current != null) {
            current.abort(bot);
            lastStatus.put(uuid, TaskStatus.from(current));
            BotReporter.INSTANCE.onStatus(bot.getServer(), bot, TaskStatus.from(current));
            if (resumesSystemPause(origin)) {
                resumeSystemPause(bot);
            } else if (origin != null && origin.safety()) {
                resumeNestedSafetyPause(bot);
            }
        }
    }

    /**
     * 把 bot 彻底复位到干净的空闲:停掉活跃/暂停任务、清失败记录与状态缓存,使 status() 返回 idle。
     * 供大脑在"反复失败已放弃"(max_turns)等场景善后调用——否则遗留任务 FAILED 后 lastStatus 会长期
     * 缓存 FAILED(面板/诊断一直显示卡死),pendingFailure 滞留也会让 idle-watcher 空转(实测发呆 13 分钟根因)。
     */
    public void resetToIdle(AIPlayerEntity bot) {
        UUID uuid = bot.getUUID();
        Task current = active.remove(uuid);
        if (current != null) {
            current.abort(bot);
        }
        ExecutionStack<Task> stack = executionStacks.remove(uuid);
        if (stack != null) {
            for (ExecutionStack.Frame<Task> frame : stack.drain()) {
                frame.work().abort(bot);
            }
        }
        activeOrigins.remove(uuid);
        pauseLocks.remove(uuid);
        missionInterruptions.remove(uuid);
        lastFailure.remove(uuid);
        pendingFailure.remove(uuid);
        lastStatus.put(uuid, TaskStatus.idle());
        BotReporter.INSTANCE.onStatus(bot.getServer(), bot, TaskStatus.idle());
    }

    /** User-intent cancellation: clear active and paused work without creating a failure/replan. */
    public boolean cancelIntentTasks(AIPlayerEntity bot, String reason) {
        UUID uuid = bot.getUUID();
        Task current = active.remove(uuid);
        activeOrigins.remove(uuid);
        ExecutionStack<Task> stack = executionStacks.remove(uuid);
        java.util.List<ExecutionStack.Frame<Task>> pausedFrames = stack == null ? java.util.List.of() : stack.drain();
        pauseLocks.remove(uuid);
        missionInterruptions.remove(uuid);
        boolean hadFailure = lastFailure.remove(uuid) != null;
        boolean hadPendingFailure = pendingFailure.remove(uuid) != null;
        if (current != null) {
            try {
                current.cancel(bot, reason);
            } catch (RuntimeException exception) {
                BotLog.error(bot, "task_cancel_cleanup_failed", exception, "name", current.name(), "reason", reason);
            }
        }
        for (ExecutionStack.Frame<Task> frame : pausedFrames) {
            Task pausedTask = frame.work();
            if (pausedTask == current) {
                continue;
            }
            try {
                pausedTask.cancel(bot, reason);
            } catch (RuntimeException exception) {
                BotLog.error(bot, "paused_task_cancel_cleanup_failed", exception, "name", pausedTask.name(), "reason", reason);
            }
        }
        Task representative = current != null ? current : pausedFrames.isEmpty() ? null : pausedFrames.get(0).work();
        if (representative != null) {
            TaskStatus cancelled = TaskStatus.from(representative);
            lastStatus.put(uuid, cancelled);
            BotReporter.INSTANCE.onStatus(bot.getServer(), bot, cancelled);
            BotLog.task(bot, "task_cancelled", "name", representative.name(), "reason", reason);
        } else if (hadFailure || hadPendingFailure) {
            lastStatus.put(uuid, TaskStatus.idle());
            BotReporter.INSTANCE.onStatus(bot.getServer(), bot, TaskStatus.idle());
        }
        return representative != null || hadFailure || hadPendingFailure;
    }

    /** Cancels only Task frames owned by one Mission, leaving active safety/reflex work untouched. */
    public boolean cancelMissionTasks(AIPlayerEntity bot, UUID missionId, String reason) {
        if (bot == null || missionId == null) {
            return false;
        }
        UUID uuid = bot.getUUID();
        java.util.List<Task> cancelled = new ArrayList<>();
        Task current = active.get(uuid);
        TaskOrigin currentOrigin = activeOrigins.get(uuid);
        if (current != null && currentOrigin != null && missionId.equals(currentOrigin.missionId())
                && active.remove(uuid, current)) {
            activeOrigins.remove(uuid, currentOrigin);
            cancelled.add(current);
        }
        ExecutionStack<Task> stack = executionStacks.get(uuid);
        if (stack != null) {
            for (ExecutionStack.Frame<Task> frame : stack.removeMatching(
                    candidate -> missionId.equals(candidate.origin().missionId()))) {
                cancelled.add(frame.work());
            }
            if (stack.isEmpty()) {
                executionStacks.remove(uuid, stack);
            }
        }
        boolean preserveActiveActions = active.containsKey(uuid);
        for (Task task : cancelled) {
            try {
                cancelDetachedTask(bot, task, reason, preserveActiveActions);
            } catch (RuntimeException exception) {
                BotLog.error(bot, "mission_task_cancel_cleanup_failed", exception,
                        "mission_id", missionId, "name", task.name(), "reason", reason);
            }
        }
        if (!cancelled.isEmpty()) {
            Set<UUID> interrupted = missionInterruptions.get(uuid);
            if (interrupted != null) {
                interrupted.remove(missionId);
                if (interrupted.isEmpty()) {
                    missionInterruptions.remove(uuid, interrupted);
                }
            }
            if (!active.containsKey(uuid)) {
                TaskStatus status = TaskStatus.from(cancelled.get(0));
                lastStatus.put(uuid, status);
                BotReporter.INSTANCE.onStatus(bot.getServer(), bot, status);
            }
            BotLog.task(bot, "mission_tasks_cancelled", "mission_id", missionId,
                    "count", cancelled.size(), "reason", reason);
        }
        return !cancelled.isEmpty();
    }

    static void cancelDetachedTask(AIPlayerEntity bot,
                                   Task task,
                                   String reason,
                                   boolean preserveActiveActions) {
        if (preserveActiveActions) {
            // The removed frame no longer owns the shared ActionPack. Its explicit detached
            // callback can release private resources without stopping the safety/reflex Task that
            // currently owns movement, mining and navigation.
            task.cancelDetached(bot, reason);
        } else {
            task.cancel(bot, reason);
        }
    }

    public Optional<Task> getActive(AIPlayerEntity bot) {
        return Optional.ofNullable(active.get(bot.getUUID()));
    }

    public boolean hasPaused(AIPlayerEntity bot) {
        ExecutionStack<Task> stack = executionStacks.get(bot.getUUID());
        return stack != null && !stack.isEmpty();
    }

    public int pausedDepth(AIPlayerEntity bot) {
        ExecutionStack<Task> stack = executionStacks.get(bot.getUUID());
        return stack == null ? 0 : stack.size();
    }

    public Optional<TaskOrigin> activeOrigin(AIPlayerEntity bot) {
        return Optional.ofNullable(activeOrigins.get(bot.getUUID()));
    }

    /** Propagates a stronger admission for the same Mission into active and paused Task frames. */
    public boolean upgradeMissionAdmission(AIPlayerEntity bot, UUID missionId, GoalSpec goal) {
        if (bot == null || missionId == null || goal == null) {
            throw new IllegalArgumentException("mission_admission_upgrade_incomplete");
        }
        UUID botId = bot.getUUID();
        boolean[] changed = {false};
        activeOrigins.computeIfPresent(botId, (ignored, origin) -> {
            if (!missionId.equals(origin.missionId())) {
                return origin;
            }
            changed[0] = true;
            return TaskOrigin.mission(missionId, origin.reason(), goal);
        });
        ExecutionStack<Task> stack = executionStacks.get(botId);
        if (stack != null) {
            changed[0] |= stack.replaceOrigins(
                    origin -> missionId.equals(origin.missionId()),
                    origin -> TaskOrigin.mission(missionId, origin.reason(), goal)) > 0;
        }
        return changed[0];
    }

    public boolean isUserPaused(AIPlayerEntity bot) {
        return isPausedBy(bot, PauseOwner.USER);
    }

    public boolean isPausedBy(AIPlayerEntity bot, PauseOwner owner) {
        Set<PauseOwner> owners = pauseLocks.get(bot.getUUID());
        return owners != null && owners.contains(owner);
    }

    /** True when user or inventory interaction explicitly owns the bot's ordinary-work pause. */
    public boolean hasPersistentPause(AIPlayerEntity bot) {
        Set<PauseOwner> owners = pauseLocks.get(bot.getUUID());
        return owners != null && owners.stream().anyMatch(PauseOwner::persistentLock);
    }

    public void acquireNavigationSafetyLease(AIPlayerEntity bot, String reason) {
        if (bot != null) {
            navigationSafetyLeases.put(bot.getUUID(), reason == null ? "unknown" : reason);
        }
    }

    public void releaseNavigationSafetyLease(AIPlayerEntity bot) {
        if (bot != null) {
            navigationSafetyLeases.remove(bot.getUUID());
        }
    }

    public boolean hasNavigationSafetyLease(AIPlayerEntity bot) {
        return bot != null && navigationSafetyLeases.containsKey(bot.getUUID());
    }

    /** Fail-closed lock used when the source runtime snapshot could not be fully accounted. */
    public void acquireRuntimeRecoveryLock(AIPlayerEntity bot) {
        if (bot == null) {
            return;
        }
        runtimeRecoveryLocks.add(bot.getUUID());
        BotLog.warn(io.github.greytaiwolf.fakeaiplayer.log.LogCategory.LIFECYCLE,
                bot,
                "runtime_recovery_lock_acquired",
                "reason", "partial_restore_read_only");
    }

    /**
     * Freezes ordinary work for the whole runtime session. Per-Bot locks remain useful diagnostics,
     * but this global gate also covers Bots created after an unreadable or partial restore.
     */
    public void enterRuntimeRecoveryMode(String reason) {
        runtimeRecoveryMode = true;
        BotLog.security("runtime_recovery_mode_entered",
                "reason", reason == null ? "runtime_restore_unaccounted" : reason);
    }

    /** Opens a new restore admission epoch before any persisted work is reconstructed. */
    public void beginRuntimeSession() {
        runtimeRecoveryMode = false;
        runtimeRecoveryLocks.clear();
    }

    public boolean runtimeRecoveryModeActive() {
        return runtimeRecoveryMode;
    }

    public boolean hasRuntimeRecoveryLock(AIPlayerEntity bot) {
        return runtimeRecoveryMode
                || (bot != null && runtimeRecoveryLocks.contains(bot.getUUID()));
    }

    public boolean hasSafetyOwnership(AIPlayerEntity bot) {
        if (bot == null) {
            return false;
        }
        UUID uuid = bot.getUUID();
        TaskOrigin origin = activeOrigins.get(uuid);
        if (origin != null && origin.safety()) {
            return true;
        }
        ExecutionStack<Task> stack = executionStacks.get(uuid);
        return navigationSafetyLeases.containsKey(uuid)
                || (stack != null && stack.anyMatch(
                frame -> frame.pauseOwner() == PauseOwner.SAFETY));
    }

    public boolean isMissionAutomaticallyPaused(AIPlayerEntity bot, UUID missionId) {
        if (bot == null || missionId == null) {
            return false;
        }
        ExecutionStack<Task> stack = executionStacks.get(bot.getUUID());
        return stack != null && stack.anyMatch(frame ->
                missionId.equals(frame.origin().missionId())
                        && frame.pauseOwner().automaticResumeAllowed());
    }

    /** Returns a latched preemption even if the interrupt already resumed before GoalExecutor ran. */
    public boolean hasMissionInterruption(AIPlayerEntity bot, UUID missionId) {
        if (bot == null || missionId == null) {
            return false;
        }
        Set<UUID> interrupted = missionInterruptions.get(bot.getUUID());
        return interrupted != null && interrupted.contains(missionId);
    }

    /** Returns and clears a latched preemption after the Mission has made it durable. */
    public boolean consumeMissionInterruption(AIPlayerEntity bot, UUID missionId) {
        if (bot == null || missionId == null) {
            return false;
        }
        UUID uuid = bot.getUUID();
        Set<UUID> interrupted = missionInterruptions.get(uuid);
        if (interrupted == null || !interrupted.remove(missionId)) {
            return false;
        }
        if (interrupted.isEmpty()) {
            missionInterruptions.remove(uuid, interrupted);
        }
        return true;
    }

    public Set<PauseOwner> pauseOwners(AIPlayerEntity bot) {
        Set<PauseOwner> owners = pauseLocks.get(bot.getUUID());
        return owners == null ? Set.of() : Set.copyOf(owners);
    }

    public TaskStatus status(AIPlayerEntity bot) {
        Task current = active.get(bot.getUUID());
        if (current != null) {
            return TaskStatus.from(current);
        }
        ExecutionStack<Task> stack = executionStacks.get(bot.getUUID());
        if (stack != null && stack.peek().isPresent()) {
            return TaskStatus.from(stack.peek().orElseThrow().work());
        }
        return lastStatus.getOrDefault(bot.getUUID(), TaskStatus.idle());
    }

    public void pauseFor(AIPlayerEntity bot, String why) {
        pauseFor(bot, PauseOwner.SYSTEM, why);
    }

    public boolean pauseFor(AIPlayerEntity bot, PauseOwner pauseOwner, String why) {
        return pauseFor(bot, pauseOwner, why, true);
    }

    private boolean pauseFor(AIPlayerEntity bot,
                             PauseOwner pauseOwner,
                             String why,
                             boolean publishInterruption) {
        UUID uuid = bot.getUUID();
        boolean changed = acquirePauseLock(uuid, pauseOwner);
        if (pauseOwner.automaticResumeAllowed()) {
            // Every safety producer must publish PREEMPTED before Task.pause reaches
            // AbstractTask.onPause/stopAll. Keeping this at the common ownership boundary covers
            // DangerWatcher, NavSafetyNet, and future safety callers uniformly.
            bot.getActionPack().cancelActivePathForSafety(
                    "automatic_pause:" + pauseOwner + ':' + why);
        }
        Task current = active.remove(uuid);
        TaskOrigin origin = activeOrigins.remove(uuid);
        if (current == null) {
            return changed;
        }
        // Keep pause as a state transaction. AbstractTask changes its state before invoking
        // onPause(), so a throwing hook would otherwise remove the only active reference and
        // leave a half-paused Task outside both the active slot and the execution stack.
        try {
            current.pause(bot);
        } catch (RuntimeException pauseFailure) {
            active.put(uuid, current);
            if (origin != null) {
                activeOrigins.put(uuid, origin);
            }
            if (changed) {
                releasePauseLock(uuid, pauseOwner);
            }
            restoreRunningAfterFailedPause(bot, current, pauseFailure);
            throw pauseFailure;
        }
        TaskOrigin preservedOrigin = origin == null
                ? TaskOrigin.of(TaskOrigin.Kind.SYSTEM_BACKGROUND, "unknown_origin") : origin;
        ExecutionStack<Task> stack = executionStacks.computeIfAbsent(uuid, ignored -> new ExecutionStack<>());
        stack.push(current, preservedOrigin, pauseOwner);
        if (publishInterruption && pauseOwner.automaticResumeAllowed()) {
            publishMissionInterruption(bot, preservedOrigin.missionId());
        }
        TaskStatus status = TaskStatus.from(current);
        lastStatus.put(bot.getUUID(), status);
        BotReporter.INSTANCE.onStatus(bot.getServer(), bot, status);
        BotLog.task(bot, "task_paused", "name", current.name(), "why", why,
                "origin", preservedOrigin.kind(), "pause_owner", pauseOwner,
                "stack_depth", stack.size());
        return true;
    }

    private void publishMissionInterruption(AIPlayerEntity bot, UUID missionId) {
        if (missionId == null) {
            return;
        }
        missionInterruptions.computeIfAbsent(bot.getUUID(), ignored -> ConcurrentHashMap.newKeySet())
                .add(missionId);
    }

    /** Legacy automatic-unwind entry point. Manual USER/INVENTORY frames are intentionally ignored. */
    public void resumeFromPause(AIPlayerEntity bot) {
        resumeAutomaticPause(bot);
    }

    public boolean resumeAutomaticPause(AIPlayerEntity bot) {
        return resumeSafetyPause(bot);
    }

    public boolean resumeSafetyPause(AIPlayerEntity bot) {
        return resumeAutomaticOwnedPause(bot, PauseOwner.SAFETY);
    }

    public boolean resumeSystemPause(AIPlayerEntity bot) {
        return resumeAutomaticOwnedPause(bot, PauseOwner.SYSTEM);
    }

    /**
     * GoalExecutor-only handoff after it has applied the bound Mission interruption policy.
     * Generic safety/system unwind paths deliberately cannot call onResume while the transient
     * Mission interruption latch is still pending.
     */
    public boolean resumeMissionAfterInterruption(AIPlayerEntity bot, UUID missionId) {
        if (bot == null || missionId == null) {
            return false;
        }
        if (!hasMissionInterruption(bot, missionId)) {
            return false;
        }
        return resumeTop(bot, frame ->
                frame.pauseOwner().automaticResumeAllowed()
                        && frame.origin().kind() == TaskOrigin.Kind.MISSION
                        && missionId.equals(frame.origin().missionId()), true);
    }

    private boolean resumeAutomaticOwnedPause(AIPlayerEntity bot, PauseOwner owner) {
        boolean released = releasePauseLock(bot.getUUID(), owner);
        boolean resumed = resumeTop(bot, frame -> frame.pauseOwner() == owner);
        return released || resumed;
    }

    /**
     * Releases exactly one persistent pause owner. Closing an inventory can therefore never release
     * a user pause (and vice versa).
     */
    public boolean resumeOwnedPause(AIPlayerEntity bot, PauseOwner pauseOwner) {
        UUID uuid = bot.getUUID();
        boolean changed = releasePauseLock(uuid, pauseOwner);
        if (!changed) {
            return false;
        }
        if (hasPersistentPause(bot)) {
            return true;
        }
        Set<PauseOwner> remainingOwners = pauseOwners(bot);
        try {
            return resumeTop(bot, frame -> PauseOwner.resumeAllowedAfterPersistentRelease(
                    frame.pauseOwner(), remainingOwners)) || changed;
        } catch (RuntimeException resumeFailure) {
            // resumeTop preserves the frame when Task.onResume throws. Restore the matching lock
            // as part of the same transaction so a failed USER/INVENTORY resume cannot expose
            // ordinary work to a later assignment.
            acquirePauseLock(uuid, pauseOwner);
            throw resumeFailure;
        }
    }

    private boolean resumeTop(AIPlayerEntity bot,
                              java.util.function.Predicate<ExecutionStack.Frame<Task>> allowed) {
        return resumeTop(bot, allowed, false);
    }

    private boolean resumeTop(AIPlayerEntity bot,
                              java.util.function.Predicate<ExecutionStack.Frame<Task>> allowed,
                              boolean missionPolicyAuthorized) {
        UUID uuid = bot.getUUID();
        if (active.containsKey(uuid)) {
            return false;
        }
        ExecutionStack<Task> stack = executionStacks.get(uuid);
        if (stack == null) {
            return false;
        }
        Optional<ExecutionStack.Frame<Task>> top = stack.peek();
        if (top.isEmpty() || !allowed.test(top.get())) {
            return false;
        }
        // A USER/INVENTORY lock protects ordinary work, but it must not strand an outer safety
        // task when a nested safety interrupt completes. Only a non-safety frame is blocked by a
        // persistent lock; safety frames retain their own LIFO continuation.
        if (hasPersistentPause(bot) && !top.get().origin().safety()) {
            return false;
        }
        ExecutionStack.Frame<Task> frame = top.orElseThrow();
        if (runtimeRecoveryGateBlocks(
                runtimeRecoveryMode,
                runtimeRecoveryLocks.contains(uuid),
                frame.origin())) {
            return false;
        }
        if (!missionPolicyAuthorized
                && missionTickQuarantined(frame.origin(), missionInterruptions.get(uuid))) {
            // onResume is not observational: MoveTask and mining/drop-recovery adapters can start
            // paths or child Tasks immediately. Leave the exact frame paused until GoalExecutor
            // has dispatched RESUME/REPLAN/CANCEL from the bound MissionPolicy.
            return false;
        }
        Task task = frame.work();
        try {
            task.resume(bot);
        } catch (RuntimeException resumeFailure) {
            restorePausedAfterFailedResume(bot, task, resumeFailure);
            throw resumeFailure;
        }
        if (stack.peek().orElse(null) != frame) {
            IllegalStateException changed =
                    new IllegalStateException("execution_stack_changed_during_resume");
            restorePausedAfterFailedResume(bot, task, changed);
            throw changed;
        }
        stack.pop().orElseThrow();
        active.put(uuid, task);
        activeOrigins.put(uuid, frame.origin());
        TaskStatus status = TaskStatus.from(task);
        lastStatus.put(bot.getUUID(), status);
        BotReporter.INSTANCE.onStatus(bot.getServer(), bot, status);
        if (stack.isEmpty()) {
            executionStacks.remove(uuid, stack);
        }
        BotLog.task(bot, "task_resumed", "name", task.name(), "origin", frame.origin().kind(),
                "pause_owner", frame.pauseOwner(), "stack_depth", stack.size(),
                "pause_locks", pauseOwners(bot));
        return true;
    }

    public boolean pauseUserIntent(AIPlayerEntity bot, String why) {
        UUID uuid = bot.getUUID();
        boolean safetyOwned = hasSafetyOwnership(bot);
        boolean changed = safetyOwned
                ? acquirePauseLock(uuid, PauseOwner.USER)
                : pauseFor(bot, PauseOwner.USER, "user_pause:" + why);
        if (!safetyOwned) {
            bot.getActionPack().stopAll();
        }
        BotLog.task(bot, "mission_user_paused", "why", why, "stack_depth", pausedDepth(bot));
        return changed;
    }

    public boolean resumeUserIntent(AIPlayerEntity bot, String why) {
        if (hasRuntimeRecoveryLock(bot)) {
            BotLog.security("runtime_recovery_resume_rejected",
                    "bot_uuid", bot.getUUID(),
                    "reason", why == null ? "user_resume" : why);
            return false;
        }
        boolean changed = resumeOwnedPause(bot, PauseOwner.USER);
        BotLog.task(bot, "mission_user_resumed", "why", why, "stack_depth", pausedDepth(bot));
        return changed;
    }

    public void tickAll(MinecraftServer server) {
        for (Map.Entry<UUID, Task> entry : new ArrayList<>(active.entrySet())) {
            UUID uuid = entry.getKey();
            Task task = entry.getValue();
            Optional<AIPlayerEntity> bot = AIPlayerManager.INSTANCE.getByUuid(uuid);
            if (bot.isEmpty()) {
                active.remove(uuid);
                activeOrigins.remove(uuid);
                executionStacks.remove(uuid);
                pauseLocks.remove(uuid);
                navigationSafetyLeases.remove(uuid);
                missionInterruptions.remove(uuid);
                runtimeRecoveryLocks.remove(uuid);
                continue;
            }
            AIPlayerEntity player = bot.get();
            TaskOrigin origin = activeOrigins.get(uuid);
            if (runtimeRecoveryGateBlocks(
                    runtimeRecoveryMode,
                    runtimeRecoveryLocks.contains(uuid),
                    origin)) {
                BotProfiler.INSTANCE.record(
                        player, "runtime_recovery_lock_tick_skipped", 0L);
                continue;
            }
            if (task.state() == TaskState.RUNNING
                    && missionTickQuarantined(origin, missionInterruptions.get(uuid))) {
                // A safety unwind may have restored the Task after TaskManager already ran in the
                // current server tick. Do not give that Task another world-mutating tick until
                // GoalExecutor has consumed and applied the Mission's interruption policy.
                BotProfiler.INSTANCE.record(player, "mission_tick_quarantined", 0L);
                continue;
            }
            String dimensionFailure = missionDimensionFailure(
                    origin, player.serverLevel().dimension().location().toString());
            boolean tickAllowed = dimensionFailure == null;
            if (dimensionFailure != null && task.state() == TaskState.RUNNING) {
                try {
                    task.abort(player);
                } catch (RuntimeException cleanupFailure) {
                    BotLog.error(player, "mission_dimension_gate_cleanup_failed", cleanupFailure,
                            "task", task.name(), "reason", dimensionFailure);
                } finally {
                    if (task instanceof AbstractTask abstractTask) {
                        abstractTask.state = TaskState.FAILED;
                        abstractTask.failureReason = dimensionFailure;
                    }
                }
                BotLog.warn(io.github.greytaiwolf.fakeaiplayer.log.LogCategory.TASK,
                        player,
                        "mission_dimension_gate_abort",
                        "task", task.name(),
                        "expected_dimension", origin.missionDimension(),
                        "actual_dimension", player.serverLevel().dimension().location());
            }
            if (task.state() == TaskState.RUNNING
                    && (origin == null || !origin.safety()) && !isCritical(task)
                    && !TpsGuard.INSTANCE.shouldTickNonCriticalTask(server)) {
                BotProfiler.INSTANCE.record(player, "task_tick_skipped", 0L);
                continue;
            }
            // V1 统一生存层:任务 tick 前熔断检查——溺水/岩浆/着火/垂死作业一律叫停,
            // 失败原因透传给 goal 层 replan。任务私有熔断可以更早更聪明,但漏配时这里兜底。
            String breaker = SurvivalGuard.INSTANCE.check(player, task);
            if (breaker != null && task.state() == TaskState.RUNNING) {
                task.abort(player);
                if (task instanceof AbstractTask at) {
                    at.failureReason = breaker; // abort 默认 "aborted",改成可诊断的熔断理由
                }
                BotLog.danger(player, "survival_guard_abort", "task", task.name(), "why", breaker);
            }
            long started = System.nanoTime();
            try {
                if (tickAllowed) {
                    task.tick(player);
                }
            } catch (RuntimeException tickFailure) {
                terminalizeTickFailure(player, task, tickFailure);
                BotLog.error(player, "task_tick_failed", tickFailure,
                        "name", task.name(),
                        "origin", origin == null ? "unknown" : origin.kind());
            } finally {
                BotProfiler.INSTANCE.record(player, "task_tick", System.nanoTime() - started);
            }
            TaskStatus status = TaskStatus.from(task);
            lastStatus.put(uuid, status);
            BotReporter.INSTANCE.onStatus(server, player, status);
            if (task.state() == TaskState.COMPLETED) {
                active.remove(uuid);
                activeOrigins.remove(uuid);
                lastFailure.remove(uuid);
                pendingFailure.remove(uuid);
                BotLog.task(player, "task_completed", "name", task.name(), "elapsed_ticks", task.elapsedTicks());
                if (origin != null && origin.safety()) {
                    resumeNestedSafetyPause(player);
                } else if (resumesSystemPause(origin)) {
                    resumeSystemPause(player);
                }
            } else if (task.state() == TaskState.FAILED) {
                active.remove(uuid);
                activeOrigins.remove(uuid);
                recordFailure(player, task.name(), task.failureReason(), server.getTickCount());
                BotLog.warn(io.github.greytaiwolf.fakeaiplayer.log.LogCategory.TASK, player, "task_failed",
                        "name", task.name(), "reason", task.failureReason(), "elapsed_ticks", task.elapsedTicks());
                if (origin != null && origin.safety()) {
                    resumeNestedSafetyPause(player);
                } else if (resumesSystemPause(origin)) {
                    resumeSystemPause(player);
                }
            } else if (task.state() == TaskState.CANCELLED) {
                active.remove(uuid);
                activeOrigins.remove(uuid);
                lastFailure.remove(uuid);
                pendingFailure.remove(uuid);
                BotLog.task(player, "task_cancelled", "name", task.name(), "reason", task.failureReason());
                if (origin != null && origin.safety()) {
                    resumeNestedSafetyPause(player);
                } else if (resumesSystemPause(origin)) {
                    resumeSystemPause(player);
                }
            }
        }
    }

    static String missionDimensionFailure(TaskOrigin origin, String actualDimension) {
        return origin == null ? null : origin.dimensionFailure(actualDimension);
    }

    static boolean missionTickQuarantined(TaskOrigin origin, Set<UUID> interruptions) {
        return origin != null
                && origin.kind() == TaskOrigin.Kind.MISSION
                && origin.missionId() != null
                && interruptions != null
                && interruptions.contains(origin.missionId());
    }

    public void recordFailure(AIPlayerEntity bot, String name, String reason, int tick) {
        UUID uuid = bot.getUUID();
        FailureRecord previous = lastFailure.get(uuid);
        int count = previous != null && previous.name().equals(name) && previous.reason().equals(reason)
                ? previous.count() + 1
                : 1;
        FailureRecord record = new FailureRecord(name, reason, count, tick);
        lastFailure.put(uuid, record);
        pendingFailure.put(uuid, record);
    }

    public Optional<FailureRecord> peekFailure(AIPlayerEntity bot) {
        return Optional.ofNullable(pendingFailure.get(bot.getUUID()));
    }

    public Optional<FailureRecord> consumeFailure(AIPlayerEntity bot) {
        return Optional.ofNullable(pendingFailure.remove(bot.getUUID()));
    }

    public void onServerStopping(MinecraftServer server) {
        for (AIPlayerEntity bot : AIPlayerManager.INSTANCE.all()) {
            cancelIntentTasks(bot, "server_unload");
        }
        active.clear();
        activeOrigins.clear();
        executionStacks.clear();
        pauseLocks.clear();
        lastStatus.clear();
        lastFailure.clear();
        pendingFailure.clear();
        navigationSafetyLeases.clear();
        missionInterruptions.clear();
        runtimeRecoveryLocks.clear();
        runtimeRecoveryMode = false;
        BotLog.task(null, "tasks_cleared");
    }

    public void onBotDespawn(AIPlayerEntity bot) {
        cancelIntentTasks(bot, "bot_unload");
        executionStacks.remove(bot.getUUID());
        activeOrigins.remove(bot.getUUID());
        pauseLocks.remove(bot.getUUID());
        lastStatus.remove(bot.getUUID());
        lastFailure.remove(bot.getUUID());
        pendingFailure.remove(bot.getUUID());
        navigationSafetyLeases.remove(bot.getUUID());
        missionInterruptions.remove(bot.getUUID());
        runtimeRecoveryLocks.remove(bot.getUUID());
        BotReporter.INSTANCE.onCleared(bot);
    }

    public void clearAllRuntime() {
        active.clear();
        activeOrigins.clear();
        executionStacks.clear();
        pauseLocks.clear();
        lastStatus.clear();
        lastFailure.clear();
        pendingFailure.clear();
        navigationSafetyLeases.clear();
        missionInterruptions.clear();
        runtimeRecoveryLocks.clear();
        runtimeRecoveryMode = false;
    }

    public int activeCount() {
        return active.size();
    }

    private static boolean isCritical(Task task) {
        return task instanceof EvadeTask || task instanceof CombatTask || task instanceof EatTask || task instanceof ResupplyTask;
    }

    private static boolean resumesSystemPause(TaskOrigin origin) {
        return origin != null && (origin.kind() == TaskOrigin.Kind.REFLEX
                || origin.kind() == TaskOrigin.Kind.VERIFY
                || origin.kind() == TaskOrigin.Kind.SYSTEM_BACKGROUND);
    }

    private static void restoreRunningAfterFailedPause(AIPlayerEntity bot,
                                                       Task task,
                                                       RuntimeException failure) {
        if (task.state() != TaskState.PAUSED) {
            return;
        }
        try {
            task.resume(bot);
        } catch (RuntimeException rollbackFailure) {
            failure.addSuppressed(rollbackFailure);
            if (task instanceof AbstractTask abstractTask
                    && task.state() == TaskState.PAUSED) {
                abstractTask.state = TaskState.RUNNING;
            }
        }
    }

    private static void restorePausedAfterFailedResume(AIPlayerEntity bot,
                                                       Task task,
                                                       RuntimeException failure) {
        if (task.state() != TaskState.RUNNING) {
            return;
        }
        try {
            task.pause(bot);
        } catch (RuntimeException rollbackFailure) {
            failure.addSuppressed(rollbackFailure);
            if (task instanceof AbstractTask abstractTask
                    && task.state() == TaskState.RUNNING) {
                abstractTask.state = TaskState.PAUSED;
            }
            try {
                bot.getActionPack().stopAll();
            } catch (RuntimeException cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
        }
    }

    private static void terminalizeTickFailure(AIPlayerEntity bot,
                                               Task task,
                                               RuntimeException failure) {
        if (task.state() == TaskState.COMPLETED
                || task.state() == TaskState.FAILED
                || task.state() == TaskState.CANCELLED) {
            return;
        }
        String message = failure.getMessage() == null || failure.getMessage().isBlank()
                ? failure.getClass().getSimpleName()
                : failure.getMessage();
        String reason = "tick_failed:" + message;
        if (task instanceof AbstractTask abstractTask) {
            abstractTask.fail(reason);
            try {
                bot.getActionPack().stopAll();
            } catch (RuntimeException cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            return;
        }
        try {
            task.abort(bot);
        } catch (RuntimeException cleanupFailure) {
            failure.addSuppressed(cleanupFailure);
        }
    }

    private boolean resumeNestedSafetyPause(AIPlayerEntity bot) {
        return resumeTop(bot, frame -> frame.pauseOwner() == PauseOwner.SAFETY
                && frame.origin().safety());
    }

    private boolean acquirePauseLock(UUID uuid, PauseOwner owner) {
        if (!owner.persistentLock()) {
            return false;
        }
        return pauseLocks.computeIfAbsent(uuid, ignored -> ConcurrentHashMap.newKeySet()).add(owner);
    }

    private boolean releasePauseLock(UUID uuid, PauseOwner owner) {
        Set<PauseOwner> owners = pauseLocks.get(uuid);
        if (owners == null || !owners.remove(owner)) {
            return false;
        }
        if (owners.isEmpty()) {
            pauseLocks.remove(uuid, owners);
        }
        return true;
    }

    public record FailureRecord(String name, String reason, int count, int tick) {
    }
}
