package io.github.greytaiwolf.fakeaiplayer.action;

import io.github.greytaiwolf.fakeaiplayer.AIBotConfig;
import io.github.greytaiwolf.fakeaiplayer.building.plan.BlockStateResolver;
import io.github.greytaiwolf.fakeaiplayer.building.plan.BlockStateSpec;
import io.github.greytaiwolf.fakeaiplayer.entity.AIPlayerEntity;
import io.github.greytaiwolf.fakeaiplayer.log.BotLog;
import io.github.greytaiwolf.fakeaiplayer.log.LogFields;
import io.github.greytaiwolf.fakeaiplayer.mode.ObservableWorldQuery;
import io.github.greytaiwolf.fakeaiplayer.mode.OperatingProfile;
import io.github.greytaiwolf.fakeaiplayer.pathfinding.AStarPathfinder;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.DoubleHighBlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.DoublePlantBlock;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;

public final class BuildAction {
    private BuildAction() {
    }

    public static ActionResult placeBlock(AIPlayerEntity player, BlockPos against, Direction face, InteractionHand hand) {
        return placeBlock(player, against, face, hand, null, null, Map.of());
    }

    public static ActionResult placeBlock(AIPlayerEntity player,
                                          BlockPos against,
                                          Direction face,
                                          InteractionHand hand,
                                          Map<String, String> expectedProperties) {
        return placeBlock(player, against, face, hand, null, null, expectedProperties);
    }

    public static ActionResult placeBlock(AIPlayerEntity player,
                                          BlockPos against,
                                          Direction face,
                                          InteractionHand hand,
                                          String expectedBlockId,
                                          String expectedPalette,
                                          Map<String, String> expectedProperties) {
        double reach = player.blockInteractionRange();
        if (player.getEyePosition().distanceToSqr(against.getCenter()) > reach * reach
                || !player.canInteractWithBlock(against, 0.0D)) {
            return ActionResult.failed("support_out_of_reach_or_sight");
        }
        LookAction.lookAtBlock(player, against, face);
        var lookedAt = player.pick(reach, 1.0F, false);
        if (!(lookedAt instanceof BlockHitResult hit)
                || hit.getBlockPos() == null
                || !hit.getBlockPos().equals(against)
                || hit.getDirection() != face) {
            return ActionResult.failed("support_face_not_visible");
        }
        BlockPos destination = against.relative(face);
        return placeUsingHit(
                player, hit, destination, hand,
                expectedBlockId, expectedPalette, expectedProperties);
    }

    private static ActionResult placeUsingHit(AIPlayerEntity player,
                                              BlockHitResult hit,
                                              BlockPos destination,
                                              InteractionHand hand,
                                              String expectedBlockId,
                                              String expectedPalette,
                                              Map<String, String> expectedProperties) {
        ItemStack stack = player.getItemInHand(hand);
        if (stack.isEmpty()) {
            BotLog.warn(io.github.greytaiwolf.fakeaiplayer.log.LogCategory.ERROR, player,
                    "place_failed", "reason", "empty_hand");
            return ActionResult.failed("empty_hand");
        }
        var item = stack.getItem();
        ActionResult preflight = preflightPlacement(
                player, hand, stack, hit, destination,
                expectedBlockId, expectedPalette, expectedProperties);
        if (preflight.isFailed()) {
            return preflight;
        }
        var before = player.serverLevel().getBlockState(destination);
        net.minecraft.world.InteractionResult result = player.gameMode.useItemOn(
                player,
                player.serverLevel(),
                stack,
                hand,
                hit);
        var after = player.serverLevel().getBlockState(destination);
        if (result.consumesAction() && !after.equals(before)) {
            // The interaction already changed the world (and may have consumed an item), even if
            // the resulting orientation/state is not the one requested by the plan.
            player.swing(hand);
            player.resetLastActionTime();
            AStarPathfinder.invalidateCache("block_place");
            ActionResult stateResult = conformPlacedState(
                    player, destination, after, expectedBlockId, expectedPalette, expectedProperties);
            if (stateResult.isFailed()) {
                BotLog.warn(io.github.greytaiwolf.fakeaiplayer.log.LogCategory.ERROR, player, "place_state_mismatch",
                        "pos", LogFields.pos(destination), "reason", stateResult.reason());
                return stateResult;
            }
            BotLog.action(player, "place", "pos", LogFields.pos(destination),
                    "face", hit.getDirection(), "item", item);
            return ActionResult.SUCCESS;
        }
        String reason = result.consumesAction() ? "accepted_without_block_change" : result.getClass().getSimpleName();
        BotLog.warn(io.github.greytaiwolf.fakeaiplayer.log.LogCategory.ERROR, player, "place_failed",
                "pos", LogFields.pos(destination), "reason", reason);
        return ActionResult.failed("interact_block_" + reason);
    }

