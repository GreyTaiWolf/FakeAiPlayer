package io.github.greytaiwolf.fakeaiplayer.pathfinding;

public enum FailureReason {
    NONE,
    NO_START,
    GOAL_UNREACHABLE,
    SEARCH_LIMIT,
    SEARCH_BUDGET,
    TIMEOUT,
    GOAL_NOT_STANDABLE,
    PATH_BLOCKED,
    STALE_WORLD
}
