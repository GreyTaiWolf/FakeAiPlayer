package io.github.greytaiwolf.fakeaiplayer.task.tree;

import io.github.greytaiwolf.fakeaiplayer.action.ActionResult;
import io.github.greytaiwolf.fakeaiplayer.action.BlockMiner;
import io.github.greytaiwolf.fakeaiplayer.entity.AIPlayerEntity;
import io.github.greytaiwolf.fakeaiplayer.log.BotLog;
import io.github.greytaiwolf.fakeaiplayer.pathfinding.InteractionPosePlanner;
import io.github.greytaiwolf.fakeaiplayer.pathfinding.InteractionPosePlanner.InteractionPose;
import io.github.greytaiwolf.fakeaiplayer.pathfinding.InteractionPosePlanner.PlanningBudget;
import io.github.greytaiwolf.fakeaiplayer.pathfinding.NavigationSnapshot;
import io.github.greytaiwolf.fakeaiplayer.pathfinding.NavigationState;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * A deterministic, resumable commitment to one natural tree.
 *
 * <p>The parent gather task still owns quotas, exploration and pickup. This session owns only the
 * topology and execution of one tree so reaching a quota halfway through cannot leave a floating
 * trunk and a safety interruption cannot blacklist a healthy tree.</p>
 */
public final class TreeFellingSession {
    private static final int MAX_POSE_CANDIDATES = 4;
    private static final int MAX_GENUINE_POSE_FAILURES = 2;

    public enum Status {
        RUNNING,
        SUCCEEDED,
        RETRYABLE_BLOCKED,
        PLANNING_BUDGET_EXHAUSTED,
        NEEDS_VERTICAL_ACCESS,
        STALE_TREE,
        CANCELLED
    }

    private enum Phase {
        PREFLIGHT,
        PLAN_POSE,
        NAVIGATE,
        CUT_REACHABLE
    }

    private final TreeSnapshot tree;
    private final Set<Block> allowedLogBlocks;
    private final Map<BlockPos, BlockState> committedStates;
    private final Set<BlockPos> remainingLogs;
    private final Set<BlockPos> preflightRemaining;
    private final Set<BlockPos> forbiddenSupportFeet;
    private final Set<BlockPos> attemptedStands = new LinkedHashSet<>();
    private final BlockMiner miner = new BlockMiner();
    private Phase phase = Phase.PREFLIGHT;
    private Status status = Status.RUNNING;
    private InteractionPose activePose;
    private BlockPos activeLog;
    private long navigationRequestId;
    private int logsBroken;
    private int poseReplans;
    private int genuinePoseFailures;
    private int miningFailures;
    private int preflightBudgetRetries;
    private int planningBudgetRetries;
    private int retryNotBeforeTick;
    private boolean resumeValidationRequired;
    private boolean activeMiningIssued;
    private String reason = "";