    public static ActionResult placeBlockAt(AIPlayerEntity player, BlockPos pos) {
        return placeBlockAt(player, pos, null, null, Map.of());
    }

    public static ActionResult placeBlockAt(AIPlayerEntity player,
                                            BlockPos pos,
                                            Map<String, String> expectedProperties) {
        return placeBlockAt(player, pos, null, null, expectedProperties);
    }

    public static ActionResult placeBlockAt(AIPlayerEntity player,
                                            BlockPos pos,
                                            String expectedBlockId,
                                            String expectedPalette,
                                            Map<String, String> expectedProperties) {
        ActionResult lastFailure = ActionResult.failed("no_adjacent_block");

        // Grass, flowers and snow layers can intercept the ray before a supporting floor face.
        // Vanilla handles these by clicking the replaceable target itself: BlockPlaceContext then
        // keeps getClickedPos() at this cell. Use the real player interaction path so placement
        // events, claims and block-specific callbacks still run. Fluids stay on the adjacent-face
        // path because the normal pick call deliberately ignores them.
        var targetState = player.serverLevel().getBlockState(pos);
        if (!targetState.isAir()
                && targetState.canBeReplaced()
                && targetState.getFluidState().isEmpty()
                && ObservableWorldQuery.canObserveCell(player, pos)) {
            ActionResult result = placeIntoReplaceableTarget(
                    player, pos, expectedBlockId, expectedPalette, expectedProperties);
            if (result.isSuccess()) {
                return result;
            }
            if (result.reason().startsWith("placed_state_")) {
                return result;
            }
            lastFailure = result;
        }

        BlockPos below = pos.below();
        if (ObservableWorldQuery.canObserveBlock(player, below)
                && !player.serverLevel().getBlockState(below).isAir()) {
            ActionResult result = placeBlock(
                    player, below, Direction.UP, InteractionHand.MAIN_HAND,
                    expectedBlockId, expectedPalette, expectedProperties);
            if (result.isSuccess()) {
                return result;
            }
            if (result.reason().startsWith("placed_state_")) {
                return result;
            }
            lastFailure = result;
        }

        for (Direction direction : Direction.values()) {
            BlockPos against = pos.relative(direction.getOpposite());
            if (ObservableWorldQuery.canObserveBlock(player, against)
                    && !player.serverLevel().getBlockState(against).isAir()) {
                ActionResult result = placeBlock(
                        player, against, direction, InteractionHand.MAIN_HAND,
                        expectedBlockId, expectedPalette, expectedProperties);
                if (result.isSuccess()) {
                    return result;
                }
                if (result.reason().startsWith("placed_state_")) {
                    return result;
                }
                lastFailure = result;
            }
        }
        if (AIBotConfig.get().profile() == OperatingProfile.STRICT_SURVIVAL) {
            return lastFailure;
        }
        ActionResult fallback = directPlaceFallback(
                player, pos, InteractionHand.MAIN_HAND,
                expectedBlockId, expectedPalette, expectedProperties);
        if (fallback.isSuccess()) {
            return fallback;
        }
        if (fallback.reason().startsWith("stateful_")
                || fallback.reason().startsWith("unsupported_held_block_state")
                || fallback.reason().startsWith("held_block_not_expected")) {
            return fallback;
        }
        return lastFailure;
    }

