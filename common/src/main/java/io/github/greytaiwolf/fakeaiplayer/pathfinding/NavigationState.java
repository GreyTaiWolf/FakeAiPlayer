package io.github.greytaiwolf.fakeaiplayer.pathfinding;

/**
 * Server-authoritative lifecycle of the most recent A* navigation request.
 *
 * <p>Tasks must not infer success from an idle executor. An idle executor can be the result of an
 * arrival, a safety preemption, an explicit cancellation, or a real path failure.</p>
 */
public enum NavigationState {
    IDLE,
    PLANNING,
    FOLLOWING,
    ARRIVED,
    BLOCKED,
    STALE_WORLD,
    PREEMPTED,
    CANCELLED,
    FAILED;

    public boolean terminal() {
        return this == ARRIVED || this == BLOCKED || this == STALE_WORLD
                || this == PREEMPTED || this == CANCELLED || this == FAILED;
    }
}
