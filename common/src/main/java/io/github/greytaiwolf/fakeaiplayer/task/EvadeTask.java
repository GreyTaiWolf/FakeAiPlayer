package io.github.greytaiwolf.fakeaiplayer.task;

import io.github.greytaiwolf.fakeaiplayer.action.ActionResult;
import io.github.greytaiwolf.fakeaiplayer.action.EquipAction;
import io.github.greytaiwolf.fakeaiplayer.action.WalkToController;
import io.github.greytaiwolf.fakeaiplayer.entity.AIPlayerEntity;
import io.github.greytaiwolf.fakeaiplayer.log.BotLog;
import io.github.greytaiwolf.fakeaiplayer.mode.ObservableWorldQuery;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.monster.Skeleton;
import net.minecraft.world.phys.Vec3;

/**
 * Threat response state machine: find a safe walk-only route, attack only to open space when no
 * route exists, and periodically return to escape as soon as combat creates an exit.
 */
public final class EvadeTask extends AbstractTask {
    private enum Phase {
        PLAN_ESCAPE,
        ESCAPE,
        BREAK_CONTACT,
        LAST_STAND
    }

    private static final double HOSTILE_SCAN_RANGE = 16.0D;
    private static final double SAFE_COMPLETE_DISTANCE = 12.0D;
    private static final int MAX_TOTAL_TICKS = 600;
    private static final int BREAK_CONTACT_LIMIT = 140;
    private static final int ROUTE_RECHECK_INTERVAL = 30;
    private static final int MIN_COMBAT_TICKS_BEFORE_REPLAN = 12;
    private static final int MAX_REJECTED_GOALS = 8;

    private final Threat threat;
    private final Set<BlockPos> rejectedGoals = new LinkedHashSet<>();
    private Phase phase = Phase.PLAN_ESCAPE;
    private BlockPos escapeGoal;
    private EscapeRouteExecutor routeExecutor;
    private boolean terminalRoute;
    private LivingEntity combatTarget;
    private WalkToController combatWalker;
    private Vec3 combatWalkTarget;
    private int phaseTicks;
    private int failedEscapePlans;
    private int strikes;
    private int repositionTicks;
    private int nextRouteCheckTick;

    public EvadeTask(Threat threat) {
        this.threat = threat;
    }

    @Override
    public String name() {
        return "evade";
    }

    @Override
    public String describe() {
        String goal = escapeGoal == null ? "(pending)" : compact(escapeGoal);
        String target = combatTarget == null
                ? "none"
                : String.valueOf(BuiltInRegistries.ENTITY_TYPE.getKey(combatTarget.getType()));
        return "Threat response " + phase + " goal=" + goal + " target=" + target
                + " failed_routes=" + failedEscapePlans + " strikes=" + strikes;
    }

    @Override
    public double progress() {
        return state == TaskState.COMPLETED ? 1.0D : Math.min(0.95D, elapsed / (double) MAX_TOTAL_TICKS);
    }

    @Override
    protected void onStart(AIPlayerEntity bot) {
        bot.getActionPack().stopAll();
        if (!installEscapePlan(bot, "initial")) {
            failedEscapePlans++;
            beginCombatFallback(bot, "no_initial_escape_route");
        }
    }

    @Override
    protected void onTick(AIPlayerEntity bot) {
        phaseTicks++;
        if (elapsed > MAX_TOTAL_TICKS) {
            finishFailed(bot, "threat_response_timeout");
            return;
        }
        if (hostileSituationResolved(bot)) {
            finishCompleted(bot, "threat_cleared");
            return;
        }
        switch (phase) {
            case PLAN_ESCAPE -> {
                if (!installEscapePlan(bot, "replan")) {
                    failedEscapePlans++;
                    beginCombatFallback(bot, "replan_no_route");
                }
            }
            case ESCAPE -> tickEscape(bot);
            case BREAK_CONTACT, LAST_STAND -> tickCombatFallback(bot);
        }
    }

