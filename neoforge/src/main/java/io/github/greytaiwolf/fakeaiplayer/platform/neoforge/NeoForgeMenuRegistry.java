package io.github.greytaiwolf.fakeaiplayer.platform.neoforge;

import io.github.greytaiwolf.fakeaiplayer.FakeAiPlayer;
import io.github.greytaiwolf.fakeaiplayer.inventory.BotInventoryMenu;
import io.github.greytaiwolf.fakeaiplayer.inventory.BotMenuTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.inventory.MenuType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

final class NeoForgeMenuRegistry {
    private static final DeferredRegister<MenuType<?>> MENUS =
            DeferredRegister.create(BuiltInRegistries.MENU, FakeAiPlayer.MOD_ID);
    private static final DeferredHolder<MenuType<?>, MenuType<BotInventoryMenu>> BOT_INVENTORY =
            MENUS.register("bot_inventory",
                    () -> new MenuType<>(BotInventoryMenu::new, FeatureFlags.VANILLA_SET));

    private NeoForgeMenuRegistry() {
    }

    static void register(IEventBus modEventBus) {
        MENUS.register(modEventBus);
        BotMenuTypes.installInventory(BOT_INVENTORY);
    }
}
