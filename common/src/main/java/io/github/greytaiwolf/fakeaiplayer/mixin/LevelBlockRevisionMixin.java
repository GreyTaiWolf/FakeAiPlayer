package io.github.greytaiwolf.fakeaiplayer.mixin;

import io.github.greytaiwolf.fakeaiplayer.pathfinding.AStarPathfinder;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Invalidates navigation-derived caches after a successful server-side block mutation. */
@Mixin(Level.class)
public abstract class LevelBlockRevisionMixin {
    @Inject(
            method = "setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;II)Z",
            at = @At("RETURN"))
    private void fakeaiplayer$invalidateNavigationCachesAfterSetBlock(
            BlockPos pos,
            BlockState state,
            int flags,
            int recursionLeft,
            CallbackInfoReturnable<Boolean> callback) {
        if ((Object) this instanceof ServerLevel
                && Boolean.TRUE.equals(callback.getReturnValue())) {
            AStarPathfinder.invalidateWorldRevision();
        }
    }
}