    private void tickEscape(AIPlayerEntity bot) {
        if (routeExecutor == null) {
            failedEscapePlans++;
            beginCombatFallback(bot, "missing_route_executor");
            return;
        }
        ActionResult result = routeExecutor.tick(bot.getActionPack());
        bot.getActionPack().setSprinting(true);
        if (result.isInProgress()) {
            return;
        }
        if (result.isSuccess()) {
            routeExecutor = null;
            if (terminalRoute || hostileSituationResolved(bot)) {
                finishCompleted(bot, "safe_route_completed");
                return;
            }
            if (!installEscapePlan(bot, "incremental_route_completed")) {
                failedEscapePlans++;
                beginCombatFallback(bot, "incremental_route_exhausted");
            }
            return;
        }

        rejectCurrentGoal();
        routeExecutor = null;
        failedEscapePlans++;
        BotLog.danger(bot, "escape_route_failed",
                "reason", result.reason(),
                "goal", escapeGoal == null ? "none" : compact(escapeGoal),
                "failed_routes", failedEscapePlans);
        if (failedEscapePlans < 2 && installEscapePlan(bot, "route_failed:" + result.reason())) {
            return;
        }
        beginCombatFallback(bot, "route_failed:" + result.reason());
    }

    private void tickCombatFallback(AIPlayerEntity bot) {
        if (phase == Phase.BREAK_CONTACT && phaseTicks > BREAK_CONTACT_LIMIT) {
            transitionToLastStand(bot, "break_contact_limit");
        }
        if (phaseTicks >= MIN_COMBAT_TICKS_BEFORE_REPLAN && elapsed >= nextRouteCheckTick) {
            nextRouteCheckTick = elapsed + ROUTE_RECHECK_INTERVAL;
            if (installEscapePlan(bot, "combat_opened_route")) {
                return;
            }
        }

        if (!validCombatTarget(bot, combatTarget)) {
            combatTarget = selectCombatTarget(bot);
            combatWalker = null;
            combatWalkTarget = null;
            if (combatTarget == null) {
                if (installEscapePlan(bot, "combat_target_lost")) {
                    return;
                }
                if (hostileSituationResolved(bot)) {
                    finishCompleted(bot, "hostile_no_longer_reachable");
                } else {
                    finishFailed(bot, "no_escape_route_and_no_attack_target");
                }
                return;
            }
        }

        if (repositionTicks > 0) {
            tickReposition(bot);
            return;
        }
        if (combatTarget instanceof Creeper) {
            tickCreeperDefense(bot);
            return;
        }
        tickMeleeBreakContact(bot);
    }

    private void tickMeleeBreakContact(AIPlayerEntity bot) {
        double distance = bot.distanceTo(combatTarget);
        CombatCore.lookAt(bot, combatTarget);
        if (distance <= CombatCore.ATTACK_RANGE + 0.25D) {
            combatWalker = null;
            combatWalkTarget = null;
            bot.getActionPack().setForward(0.0F);
            if (CombatCore.strikeIfReady(bot, combatTarget)) {
                strikes++;
                repositionTicks = phase == Phase.LAST_STAND ? 5 : 9;
                nextRouteCheckTick = Math.min(nextRouteCheckTick, elapsed + 4);
                BotLog.danger(bot, "escape_break_contact_strike",
                        "target", BuiltInRegistries.ENTITY_TYPE.getKey(combatTarget.getType()),
                        "mode", phase,
                        "distance", String.format(java.util.Locale.ROOT, "%.2f", distance),
                        "strikes", strikes);
            } else {
                bot.getActionPack().setStrafing(elapsed % 20 < 10 ? 0.35F : -0.35F);
            }
            return;
        }

        if (combatWalker == null
                || combatWalkTarget == null
                || combatWalkTarget.distanceToSqr(combatTarget.position()) > 2.25D
                || phaseTicks % 12 == 0) {
            combatWalkTarget = combatTarget.position();
            combatWalker = new WalkToController(combatWalkTarget);
        }
        ActionResult approach = combatWalker.tick(bot.getActionPack());
        bot.getActionPack().setSprinting(true);
        if (approach.isFailed()) {
            combatWalker = null;
            combatWalkTarget = null;
            if (phase == Phase.BREAK_CONTACT) {
                transitionToLastStand(bot, "attack_approach_failed:" + approach.reason());
            } else {
                bot.getActionPack().setStrafing(elapsed % 20 < 10 ? 0.65F : -0.65F);
            }
        }
    }

