package io.github.greytaiwolf.fakeaiplayer.coordination;

import io.github.greytaiwolf.fakeaiplayer.brain.BrainCoordinator;
import io.github.greytaiwolf.fakeaiplayer.entity.AIPlayerEntity;
import io.github.greytaiwolf.fakeaiplayer.manager.AIPlayerManager;
import io.github.greytaiwolf.fakeaiplayer.task.CraftTask;
import io.github.greytaiwolf.fakeaiplayer.task.EatTask;
import io.github.greytaiwolf.fakeaiplayer.task.LightAreaTask;
import io.github.greytaiwolf.fakeaiplayer.task.MineTask;
import io.github.greytaiwolf.fakeaiplayer.task.MoveTask;
import io.github.greytaiwolf.fakeaiplayer.task.SmeltTask;
import io.github.greytaiwolf.fakeaiplayer.task.Task;
import io.github.greytaiwolf.fakeaiplayer.task.TaskManager;
import io.github.greytaiwolf.fakeaiplayer.task.TaskState;
import io.github.greytaiwolf.fakeaiplayer.task.TaskStatus;
import io.github.greytaiwolf.fakeaiplayer.runtime.TaskOrigin;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class IdleCoordinator {
    public static final IdleCoordinator INSTANCE = new IdleCoordinator();

    private final Map<UUID, UUID> claimedJobs = new ConcurrentHashMap<>();

    private IdleCoordinator() {
    }

    public void tick(MinecraftServer server) {
        for (AIPlayerEntity bot : AIPlayerManager.INSTANCE.all()) {
            tickBot(bot);
        }
    }

    public boolean tickBot(AIPlayerEntity bot) {
        return tickBot(bot, true);
    }

    /** Runs ambient state every tick while expensive job sampling remains background-throttled. */
    public boolean tickBot(AIPlayerEntity bot, boolean allowJobClaim) {
        if (TaskManager.INSTANCE.hasPersistentPause(bot)
                || TaskManager.INSTANCE.getActive(bot).isPresent()
                || TaskManager.INSTANCE.hasPaused(bot)) {
            IdleBehaviorController.INSTANCE.cancel(bot, "task_or_pause");
            return false;
        }
        // GOALFIX-GF1 P0-A:bot 有活跃目标计划时,空闲分配让位给 GoalExecutor(防步骤间隙抢任务板作业)。
        if (io.github.greytaiwolf.fakeaiplayer.goal.GoalExecutor.INSTANCE.hasActivePlan(bot)) {
            IdleBehaviorController.INSTANCE.cancel(bot, "goal_active");
            return false;
        }
        // Low-level action-only work (for example move_to) has no Task object but still owns the
        // bot until ActionPack settles. Do not wake Brain, resume paused work, or claim a Job over it.
        if (bot.getActionPack().isSuspended()) {
            IdleBehaviorController.INSTANCE.cancel(bot, "action_suspended");
            return false;
        }
        if (bot.getActionPack().hasActiveActions()
                && !IdleBehaviorController.INSTANCE.ownsActiveAction(bot)) {
            IdleBehaviorController.INSTANCE.cancel(bot, "foreign_action");
            return false;
        }
        UUID currentJob = claimedJobs.remove(bot.getUUID());
        if (currentJob != null) {
            finishClaimedJob(bot, currentJob);
        }
        if (BrainCoordinator.INSTANCE.status(bot).busy()) {
            IdleBehaviorController.INSTANCE.cancel(bot, "brain_busy");
            return false;
        }
        Optional<Job> job = allowJobClaim
                ? TaskBoard.INSTANCE.claimNext(bot, AIPlayerManager.INSTANCE.roles(bot))
                : Optional.empty();
        job.ifPresent(next -> assignJob(bot, next));
        if (job.isPresent()) {
            IdleBehaviorController.INSTANCE.cancel(bot, "job_claimed");
            markDirty(bot);
            return true;
        }
        return IdleBehaviorController.INSTANCE.tick(bot);
    }

    public void onBotRemoved(AIPlayerEntity bot) {
        IdleBehaviorController.INSTANCE.clear(bot);
        UUID jobId = claimedJobs.remove(bot.getUUID());
        if (jobId != null) {
            TaskBoard.INSTANCE.markFailed(jobId, "bot_removed");
            markDirty(bot);
        }
    }

    /** Server unload keeps the persisted lease; the next runtime session will reopen it as stale. */
    public void onBotUnloaded(AIPlayerEntity bot) {
        IdleBehaviorController.INSTANCE.clear(bot);
        claimedJobs.remove(bot.getUUID());
    }

    public void clearAllRuntime() {
        claimedJobs.clear();
        IdleBehaviorController.INSTANCE.clearAll();
    }

    public boolean cancelAmbient(AIPlayerEntity bot, String reason) {
        return IdleBehaviorController.INSTANCE.cancel(bot, reason);
    }

    public boolean ownsAmbientAction(AIPlayerEntity bot) {
        return IdleBehaviorController.INSTANCE.ownsActiveAction(bot);
    }

    public boolean cancelClaimedJob(AIPlayerEntity bot, String reason) {
        UUID jobId = claimedJobs.remove(bot.getUUID());
        if (jobId == null) {
            return false;
        }
        TaskBoard.INSTANCE.markFailed(jobId, "cancelled: " + reason);
        markDirty(bot);
        return true;
    }

    private void finishClaimedJob(AIPlayerEntity bot, UUID jobId) {
        TaskStatus status = TaskManager.INSTANCE.status(bot);
        if (status.state() == TaskState.FAILED || status.state() == TaskState.CANCELLED) {
            TaskBoard.INSTANCE.markFailed(jobId, status.failureReason().isBlank() ? "task_failed" : status.failureReason());
        } else {
            TaskBoard.INSTANCE.markDone(jobId);
        }
        markDirty(bot);
    }

    private void assignJob(AIPlayerEntity bot, Job job) {
        Optional<Task> task = jobToTask(bot, job);
        if (task.isEmpty()) {
            TaskBoard.INSTANCE.markFailed(job.id(), "unknown_or_bad_job: " + job.kind());
            markDirty(bot);
            return;
        }
        claimedJobs.put(bot.getUUID(), job.id());
        try {
            io.github.greytaiwolf.fakeaiplayer.task.TaskAssignmentResult assignment =
                    TaskManager.INSTANCE.assign(
                            bot, task.get(), TaskOrigin.job(job.id(), "task_board"));
            if (!assignment.started()) {
                claimedJobs.remove(bot.getUUID(), job.id());
                TaskBoard.INSTANCE.markFailed(job.id(), "assignment_deferred:" + assignment.reason());
                markDirty(bot);
            }
        } catch (RuntimeException exception) {
            claimedJobs.remove(bot.getUUID(), job.id());
            TaskBoard.INSTANCE.markFailed(job.id(), "assignment_start_failed");
            markDirty(bot);
            io.github.greytaiwolf.fakeaiplayer.log.BotLog.error(
                    bot, "job_assignment_start_failed", exception, "job_id", job.id());
        }
    }

    private static void markDirty(AIPlayerEntity bot) {
        io.github.greytaiwolf.fakeaiplayer.persist.BotPersistence.INSTANCE.markDirty(bot.getServer());
    }

    public static Optional<Task> jobToTask(AIPlayerEntity bot, Job job) {
        try {
            Map<String, String> params = job.params();
            return switch (job.kind()) {
                case "move" -> Optional.of(new MoveTask(bot, blockPos(params, "x", "y", "z")));
                case "mine" -> Optional.of(new MineTask(requiredBlock(params, "block"), intParam(params, "count", 1)));
                case "craft" -> Optional.of(new CraftTask(requiredItem(params, "item"), intParam(params, "count", 1)));
                case "smelt" -> Optional.of(new SmeltTask(requiredItem(params, "input_item"), requiredItem(params, "output_item"), intParam(params, "count", 1)));
                case "eat" -> Optional.of(new EatTask());
                case "light_area" -> Optional.of(new LightAreaTask(intParam(params, "radius", 8), intParam(params, "max_torches", 8)));
                // Persisted legacy AI jobs must not survive the new human-confirmation boundary.
                // Human command-driven construction remains available through the command graph.
                case "build" -> Optional.empty();
                default -> Optional.empty();
            };
        } catch (RuntimeException exception) {
            return Optional.empty();
        }
    }

    private static Block requiredBlock(Map<String, String> params, String name) {
        ResourceLocation id = ResourceLocation.parse(required(params, name));
        return BuiltInRegistries.BLOCK.getOptional(id)
                .orElseThrow(() -> new IllegalArgumentException("unknown_block: " + id));
    }

    private static Item requiredItem(Map<String, String> params, String name) {
        ResourceLocation id = ResourceLocation.parse(required(params, name));
        return BuiltInRegistries.ITEM.getOptional(id)
                .orElseThrow(() -> new IllegalArgumentException("unknown_item: " + id));
    }

    private static BlockPos blockPos(Map<String, String> params, String x, String y, String z) {
        return new BlockPos(
                Integer.parseInt(required(params, x)),
                Integer.parseInt(required(params, y)),
                Integer.parseInt(required(params, z)));
    }

    private static int intParam(Map<String, String> params, String name, int fallback) {
        String value = params.get(name);
        return value == null || value.isBlank() ? fallback : Math.max(1, Integer.parseInt(value));
    }

    private static String required(Map<String, String> params, String name) {
        String value = params.get(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("missing_param: " + name);
        }
        return value;
    }
}
