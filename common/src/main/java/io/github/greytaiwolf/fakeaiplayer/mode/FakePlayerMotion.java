package io.github.greytaiwolf.fakeaiplayer.mode;

import io.github.greytaiwolf.fakeaiplayer.entity.AIPlayerEntity;
import io.github.greytaiwolf.fakeaiplayer.log.BotLog;
import java.util.Collections;
import net.minecraft.core.BlockPos;

/** Validated adjacent fake-client step; distinct from privileged long-distance teleportation. */
public final class FakePlayerMotion {
    private FakePlayerMotion() {
    }

    public static boolean stepTo(AIPlayerEntity bot, BlockPos target, String reason) {
        BlockPos from = bot.blockPosition();
        int dx = Math.abs(target.getX() - from.getX());
        int dy = Math.abs(target.getY() - from.getY());
        int dz = Math.abs(target.getZ() - from.getZ());
        int changedAxes = (dx == 0 ? 0 : 1) + (dy == 0 ? 0 : 1) + (dz == 0 ? 0 : 1);
        if (dx > 1 || dy > 1 || dz > 1 || changedAxes == 0 || changedAxes > 2) {
            BotLog.action(bot, "fake_player_step_rejected", "reason", reason, "from", from, "to", target);
            return false;
        }
        var world = bot.serverLevel();
        if (!world.getBlockState(target).getCollisionShape(world, target).isEmpty()
                || !world.getBlockState(target.above()).getCollisionShape(world, target.above()).isEmpty()) {
            BotLog.action(bot, "fake_player_step_rejected", "reason", "blocked:" + reason,
                    "from", from, "to", target);
            return false;
        }
        bot.getActionPack().stopMovement();
        bot.teleportTo(world, target.getX() + 0.5D, target.getY(), target.getZ() + 0.5D,
                Collections.emptySet(), bot.getYRot(), bot.getXRot(), false);
        BotLog.action(bot, "fake_player_step", "reason", reason, "from", from, "to", target);
        return true;
    }
}
