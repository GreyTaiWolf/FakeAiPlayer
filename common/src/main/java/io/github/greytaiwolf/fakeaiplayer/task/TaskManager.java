package io.github.greytaiwolf.fakeaiplayer.task;

import io.github.greytaiwolf.fakeaiplayer.brain.BotReporter;
import io.github.greytaiwolf.fakeaiplayer.entity.AIPlayerEntity;
import io.github.greytaiwolf.fakeaiplayer.log.BotLog;
import io.github.greytaiwolf.fakeaiplayer.manager.AIPlayerManager;
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

public final class TaskManager {
    public static final TaskManager INSTANCE = new TaskManager();

    private final Map<UUID, Task> active = new ConcurrentHashMap<>();
    private final Map<UUID, TaskOrigin> activeOrigins = new ConcurrentHashMap<>();
    private final Map<UUID, ExecutionStack<Task>> executionStacks = new ConcurrentHashMap<>();
    private final Map<UUID, Set<PauseOwner>> pauseLocks = new ConcurrentHashMap<>();
    private final Map<UUID, TaskStatus> lastStatus = new ConcurrentHashMap<>();
    private final Map<UUID, FailureRecord> lastFailure = new ConcurrentHashMap<>();
    private final Map<UUID, FailureRecord> pendingFailure = new ConcurrentHashMap<>();

    private TaskManager() {
    }

    public void assign(AIPlayerEntity bot, Task task, TaskOrigin origin) {
        if (hasPersistentPause(bot) && !origin.safety()) {
            throw new IllegalStateException("mission_paused:" + pauseOwners(bot));
        }
        io.github.greytaiwolf.fakeaiplayer.coordination.IdleCoordinator.INSTANCE
                .cancelAmbient(bot, "task_assigned");
        abort(bot);
        bot.getActionPack().stopAll();
        UUID uuid = bot.getUUID();
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
            TaskStatus failed = TaskStatus.from(task);
            lastStatus.put(uuid, failed);
            if (failed.state() == TaskState.FAILED) {
                recordFailure(bot, task.name(), failed.failureReason(), bot.getServer().getTickCount());
            }
            BotReporter.INSTANCE.onStatus(bot.getServer(), bot, failed);
            BotLog.error(bot, "task_start_failed", startFailure, "name", task.name());
            throw startFailure;
        }
        TaskStatus status = TaskStatus.from(task);
        lastStatus.put(uuid, status);
        BotReporter.INSTANCE.onAssigned(bot, status);
        BotLog.task(bot, "task_assigned", "name", task.name(), "params", task.describe(),
                "origin", origin.kind(), "origin_reason", origin.reason());
    }

    public void abort(AIPlayerEntity bot) {
        Task current = active.remove(bot.getUUID());
        activeOrigins.remove(bot.getUUID());
        if (current != null) {
            current.abort(bot);
            lastStatus.put(bot.getUUID(), TaskStatus.from(current));
            BotReporter.INSTANCE.onStatus(bot.getServer(), bot, TaskStatus.from(current));
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
        UUID uuid = bot.getUUID();
        boolean changed = acquirePauseLock(uuid, pauseOwner);
        Task current = active.remove(uuid);
        TaskOrigin origin = activeOrigins.remove(uuid);
        if (current == null) {
            return changed;
        }
        current.pause(bot);
        TaskOrigin preservedOrigin = origin == null
                ? TaskOrigin.of(TaskOrigin.Kind.SYSTEM_BACKGROUND, "unknown_origin") : origin;
        ExecutionStack<Task> stack = executionStacks.computeIfAbsent(uuid, ignored -> new ExecutionStack<>());
        stack.push(current, preservedOrigin, pauseOwner);
        TaskStatus status = TaskStatus.from(current);
        lastStatus.put(bot.getUUID(), status);
        BotReporter.INSTANCE.onStatus(bot.getServer(), bot, status);
        BotLog.task(bot, "task_paused", "name", current.name(), "why", why,
                "origin", preservedOrigin.kind(), "pause_owner", pauseOwner,
                "stack_depth", stack.size());
        return true;
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
        return resumeTop(bot, frame -> PauseOwner.resumeAllowedAfterPersistentRelease(
                frame.pauseOwner(), remainingOwners)) || changed;
    }

    private boolean resumeTop(AIPlayerEntity bot,
                              java.util.function.Predicate<ExecutionStack.Frame<Task>> allowed) {
        UUID uuid = bot.getUUID();
        if (active.containsKey(uuid)) {
            return false;
        }
        if (hasPersistentPause(bot)) {
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
        ExecutionStack.Frame<Task> frame = stack.pop().orElseThrow();
        Task task = frame.work();
        active.put(uuid, task);
        activeOrigins.put(uuid, frame.origin());
        task.resume(bot);
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
        TaskOrigin origin = activeOrigins.get(uuid);
        boolean safetyActive = origin != null && origin.safety();
        boolean changed = safetyActive
                ? acquirePauseLock(uuid, PauseOwner.USER)
                : pauseFor(bot, PauseOwner.USER, "user_pause:" + why);
        if (!safetyActive) {
            bot.getActionPack().stopAll();
        }
        BotLog.task(bot, "mission_user_paused", "why", why, "stack_depth", pausedDepth(bot));
        return changed;
    }

    public boolean resumeUserIntent(AIPlayerEntity bot, String why) {
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
                continue;
            }
            AIPlayerEntity player = bot.get();
            TaskOrigin origin = activeOrigins.get(uuid);
            if ((origin == null || !origin.safety()) && !isCritical(task)
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
                task.tick(player);
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
                if (origin != null && origin.kind() == TaskOrigin.Kind.SYSTEM_BACKGROUND) {
                    resumeSystemPause(player);
                }
            } else if (task.state() == TaskState.FAILED) {
                active.remove(uuid);
                activeOrigins.remove(uuid);
                recordFailure(player, task.name(), task.failureReason(), server.getTickCount());
                BotLog.warn(io.github.greytaiwolf.fakeaiplayer.log.LogCategory.TASK, player, "task_failed",
                        "name", task.name(), "reason", task.failureReason(), "elapsed_ticks", task.elapsedTicks());
                if (origin != null && origin.kind() == TaskOrigin.Kind.SYSTEM_BACKGROUND) {
                    resumeSystemPause(player);
                }
            } else if (task.state() == TaskState.CANCELLED) {
                active.remove(uuid);
                activeOrigins.remove(uuid);
                lastFailure.remove(uuid);
                pendingFailure.remove(uuid);
                BotLog.task(player, "task_cancelled", "name", task.name(), "reason", task.failureReason());
                if (origin != null && origin.kind() == TaskOrigin.Kind.SYSTEM_BACKGROUND) {
                    resumeSystemPause(player);
                }
            }
        }
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
    }

    public int activeCount() {
        return active.size();
    }

    private static boolean isCritical(Task task) {
        return task instanceof EvadeTask || task instanceof CombatTask || task instanceof EatTask || task instanceof ResupplyTask;
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
