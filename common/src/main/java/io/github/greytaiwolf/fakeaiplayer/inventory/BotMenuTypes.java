package io.github.greytaiwolf.fakeaiplayer.inventory;

import java.util.Objects;
import java.util.function.Supplier;
import net.minecraft.world.inventory.MenuType;

/** Loader-neutral access to menu types registered by Fabric or NeoForge. */
public final class BotMenuTypes {
    private static volatile Supplier<MenuType<BotInventoryMenu>> inventory;

    private BotMenuTypes() {
    }

    public static synchronized void installInventory(Supplier<MenuType<BotInventoryMenu>> supplier) {
        Objects.requireNonNull(supplier, "supplier");
        if (inventory != null) {
            throw new IllegalStateException("Bot inventory menu type is already installed");
        }
        inventory = supplier;
    }

    public static MenuType<BotInventoryMenu> inventory() {
        Supplier<MenuType<BotInventoryMenu>> supplier = inventory;
        if (supplier == null) {
            throw new IllegalStateException("Bot inventory menu type has not been registered by the active loader");
        }
        return supplier.get();
    }
}
