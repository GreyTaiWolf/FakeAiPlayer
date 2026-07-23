package io.github.greytaiwolf.fakeaiplayer.mission;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Complete, versioned state of one deterministic {@link PlanCursor}.
 *
 * <p>The checkpoint is intentionally a pure value object. Persistence codecs may wrap it, but a
 * cursor will restore it only when mission id, plan revision, and the full plan fingerprint match.
 * Node paths are stable only within that bound plan.</p>
 */
public record CursorCheckpoint(
        int schemaVersion,
        UUID missionId,
        int planRevision,
        String planFingerprint,
        long tick,
        Map<String, NodeState> nodeStates,
        Map<String, Integer> activationCounts,
        Set<String> reachedCheckpoints,
        Set<String> waitingEvents
) {
    public static final int CURRENT_VERSION = 2;

    private static final String NODE_PATH = "root(?:/(?:child|[0-9]+))*";
    private static final String TOKEN = "[a-z0-9_.:-]+";

    public CursorCheckpoint {
        if (schemaVersion < 1) {
            throw new IllegalArgumentException("cursor_checkpoint_version_invalid");
        }
        if (missionId == null) {
            throw new IllegalArgumentException("cursor_checkpoint_mission_id_missing");
        }
        if (planRevision < 0) {
            throw new IllegalArgumentException("cursor_checkpoint_plan_revision_negative");
        }
        if (planFingerprint == null || !planFingerprint.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("cursor_checkpoint_plan_fingerprint_invalid");
        }
        if (tick < 0) {
            throw new IllegalArgumentException("cursor_checkpoint_tick_negative");
        }
        if (nodeStates == null || nodeStates.isEmpty()
                || nodeStates.size() > MissionPlan.MAX_PLAN_NODES) {
            throw new IllegalArgumentException("cursor_checkpoint_node_states_invalid");
        }
        TreeMap<String, NodeState> sortedNodes = new TreeMap<>();
        for (Map.Entry<String, NodeState> entry : nodeStates.entrySet()) {
            if (entry.getKey() == null || !entry.getKey().matches(NODE_PATH)
                    || entry.getValue() == null) {
                throw new IllegalArgumentException("cursor_checkpoint_node_state_invalid");
            }
            sortedNodes.put(entry.getKey(), entry.getValue());
        }
        nodeStates = Collections.unmodifiableMap(new LinkedHashMap<>(sortedNodes));

        if (activationCounts == null || activationCounts.size() > MissionPlan.MAX_PLAN_NODES) {
            throw new IllegalArgumentException("cursor_checkpoint_activation_counts_invalid");
        }
        TreeMap<String, Integer> sortedCounts = new TreeMap<>();
        for (Map.Entry<String, Integer> entry : activationCounts.entrySet()) {
            if (entry.getKey() == null || !entry.getKey().matches("[a-z0-9_.-]+")
                    || entry.getValue() == null || entry.getValue() < 1) {
                throw new IllegalArgumentException("cursor_checkpoint_activation_count_invalid");
            }
            sortedCounts.put(entry.getKey(), entry.getValue());
        }
        activationCounts = Collections.unmodifiableMap(new LinkedHashMap<>(sortedCounts));
        reachedCheckpoints = immutableTokens(
                reachedCheckpoints, "cursor_checkpoint_reached_checkpoints_invalid");
        waitingEvents = immutableTokens(
                waitingEvents, "cursor_checkpoint_waiting_events_invalid");
    }

    private static Set<String> immutableTokens(Set<String> source, String reason) {
        if (source == null || source.size() > MissionPlan.MAX_PLAN_NODES) {
            throw new IllegalArgumentException(reason);
        }
        for (String value : source) {
            if (value == null || !value.matches(TOKEN)) {
                throw new IllegalArgumentException(reason);
            }
        }
        java.util.List<String> sorted = source.stream().sorted().toList();
        return Collections.unmodifiableSet(new LinkedHashSet<>(sorted));
    }

    public enum NodePhase {
        PENDING,
        ACTIVE,
        SUCCEEDED,
        FAILED
    }

    /**
     * Generic state for a stable node path. Composite-specific fields are zero or {@code -1} when
     * not applicable. Timeout elapsed ticks are stored alongside the start to detect corruption.
     * {@code checkpointReached} is a durable marker owned only by a Checkpoint node; unlike its
     * execution phase it survives an enclosing Retry reset.
     */
    public record NodeState(
            NodePhase phase,
            int childIndex,
            int retryAttempt,
            long timeoutStartedTick,
            long timeoutElapsedTicks,
            boolean checkpointReached,
            String failurePath,
            SkillOutcome failureOutcome
    ) {
        public NodeState {
            if (phase == null || childIndex < 0 || retryAttempt < 0
                    || timeoutStartedTick < -1 || timeoutElapsedTicks < -1) {
                throw new IllegalArgumentException("cursor_checkpoint_node_state_invalid");
            }
            if ((timeoutStartedTick == -1) != (timeoutElapsedTicks == -1)) {
                throw new IllegalArgumentException("cursor_checkpoint_timeout_state_incomplete");
            }
            boolean hasFailurePath = failurePath != null && !failurePath.isBlank();
            if (hasFailurePath && !failurePath.matches(NODE_PATH)) {
                throw new IllegalArgumentException("cursor_checkpoint_failure_path_invalid");
            }
            if (hasFailurePath != (failureOutcome != null)) {
                throw new IllegalArgumentException("cursor_checkpoint_failure_state_incomplete");
            }
            if ((phase == NodePhase.FAILED) != hasFailurePath) {
                throw new IllegalArgumentException("cursor_checkpoint_failure_phase_mismatch");
            }
        }
    }
}