    private TreeFellingSession(
            AIPlayerEntity bot, TreeSnapshot tree, Set<Block> allowedLogBlocks) {
        this.tree = tree;
        this.allowedLogBlocks = Set.copyOf(allowedLogBlocks);
        this.committedStates = tree.logs().stream().collect(
                java.util.stream.Collectors.toUnmodifiableMap(
                        BlockPos::immutable,
                        pos -> bot.serverLevel().getBlockState(pos)));
        this.remainingLogs = new LinkedHashSet<>(tree.logs());
        this.preflightRemaining = new LinkedHashSet<>(tree.logs());
        this.forbiddenSupportFeet = java.util.stream.Stream.concat(
                        tree.logs().stream(), tree.leafEvidence().stream())
                .map(BlockPos::above)
                .map(BlockPos::immutable)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    /** Returns empty for a placed log structure, a truncated giant, or a non-log seed. */
    public static Optional<TreeFellingSession> create(
            AIPlayerEntity bot, BlockPos seed, Set<Block> allowedLogBlocks) {
        if (!TreeDetector.supportsWholeTreeDetection(
                bot.serverLevel().getBlockState(seed))) {
            return Optional.empty();
        }
        TreeSnapshot snapshot = TreeDetector.detect(bot.serverLevel(), seed, allowedLogBlocks);
        return fromSnapshot(bot, snapshot, allowedLogBlocks);
    }

    public static Optional<TreeFellingSession> fromSnapshot(
            AIPlayerEntity bot, TreeSnapshot snapshot, Set<Block> allowedLogBlocks) {
        if (!TreeDetector.supportsWholeTreeDetection(
                bot.serverLevel().getBlockState(snapshot.seed()))
                || snapshot.logs().isEmpty()
                || !snapshot.natural()
                || snapshot.truncated()) {
            return Optional.empty();
        }
        Set<Block> normalized = allowedLogBlocks == null || allowedLogBlocks.isEmpty()
                ? snapshot.logs().stream()
                .map(pos -> bot.serverLevel().getBlockState(pos).getBlock())
                .collect(java.util.stream.Collectors.toUnmodifiableSet())
                : Set.copyOf(allowedLogBlocks);
        if (normalized.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new TreeFellingSession(bot, snapshot, normalized));
    }

    public Status tick(AIPlayerEntity bot) {
        if (status != Status.RUNNING) {
            return status;
        }
        if (!bot.level().dimension().equals(tree.id().dimension())) {
            stale(bot, "tree_dimension_changed");
            return status;
        }
        if (resumeValidationRequired && !validateResumeIdentity(bot)) {
            stale(bot, "tree_changed_while_paused");
            return status;
        }
        resumeValidationRequired = false;
        if (!validateUnownedLogs(bot)) {
            stale(bot, "tree_changed_during_felling");
            return status;
        }
        if (remainingLogs.isEmpty() && activeLog == null) {
            finishMissingTree();
            return status;
        }
        if (bot.getServer().getTickCount() < retryNotBeforeTick) {
            return status;
        }

        switch (phase) {
            case PREFLIGHT -> preflight(bot);
            case PLAN_POSE -> planPose(bot);
            case NAVIGATE -> navigate(bot);
            case CUT_REACHABLE -> cutReachable(bot);
        }
        return status;
    }

    public void prepareForResume(AIPlayerEntity bot) {
        if (status != Status.RUNNING) {
            return;
        }
        if (activeLog != null
                && activeMiningIssued
                && bot.serverLevel().getBlockState(activeLog).isAir()) {
            remainingLogs.remove(activeLog);
            logsBroken++;
            attemptedStands.clear();
        }
        miner.cancel(bot);
        activeLog = null;
        activePose = null;
        navigationRequestId = 0L;
        resumeValidationRequired = true;
        activeMiningIssued = false;
        phase = preflightRemaining.isEmpty() ? Phase.PLAN_POSE : Phase.PREFLIGHT;
    }

    public void cancel(AIPlayerEntity bot) {
        miner.cancel(bot);
        status = Status.CANCELLED;
        reason = "cancelled";
    }

    public TreeSnapshot committedTree() {
        return tree;
    }

    public Set<BlockPos> remainingLogs() {
        return Set.copyOf(remainingLogs);
    }

    public Diagnostic diagnostic() {
        return new Diagnostic(
                tree.id(), status, activePose == null ? null : activePose.stand(), activeLog,
                tree.logs().size(), remainingLogs.size(), logsBroken, poseReplans, reason);
    }

    /**
     * Proves that every log in the intact snapshot has at least one dry, visible, reachable work
     * pose before the first irreversible block break. Removing wood can only open this reviewed
     * geometry, so a tall/unsupported tree is rejected whole instead of becoming a floating
     * remainder after its base is cut.
     */
    private void preflight(AIPlayerEntity bot) {
        if (preflightRemaining.isEmpty()) {
            phase = Phase.PLAN_POSE;
            reason = "";
            return;
        }
        BlockPos log = preflightRemaining.stream()
                .min(logOrder(bot))
                .orElseThrow();
        if (hasIndependentSupport(bot, bot.blockPosition())
                && InteractionPosePlanner.canInteractFromCurrent(bot, log)) {
            preflightRemaining.remove(log);
            preflightBudgetRetries = 0;
            if (preflightRemaining.isEmpty()) {
                phase = Phase.PLAN_POSE;
                reason = "";
            }
            return;
        }

        int searchesForTarget = Math.min(24, 8 + preflightBudgetRetries * 4);
        PlanningBudget budget = PlanningBudget.bounded(searchesForTarget, 45L);
        Set<BlockPos> excluded = hasIndependentSupport(bot, bot.blockPosition())
                ? Set.of()
                : Set.of(bot.blockPosition());
        Optional<InteractionPose> proof = InteractionPosePlanner.plan(
                bot, log, excluded, budget, searchesForTarget,
                forbiddenSupportFeet,
                feet -> hasIndependentSupport(bot, feet),
                pose -> hasIndependentSupport(bot, pose));
        if (proof.isPresent()) {
            preflightRemaining.remove(log);
            preflightBudgetRetries = 0;
            if (preflightRemaining.isEmpty()) {
                phase = Phase.PLAN_POSE;
                reason = "";
            }
            return;
        }
        if (budget.inconclusive()) {
            preflightBudgetRetries++;
            poseReplans++;
            reason = "tree_preflight_budget_exhausted";
            if (preflightBudgetRetries >= 5) {
                status = Status.PLANNING_BUDGET_EXHAUSTED;
            }
            return;
        }
        status = log.getY() - bot.blockPosition().getY() > 4
                ? Status.NEEDS_VERTICAL_ACCESS
                : Status.RETRYABLE_BLOCKED;
        reason = status == Status.NEEDS_VERTICAL_ACCESS
                ? "tree_preflight_requires_vertical_access"
                : "tree_preflight_has_unreachable_log";
    }

    private void planPose(AIPlayerEntity bot) {
        BlockPos direct = remainingLogs.stream()
                .filter(ignored -> hasIndependentSupport(bot, bot.blockPosition()))
                .filter(pos -> InteractionPosePlanner.canInteractFromCurrent(bot, pos))
                .min(logOrder(bot))
                .orElse(null);
        if (direct != null) {
            activePose = null;
            phase = Phase.CUT_REACHABLE;
            return;
        }

        double reach = bot.getAttributeValue(Attributes.BLOCK_INTERACTION_RANGE) + 0.5D;
        int searchesPerTarget = Math.min(24, 8 + planningBudgetRetries * 4);
        PlanningBudget budget = PlanningBudget.bounded(
                Math.min(64, searchesPerTarget * MAX_POSE_CANDIDATES),
                Math.min(75L, 35L + planningBudgetRetries * 10L));
        InteractionPose selected = remainingLogs.stream()
                .sorted(logOrder(bot))
                .limit(MAX_POSE_CANDIDATES)
                .map(log -> InteractionPosePlanner
                        .plan(
                                bot, log, attemptedStands, budget, searchesPerTarget,
                                forbiddenSupportFeet,
                                feet -> hasIndependentSupport(bot, feet),
                                pose -> hasIndependentSupport(bot, pose))
                        .orElse(null))
                .filter(java.util.Objects::nonNull)
                .min(Comparator
                        .comparingDouble((InteractionPose pose) -> pose.pathCost()
                                - 0.25D * reachableLogCount(bot, pose.stand(), reach))
                        .thenComparingDouble(InteractionPose::pathCost)
                        .thenComparingLong(pose -> pose.stand().asLong()))
                .orElse(null);
        if (selected == null) {
            if (budget.inconclusive()) {
                planningBudgetRetries++;
                poseReplans++;
                reason = "interaction_pose_budget_exhausted";
                if (planningBudgetRetries >= 5 && logsBroken == 0) {
                    status = Status.PLANNING_BUDGET_EXHAUSTED;
                } else if (planningBudgetRetries >= 5) {
                    retainAfterPartialFailure(bot, "post_cut_pose_budget_exhausted");
                }
                return;
            }
            planningBudgetRetries = 0;
            genuinePoseFailures++;
            if (requiresVerticalAccess(bot)) {
                if (logsBroken > 0) {
                    retainAfterPartialFailure(bot, "post_cut_vertical_pose_lost");
                } else {
                    status = Status.NEEDS_VERTICAL_ACCESS;
                    reason = "remaining_logs_require_vertical_access";
                }
            } else if (genuinePoseFailures >= MAX_GENUINE_POSE_FAILURES) {
                if (logsBroken > 0) {
                    retainAfterPartialFailure(bot, "post_cut_interaction_pose_blocked");
                } else {
                    status = Status.RETRYABLE_BLOCKED;
                    reason = "no_reachable_interaction_pose";
                }
            }
            return;
        }

        activePose = selected;
        planningBudgetRetries = 0;
        if (selected.currentPosition()) {
            phase = Phase.CUT_REACHABLE;
            return;
        }
        ActionResult started = bot.getActionPack().startPlannedNonMutatingPath(
                selected.stand(), selected.path(), forbiddenSupportFeet,
                feet -> hasIndependentSupport(bot, feet));
        navigationRequestId = bot.getActionPack().navigationSnapshot().requestId();
        if (started.isSuccess()) {
            phase = Phase.CUT_REACHABLE;
            return;
        }
        if (started.isFailed()) {
            onGenuineNavigationFailure(bot, selected.stand(), started.reason());
            return;
        }
        phase = Phase.NAVIGATE;
    }

    private void navigate(AIPlayerEntity bot) {
        NavigationSnapshot navigation = bot.getActionPack().navigationSnapshot();
        if (navigation.requestId() != navigationRequestId) {
            // Another owner replaced the executor. This is an interruption, not evidence that the
            // tree or pose is unreachable; retain the tree and re-evaluate from the new position.
            activePose = null;
            phase = Phase.PLAN_POSE;
            reason = "navigation_replaced";
            return;
        }
        NavigationState navState = navigation.state();
        if (navState == NavigationState.PLANNING || navState == NavigationState.FOLLOWING) {
            return;
        }
        if (navState == NavigationState.ARRIVED) {
            phase = Phase.CUT_REACHABLE;
            genuinePoseFailures = 0;
            reason = "";
            return;
        }
        if (navState == NavigationState.PREEMPTED || navState == NavigationState.CANCELLED) {
            activePose = null;
            phase = Phase.PLAN_POSE;
            reason = "navigation_" + navState.name().toLowerCase(java.util.Locale.ROOT);
            return;
        }
        if (navState == NavigationState.FAILED) {
            BlockPos failedStand = activePose == null ? null : activePose.stand();
            onGenuineNavigationFailure(bot, failedStand, navigation.reason());
        }
    }

    private void cutReachable(AIPlayerEntity bot) {
        if (activeLog == null) {
            activeLog = remainingLogs.stream()
                    .filter(pos -> isAllowedLog(bot, pos))
                    .filter(ignored -> hasIndependentSupport(bot, bot.blockPosition()))
                    .filter(pos -> InteractionPosePlanner.canInteractFromCurrent(bot, pos))
                    .min(logOrder(bot))
                    .orElse(null);
            if (activeLog == null) {
                activePose = null;
                phase = Phase.PLAN_POSE;
                return;
            }
            miner.begin(bot, activeLog);
            activeMiningIssued = false;
        }

        if (!isAllowedLog(bot, activeLog)) {
            boolean disappeared = bot.serverLevel().getBlockState(activeLog).isAir();
            miner.cancel(bot);
            if (disappeared && activeMiningIssued) {
                remainingLogs.remove(activeLog);
                logsBroken++;
                genuinePoseFailures = 0;
                miningFailures = 0;
                attemptedStands.clear();
            } else {
                activeLog = null;
                activeMiningIssued = false;
                stale(bot, "active_log_state_changed");
                return;
            }
            activeLog = null;
            activeMiningIssued = false;
            return;
        }
        if (!hasIndependentSupport(bot, bot.blockPosition())
                || !InteractionPosePlanner.canInteractFromCurrent(bot, activeLog)) {
            miner.cancel(bot);
            activeLog = null;
            activePose = null;
            phase = Phase.PLAN_POSE;
            return;
        }

        BlockMiner.Status mine = miner.tick(bot);
        if (mine == BlockMiner.Status.MINING) {
            activeMiningIssued = true;
        }
        if (mine == BlockMiner.Status.DONE) {
            remainingLogs.remove(activeLog);
            logsBroken++;
            genuinePoseFailures = 0;
            miningFailures = 0;
            attemptedStands.clear();
            BotLog.action(bot, "tree_log_broken",
                    "root", tree.id().root().toShortString(),
                    "remaining", remainingLogs.size());
            activeLog = null;
            activeMiningIssued = false;
        } else if (mine == BlockMiner.Status.FAILED) {
            String mineReason = miner.failureReason();
            activeLog = null;
            activeMiningIssued = false;
            if (activePose != null) {
                attemptedStands.add(activePose.stand());
            }
            activePose = null;
            miningFailures++;
            poseReplans++;
            phase = Phase.PLAN_POSE;
            reason = "mining_failed:" + mineReason;
            if (miningFailures >= MAX_GENUINE_POSE_FAILURES) {
                if (logsBroken > 0) {
                    retainAfterPartialFailure(bot, "post_cut_mining_failed:" + mineReason);
                } else {
                    status = Status.RETRYABLE_BLOCKED;
                }
            }
        }
    }

    private void onGenuineNavigationFailure(
            AIPlayerEntity bot, BlockPos stand, String failure) {
        if (stand != null) {
            attemptedStands.add(stand.immutable());
        }
        poseReplans++;
        genuinePoseFailures++;
        activePose = null;
        navigationRequestId = 0L;
        phase = Phase.PLAN_POSE;
        reason = "navigation_failed:" + failure;
        if (genuinePoseFailures >= MAX_GENUINE_POSE_FAILURES) {
            if (logsBroken > 0) {
                retainAfterPartialFailure(bot, "post_cut_navigation_failed:" + failure);
            } else {
                status = Status.RETRYABLE_BLOCKED;
            }
        }
    }

    private void retainAfterPartialFailure(AIPlayerEntity bot, String retryReason) {
        status = Status.RUNNING;
        reason = retryReason;
        activePose = null;
        activeLog = null;
        navigationRequestId = 0L;
        activeMiningIssued = false;
        attemptedStands.clear();
        genuinePoseFailures = 0;
        miningFailures = 0;
        planningBudgetRetries = 0;
        phase = Phase.PLAN_POSE;
        retryNotBeforeTick = bot.getServer().getTickCount() + 40;
    }

    private boolean validateUnownedLogs(AIPlayerEntity bot) {
        return remainingLogs.stream()
                .filter(pos -> !pos.equals(activeLog))
                .allMatch(pos -> isAllowedLog(bot, pos));
    }

    private boolean validateResumeIdentity(AIPlayerEntity bot) {
        for (BlockPos original : tree.logs()) {
            if (remainingLogs.contains(original) != isAllowedLog(bot, original)) {
                return false;
            }
        }
        if (logsBroken == 0 && !remainingLogs.isEmpty()) {
            TreeSnapshot refreshed = TreeDetector.detect(
                    bot.serverLevel(), tree.seed(), allowedLogBlocks);
            return refreshed.id().fingerprint() == tree.id().fingerprint()
                    && refreshed.logs().equals(tree.logs());
        }
        return true;
    }

    private void stale(AIPlayerEntity bot, String staleReason) {
        miner.cancel(bot);
        bot.getActionPack().stopAll();
        status = Status.STALE_TREE;
        reason = staleReason;
    }

    private void finishMissingTree() {
        if (logsBroken > 0) {
            status = Status.SUCCEEDED;
            reason = "";
        } else {
            status = Status.STALE_TREE;
            reason = "tree_changed_before_felling";
        }
    }

    private int reachableLogCount(AIPlayerEntity bot, BlockPos stand, double reach) {
        int count = 0;
        for (BlockPos log : remainingLogs) {
            if (InteractionPosePlanner.canInteractFrom(bot, stand, log, reach)) {
                count++;
            }
        }
        return count;
    }

    private boolean requiresVerticalAccess(AIPlayerEntity bot) {
        int feetY = bot.blockPosition().getY();
        return remainingLogs.stream().allMatch(pos -> pos.getY() - feetY > 4);
    }

    private boolean isAllowedLog(AIPlayerEntity bot, BlockPos position) {
        BlockState committed = committedStates.get(position);
        return committed != null && bot.serverLevel().getBlockState(position).equals(committed);
    }

    private boolean hasIndependentSupport(AIPlayerEntity bot, BlockPos stand) {
        BlockPos support = stand.below();
        return !tree.logs().contains(support)
                && !bot.serverLevel().getBlockState(support).is(BlockTags.LEAVES);
    }

    private boolean hasIndependentSupport(AIPlayerEntity bot, InteractionPose pose) {
        return hasIndependentSupport(bot, pose.stand())
                && pose.path().stream().allMatch(node ->
                hasIndependentSupport(bot, node.pos()));
    }

    private Comparator<BlockPos> logOrder(AIPlayerEntity bot) {
        return Comparator.comparingInt(BlockPos::getY)
                .thenComparingDouble(pos -> pos.distSqr(bot.blockPosition()))
                .thenComparingLong(BlockPos::asLong);
    }

    public record Diagnostic(
            TreeSnapshot.TreeId tree,
            Status status,
            BlockPos currentPose,
            BlockPos currentLog,
            int discoveredLogs,
            int remainingLogs,
            int logsBroken,
            int replans,
            String reason
    ) {
        public Diagnostic {
            currentPose = currentPose == null ? null : currentPose.immutable();
            currentLog = currentLog == null ? null : currentLog.immutable();
            reason = reason == null ? "" : reason;
        }
    }
}
