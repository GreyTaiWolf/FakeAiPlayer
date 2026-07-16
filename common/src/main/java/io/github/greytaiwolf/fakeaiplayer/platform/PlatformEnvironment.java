package io.github.greytaiwolf.fakeaiplayer.platform;

import java.nio.file.Path;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;

/** Loader-owned paths and metadata required by the shared runtime. */
public interface PlatformEnvironment {
    Path gameDirectory();

    Path configDirectory();

    String modVersion();

    String loaderName();

    /** Loader-aware equipment predicate; NeoForge overrides this for its item extension hook. */
    default boolean canEquip(ItemStack stack, EquipmentSlot slot, LivingEntity entity) {
        return entity.getEquipmentSlotForItem(stack) == slot;
    }
}
