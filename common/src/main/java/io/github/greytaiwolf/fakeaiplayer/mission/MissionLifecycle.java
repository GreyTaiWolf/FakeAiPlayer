package io.github.greytaiwolf.fakeaiplayer.mission;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/** Fail-closed Mission lifecycle transition table. */
public final class MissionLifecycle {
    private static final Map<MissionState, Set<MissionState>> ALLOWED = allowedTransitions();

    private MissionLifecycle() {
    }

    public static boolean canTransition(MissionState from, MissionState to) {
        return from != null && to != null && (from == to || ALLOWED.getOrDefault(from, Set.of()).contains(to));
    }

    public static MissionState transition(MissionState from, MissionState to) {
        if (!canTransition(from, to)) {
            throw new IllegalStateException("invalid_mission_transition:" + from + "->" + to);
        }
        return to;
    }

    private static Map<MissionState, Set<MissionState>> allowedTransitions() {
        EnumMap<MissionState, Set<MissionState>> result = new EnumMap<>(MissionState.class);
        result.put(MissionState.PROPOSED, EnumSet.of(
                MissionState.VALIDATED, MissionState.FAILED, MissionState.CANCELLED));
        result.put(MissionState.VALIDATED, EnumSet.of(
                MissionState.PLANNED, MissionState.BLOCKED, MissionState.FAILED, MissionState.CANCELLED));
        result.put(MissionState.PLANNED, EnumSet.of(
                MissionState.RUNNING, MissionState.SUSPENDED, MissionState.RECOVERING,
                MissionState.VERIFYING,
                MissionState.BLOCKED, MissionState.FAILED, MissionState.CANCELLED));
        result.put(MissionState.RUNNING, EnumSet.of(
                MissionState.SUSPENDED, MissionState.RECOVERING, MissionState.VERIFYING,
                MissionState.BLOCKED, MissionState.FAILED, MissionState.CANCELLED));
        result.put(MissionState.SUSPENDED, EnumSet.of(
                MissionState.RUNNING, MissionState.RECOVERING, MissionState.VERIFYING,
                MissionState.BLOCKED, MissionState.FAILED, MissionState.CANCELLED));
        result.put(MissionState.RECOVERING, EnumSet.of(
                MissionState.RUNNING, MissionState.SUSPENDED, MissionState.VERIFYING,
                MissionState.BLOCKED, MissionState.FAILED, MissionState.CANCELLED));
        result.put(MissionState.VERIFYING, EnumSet.of(
                MissionState.SUCCEEDED, MissionState.RECOVERING,
                MissionState.BLOCKED, MissionState.FAILED, MissionState.CANCELLED));
        return Map.copyOf(result);
    }
}
