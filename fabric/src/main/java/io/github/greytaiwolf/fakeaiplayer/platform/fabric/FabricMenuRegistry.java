package io.github.greytaiwolf.fakeaiplayer.platform.fabric;

import io.github.greytaiwolf.fakeaiplayer.FakeAiPlayer;
import io.github.greytaiwolf.fakeaiplayer.inventory.BotInventoryMenu;
import io.github.greytaiwolf.fakeaiplayer.inventory.BotMenuTypes;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.inventory.MenuType;

final class FabricMenuRegistry {
    private FabricMenuRegistry() {
    }

    static void register() {
        MenuType<BotInventoryMenu> inventory = Registry.register(
                BuiltInRegistries.MENU,
                ResourceLocation.fromNamespaceAndPath(FakeAiPlayer.MOD_ID, "bot_inventory"),
                new MenuType<>(BotInventoryMenu::new, FeatureFlags.VANILLA_SET));
        BotMenuTypes.installInventory(() -> inventory);
    }
}
