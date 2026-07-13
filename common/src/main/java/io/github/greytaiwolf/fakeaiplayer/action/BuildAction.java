package io.github.greytaiwolf.fakeaiplayer.action;

import io.github.greytaiwolf.fakeaiplayer.AIBotConfig;
import io.github.greytaiwolf.fakeaiplayer.entity.AIPlayerEntity;
import io.github.greytaiwolf.fakeaiplayer.log.BotLog;
import io.github.greytaiwolf.fakeaiplayer.log.LogFields;
import io.github.greytaiwolf.fakeaiplayer.mode.ObservableWorldQuery;
import io.github.greytaiwolf.fakeaiplayer.mode.OperatingProfile;
import io.github.greytaiwolf.fakeaiplayer.pathfinding.AStarPathfinder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;

public final class BuildAction {
    private BuildAction() {
    }

    public static ActionResult placeBlock(AIPlayerEntity player, BlockPos against, Direction face, InteractionHand hand) {
        double reach = player.blockInteractionRange();
        if (player.getEyePosition().distanceToSqr(against.getCenter()) > reach * reach
                || !player.canInteractWithBlock(against, 0.0D)) {
            return ActionResult.failed("support_out_of_reach_or_sight");
        }
        ItemStack stack = player.getItemInHand(hand);
        if (stack.isEmpty()) {
            BotLog.warn(io.github.greytaiwolf.fakeaiplayer.log.LogCategory.ERROR, player, "place_failed", "reason", "empty_hand");
            return ActionResult.failed("empty_hand");
        }
        var item = stack.getItem();

        LookAction.lookAtBlock(player, against, face);
        var lookedAt = player.pick(reach, 1.0F, false);
        if (!(lookedAt instanceof BlockHitResult hit)
                || hit.getBlockPos() == null
                || !hit.getBlockPos().equals(against)
                || hit.getDirection() != face) {
            return ActionResult.failed("support_face_not_visible");
        }
        BlockPos destination = against.relative(face);
        var before = player.serverLevel().getBlockState(destination);
        net.minecraft.world.InteractionResult result = player.gameMode.useItemOn(
                player,
                player.serverLevel(),
                stack,
                hand,
                hit);
        var after = player.serverLevel().getBlockState(destination);
        if (result.consumesAction() && !after.equals(before)) {
            player.swing(hand);
            player.resetLastActionTime();
            AStarPathfinder.invalidateCache("block_place");
            BotLog.action(player, "place", "pos", LogFields.pos(destination), "face", face, "item", item);
            return ActionResult.SUCCESS;
        }
        String reason = result.consumesAction() ? "accepted_without_block_change" : result.getClass().getSimpleName();
        BotLog.warn(io.github.greytaiwolf.fakeaiplayer.log.LogCategory.ERROR, player, "place_failed",
                "pos", LogFields.pos(destination), "reason", reason);
        return ActionResult.failed("interact_block_" + reason);
    }

    public static ActionResult placeBlockAt(AIPlayerEntity player, BlockPos pos) {
        ActionResult lastFailure = ActionResult.failed("no_adjacent_block");
        BlockPos below = pos.below();
        if (ObservableWorldQuery.canObserveBlock(player, below)
                && !player.serverLevel().getBlockState(below).isAir()) {
            ActionResult result = placeBlock(player, below, Direction.UP, InteractionHand.MAIN_HAND);
            if (result.isSuccess()) {
                return result;
            }
            lastFailure = result;
        }

        for (Direction direction : Direction.values()) {
            BlockPos against = pos.relative(direction.getOpposite());
            if (ObservableWorldQuery.canObserveBlock(player, against)
                    && !player.serverLevel().getBlockState(against).isAir()) {
                ActionResult result = placeBlock(player, against, direction, InteractionHand.MAIN_HAND);
                if (result.isSuccess()) {
                    return result;
                }
                lastFailure = result;
            }
        }
        if (AIBotConfig.get().profile() == OperatingProfile.STRICT_SURVIVAL) {
            return lastFailure;
        }
        ActionResult fallback = directPlaceFallback(player, pos, InteractionHand.MAIN_HAND);
        if (fallback.isSuccess()) {
            return fallback;
        }
        return lastFailure;
    }

    private static ActionResult directPlaceFallback(AIPlayerEntity player, BlockPos pos, InteractionHand hand) {
        double reach = player.blockInteractionRange();
        if (player.getEyePosition().distanceToSqr(pos.getCenter()) > reach * reach) {
            return ActionResult.failed("target_out_of_reach");
        }
        if (!ObservableWorldQuery.canObserveCell(player, pos)) {
            return ActionResult.failed("target_not_visible");
        }
        ItemStack stack = player.getItemInHand(hand);
        if (!(stack.getItem() instanceof BlockItem blockItem)) {
            return ActionResult.failed("not_block_item");
        }
        var item = stack.getItem();
        var existing = player.serverLevel().getBlockState(pos);
        // 可替换格(流体源/草丛等)放行:封岩浆就是对浆格直接放块,原版玩家合法操作。
        if (!existing.isAir() && !existing.canBeReplaced()) {
            return ActionResult.failed("target_not_air");
        }
        var placementState = blockItem.getBlock().defaultBlockState();
        if (!placementState.canSurvive(player.serverLevel(), pos)
                || !player.serverLevel().isUnobstructed(placementState, pos, CollisionContext.of(player))) {
            return ActionResult.failed("target_blocked_or_unsupported");
        }
        if (!player.serverLevel().setBlock(pos, placementState, 3)) {
            return ActionResult.failed("world_mutation_rejected");
        }
        if (!player.getAbilities().instabuild) {
            stack.shrink(1);
        }
        player.swing(hand);
        player.resetLastActionTime();
        AStarPathfinder.invalidateCache("block_place_fallback");
        BotLog.action(player, "place_fallback", "pos", LogFields.pos(pos), "item", item);
        return ActionResult.SUCCESS;
    }
}
