package io.github.greytaiwolf.fakeaiplayer.perception.focus;

import net.minecraft.world.LockCode;

/** Side-effect-free access to vanilla container locks for semantic inspection. */
public interface ContainerLockAccessor {
    LockCode fakeaiplayer$getLockKey();
}
