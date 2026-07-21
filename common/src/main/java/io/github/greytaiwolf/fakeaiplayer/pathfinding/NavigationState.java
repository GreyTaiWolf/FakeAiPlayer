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
    PREEMPTED,
    CANCELLED,
    FAILED;

    public boolean terminal() {
        return this == ARRIVED || this == PREEMPTED || this == CANCELLED || this == FAILED;
    }
}
