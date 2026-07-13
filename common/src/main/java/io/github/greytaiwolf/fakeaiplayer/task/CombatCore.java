package io.github.greytaiwolf.fakeaiplayer.task;

import io.github.greytaiwolf.fakeaiplayer.action.ActionResult;
import io.github.greytaiwolf.fakeaiplayer.action.EquipAction;
import io.github.greytaiwolf.fakeaiplayer.action.InteractAction;
import io.github.greytaiwolf.fakeaiplayer.action.LookAction;
import io.github.greytaiwolf.fakeaiplayer.entity.AIPlayerEntity;
import java.util.Comparator;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

public final class CombatCore {
    public static final float ATTACK_RANGE = 3.0F;

    private CombatCore() {
    }

    public static void equipMelee(AIPlayerEntity bot) {
        EquipAction.equipBestArmor(bot);
        EquipAction.equipBestWeapon(bot);
    }

    public static Optional<LivingEntity> nearestTarget(AIPlayerEntity bot, EntityType<?> targetType, double range) {
        return bot.serverLevel()
                .getEntitiesOfClass(LivingEntity.class, bot.getBoundingBox().inflate(range),
                        entity -> entity.isAlive() && entity.getType().equals(targetType) && entity != bot)
                .stream()
                .filter(entity -> io.github.greytaiwolf.fakeaiplayer.mode.ObservableWorldQuery.canObserveEntity(bot, entity))
                .min(Comparator.comparingDouble(bot::distanceTo));
    }

    public static Optional<LivingEntity> nearestHostileAround(AIPlayerEntity bot, BlockPos center, double range) {
        AABB box = new AABB(center).inflate(range);
        return bot.serverLevel()
                .getEntitiesOfClass(LivingEntity.class, box,
                        entity -> entity instanceof Monster && entity.isAlive() && entity != bot)
                .stream()
                .filter(entity -> io.github.greytaiwolf.fakeaiplayer.mode.ObservableWorldQuery.canObserveEntity(bot, entity))
                .min(Comparator.comparingDouble(bot::distanceTo));
    }

    public static boolean inMeleeRange(AIPlayerEntity bot, LivingEntity target) {
        return bot.distanceTo(target) <= ATTACK_RANGE;
    }

    // 视线/可达判定:bot 眼睛 → 目标眼睛之间做一次方块 raycast,中间被实心方块挡住(非 MISS)即视为
    // 够不到(隔墙/隔隧道)。raycast 只检测方块、不含实体,正好判断"有没有墙挡着"。被挡的怪近战打不到、
    // 远程射不到、苦力怕炸不到,不应触发/维持战斗(实测 bug:被方块阻隔的怪让 bot 一直"正在战斗")。
    public static boolean hasLineOfSight(AIPlayerEntity bot, LivingEntity mob) {
        HitResult hit = bot.serverLevel().clip(new ClipContext(
                bot.getEyePosition(), mob.getEyePosition(),
                ClipContext.Block.COLLIDER,
                ClipContext.Fluid.NONE,
                bot));
        return hit.getType() == HitResult.Type.MISS;
    }

    public static void lookAt(AIPlayerEntity bot, LivingEntity target) {
        Vec3 targetCenter = target.position().add(0.0D, target.getBbHeight() * 0.5D, 0.0D);
        LookAction.lookAt(bot, targetCenter);
    }

    public static void startApproach(AIPlayerEntity bot, LivingEntity target) {
        ActionResult result = bot.getActionPack().startPathTo(target.blockPosition());
        if (result.isFailed()) {
            bot.getActionPack().startWalkTo(target.position());
        }
    }

    public static boolean strikeIfReady(AIPlayerEntity bot, LivingEntity target) {
        lookAt(bot, target);
        if (bot.getAttackStrengthScale(0.5F) < 0.95F) {
            return false;
        }
        InteractAction.attackEntity(bot, target);
        return true;
    }
}