    private static ActionResult placeIntoReplaceableTarget(AIPlayerEntity player,
                                                           BlockPos pos,
                                                           String expectedBlockId,
                                                           String expectedPalette,
                                                           Map<String, String> expectedProperties) {
        double reach = player.blockInteractionRange();
        if (player.getEyePosition().distanceToSqr(pos.getCenter()) > reach * reach
                || !player.canInteractWithBlock(pos, 0.0D)) {
            return ActionResult.failed("replaceable_target_out_of_reach_or_sight");
        }
        var state = player.serverLevel().getBlockState(pos);
        if (state.isAir() || !state.canBeReplaced() || !state.getFluidState().isEmpty()) {
            return ActionResult.failed("target_is_not_click_replaceable");
        }
        var outline = state.getShape(player.serverLevel(), pos, CollisionContext.of(player));
        if (outline.isEmpty()) {
            return ActionResult.failed("replaceable_target_has_no_outline");
        }
        var bounds = outline.bounds();
        Vec3 aim = new Vec3(
                pos.getX() + (bounds.minX + bounds.maxX) * 0.5D,
                pos.getY() + (bounds.minY + bounds.maxY) * 0.5D,
                pos.getZ() + (bounds.minZ + bounds.maxZ) * 0.5D);
        LookAction.lookAt(player, aim);
        var lookedAt = player.pick(reach, 1.0F, false);
        if (!(lookedAt instanceof BlockHitResult hit)
                || hit.getBlockPos() == null
                || !hit.getBlockPos().equals(pos)) {
            return ActionResult.failed("replaceable_target_not_visible");
        }
        return placeUsingHit(
                player, hit, pos, InteractionHand.MAIN_HAND,
                expectedBlockId, expectedPalette, expectedProperties);
    }

    private static ActionResult directPlaceFallback(AIPlayerEntity player,
                                                    BlockPos pos,
                                                    InteractionHand hand,
                                                    String expectedBlockId,
                                                    String expectedPalette,
                                                    Map<String, String> expectedProperties) {
        if (hasPlanExpectation(expectedBlockId, expectedPalette, expectedProperties)) {
            // Reviewed building plans must remain observable vanilla interactions. A direct
            // setBlock fallback would bypass NeoForge/Fabric placement events and claim hooks.
            return ActionResult.failed("planned_direct_mutation_disabled");
        }
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
        net.minecraft.world.level.block.state.BlockState placementState;
        if (requiresStatefulAdapter(blockItem.getBlock(), expectedProperties)) {
            return ActionResult.failed("stateful_block_requires_placement_adapter");
        }
        try {
            placementState = BlockStateResolver.applyProperties(
                    blockItem.getBlock().defaultBlockState(), expectedProperties);
        } catch (IllegalArgumentException exception) {
            return ActionResult.failed("unsupported_held_block_state: " + exception.getMessage());
        }
        if (!matchesExpectedState(placementState, expectedBlockId, expectedPalette, expectedProperties)) {
            return ActionResult.failed("held_block_not_expected_by_plan");
        }
        if (!io.github.greytaiwolf.fakeaiplayer.task.tree.PlayerPlacedLogLedger.INSTANCE
                .allowsTrackedPlacement(placementState)) {
            return ActionResult.failed("log_provenance_unavailable");
        }
        if (!placementState.canSurvive(player.serverLevel(), pos)
                || !player.serverLevel().isUnobstructed(placementState, pos, CollisionContext.of(player))) {
            return ActionResult.failed("target_blocked_or_unsupported");
        }
        if (!player.serverLevel().setBlock(pos, placementState, 3)) {
            return ActionResult.failed("world_mutation_rejected");
        }
        io.github.greytaiwolf.fakeaiplayer.task.tree.PlayerPlacedLogLedger.INSTANCE.record(
                player.serverLevel(), pos, placementState);
        if (!player.getAbilities().instabuild) {
            stack.shrink(1);
        }
        player.swing(hand);
        player.resetLastActionTime();
        AStarPathfinder.invalidateCache("block_place_fallback");
        BotLog.action(player, "place_fallback", "pos", LogFields.pos(pos), "item", item);
        return ActionResult.SUCCESS;
    }

