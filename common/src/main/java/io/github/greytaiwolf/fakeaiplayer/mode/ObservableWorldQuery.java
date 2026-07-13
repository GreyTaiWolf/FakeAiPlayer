package io.github.greytaiwolf.fakeaiplayer.mode;

import io.github.greytaiwolf.fakeaiplayer.AIBotConfig;
import io.github.greytaiwolf.fakeaiplayer.entity.AIPlayerEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

/** Strict-survival perception filter: nearby, exposed, and actually on the Bot's line of sight. */
public final class ObservableWorldQuery {
    private ObservableWorldQuery() {
    }

    public static boolean canObserveBlock(AIPlayerEntity bot, BlockPos pos) {
        if (CapabilityRuntime.decide(bot, PrivilegedCapability.HIDDEN_BLOCK_SCAN,
                "observable_block_query").allowed()) {
            return true;
        }
        for (Direction direction : Direction.values()) {
            // Aim at the exposed face, not the block center. A center ray to distant flat ground
            // intersects a nearer ground block first and incorrectly reports the target as hidden.
            // Keep the endpoint just inside the target block. Stopping just outside the face
            // lets the ray end before entering the collision shape and produces MISS for an
            // otherwise visible floor block.
            var face = pos.getCenter().add(
                    direction.getStepX() * 0.499D,
                    direction.getStepY() * 0.499D,
                    direction.getStepZ() * 0.499D);
            if (canObserveFaceAfterPolicy(bot, pos, direction, face)) {
                return true;
            }
        }
        return false;
    }

    private static boolean canObserveFaceAfterPolicy(AIPlayerEntity bot,
                                                      BlockPos pos,
                                                      Direction face,
                                                      net.minecraft.world.phys.Vec3 endpoint) {
        int radius = Math.max(1, AIBotConfig.get().perception().radius());
        if (bot.getEyePosition().distanceToSqr(endpoint) > (double) radius * radius) {
            return false;
        }
        BlockHitResult hit = bot.serverLevel().clip(new ClipContext(
                bot.getEyePosition(), endpoint,
                ClipContext.Block.COLLIDER, ClipContext.Fluid.ANY, bot));
        return hit.getType() == HitResult.Type.BLOCK
                && hit.getBlockPos().equals(pos)
                && hit.getDirection() == face;
    }

    /**
     * Returns whether the bot has an unobstructed view into a nearby world cell. Unlike
     * {@link #canObserveBlock(AIPlayerEntity, BlockPos)}, an empty/non-colliding target is a valid
     * result, so callers can gate feet/head reads before asking whether a position is standable.
     */
    public static boolean canObserveCell(AIPlayerEntity bot, BlockPos pos) {
        if (CapabilityRuntime.decide(bot, PrivilegedCapability.HIDDEN_BLOCK_SCAN,
                "observable_cell_query").allowed()) {
            return true;
        }
        int radius = Math.max(1, AIBotConfig.get().perception().radius());
        if (bot.getEyePosition().distanceToSqr(pos.getCenter()) > (double) radius * radius) {
            return false;
        }
        BlockHitResult hit = bot.serverLevel().clip(new ClipContext(
                bot.getEyePosition(), pos.getCenter(),
                ClipContext.Block.COLLIDER, ClipContext.Fluid.ANY, bot));
        return hit.getType() == HitResult.Type.MISS
                || (hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(pos));
    }

    public static boolean canObserveEntity(AIPlayerEntity bot, Entity entity) {
        if (CapabilityRuntime.decide(bot, PrivilegedCapability.HIDDEN_BLOCK_SCAN,
                "observable_entity_query").allowed()) {
            return true;
        }
        int radius = Math.max(1, AIBotConfig.get().perception().radius());
        return bot.distanceToSqr(entity) <= (double) radius * radius && bot.hasLineOfSight(entity);
    }
}
