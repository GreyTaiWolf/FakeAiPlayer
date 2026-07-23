package io.github.greytaiwolf.fakeaiplayer.mission;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Pure Mission recovery accounting. Skill attempts are keyed by a semantic fingerprint rather
 * than an invocation/node id, so recompiling the same Skill in a new plan revision cannot reset
 * its declared attempt limit.
 *
 * <p>An initial execution is attempt {@code 1}. Call {@link #beginAttempt(SkillSpec)} immediately
 * before installing the Skill. A denied result must not be installed. Verified world progress may
 * open a new attempt window for that Skill, but never refunds the Mission-wide recovery budget.</p>
 */
public final class RecoveryLedger {
    private static final HexFormat HEX = HexFormat.of();
    private static final int MAX_TRACKED_SKILLS = 256;

    private final int recoveryBudget;
    private final Map<String, Integer> attemptsBySkill;
    private int recoveriesConsumed;
    private int consecutiveNoProgressRecoveries;
    private int postconditionRecoveriesConsumed;

    public RecoveryLedger(int recoveryBudget) {
        this(recoveryBudget, Snapshot.empty());
    }

    public RecoveryLedger(int recoveryBudget, Snapshot restored) {
        if (recoveryBudget < 0) {
            throw new IllegalArgumentException("recovery_budget_must_be_non_negative");
        }
        Snapshot safe = Objects.requireNonNull(restored, "restored");
        if (safe.recoveriesConsumed() > recoveryBudget) {
            throw new IllegalArgumentException("recovery_snapshot_exceeds_budget");
        }
        this.recoveryBudget = recoveryBudget;
        this.attemptsBySkill = new LinkedHashMap<>(safe.attemptsBySkill());
        this.recoveriesConsumed = safe.recoveriesConsumed();
        this.consecutiveNoProgressRecoveries = safe.consecutiveNoProgressRecoveries();
        this.postconditionRecoveriesConsumed = safe.postconditionRecoveriesConsumed();
    }

    /**
     * Reserves the next execution attempt. The first successful reservation returns attempt 1.
     */
    public AttemptDecision beginAttempt(SkillSpec skill) {
        Objects.requireNonNull(skill, "skill");
        String fingerprint = fingerprint(skill);
        int used = attemptsBySkill.getOrDefault(fingerprint, 0);
        int limit = skill.retryPolicy().maxAttempts();
        if (used == 0 && attemptsBySkill.size() >= MAX_TRACKED_SKILLS) {
            return new AttemptDecision(false, fingerprint, 0, limit,
                    "skill_attempt_tracking_capacity_exhausted");
        }
        if (used >= limit) {
            return new AttemptDecision(false, fingerprint, used, limit,
                    "skill_attempt_budget_exhausted");
        }
        int next = used + 1;
        attemptsBySkill.put(fingerprint, next);
        return new AttemptDecision(true, fingerprint, next, limit, "attempt_reserved");
    }

    /**
     * Rolls back the most recent reservation when the central arbiter defers installation. A
     * Skill whose {@code start} method was entered is a real attempt and must not be rolled back.
     */
    public void rollbackDeferredAttempt(AttemptDecision reservation) {
        Objects.requireNonNull(reservation, "reservation");
        if (!reservation.allowed()) {
            throw new IllegalArgumentException("cannot_rollback_denied_attempt");
        }
        Integer used = attemptsBySkill.get(reservation.fingerprint());
        if (used == null || used != reservation.attempt()) {
            throw new IllegalStateException("attempt_reservation_not_latest");
        }
        if (used == 1) {
            attemptsBySkill.remove(reservation.fingerprint());
        } else {
            attemptsBySkill.put(reservation.fingerprint(), used - 1);
        }
    }

    /**
     * Opens a fresh attempt window after authoritative progress for this semantic Skill. Other
     * Skills retain their own counters and the Mission-wide recovery budget is unchanged.
     */
    public void markVerifiedProgress(SkillSpec skill) {
        Objects.requireNonNull(skill, "skill");
        attemptsBySkill.remove(fingerprint(skill));
        consecutiveNoProgressRecoveries = 0;
    }

    /**
     * Consumes one Mission-wide recovery. Exactly {@code recoveryBudget} successful calls are
     * allowed. Callers should reserve this before rebuilding a plan.
     */
    public RecoveryDecision consumeRecovery(RecoveryKind kind, boolean verifiedProgress) {
        Objects.requireNonNull(kind, "kind");
        if (recoveriesConsumed >= recoveryBudget) {
            return new RecoveryDecision(false, kind, recoveriesConsumed, recoveryBudget,
                    consecutiveNoProgressRecoveries, postconditionRecoveriesConsumed,
                    "mission_recovery_budget_exhausted");
        }
        recoveriesConsumed++;
        if (verifiedProgress) {
            consecutiveNoProgressRecoveries = 0;
        } else {
            consecutiveNoProgressRecoveries++;
        }
        if (kind == RecoveryKind.POSTCONDITION) {
            postconditionRecoveriesConsumed++;
        }
        return new RecoveryDecision(true, kind, recoveriesConsumed, recoveryBudget,
                consecutiveNoProgressRecoveries, postconditionRecoveriesConsumed,
                "recovery_reserved");
    }

    public int attemptsFor(SkillSpec skill) {
        Objects.requireNonNull(skill, "skill");
        return attemptsBySkill.getOrDefault(fingerprint(skill), 0);
    }

    public int recoveryBudget() {
        return recoveryBudget;
    }

    public int recoveriesConsumed() {
        return recoveriesConsumed;
    }

    public int recoveriesRemaining() {
        return Math.max(0, recoveryBudget - recoveriesConsumed);
    }

    public int consecutiveNoProgressRecoveries() {
        return consecutiveNoProgressRecoveries;
    }

    public int postconditionRecoveriesConsumed() {
        return postconditionRecoveriesConsumed;
    }

    public Snapshot snapshot() {
        return new Snapshot(attemptsBySkill, recoveriesConsumed,
                consecutiveNoProgressRecoveries, postconditionRecoveriesConsumed);
    }

    static int maxTrackedSkills() {
        return MAX_TRACKED_SKILLS;
    }

    /**
     * Stable across JVMs and map iteration orders. Invocation identity is intentionally excluded.
     */
    public static String fingerprint(SkillSpec skill) {
        Objects.requireNonNull(skill, "skill");
        StringBuilder canonical = new StringBuilder();
        canonical.append("id=");
        appendLengthPrefixed(canonical, skill.id());
        canonical.append("version=").append(skill.version()).append(';');
        canonical.append("parameters=");
        new TreeMap<>(skill.parameters()).forEach((key, value) -> {
            appendLengthPrefixed(canonical, key);
            appendLengthPrefixed(canonical, value);
        });
        canonical.append("preconditions=");
        skill.preconditions().forEach(value -> appendLengthPrefixed(canonical, value));
        canonical.append("success_predicates=");
        skill.successPredicates().forEach(value -> appendLengthPrefixed(canonical, value));
        canonical.append("max_attempts=").append(skill.retryPolicy().maxAttempts()).append(';');
        canonical.append("replan_on=");
        skill.retryPolicy().replanOn().stream()
                .map(Enum::name)
                .sorted()
                .forEach(value -> appendLengthPrefixed(canonical, value));
        canonical.append("mutation_scope=");
        appendLengthPrefixed(canonical, skill.mutationScope().name());
        canonical.append("required_risk=");
        appendLengthPrefixed(canonical, skill.requiredRisk().name());
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
            return HEX.formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("sha256_unavailable", exception);
        }
    }

    private static void appendLengthPrefixed(StringBuilder target, String value) {
        String safe = Objects.requireNonNull(value, "fingerprint_component");
        target.append(safe.length()).append(':').append(safe).append(';');
    }

    public enum RecoveryKind {
        SKILL_FAILURE,
        POSTCONDITION,
        INTERRUPT_REPLAN
    }

    public record AttemptDecision(
            boolean allowed,
            String fingerprint,
            int attempt,
            int maxAttempts,
            String reason
    ) {
        public AttemptDecision {
            if (fingerprint == null || fingerprint.isBlank() || attempt < 0 || maxAttempts < 1
                    || reason == null || reason.isBlank()) {
                throw new IllegalArgumentException("invalid_attempt_decision");
            }
        }
    }

    public record RecoveryDecision(
            boolean allowed,
            RecoveryKind kind,
            int consumed,
            int budget,
            int consecutiveNoProgress,
            int postconditionConsumed,
            String reason
    ) {
        public RecoveryDecision {
            if (kind == null || consumed < 0 || budget < 0 || consecutiveNoProgress < 0
                    || postconditionConsumed < 0 || reason == null || reason.isBlank()) {
                throw new IllegalArgumentException("invalid_recovery_decision");
            }
        }
    }

    public record Snapshot(
            Map<String, Integer> attemptsBySkill,
            int recoveriesConsumed,
            int consecutiveNoProgressRecoveries,
            int postconditionRecoveriesConsumed
    ) {
        public Snapshot {
            attemptsBySkill = attemptsBySkill == null ? Map.of() : Map.copyOf(attemptsBySkill);
            if (recoveriesConsumed < 0 || consecutiveNoProgressRecoveries < 0
                    || postconditionRecoveriesConsumed < 0) {
                throw new IllegalArgumentException("negative_recovery_snapshot_counter");
            }
            if (consecutiveNoProgressRecoveries > recoveriesConsumed
                    || postconditionRecoveriesConsumed > recoveriesConsumed) {
                throw new IllegalArgumentException("inconsistent_recovery_snapshot_counter");
            }
            if (attemptsBySkill.size() > MAX_TRACKED_SKILLS) {
                throw new IllegalArgumentException("too_many_skill_attempt_snapshots");
            }
            for (Map.Entry<String, Integer> entry : attemptsBySkill.entrySet()) {
                if (entry.getKey() == null || !entry.getKey().matches("[0-9a-f]{64}")
                        || entry.getValue() == null || entry.getValue() < 1) {
                    throw new IllegalArgumentException("invalid_skill_attempt_snapshot");
                }
            }
        }

        public static Snapshot empty() {
            return new Snapshot(Map.of(), 0, 0, 0);
        }
    }
}