    private void tickCreeperDefense(AIPlayerEntity bot) {
        double distance = bot.distanceTo(combatTarget);
        CombatCore.lookAt(bot, combatTarget);
        combatWalker = null;
        combatWalkTarget = null;
        if (distance <= CombatCore.ATTACK_RANGE + 0.25D) {
            if (CombatCore.strikeIfReady(bot, combatTarget)) {
                strikes++;
                repositionTicks = 14;
                nextRouteCheckTick = Math.min(nextRouteCheckTick, elapsed + 2);
                BotLog.danger(bot, "escape_creeper_knockback",
                        "distance", String.format(java.util.Locale.ROOT, "%.2f", distance),
                        "mode", phase,
                        "strikes", strikes);
            }
            bot.getActionPack().setForward(-1.0F);
            bot.getActionPack().setStrafing(elapsed % 20 < 10 ? 0.65F : -0.65F);
            return;
        }
        // Never chase a creeper while trying to disengage. Maintain spacing and keep looking for a route.
        bot.getActionPack().setForward(distance < 7.0D ? -0.8F : 0.0F);
        bot.getActionPack().setStrafing(elapsed % 24 < 12 ? 0.55F : -0.55F);
        if (distance >= SAFE_COMPLETE_DISTANCE && visibleHostiles(bot, SAFE_COMPLETE_DISTANCE).isEmpty()) {
            finishCompleted(bot, "creeper_separation_created");
        }
    }

    private void tickReposition(AIPlayerEntity bot) {
        if (combatTarget == null || !combatTarget.isAlive()) {
            repositionTicks = 0;
            return;
        }
        CombatCore.lookAt(bot, combatTarget);
        bot.getActionPack().setForward(phase == Phase.LAST_STAND ? -0.25F : -0.55F);
        bot.getActionPack().setStrafing(elapsed % 20 < 10 ? 0.65F : -0.65F);
        repositionTicks--;
        if (repositionTicks <= 0) {
            bot.getActionPack().stopMovement();
            if (installEscapePlan(bot, "post_strike_replan")) {
                return;
            }
            combatTarget = selectCombatTarget(bot);
        }
    }

    private boolean installEscapePlan(AIPlayerEntity bot, String reason) {
        java.util.Optional<EscapePlanner.Plan> plan = EscapePlanner.plan(bot, threat, rejectedGoals);
        if (plan.isEmpty()) {
            return false;
        }
        stopExecutors(bot);
        EscapePlanner.Plan selected = plan.get();
        escapeGoal = selected.goal();
        terminalRoute = selected.terminalSafe();
        routeExecutor = new EscapeRouteExecutor(selected.path());
        phase = Phase.ESCAPE;
        phaseTicks = 0;
        combatTarget = null;
        nextRouteCheckTick = elapsed + ROUTE_RECHECK_INTERVAL;
        bot.getActionPack().setSprinting(true);
        BotLog.danger(bot, "escape_route_selected",
                "reason", reason,
                "goal", compact(escapeGoal),
                "steps", selected.path().size(),
                "terminal_safe", terminalRoute,
                "start_distance", format(selected.startDistance()),
                "end_distance", format(selected.endDistance()),
                "score", format(selected.score()));
        return true;
    }

    private void beginCombatFallback(AIPlayerEntity bot, String reason) {
        stopExecutors(bot);
        combatTarget = selectCombatTarget(bot);
        ThreatResponsePolicy.Fallback fallback = ThreatResponsePolicy.fallback(
                combatTarget != null, bot.getHealth(), failedEscapePlans);
        if (fallback == ThreatResponsePolicy.Fallback.NONE) {
            if (hostileSituationResolved(bot)) {
                finishCompleted(bot, "no_reachable_hostile");
            } else {
                finishFailed(bot, "no_valid_escape_route:" + reason);
            }
            return;
        }
        CombatCore.equipMelee(bot);
        EquipAction.equipShieldOffhand(bot);
        phase = fallback == ThreatResponsePolicy.Fallback.LAST_STAND
                ? Phase.LAST_STAND : Phase.BREAK_CONTACT;
        phaseTicks = 0;
        repositionTicks = 0;
        nextRouteCheckTick = elapsed + MIN_COMBAT_TICKS_BEFORE_REPLAN;
        BotLog.danger(bot, "escape_combat_fallback",
                "reason", reason,
                "mode", phase,
                "target", BuiltInRegistries.ENTITY_TYPE.getKey(combatTarget.getType()),
                "target_distance", format(bot.distanceTo(combatTarget)),
                "health", format(bot.getHealth()),
                "failed_routes", failedEscapePlans);
    }

    private void transitionToLastStand(AIPlayerEntity bot, String reason) {
        if (phase == Phase.LAST_STAND) {
            return;
        }
        phase = Phase.LAST_STAND;
        phaseTicks = 0;
        combatWalker = null;
        combatWalkTarget = null;
        BotLog.danger(bot, "escape_last_stand",
                "reason", reason,
                "health", format(bot.getHealth()),
                "target", combatTarget == null ? "none"
                        : BuiltInRegistries.ENTITY_TYPE.getKey(combatTarget.getType()));
    }

