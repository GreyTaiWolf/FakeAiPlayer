package io.github.greytaiwolf.fakeaiplayer.perception.focus;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;

import java.util.ArrayList;
import java.util.List;

/** Deterministic observations only; this classifier never claims to know an entity's intent. */
public final class EntityBehaviorClassifier {
    private static final double MOVING_SPEED_SQUARED = 0.0025D;

    private EntityBehaviorClassifier() {
    }

    public static FocusSnapshot.BehaviorDetails classify(Entity entity, Entity observableAttackTarget) {
        List<String> evidence = new ArrayList<>();
        if (!entity.isAlive()) {
            return behavior(ObservedBehavior.DEAD, 1.0D, "alive=false");
        }
        if (entity instanceof LivingEntity living && living.isSleeping()) {
            return behavior(ObservedBehavior.SLEEPING, 1.0D, "pose=sleeping");
        }
        if (entity.isSwimming()) {
            return behavior(ObservedBehavior.SWIMMING, 0.98D, "swimming=true");
        }
        if (entity.isOnFire()) {
            return behavior(ObservedBehavior.BURNING, 1.0D, "on_fire=true");
        }
        if (entity instanceof LivingEntity living && living.isUsingItem()) {
            return behavior(ObservedBehavior.USING_ITEM, 0.98D, "using_item=true");
        }
        if (entity instanceof Mob && observableAttackTarget != null && observableAttackTarget.isAlive()) {
            double targetDistance = entity.distanceTo(observableAttackTarget);
            evidence.add("has_attack_target=true");
            evidence.add("attack_target_distance=" + round(targetDistance));
            if (targetDistance <= 2.5D) {
                return new FocusSnapshot.BehaviorDetails(ObservedBehavior.ATTACKING, 0.72D, evidence);
            }
            return new FocusSnapshot.BehaviorDetails(ObservedBehavior.CHASING, 0.86D, evidence);
        }
        if (entity instanceof LivingEntity living && living.hurtTime > 0) {
            return behavior(ObservedBehavior.HURT, 0.95D, "hurt_time=" + living.hurtTime);
        }
        double horizontalSpeedSquared = entity.getDeltaMovement().horizontalDistanceSqr();
        if (horizontalSpeedSquared >= MOVING_SPEED_SQUARED) {
            evidence.add("horizontal_speed=" + round(Math.sqrt(horizontalSpeedSquared)));
            return new FocusSnapshot.BehaviorDetails(ObservedBehavior.MOVING, 0.92D, evidence);
        }
        if (entity.getDeltaMovement().lengthSqr() < MOVING_SPEED_SQUARED) {
            return behavior(ObservedBehavior.IDLE, 0.84D, "speed_below_threshold=true");
        }
        return behavior(ObservedBehavior.UNKNOWN, 1.0D, "insufficient_evidence");
    }

    private static FocusSnapshot.BehaviorDetails behavior(ObservedBehavior label,
                                                           double confidence,
                                                           String evidence) {
        return new FocusSnapshot.BehaviorDetails(label, confidence, List.of(evidence));
    }

    private static double round(double value) {
        return Math.round(value * 100.0D) / 100.0D;
    }
}
