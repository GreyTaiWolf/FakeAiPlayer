package io.github.greytaiwolf.fakeaiplayer.mission;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Deterministic, in-memory reference executor for a {@link MissionPlan} tree.
 *
 * <p>The cursor owns plan structure only: callers start the single ready Skill in their runtime,
 * then report its typed outcome through
 * {@link #completeSkill(ActivationLease, SkillOutcome, long)}. It
 * deliberately does not call Tasks, inspect the world, persist data, or decide policy. AllOf is
 * executed in declared order for now, which leaves parallel scheduling as an additive runtime
 * optimization without changing its success semantics. Cancellation, fatal adapter errors, and
 * safety outcomes always bubble to the caller; the cursor never converts them into an automatic
 * retry or an AnyOf fallback.</p>
 */
public final class PlanCursor {
    private static final String ROOT_PATH = "root";

    private final MissionPlan plan;
    private final String planFingerprint;
    private final UUID runtimeEpoch;
    private final RuntimeNode root;
    private final Set<String> knownInvocationIds;
    private final LinkedHashSet<String> reachedCheckpoints;
    private final Map<String, Integer> activationCounts = new LinkedHashMap<>();
    private long tick;

    public PlanCursor(MissionPlan plan, long startTick) {
        this.plan = Objects.requireNonNull(plan, "plan");
        this.planFingerprint = plan.fingerprint();
        this.runtimeEpoch = UUID.randomUUID();
        requireTick(startTick);
        this.reachedCheckpoints = new LinkedHashSet<>();
        this.knownInvocationIds = collectInvocationIds(plan.root());
        this.tick = startTick;
        root = build(plan.root(), ROOT_PATH);
        settle();
    }

    /** Restores the exact state captured by {@link #checkpoint()}. */
    public PlanCursor(MissionPlan plan, CursorCheckpoint checkpoint) {
        this.plan = Objects.requireNonNull(plan, "plan");
        this.planFingerprint = plan.fingerprint();
        this.runtimeEpoch = UUID.randomUUID();
        Objects.requireNonNull(checkpoint, "checkpoint");
        validateCheckpointBinding(plan, checkpoint);
        this.tick = checkpoint.tick();
        this.reachedCheckpoints = new LinkedHashSet<>(checkpoint.reachedCheckpoints());
        this.knownInvocationIds = collectInvocationIds(plan.root());
        this.activationCounts.putAll(checkpoint.activationCounts());
        this.root = build(plan.root(), ROOT_PATH);
        restoreNodeStates(checkpoint);
        validateRestoredState(checkpoint);
    }

    public MissionPlan plan() {
        return plan;
    }

    /** Captures every state needed to continue without resetting a branch, retry, or timeout. */
    public CursorCheckpoint checkpoint() {
        Map<String, CursorCheckpoint.NodeState> states = new LinkedHashMap<>();
        collectNodeStates(root, states);
        Snapshot snapshot = snapshot();
        return new CursorCheckpoint(
                CursorCheckpoint.CURRENT_VERSION,
                plan.missionId(),
                plan.revision(),
                planFingerprint,
                tick,
                states,
                activationCounts,
                reachedCheckpoints,
                snapshot.waitingEvents());
    }

    /**
     * Rebinds the exact runtime tree to a new revision whose only semantic change is Goal
     * admission (source/priority). The fresh cursor receives a new runtime epoch, so callbacks
     * leased before the admission upgrade cannot complete work under the stronger authority.
     */
    public PlanCursor rebindAdmission(MissionPlan upgradedPlan) {
        Objects.requireNonNull(upgradedPlan, "upgradedPlan");
        if (!plan.missionId().equals(upgradedPlan.missionId())
                || upgradedPlan.revision() != plan.revision() + 1
                || !plan.root().equals(upgradedPlan.root())
                || !plan.plannerVersion().equals(upgradedPlan.plannerVersion())
                || !sameNonAdmissionGoal(plan.goal(), upgradedPlan.goal())) {
            throw new IllegalArgumentException("plan_admission_rebind_invalid");
        }
        CursorCheckpoint previous = checkpoint();
        CursorCheckpoint rebound = new CursorCheckpoint(
                previous.schemaVersion(),
                upgradedPlan.missionId(),
                upgradedPlan.revision(),
                upgradedPlan.fingerprint(),
                previous.tick(),
                previous.nodeStates(),
                previous.activationCounts(),
                previous.reachedCheckpoints(),
                previous.waitingEvents());
        return upgradedPlan.cursor(rebound);
    }

    private static boolean sameNonAdmissionGoal(GoalSpec before, GoalSpec after) {
        return before.type().equals(after.type())
                && before.successPredicate().equals(after.successPredicate())
                && before.dimension().equals(after.dimension())
                && before.policy().equals(after.policy())
                && before.attributes().equals(after.attributes());
    }

    /**
     * Advances the monotonic execution clock used by Timeout nodes. The caller owns this clock and
     * must not advance it while the Mission is suspended if timeout budgets represent active work.
     */
    public Snapshot advanceTo(long tick) {
        advanceClock(tick);
        return snapshot();
    }

    /**
     * Completes the currently ready invocation only when its activation lease still matches.
     * A result arriving on or after a Timeout deadline loses to that timeout, and a callback from
     * an older retry activation is ignored instead of completing the fresh activation.
     */
    public Snapshot completeSkill(ActivationLease lease,
                                  SkillOutcome outcome,
                                  long tick) {
        return tryCompleteSkill(lease, outcome, tick).snapshot();
    }

    /**
     * Reports whether this exact activation consumed the supplied outcome. A plain Snapshot cannot
     * encode that fact: a timeout may switch an AnyOf/Retry branch to a different ready Skill, so
     * merely observing that the old lease is no longer ready would incorrectly treat its late
     * callback as accepted.
     */
    public Completion tryCompleteSkill(ActivationLease lease,
                                       SkillOutcome outcome,
                                       long tick) {
        Objects.requireNonNull(lease, "lease");
        Objects.requireNonNull(outcome, "outcome");
        requireTick(tick);
        if (root.terminal() || !leaseMatchesRuntime(lease)) {
            return new Completion(snapshot(), false);
        }
        String invocationId = lease.invocationId();
        if (!knownInvocationIds.contains(invocationId)) {
            throw new IllegalArgumentException("skill_invocation_unknown:" + invocationId);
        }
        RuntimeNode active = findActiveSkill(root, invocationId);
        int activationAttempt = activationCounts.getOrDefault(invocationId, 0);
        if (active == null || activationAttempt != lease.activationAttempt()) {
            return new Completion(snapshot(), false);
        }
        advanceClock(tick);
        if (root.terminal()) {
            return new Completion(snapshot(), false);
        }
        if (findActiveSkill(root, invocationId) != active
                || activationCounts.getOrDefault(invocationId, 0) != lease.activationAttempt()) {
            return new Completion(snapshot(), false);
        }
        if (outcome.status() == SkillOutcome.Status.SUCCEEDED) {
            active.phase = Phase.SUCCEEDED;
        } else {
            fail(active, active.path, outcome);
        }
        settle();
        return new Completion(snapshot(), true);
    }

    private boolean leaseMatchesRuntime(ActivationLease lease) {
        return plan.missionId().equals(lease.missionId())
                && plan.revision() == lease.planRevision()
                && planFingerprint.equals(lease.planFingerprint())
                && runtimeEpoch.equals(lease.runtimeEpoch());
    }

    /** Signals an edge-triggered event. Events with no matching active waiter are ignored. */
    public Snapshot signalEvent(String eventKey, long tick) {
        requireToken(eventKey, "event_key");
        requireMutable();
        List<WaitActivation> activeWaiters = new ArrayList<>();
        collectActiveWaiters(root, eventKey, new ArrayList<>(), activeWaiters);
        advanceClock(tick);
        if (root.terminal()) {
            return snapshot();
        }
        for (WaitActivation activation : activeWaiters) {
            if (activation.stillActive()) {
                activation.waiter().phase = Phase.SUCCEEDED;
            }
        }
        settle();
        return snapshot();
    }

    public Snapshot snapshot() {
        List<ReadySkill> ready = new ArrayList<>();
        LinkedHashSet<String> waiting = new LinkedHashSet<>();
        collectActiveLeaves(root, ready, waiting);

        State state;
        Optional<Failure> failure = Optional.empty();
        if (root.phase == Phase.SUCCEEDED) {
            state = State.SUCCEEDED;
        } else if (root.phase == Phase.FAILED) {
            state = State.FAILED;
            failure = Optional.of(new Failure(root.failurePath, root.failureOutcome));
        } else if (!ready.isEmpty()) {
            state = State.READY;
        } else if (!waiting.isEmpty()) {
            state = State.WAITING;
        } else {
            state = State.RUNNING;
        }
        return new Snapshot(
                plan.missionId(),
                plan.revision(),
                tick,
                state,
                ready,
                waiting,
                reachedCheckpoints,
                failure);
    }

    private void advanceClock(long nextTick) {
        requireTick(nextTick);
        if (nextTick < tick) {
            throw new IllegalArgumentException("plan_tick_regressed");
        }
        tick = nextTick;
        settle();
    }

    private void requireMutable() {
        if (root.terminal()) {
            throw new IllegalStateException("plan_cursor_terminal");
        }
    }

    private void settle() {
        boolean changed;
        do {
            changed = progress(root);
        } while (changed && !root.terminal());
    }

    private boolean progress(RuntimeNode runtime) {
        if (runtime.terminal()) {
            return false;
        }
        return switch (runtime.node) {
            case PlanNode.Skill ignored -> activateLeaf(runtime);
            case PlanNode.WaitForEvent ignored -> activateLeaf(runtime);
            case PlanNode.Sequence ignored -> progressRequiredChildren(runtime);
            case PlanNode.AllOf ignored -> progressRequiredChildren(runtime);
            case PlanNode.AnyOf ignored -> progressAlternatives(runtime);
            case PlanNode.Retry retry -> progressRetry(runtime, retry);
            case PlanNode.Timeout timeout -> progressTimeout(runtime, timeout);
            case PlanNode.Checkpoint checkpoint -> progressCheckpoint(runtime, checkpoint);
        };
    }

    private boolean activateLeaf(RuntimeNode runtime) {
        if (runtime.phase != Phase.PENDING) {
            return false;
        }
        runtime.phase = Phase.ACTIVE;
        if (runtime.node instanceof PlanNode.Skill skill) {
            String invocationId = skill.spec().invocationId();
            int previous = activationCounts.getOrDefault(invocationId, 0);
            if (previous == Integer.MAX_VALUE) {
                fail(runtime, runtime.path, new SkillOutcome(
                        SkillOutcome.Status.FATAL_FAILURE,
                        SkillOutcome.FailureKind.INTERNAL,
                        "skill_activation_count_exhausted:" + invocationId,
                        0,
                        Map.of("invocation_id", invocationId)));
            } else {
                activationCounts.put(invocationId, previous + 1);
            }
        }
        return true;
    }

    private boolean progressRequiredChildren(RuntimeNode runtime) {
        if (runtime.phase == Phase.PENDING) {
            runtime.phase = Phase.ACTIVE;
            runtime.childIndex = 0;
            return true;
        }
        RuntimeNode child = runtime.children.get(runtime.childIndex);
        if (child.phase == Phase.PENDING || child.phase == Phase.ACTIVE) {
            return progress(child);
        }
        if (child.phase == Phase.FAILED) {
            copyFailure(runtime, child);
            return true;
        }
        if (runtime.childIndex + 1 < runtime.children.size()) {
            runtime.childIndex++;
            return true;
        }
        runtime.phase = Phase.SUCCEEDED;
        return true;
    }

    private boolean progressAlternatives(RuntimeNode runtime) {
        if (runtime.phase == Phase.PENDING) {
            runtime.phase = Phase.ACTIVE;
            runtime.childIndex = 0;
            return true;
        }
        RuntimeNode child = runtime.children.get(runtime.childIndex);
        if (child.phase == Phase.PENDING || child.phase == Phase.ACTIVE) {
            return progress(child);
        }
        if (child.phase == Phase.SUCCEEDED) {
            runtime.phase = Phase.SUCCEEDED;
            return true;
        }
        if (mayTryAlternative(child.failureOutcome)
                && runtime.childIndex + 1 < runtime.children.size()) {
            runtime.childIndex++;
            return true;
        }
        copyFailure(runtime, child);
        return true;
    }

    private boolean progressRetry(RuntimeNode runtime, PlanNode.Retry retry) {
        RuntimeNode child = runtime.onlyChild();
        if (runtime.phase == Phase.PENDING) {
            runtime.phase = Phase.ACTIVE;
            runtime.retryAttempt = 1;
            return true;
        }
        if (child.phase == Phase.PENDING || child.phase == Phase.ACTIVE) {
            return progress(child);
        }
        if (child.phase == Phase.SUCCEEDED) {
            runtime.phase = Phase.SUCCEEDED;
            return true;
        }
        SkillOutcome outcome = child.failureOutcome;
        boolean retryable = outcome != null
                && outcome.status() == SkillOutcome.Status.RETRYABLE_FAILURE
                && outcome.failureKind() != SkillOutcome.FailureKind.SAFETY
                && retry.retryOn().contains(outcome.failureKind());
        if (retryable && runtime.retryAttempt < retry.maxAttempts()) {
            runtime.retryAttempt++;
            reset(child);
            return true;
        }
        copyFailure(runtime, child);
        return true;
    }

    private static boolean mayTryAlternative(SkillOutcome outcome) {
        return outcome != null
                && outcome.failureKind() != SkillOutcome.FailureKind.SAFETY
                && (outcome.status() == SkillOutcome.Status.BLOCKED
                || outcome.status() == SkillOutcome.Status.RETRYABLE_FAILURE);
    }

    private boolean progressTimeout(RuntimeNode runtime, PlanNode.Timeout timeout) {
        RuntimeNode child = runtime.onlyChild();
        if (runtime.phase == Phase.PENDING) {
            runtime.phase = Phase.ACTIVE;
            runtime.startedTick = tick;
            return true;
        }
        if (tick - runtime.startedTick >= timeout.timeoutTicks()) {
            SkillOutcome outcome = new SkillOutcome(
                    SkillOutcome.Status.RETRYABLE_FAILURE,
                    SkillOutcome.FailureKind.TIMEOUT,
                    "plan_timeout:" + runtime.path,
                    0,
                    Map.of(
                            "node_path", runtime.path,
                            "timeout_ticks", Long.toString(timeout.timeoutTicks())));
            fail(runtime, runtime.path, outcome);
            return true;
        }
        if (child.phase == Phase.PENDING || child.phase == Phase.ACTIVE) {
            return progress(child);
        }
        if (child.phase == Phase.SUCCEEDED) {
            runtime.phase = Phase.SUCCEEDED;
        } else {
            copyFailure(runtime, child);
        }
        return true;
    }

    private boolean progressCheckpoint(RuntimeNode runtime, PlanNode.Checkpoint checkpoint) {
        RuntimeNode child = runtime.onlyChild();
        if (runtime.phase == Phase.PENDING) {
            runtime.phase = Phase.ACTIVE;
            return true;
        }
        if (child.phase == Phase.PENDING || child.phase == Phase.ACTIVE) {
            return progress(child);
        }
        if (child.phase == Phase.SUCCEEDED) {
            runtime.checkpointReached = true;
            reachedCheckpoints.add(checkpoint.checkpointId());
            runtime.phase = Phase.SUCCEEDED;
        } else {
            copyFailure(runtime, child);
        }
        return true;
    }

    private void collectActiveWaiters(RuntimeNode runtime,
                                      String eventKey,
                                      List<NodeActivation> lineage,
                                      List<WaitActivation> target) {
        if (runtime.phase != Phase.ACTIVE) {
            return;
        }
        List<NodeActivation> activeLineage = new ArrayList<>(lineage);
        activeLineage.add(NodeActivation.capture(runtime));
        if (runtime.node instanceof PlanNode.WaitForEvent wait
                && wait.eventKey().equals(eventKey)) {
            target.add(new WaitActivation(runtime, activeLineage));
            return;
        }
        for (RuntimeNode child : runtime.children) {
            collectActiveWaiters(child, eventKey, activeLineage, target);
        }
    }

    private RuntimeNode findActiveSkill(RuntimeNode runtime, String invocationId) {
        if (runtime.phase != Phase.ACTIVE) {
            return null;
        }
        if (runtime.phase == Phase.ACTIVE
                && runtime.node instanceof PlanNode.Skill skill
                && skill.spec().invocationId().equals(invocationId)) {
            return runtime;
        }
        for (RuntimeNode child : runtime.children) {
            RuntimeNode found = findActiveSkill(child, invocationId);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private void collectActiveLeaves(RuntimeNode runtime,
                                     List<ReadySkill> ready,
                                     LinkedHashSet<String> waiting) {
        if (runtime.phase != Phase.ACTIVE) {
            return;
        }
        if (runtime.phase == Phase.ACTIVE && runtime.node instanceof PlanNode.Skill skill) {
            ready.add(new ReadySkill(
                    runtime.path,
                    skill.spec(),
                    new ActivationLease(
                            plan.missionId(),
                            plan.revision(),
                            planFingerprint,
                            runtimeEpoch,
                            skill.spec().invocationId(),
                            activationCounts.getOrDefault(skill.spec().invocationId(), 1))));
            return;
        }
        if (runtime.phase == Phase.ACTIVE && runtime.node instanceof PlanNode.WaitForEvent wait) {
            waiting.add(wait.eventKey());
            return;
        }
        for (RuntimeNode child : runtime.children) {
            collectActiveLeaves(child, ready, waiting);
        }
    }

    private static RuntimeNode build(PlanNode node, String path) {
        List<RuntimeNode> children = new ArrayList<>();
        if (node instanceof PlanNode.Sequence sequence) {
            addIndexedChildren(sequence.children(), path, children);
        } else if (node instanceof PlanNode.AllOf allOf) {
            addIndexedChildren(allOf.children(), path, children);
        } else if (node instanceof PlanNode.AnyOf anyOf) {
            addIndexedChildren(anyOf.children(), path, children);
        } else if (node instanceof PlanNode.Retry retry) {
            children.add(build(retry.child(), path + "/child"));
        } else if (node instanceof PlanNode.Timeout timeout) {
            children.add(build(timeout.child(), path + "/child"));
        } else if (node instanceof PlanNode.Checkpoint checkpoint) {
            children.add(build(checkpoint.child(), path + "/child"));
        }
        return new RuntimeNode(node, path, children);
    }

    private static Set<String> collectInvocationIds(PlanNode root) {
        LinkedHashSet<String> invocationIds = new LinkedHashSet<>();
        collectInvocationIds(root, invocationIds);
        return Collections.unmodifiableSet(invocationIds);
    }

    private static void validateCheckpointBinding(MissionPlan plan,
                                                  CursorCheckpoint checkpoint) {
        if (checkpoint.schemaVersion() != CursorCheckpoint.CURRENT_VERSION) {
            throw new IllegalArgumentException("cursor_checkpoint_version_unsupported");
        }
        if (!plan.missionId().equals(checkpoint.missionId())) {
            throw new IllegalArgumentException("cursor_checkpoint_mission_mismatch");
        }
        if (plan.revision() != checkpoint.planRevision()) {
            throw new IllegalArgumentException("cursor_checkpoint_revision_mismatch");
        }
        if (!plan.fingerprint().equals(checkpoint.planFingerprint())) {
            throw new IllegalArgumentException("cursor_checkpoint_plan_mismatch");
        }
    }

    private void restoreNodeStates(CursorCheckpoint checkpoint) {
        Map<String, RuntimeNode> runtimeByPath = new LinkedHashMap<>();
        collectRuntimeNodes(root, runtimeByPath);
        if (!runtimeByPath.keySet().equals(checkpoint.nodeStates().keySet())) {
            throw new IllegalArgumentException("cursor_checkpoint_node_set_mismatch");
        }

        Set<String> knownCheckpoints = collectCheckpointIds(plan.root());
        if (!knownCheckpoints.containsAll(reachedCheckpoints)) {
            throw new IllegalArgumentException("cursor_checkpoint_unknown_reached_checkpoint");
        }
        Set<String> knownInvocations = new LinkedHashSet<>();
        collectInvocationIds(plan.root(), knownInvocations);
        if (!knownInvocations.containsAll(activationCounts.keySet())) {
            throw new IllegalArgumentException("cursor_checkpoint_unknown_activation");
        }

        for (Map.Entry<String, RuntimeNode> entry : runtimeByPath.entrySet()) {
            RuntimeNode runtime = entry.getValue();
            CursorCheckpoint.NodeState state = checkpoint.nodeStates().get(entry.getKey());
            runtime.phase = Phase.valueOf(state.phase().name());
            runtime.childIndex = state.childIndex();
            runtime.retryAttempt = state.retryAttempt();
            runtime.startedTick = state.timeoutStartedTick();
            runtime.checkpointReached = state.checkpointReached();
            runtime.failurePath = state.failurePath();
            runtime.failureOutcome = state.failureOutcome();
            validateRestoredNodeFields(runtime, state);
        }
    }

    private void validateRestoredNodeFields(RuntimeNode runtime,
                                            CursorCheckpoint.NodeState state) {
        if (!(runtime.node instanceof PlanNode.Checkpoint) && runtime.checkpointReached) {
            throw new IllegalArgumentException(
                    "cursor_checkpoint_marker_on_non_checkpoint:" + runtime.path);
        }
        if (runtime.node instanceof PlanNode.Sequence
                || runtime.node instanceof PlanNode.AllOf
                || runtime.node instanceof PlanNode.AnyOf) {
            if (runtime.childIndex >= runtime.children.size()) {
                throw new IllegalArgumentException("cursor_checkpoint_child_index_invalid:" + runtime.path);
            }
        } else if (runtime.childIndex != 0) {
            throw new IllegalArgumentException("cursor_checkpoint_child_index_invalid:" + runtime.path);
        }

        if (runtime.node instanceof PlanNode.Retry retry) {
            if (runtime.phase == Phase.PENDING) {
                if (runtime.retryAttempt != 0) {
                    throw new IllegalArgumentException("cursor_checkpoint_retry_state_invalid:" + runtime.path);
                }
            } else if (runtime.retryAttempt < 1 || runtime.retryAttempt > retry.maxAttempts()) {
                throw new IllegalArgumentException("cursor_checkpoint_retry_state_invalid:" + runtime.path);
            }
        } else if (runtime.retryAttempt != 0) {
            throw new IllegalArgumentException("cursor_checkpoint_retry_state_invalid:" + runtime.path);
        }

        if (runtime.node instanceof PlanNode.Timeout timeout) {
            if (runtime.phase == Phase.PENDING) {
                if (runtime.startedTick != -1 || state.timeoutElapsedTicks() != -1) {
                    throw new IllegalArgumentException("cursor_checkpoint_timeout_state_invalid:" + runtime.path);
                }
            } else {
                if (runtime.startedTick < 0 || runtime.startedTick > tick
                        || state.timeoutElapsedTicks() != tick - runtime.startedTick) {
                    throw new IllegalArgumentException("cursor_checkpoint_timeout_state_invalid:" + runtime.path);
                }
                if (runtime.phase == Phase.ACTIVE
                        && state.timeoutElapsedTicks() >= timeout.timeoutTicks()) {
                    throw new IllegalArgumentException("cursor_checkpoint_timeout_deadline_passed:" + runtime.path);
                }
            }
        } else if (runtime.startedTick != -1 || state.timeoutElapsedTicks() != -1) {
            throw new IllegalArgumentException("cursor_checkpoint_timeout_state_invalid:" + runtime.path);
        }

        if (runtime.phase == Phase.FAILED) {
            if (runtime.failureOutcome == null
                    || runtime.failureOutcome.status() == SkillOutcome.Status.SUCCEEDED
                    || !isKnownNodePath(root, runtime.failurePath)) {
                throw new IllegalArgumentException("cursor_checkpoint_failure_state_invalid:" + runtime.path);
            }
        } else if (runtime.failurePath != null || runtime.failureOutcome != null) {
            throw new IllegalArgumentException("cursor_checkpoint_failure_state_invalid:" + runtime.path);
        }
    }

    private void validateRestoredState(CursorCheckpoint checkpoint) {
        validateReachedCheckpointStates();

        validateNodeConsistency(root);

        validateActivationHistory(root, 1L);

        Snapshot restoredSnapshot = snapshot();
        if (!restoredSnapshot.waitingEvents().equals(checkpoint.waitingEvents())) {
            throw new IllegalArgumentException("cursor_checkpoint_wait_state_mismatch");
        }
        if (!checkpoint.equals(checkpoint())) {
            throw new IllegalArgumentException("cursor_checkpoint_state_mismatch");
        }
    }

    private void validateNodeConsistency(RuntimeNode runtime) {
        for (RuntimeNode child : runtime.children) {
            validateNodeConsistency(child);
        }
        if (runtime.node instanceof PlanNode.Skill || runtime.node instanceof PlanNode.WaitForEvent) {
            if (!runtime.children.isEmpty()) {
                throw invalidStructure(runtime);
            }
            if (runtime.node instanceof PlanNode.WaitForEvent && runtime.phase == Phase.FAILED) {
                throw invalidStructure(runtime);
            }
            return;
        }
        if (runtime.node instanceof PlanNode.Sequence || runtime.node instanceof PlanNode.AllOf) {
            validateRequiredChildren(runtime);
        } else if (runtime.node instanceof PlanNode.AnyOf) {
            validateAlternativeChildren(runtime);
        } else if (runtime.node instanceof PlanNode.Retry retry) {
            RuntimeNode child = runtime.onlyChild();
            validateWrapperPhases(runtime, child);
            if (runtime.phase == Phase.FAILED
                    && child.failureOutcome != null
                    && child.failureOutcome.status() == SkillOutcome.Status.RETRYABLE_FAILURE
                    && child.failureOutcome.failureKind() != SkillOutcome.FailureKind.SAFETY
                    && retry.retryOn().contains(child.failureOutcome.failureKind())
                    && runtime.retryAttempt < retry.maxAttempts()) {
                throw invalidStructure(runtime);
            }
        } else if (runtime.node instanceof PlanNode.Timeout) {
            validateTimeoutPhases(runtime);
        } else if (runtime.node instanceof PlanNode.Checkpoint) {
            validateCheckpointPhases(runtime);
        }
    }

    private static void validateRequiredChildren(RuntimeNode runtime) {
        switch (runtime.phase) {
            case PENDING -> {
                if (runtime.childIndex != 0) {
                    throw invalidStructure(runtime);
                }
                requireAllPendingOrDurable(runtime, 0, runtime.children.size());
            }
            case ACTIVE -> {
                requireAllPhase(runtime, 0, runtime.childIndex, Phase.SUCCEEDED);
                requirePhase(runtime.children.get(runtime.childIndex), Phase.ACTIVE, runtime);
                requireAllPendingOrDurable(
                        runtime, runtime.childIndex + 1, runtime.children.size());
            }
            case SUCCEEDED -> requireAllPhase(runtime, 0, runtime.children.size(), Phase.SUCCEEDED);
            case FAILED -> {
                requireAllPhase(runtime, 0, runtime.childIndex, Phase.SUCCEEDED);
                RuntimeNode failed = runtime.children.get(runtime.childIndex);
                requirePhase(failed, Phase.FAILED, runtime);
                requireSameFailure(runtime, failed);
                requireAllPendingOrDurable(
                        runtime, runtime.childIndex + 1, runtime.children.size());
            }
        }
    }

    private static void validateAlternativeChildren(RuntimeNode runtime) {
        requireAllPhase(runtime, 0, runtime.childIndex, Phase.FAILED);
        for (int index = 0; index < runtime.childIndex; index++) {
            if (!mayTryAlternative(runtime.children.get(index).failureOutcome)) {
                throw invalidStructure(runtime);
            }
        }
        switch (runtime.phase) {
            case PENDING -> {
                if (runtime.childIndex != 0) {
                    throw invalidStructure(runtime);
                }
                requireAllPendingOrDurable(runtime, 0, runtime.children.size());
            }
            case ACTIVE -> {
                requirePhase(runtime.children.get(runtime.childIndex), Phase.ACTIVE, runtime);
                requireAllPendingOrDurable(
                        runtime, runtime.childIndex + 1, runtime.children.size());
            }
            case SUCCEEDED -> {
                requirePhase(runtime.children.get(runtime.childIndex), Phase.SUCCEEDED, runtime);
                requireAllPendingOrDurable(
                        runtime, runtime.childIndex + 1, runtime.children.size());
            }
            case FAILED -> {
                RuntimeNode failed = runtime.children.get(runtime.childIndex);
                requirePhase(failed, Phase.FAILED, runtime);
                requireSameFailure(runtime, failed);
                requireAllPhase(runtime, runtime.childIndex + 1, runtime.children.size(), Phase.PENDING);
                if (runtime.childIndex + 1 < runtime.children.size()
                        && mayTryAlternative(failed.failureOutcome)) {
                    throw invalidStructure(runtime);
                }
            }
        }
    }

    private static void validateWrapperPhases(RuntimeNode runtime, RuntimeNode child) {
        switch (runtime.phase) {
            case PENDING -> requirePendingOrDurable(child, runtime);
            case ACTIVE -> requirePhase(child, Phase.ACTIVE, runtime);
            case SUCCEEDED -> requirePhase(child, Phase.SUCCEEDED, runtime);
            case FAILED -> {
                requirePhase(child, Phase.FAILED, runtime);
                requireSameFailure(runtime, child);
            }
        }
    }

    private static void validateTimeoutPhases(RuntimeNode runtime) {
        RuntimeNode child = runtime.onlyChild();
        switch (runtime.phase) {
            case PENDING -> requirePendingOrDurable(child, runtime);
            case ACTIVE -> requirePhase(child, Phase.ACTIVE, runtime);
            case SUCCEEDED -> requirePhase(child, Phase.SUCCEEDED, runtime);
            case FAILED -> {
                boolean ownTimeout = runtime.failureOutcome != null
                        && runtime.failureOutcome.failureKind() == SkillOutcome.FailureKind.TIMEOUT
                        && runtime.path.equals(runtime.failurePath);
                if (ownTimeout) {
                    requirePhase(child, Phase.ACTIVE, runtime);
                } else {
                    requirePhase(child, Phase.FAILED, runtime);
                    requireSameFailure(runtime, child);
                }
            }
        }
    }

    private void validateCheckpointPhases(RuntimeNode runtime) {
        RuntimeNode child = runtime.onlyChild();
        switch (runtime.phase) {
            case PENDING -> {
                if (runtime.checkpointReached) {
                    throw invalidStructure(runtime);
                }
                requirePendingOrDurable(child, runtime);
            }
            case ACTIVE -> {
                if (runtime.checkpointReached) {
                    throw invalidStructure(runtime);
                }
                requirePhase(child, Phase.ACTIVE, runtime);
            }
            case SUCCEEDED -> {
                if (!runtime.checkpointReached
                        || child.phase != Phase.SUCCEEDED) {
                    throw invalidStructure(runtime);
                }
            }
            case FAILED -> {
                if (runtime.checkpointReached) {
                    throw invalidStructure(runtime);
                }
                requirePhase(child, Phase.FAILED, runtime);
                requireSameFailure(runtime, child);
            }
        }
    }

    private static void requireAllPhase(RuntimeNode parent,
                                        int from,
                                        int to,
                                        Phase expected) {
        for (int index = from; index < to; index++) {
            requirePhase(parent.children.get(index), expected, parent);
        }
    }

    private static void requireAllPendingOrDurable(RuntimeNode parent, int from, int to) {
        for (int index = from; index < to; index++) {
            requirePendingOrDurable(parent.children.get(index), parent);
        }
    }

    private static void requirePendingOrDurable(RuntimeNode child, RuntimeNode parent) {
        if (child.phase != Phase.PENDING && !durablySucceededCheckpoint(child)) {
            throw invalidStructure(parent);
        }
    }

    private static boolean durablySucceededCheckpoint(RuntimeNode runtime) {
        return runtime.node instanceof PlanNode.Checkpoint
                && runtime.phase == Phase.SUCCEEDED
                && runtime.checkpointReached;
    }

    private static void requirePhase(RuntimeNode child, Phase expected, RuntimeNode parent) {
        if (child.phase != expected) {
            throw invalidStructure(parent);
        }
    }

    private static void requireSameFailure(RuntimeNode parent, RuntimeNode child) {
        if (!Objects.equals(parent.failurePath, child.failurePath)
                || !Objects.equals(parent.failureOutcome, child.failureOutcome)) {
            throw invalidStructure(parent);
        }
    }

    private static IllegalArgumentException invalidStructure(RuntimeNode runtime) {
        return new IllegalArgumentException("cursor_checkpoint_structure_invalid:" + runtime.path);
    }

    private void collectNodeStates(RuntimeNode runtime,
                                   Map<String, CursorCheckpoint.NodeState> target) {
        long elapsed = runtime.startedTick < 0 ? -1 : tick - runtime.startedTick;
        target.put(runtime.path, new CursorCheckpoint.NodeState(
                CursorCheckpoint.NodePhase.valueOf(runtime.phase.name()),
                runtime.childIndex,
                runtime.retryAttempt,
                runtime.startedTick,
                elapsed,
                runtime.checkpointReached,
                runtime.failurePath,
                runtime.failureOutcome));
        for (RuntimeNode child : runtime.children) {
            collectNodeStates(child, target);
        }
    }

    private static void collectRuntimeNodes(RuntimeNode runtime,
                                            Map<String, RuntimeNode> target) {
        target.put(runtime.path, runtime);
        for (RuntimeNode child : runtime.children) {
            collectRuntimeNodes(child, target);
        }
    }

    private static boolean isKnownNodePath(RuntimeNode runtime, String path) {
        if (runtime.path.equals(path)) {
            return true;
        }
        for (RuntimeNode child : runtime.children) {
            if (isKnownNodePath(child, path)) {
                return true;
            }
        }
        return false;
    }

    private static void collectInvocationIds(PlanNode node, Set<String> target) {
        if (node instanceof PlanNode.Skill skill) {
            target.add(skill.spec().invocationId());
        } else if (node instanceof PlanNode.Sequence sequence) {
            sequence.children().forEach(child -> collectInvocationIds(child, target));
        } else if (node instanceof PlanNode.AllOf allOf) {
            allOf.children().forEach(child -> collectInvocationIds(child, target));
        } else if (node instanceof PlanNode.AnyOf anyOf) {
            anyOf.children().forEach(child -> collectInvocationIds(child, target));
        } else if (node instanceof PlanNode.Retry retry) {
            collectInvocationIds(retry.child(), target);
        } else if (node instanceof PlanNode.Timeout timeout) {
            collectInvocationIds(timeout.child(), target);
        } else if (node instanceof PlanNode.Checkpoint checkpoint) {
            collectInvocationIds(checkpoint.child(), target);
        }
    }

    /**
     * Bounds persisted activation counters by the history still represented in the runtime tree.
     * {@code entryUpperBound} counts prior ancestor runs plus the currently represented run.
     * Retry resets intentionally discard the exact failing prefix, so prior entries are allowed to
     * have consumed their declared maximum while the current entry uses its persisted attempt.
     */
    private void validateActivationHistory(RuntimeNode runtime,
                                           long entryUpperBound) {
        int currentEntry = runtime.phase == Phase.PENDING ? 0 : 1;
        long priorEntries = Math.max(0L, entryUpperBound - currentEntry);
        if (runtime.node instanceof PlanNode.Skill skill) {
            int actual = activationCounts.getOrDefault(skill.spec().invocationId(), 0);
            if (actual < currentEntry || actual > entryUpperBound) {
                throw new IllegalArgumentException("cursor_checkpoint_activation_state_mismatch");
            }
        }
        for (RuntimeNode child : runtime.children) {
            long childEntries;
            if (runtime.node instanceof PlanNode.Retry retry) {
                childEntries = boundedActivationSum(
                        priorEntries, retry.maxAttempts(), runtime.retryAttempt);
            } else {
                int currentChildEntry = child.phase == Phase.PENDING ? 0 : 1;
                childEntries = Math.min(Integer.MAX_VALUE,
                        priorEntries + currentChildEntry);
            }
            validateActivationHistory(child, childEntries);
        }
    }

    private static long boundedActivationSum(long priorEntries,
                                             int attemptsPerPriorEntry,
                                             int currentAttempts) {
        if (priorEntries >= Integer.MAX_VALUE
                || attemptsPerPriorEntry > Integer.MAX_VALUE / Math.max(1L, priorEntries)) {
            return Integer.MAX_VALUE;
        }
        long result = priorEntries * attemptsPerPriorEntry + currentAttempts;
        return Math.min(Integer.MAX_VALUE, result);
    }

    private void validateReachedCheckpointStates() {
        LinkedHashSet<String> marked = new LinkedHashSet<>();
        collectReachedCheckpointMarkers(root, marked);
        if (!marked.equals(reachedCheckpoints)) {
            throw new IllegalArgumentException("cursor_checkpoint_reached_marker_mismatch");
        }
    }

    private static void collectReachedCheckpointMarkers(RuntimeNode runtime,
                                                         Set<String> target) {
        if (runtime.node instanceof PlanNode.Checkpoint checkpoint
                && runtime.checkpointReached) {
            target.add(checkpoint.checkpointId());
        }
        for (RuntimeNode child : runtime.children) {
            collectReachedCheckpointMarkers(child, target);
        }
    }

    private static void addIndexedChildren(List<PlanNode> source,
                                           String parentPath,
                                           List<RuntimeNode> target) {
        for (int index = 0; index < source.size(); index++) {
            target.add(build(source.get(index), parentPath + "/" + index));
        }
    }

    private static void reset(RuntimeNode runtime) {
        if (durablySucceededCheckpoint(runtime)) {
            return;
        }
        runtime.phase = Phase.PENDING;
        runtime.childIndex = 0;
        runtime.retryAttempt = 0;
        runtime.startedTick = -1;
        runtime.failurePath = null;
        runtime.failureOutcome = null;
        for (RuntimeNode child : runtime.children) {
            reset(child);
        }
    }

    private static void copyFailure(RuntimeNode target, RuntimeNode source) {
        fail(target, source.failurePath, source.failureOutcome);
    }

    private static void fail(RuntimeNode runtime, String path, SkillOutcome outcome) {
        runtime.phase = Phase.FAILED;
        runtime.failurePath = path == null ? runtime.path : path;
        runtime.failureOutcome = outcome == null ? internalFailure(runtime.path) : outcome;
    }

    private static SkillOutcome internalFailure(String path) {
        return new SkillOutcome(
                SkillOutcome.Status.FATAL_FAILURE,
                SkillOutcome.FailureKind.INTERNAL,
                "plan_node_failed_without_outcome:" + path,
                0,
                Map.of("node_path", path));
    }

    private static Set<String> collectCheckpointIds(PlanNode root) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        collectCheckpointIds(root, result);
        return Collections.unmodifiableSet(result);
    }

    private static void collectCheckpointIds(PlanNode node, Set<String> result) {
        if (node instanceof PlanNode.Checkpoint checkpoint) {
            result.add(checkpoint.checkpointId());
        }
        if (node instanceof PlanNode.Sequence sequence) {
            sequence.children().forEach(child -> collectCheckpointIds(child, result));
        } else if (node instanceof PlanNode.AllOf allOf) {
            allOf.children().forEach(child -> collectCheckpointIds(child, result));
        } else if (node instanceof PlanNode.AnyOf anyOf) {
            anyOf.children().forEach(child -> collectCheckpointIds(child, result));
        } else if (node instanceof PlanNode.Retry retry) {
            collectCheckpointIds(retry.child(), result);
        } else if (node instanceof PlanNode.Timeout timeout) {
            collectCheckpointIds(timeout.child(), result);
        } else if (node instanceof PlanNode.Checkpoint checkpoint) {
            collectCheckpointIds(checkpoint.child(), result);
        }
    }

    private static void requireTick(long tick) {
        if (tick < 0) {
            throw new IllegalArgumentException("plan_tick_negative");
        }
    }

    private static String requireToken(String value, String field) {
        if (value == null || !value.matches("[a-z0-9_.:-]+")) {
            throw new IllegalArgumentException("invalid_" + field);
        }
        return value;
    }

    public enum State {
        READY,
        WAITING,
        RUNNING,
        SUCCEEDED,
        FAILED
    }

    public record ActivationLease(
            UUID missionId,
            int planRevision,
            String planFingerprint,
            UUID runtimeEpoch,
            String invocationId,
            int activationAttempt
    ) {
        public ActivationLease {
            Objects.requireNonNull(missionId, "missionId");
            if (planRevision < 0) {
                throw new IllegalArgumentException("lease_plan_revision_negative");
            }
            if (planFingerprint == null || !planFingerprint.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("lease_plan_fingerprint_invalid");
            }
            Objects.requireNonNull(runtimeEpoch, "runtimeEpoch");
            if (invocationId == null || !invocationId.matches("[a-z0-9_.-]+")) {
                throw new IllegalArgumentException("lease_invocation_id_invalid");
            }
            if (activationAttempt < 1) {
                throw new IllegalArgumentException("lease_activation_attempt_invalid");
            }
        }
    }

    public record ReadySkill(String nodePath, SkillSpec spec, ActivationLease lease) {
        public ReadySkill {
            Objects.requireNonNull(nodePath, "nodePath");
            Objects.requireNonNull(spec, "spec");
            Objects.requireNonNull(lease, "lease");
            if (!spec.invocationId().equals(lease.invocationId())) {
                throw new IllegalArgumentException("ready_skill_lease_mismatch");
            }
        }

        public int activationAttempt() {
            return lease.activationAttempt();
        }
    }

    public record Failure(String nodePath, SkillOutcome outcome) {
        public Failure {
            Objects.requireNonNull(nodePath, "nodePath");
            Objects.requireNonNull(outcome, "outcome");
            if (outcome.status() == SkillOutcome.Status.SUCCEEDED) {
                throw new IllegalArgumentException("plan_failure_requires_failed_outcome");
            }
        }
    }

    public record Completion(Snapshot snapshot, boolean accepted) {
        public Completion {
            Objects.requireNonNull(snapshot, "snapshot");
        }
    }

    public record Snapshot(
            UUID missionId,
            int revision,
            long tick,
            State state,
            List<ReadySkill> readySkills,
            Set<String> waitingEvents,
            Set<String> reachedCheckpoints,
            Optional<Failure> failure
    ) {
        public Snapshot {
            Objects.requireNonNull(missionId, "missionId");
            Objects.requireNonNull(state, "state");
            readySkills = List.copyOf(readySkills);
            waitingEvents = immutableOrderedSet(waitingEvents);
            reachedCheckpoints = immutableOrderedSet(reachedCheckpoints);
            failure = failure == null ? Optional.empty() : failure;
        }

        public boolean terminal() {
            return state == State.SUCCEEDED || state == State.FAILED;
        }

        private static <T> Set<T> immutableOrderedSet(Set<T> values) {
            return Collections.unmodifiableSet(new LinkedHashSet<>(values));
        }
    }

    private record NodeActivation(
            RuntimeNode runtime,
            Phase phase,
            int childIndex,
            int retryAttempt,
            long startedTick
    ) {
        private static NodeActivation capture(RuntimeNode runtime) {
            return new NodeActivation(runtime, runtime.phase, runtime.childIndex,
                    runtime.retryAttempt, runtime.startedTick);
        }

        private boolean unchanged() {
            return runtime.phase == phase
                    && runtime.childIndex == childIndex
                    && runtime.retryAttempt == retryAttempt
                    && runtime.startedTick == startedTick;
        }
    }

    private record WaitActivation(RuntimeNode waiter, List<NodeActivation> lineage) {
        private WaitActivation {
            lineage = List.copyOf(lineage);
        }

        private boolean stillActive() {
            return waiter.phase == Phase.ACTIVE
                    && lineage.stream().allMatch(NodeActivation::unchanged);
        }
    }

    private enum Phase {
        PENDING,
        ACTIVE,
        SUCCEEDED,
        FAILED
    }

    private static final class RuntimeNode {
        private final PlanNode node;
        private final String path;
        private final List<RuntimeNode> children;
        private Phase phase = Phase.PENDING;
        private int childIndex;
        private int retryAttempt;
        private long startedTick = -1;
        private boolean checkpointReached;
        private String failurePath;
        private SkillOutcome failureOutcome;

        private RuntimeNode(PlanNode node, String path, List<RuntimeNode> children) {
            this.node = node;
            this.path = path;
            this.children = List.copyOf(children);
        }

        private boolean terminal() {
            return phase == Phase.SUCCEEDED || phase == Phase.FAILED;
        }

        private RuntimeNode onlyChild() {
            return children.get(0);
        }
    }
}