    private static ActionResult conformPlacedState(AIPlayerEntity player,
                                                   BlockPos pos,
                                                   net.minecraft.world.level.block.state.BlockState actual,
                                                   String expectedBlockId,
                                                   String expectedPalette,
                                                   Map<String, String> expectedProperties) {
        if (matchesExpectedState(actual, expectedBlockId, expectedPalette, expectedProperties)) {
            return ActionResult.SUCCESS;
        }
        if (!matchesExpectedBlock(actual, expectedBlockId, expectedPalette)) {
            return ActionResult.failed("placed_state_wrong_block");
        }
        if (AIBotConfig.get().profile() == OperatingProfile.STRICT_SURVIVAL) {
            return ActionResult.failed("placed_state_mismatch");
        }
        // The real useItemOn call has already fired callbacks and possibly consumed an item. Do
        // not "repair" it with setBlock, which would bypass placement/protection events. Report a
        // conflict for retry or operator review instead.
        return ActionResult.failed("placed_state_mismatch_requires_review");
    }

    private static boolean matchesExpectedState(net.minecraft.world.level.block.state.BlockState actual,
                                                String expectedBlockId,
                                                String expectedPalette,
                                                Map<String, String> expectedProperties) {
        return matchesExpectedBlock(actual, expectedBlockId, expectedPalette)
                && BlockStateResolver.matchesProperties(actual, expectedProperties);
    }

    private static ActionResult preflightPlacement(AIPlayerEntity player,
                                                   InteractionHand hand,
                                                   ItemStack stack,
                                                   BlockHitResult hit,
                                                   BlockPos expectedDestination,
                                                   String expectedBlockId,
                                                   String expectedPalette,
                                                   Map<String, String> expectedProperties) {
        if (!hasPlanExpectation(expectedBlockId, expectedPalette, expectedProperties)) {
            return ActionResult.SUCCESS;
        }
        if (!(stack.getItem() instanceof BlockItem blockItem)) {
            return ActionResult.failed("planned_item_is_not_block_item");
        }
        // Subclasses may replace the context, choose an alternate wall/floor block, place several
        // cells or move the destination (beds, signs, scaffolding, etc.). They need an explicit
        // adapter that can predict and verify the whole footprint.
        boolean ordinaryBlockItem = blockItem.getClass() == BlockItem.class;
        boolean supportedDoorItem = blockItem instanceof DoubleHighBlockItem
                && blockItem.getBlock() instanceof DoorBlock
                && expectedProperties != null
                && "lower".equals(expectedProperties.get("half"));
        if (!ordinaryBlockItem && !supportedDoorItem) {
            return ActionResult.failed("special_block_item_requires_placement_adapter");
        }
        BlockPlaceContext context = new BlockPlaceContext(player, hand, stack, hit);
        if (!context.getClickedPos().equals(expectedDestination)) {
            return ActionResult.failed("predicted_placement_destination_mismatch");
        }
        net.minecraft.world.level.block.state.BlockState predicted;
        try {
            predicted = blockItem.getBlock().getStateForPlacement(context);
        } catch (RuntimeException exception) {
            return ActionResult.failed("predicted_placement_state_error");
        }
        if (predicted == null) {
            return ActionResult.failed("predicted_placement_state_missing");
        }
        return matchesExpectedState(predicted, expectedBlockId, expectedPalette, expectedProperties)
                ? ActionResult.SUCCESS
                : ActionResult.failed("predicted_placement_state_mismatch");
    }

    private static boolean matchesExpectedBlock(net.minecraft.world.level.block.state.BlockState actual,
                                                String expectedBlockId,
                                                String expectedPalette) {
        if (expectedBlockId == null || expectedBlockId.isBlank()) {
            return true;
        }
        if (expectedPalette != null && !expectedPalette.isBlank()) {
            return MaterialPalette.matchesBlock(actual, expectedPalette);
        }
        try {
            return BlockStateResolver.matches(actual, new BlockStateSpec(expectedBlockId, Map.of()));
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private static boolean hasPlanExpectation(String expectedBlockId,
                                              String expectedPalette,
                                              Map<String, String> expectedProperties) {
        return expectedBlockId != null && !expectedBlockId.isBlank()
                || expectedPalette != null && !expectedPalette.isBlank()
                || expectedProperties != null && !expectedProperties.isEmpty();
    }

    private static boolean requiresStatefulAdapter(net.minecraft.world.level.block.Block block,
                                                   Map<String, String> expectedProperties) {
        return block instanceof DoorBlock
                || block instanceof BedBlock
                || block instanceof DoublePlantBlock
                || block instanceof EntityBlock
                || (block instanceof SlabBlock
                && expectedProperties != null
                && "double".equals(expectedProperties.get("type")));
    }
}
