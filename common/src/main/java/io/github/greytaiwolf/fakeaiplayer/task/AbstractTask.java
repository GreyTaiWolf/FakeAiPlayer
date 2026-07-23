package io.github.greytaiwolf.fakeaiplayer.task;

import io.github.greytaiwolf.fakeaiplayer.entity.AIPlayerEntity;

public abstract class AbstractTask implements Task {
    protected TaskState state = TaskState.PENDING;
    protected String failureReason = "";
    protected int elapsed;
    private int startedTick;

    @Override
    public final void start(AIPlayerEntity bot) {
        if (state != TaskState.PENDING) {
            return;
        }
        state = TaskState.RUNNING;
        startedTick = bot.getServer().getTickCount();
        onStart(bot);
    }

    @Override
    public final void tick(AIPlayerEntity bot) {
        if (state != TaskState.RUNNING) {
            return;
        }
        elapsed++;
        onTick(bot);
    }

    @Override
    public final void pause(AIPlayerEntity bot) {
        if (state != TaskState.RUNNING) {
            return;
        }
        state = TaskState.PAUSED;
        onPause(bot);
    }

    @Override
    public final void resume(AIPlayerEntity bot) {
        if (state != TaskState.PAUSED) {
            return;
        }
        state = TaskState.RUNNING;
        onResume(bot);
    }

    @Override
    public final void abort(AIPlayerEntity bot) {
        if (state == TaskState.COMPLETED || state == TaskState.FAILED || state == TaskState.CANCELLED) {
            return;
        }
        state = TaskState.FAILED;
        failureReason = "aborted";
        onAbort(bot);
    }

    @Override
    public final void cancel(AIPlayerEntity bot, String reason) {
        if (state == TaskState.COMPLETED || state == TaskState.FAILED || state == TaskState.CANCELLED) {
            return;
        }
        state = TaskState.CANCELLED;
        failureReason = reason == null ? "" : reason;
        onAbort(bot);
    }

    @Override
    public final void cancelDetached(AIPlayerEntity bot, String reason) {
        if (state == TaskState.COMPLETED || state == TaskState.FAILED || state == TaskState.CANCELLED) {
            return;
        }
        state = TaskState.CANCELLED;
        failureReason = reason == null ? "" : reason;
        onDetachedCancel(bot);
    }

    @Override
    public TaskState state() {
        return state;
    }

    @Override
    public String failureReason() {
        return failureReason;
    }

    @Override
    public int elapsedTicks() {
        return elapsed;
    }

    public int startedTick() {
        return startedTick;
    }

    protected void complete() {
        state = TaskState.COMPLETED;
    }

    protected void fail(String reason) {
        state = TaskState.FAILED;
        failureReason = reason;
    }

    protected abstract void onStart(AIPlayerEntity bot);

    protected abstract void onTick(AIPlayerEntity bot);

    protected void onPause(AIPlayerEntity bot) {
        // Inventory owns a non-destructive ActionPack suspension before pausing the task. Keep the
        // executor objects in that case so closing the menu continues the same task step.
        if (!bot.getActionPack().isSuspended()) {
            bot.getActionPack().stopAll();
        }
    }

    protected void onResume(AIPlayerEntity bot) {
    }

    protected void onAbort(AIPlayerEntity bot) {
        bot.getActionPack().stopAll();
    }

    /**
     * Releases Task-private resources after this Task has lost shared-control ownership.
     * Implementations must not mutate the bot's ActionPack or another shared executor here.
     */
    protected void onDetachedCancel(AIPlayerEntity bot) {
    }
}
