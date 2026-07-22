package io.github.greytaiwolf.fakeaiplayer.mission;

/**
 * Stable execution limits attached to a Mission. This type deliberately contains no Minecraft
 * objects so planning, persistence, and arbitration can be tested without a running world.
 */
public record MissionPolicy(
        RiskLevel riskLevel,
        MutationScope mutationScope,
        int timeBudgetTicks,
        int recoveryBudget,
        InterruptionPolicy interruptionPolicy
) {
    public MissionPolicy {
        riskLevel = riskLevel == null ? RiskLevel.BALANCED : riskLevel;
        mutationScope = mutationScope == null ? MutationScope.SURVIVAL : mutationScope;
        if (timeBudgetTicks < 1) {
            throw new IllegalArgumentException("mission_time_budget_must_be_positive");
        }
        if (recoveryBudget < 0) {
            throw new IllegalArgumentException("mission_recovery_budget_must_be_non_negative");
        }
        interruptionPolicy = interruptionPolicy == null
                ? InterruptionPolicy.RESUME_AFTER_SAFETY : interruptionPolicy;
    }

    public static MissionPolicy standard() {
        return new MissionPolicy(
                RiskLevel.BALANCED,
                MutationScope.SURVIVAL,
                72_000,
                12,
                InterruptionPolicy.RESUME_AFTER_SAFETY);
    }

    public enum RiskLevel {
        CONSERVATIVE,
        BALANCED,
        BOLD
    }

    public enum MutationScope {
        NONE,
        SURVIVAL,
        CONFIRMED_AREA
    }

    public enum InterruptionPolicy {
        RESUME_AFTER_SAFETY,
        REPLAN_AFTER_SAFETY,
        CANCEL_ON_INTERRUPT
    }
}
