package io.github.greytaiwolf.fakeaiplayer.action;

import io.github.greytaiwolf.fakeaiplayer.entity.AIPlayerEntity;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

public final class LookAction {
    private LookAction() {
    }

    public static ActionResult setYawPitch(AIPlayerEntity player, float yaw, float pitch) {
        float clampedPitch = Mth.clamp(pitch, -90.0F, 90.0F);
        player.setYRot(yaw);
        player.setYHeadRot(yaw);
        player.setYBodyRot(yaw);
        player.setXRot(clampedPitch);
        return ActionResult.SUCCESS;
    }

    public static ActionResult lookAt(AIPlayerEntity player, Vec3 target) {
        player.lookAt(EntityAnchorArgument.Anchor.EYES, target);
        player.setYHeadRot(player.getYRot());
        player.setYBodyRot(player.getYRot());
        return ActionResult.SUCCESS;
    }

    public static ActionResult lookAtBlock(AIPlayerEntity player, BlockPos pos, Direction face) {
        Vec3 target = Vec3.atCenterOf(pos).add(
                face.getStepX() * 0.5D,
                face.getStepY() * 0.5D,
                face.getStepZ() * 0.5D);
        return lookAt(player, target);
    }

    static ActionResult lookHorizontallyAt(AIPlayerEntity player, Vec3 target) {
        Vec3 current = player.position();
        double dx = target.x - current.x;
        double dz = target.z - current.z;
        float yaw = Mth.wrapDegrees((float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0D));
        return setYawPitch(player, yaw, player.getXRot());
    }
}
