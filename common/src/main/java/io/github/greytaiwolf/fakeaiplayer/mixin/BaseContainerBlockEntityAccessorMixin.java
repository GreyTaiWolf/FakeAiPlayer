package io.github.greytaiwolf.fakeaiplayer.mixin;

import io.github.greytaiwolf.fakeaiplayer.perception.focus.ContainerLockAccessor;
import net.minecraft.world.LockCode;
import net.minecraft.world.level.block.entity.BaseContainerBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(BaseContainerBlockEntity.class)
public interface BaseContainerBlockEntityAccessorMixin extends ContainerLockAccessor {
    @Override
    @Accessor("lockKey")
    LockCode fakeaiplayer$getLockKey();
}
