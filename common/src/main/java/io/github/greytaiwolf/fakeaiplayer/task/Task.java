package io.github.greytaiwolf.fakeaiplayer.task;

import io.github.greytaiwolf.fakeaiplayer.entity.AIPlayerEntity;

public interface Task {
    String name();

    String describe();

    TaskState state();

    String failureReason();

    void start(AIPlayerEntity bot);

    void tick(AIPlayerEntity bot);

    void pause(AIPlayerEntity bot);

    void resume(AIPlayerEntity bot);

    void abort(AIPlayerEntity bot);

    void cancel(AIPlayerEntity bot, String reason);

    /**
     * Retires a Task that has already lost ownership of the bot's shared controls.
     *
     * <p>This callback may release only Task-private state. It must not stop or replace movement,
     * mining, navigation, inventory, or any other bot-global action because a safety/reflex Task
     * can own those controls while the detached frame is being cancelled.</p>
     */
    void cancelDetached(AIPlayerEntity bot, String reason);

    double progress();

    int elapsedTicks();

    default boolean isWaiting() {
        return false;
    }
}
