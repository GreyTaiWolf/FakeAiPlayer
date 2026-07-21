package io.github.greytaiwolf.fakeaiplayer.mixin;

import io.github.greytaiwolf.fakeaiplayer.task.tree.PlayerPlacedLogLedger;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Records successful player/Bot BlockItem placements for conservative tree ownership checks. */
@Mixin(BlockItem.class)
public abstract class BlockItemPlacementMixin {
    @Inject(method = "placeBlock", at = @At("HEAD"), cancellable = true)
    private void fakeaiplayer$refuseUntrackedLogPlacement(
            BlockPlaceContext context,
            BlockState state,
            CallbackInfoReturnable<Boolean> callback) {
        if (context.getLevel() instanceof ServerLevel
                && !PlayerPlacedLogLedger.INSTANCE.allowsTrackedPlacement(state)) {
            callback.setReturnValue(false);
        }
    }

    @Inject(method = "placeBlock", at = @At("RETURN"))
    private void fakeaiplayer$recordPlacedLog(
            BlockPlaceContext context,
            BlockState state,
            CallbackInfoReturnable<Boolean> callback) {
        if (callback.getReturnValue()
                && context.getLevel() instanceof ServerLevel world) {
            PlayerPlacedLogLedger.INSTANCE.record(
                    world, context.getClickedPos(), state);
        }
    }
}
