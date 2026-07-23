package io.github.greytaiwolf.fakeaiplayer.mission;

public enum MissionState {
    PROPOSED,
    VALIDATED,
    PLANNED,
    RUNNING,
    SUSPENDED,
    RECOVERING,
    VERIFYING,
    SUCCEEDED,
    BLOCKED,
    FAILED,
    CANCELLED;

    public boolean terminal() {
        return this == SUCCEEDED || this == BLOCKED || this == FAILED || this == CANCELLED;
    }
}
