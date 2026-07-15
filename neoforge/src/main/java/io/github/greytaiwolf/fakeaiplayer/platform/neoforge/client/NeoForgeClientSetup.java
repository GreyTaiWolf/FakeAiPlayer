package io.github.greytaiwolf.fakeaiplayer.platform.neoforge.client;

import io.github.greytaiwolf.fakeaiplayer.client.AIBotKeyBindings;
import io.github.greytaiwolf.fakeaiplayer.client.ClientNetworkServices;
import io.github.greytaiwolf.fakeaiplayer.client.screen.BotInventoryScreen;
import io.github.greytaiwolf.fakeaiplayer.inventory.BotMenuTypes;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;

@OnlyIn(Dist.CLIENT)
public final class NeoForgeClientSetup {
    private NeoForgeClientSetup() {
    }

    public static void initialize(IEventBus modEventBus) {
        ClientNetworkServices.initialize(new NeoForgeClientNetworkTransport());
        modEventBus.addListener(NeoForgeClientSetup::registerKeys);
        modEventBus.addListener(NeoForgeClientSetup::registerMenuScreens);
    }

    private static void registerKeys(RegisterKeyMappingsEvent event) {
        event.register(AIBotKeyBindings.openPanel());
        event.register(AIBotKeyBindings.openActions());
        event.register(AIBotKeyBindings.confirmBuildingPreview());
        event.register(AIBotKeyBindings.cancelBuildingPreview());
    }

    private static void registerMenuScreens(RegisterMenuScreensEvent event) {
        event.register(BotMenuTypes.inventory(), BotInventoryScreen::new);
    }
}