    private LivingEntity selectCombatTarget(AIPlayerEntity bot) {
        LivingEntity best = null;
        double bestScore = Double.NEGATIVE_INFINITY;
        for (LivingEntity hostile : visibleHostiles(bot, HOSTILE_SCAN_RANGE)) {
            double score = targetScore(bot, hostile);
            if (score > bestScore) {
                best = hostile;
                bestScore = score;
            }
        }
        return best;
    }

    private double targetScore(AIPlayerEntity bot, LivingEntity hostile) {
        double distance = bot.distanceTo(hostile);
        double score = 120.0D / (distance + 1.0D);
        if (hostile instanceof Mob mob && mob.getTarget() == bot) {
            score += 50.0D;
        }
        if (threat.entity() != null && threat.entity().getUUID().equals(hostile.getUUID())) {
            score += 35.0D;
        }
        if (hostile instanceof Skeleton) {
            score += 15.0D;
        }
        if (hostile instanceof Creeper) {
            score += distance <= 4.0D ? 80.0D : -60.0D;
        }
        if (escapeGoal != null) {
            Vec3 toGoal = Vec3.atCenterOf(escapeGoal).subtract(bot.position());
            Vec3 toHostile = hostile.position().subtract(bot.position());
            if (toGoal.lengthSqr() > 0.01D && toHostile.lengthSqr() > 0.01D
                    && toGoal.normalize().dot(toHostile.normalize()) > 0.72D) {
                score += 30.0D;
            }
        }
        return score;
    }

    private List<LivingEntity> visibleHostiles(AIPlayerEntity bot, double range) {
        List<LivingEntity> result = new ArrayList<>();
        List<LivingEntity> nearby = bot.serverLevel().getEntitiesOfClass(
                LivingEntity.class,
                bot.getBoundingBox().inflate(range),
                entity -> entity instanceof Monster && entity.isAlive() && entity != bot);
        for (LivingEntity hostile : nearby) {
            if (ObservableWorldQuery.canObserveEntity(bot, hostile)
                    && CombatCore.hasLineOfSight(bot, hostile)) {
                result.add(hostile);
            }
        }
        return result;
    }

    private boolean validCombatTarget(AIPlayerEntity bot, LivingEntity target) {
        return target != null
                && target.isAlive()
                && bot.distanceTo(target) <= HOSTILE_SCAN_RANGE + 4.0D
                && ObservableWorldQuery.canObserveEntity(bot, target)
                && CombatCore.hasLineOfSight(bot, target);
    }

    private boolean hostileSituationResolved(AIPlayerEntity bot) {
        if (threat.type() != Threat.Type.HOSTILE && threat.type() != Threat.Type.LOW_HP) {
            return false;
        }
        return visibleHostiles(bot, SAFE_COMPLETE_DISTANCE).isEmpty();
    }

    private void rejectCurrentGoal() {
        if (escapeGoal == null) {
            return;
        }
        rejectedGoals.add(escapeGoal.immutable());
        while (rejectedGoals.size() > MAX_REJECTED_GOALS) {
            BlockPos first = rejectedGoals.iterator().next();
            rejectedGoals.remove(first);
        }
    }

    private void stopExecutors(AIPlayerEntity bot) {
        if (routeExecutor != null) {
            routeExecutor.abort(bot.getActionPack());
            routeExecutor = null;
        }
        combatWalker = null;
        combatWalkTarget = null;
        bot.getActionPack().stopMovement();
    }

    private void finishCompleted(AIPlayerEntity bot, String reason) {
        stopExecutors(bot);
        bot.getActionPack().stopAll();
        BotLog.danger(bot, "escape_completed", "reason", reason, "phase", phase, "strikes", strikes);
        complete();
    }

    private void finishFailed(AIPlayerEntity bot, String reason) {
        stopExecutors(bot);
        bot.getActionPack().stopAll();
        BotLog.danger(bot, "escape_failed", "reason", reason, "phase", phase, "strikes", strikes);
        fail(reason);
    }

    @Override
    protected void onAbort(AIPlayerEntity bot) {
        stopExecutors(bot);
        bot.getActionPack().stopAll();
    }

    private static String compact(BlockPos pos) {
        return pos.getX() + "," + pos.getY() + "," + pos.getZ();
    }

    private static String format(double value) {
        return String.format(java.util.Locale.ROOT, "%.2f", value);
    }
}
