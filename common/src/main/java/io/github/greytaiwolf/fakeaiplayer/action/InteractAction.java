package io.github.greytaiwolf.fakeaiplayer.action;

import io.github.greytaiwolf.fakeaiplayer.entity.AIPlayerEntity;
import io.github.greytaiwolf.fakeaiplayer.log.BotLog;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

public final class InteractAction {
    private InteractAction() {
    }

    public static ActionResult attackEntity(AIPlayerEntity player, Entity target) {
        Vec3 targetCenter = target.position().add(0.0D, target.getBbHeight() * 0.5D, 0.0D);
        LookAction.lookAt(player, targetCenter);
        player.attack(target);
        player.swing(InteractionHand.MAIN_HAND);
        player.resetAttackStrengthTicker();
        player.resetLastActionTime();
        BotLog.action(player, "attack", "target_type", target.getType(), "target_id", target.getId());
        return ActionResult.SUCCESS;
    }

    public static ActionResult useItemOnEntity(AIPlayerEntity player, Entity target, InteractionHand hand) {
        net.minecraft.world.InteractionResult result = target.interact(player, hand);
        return result.consumesAction() ? ActionResult.SUCCESS : ActionResult.failed("interact_entity_" + result.getClass().getSimpleName());
    }

    public static ActionResult useItemInAir(AIPlayerEntity player, InteractionHand hand) {
        net.minecraft.world.InteractionResult result = player.gameMode.useItem(
                player,
                player.serverLevel(),
                player.getItemInHand(hand),
                hand);
        return result.consumesAction() ? ActionResult.SUCCESS : ActionResult.failed("interact_item_" + result.getClass().getSimpleName());
    }
}
