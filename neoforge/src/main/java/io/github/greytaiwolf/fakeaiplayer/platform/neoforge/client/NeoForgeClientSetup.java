package io.github.greytaiwolf.fakeaiplayer.platform.neoforge.client;

import io.github.greytaiwolf.fakeaiplayer.client.AIBotKeyBindings;
import io.github.greytaiwolf.fakeaiplayer.client.ClientNetworkServices;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;

@OnlyIn(Dist.CLIENT)
public final class NeoForgeClientSetup {
    private NeoForgeClientSetup() {
    }

    public static void initialize(IEventBus modEventBus) {
        ClientNetworkServices.initialize(new NeoForgeClientNetworkTransport());
        modEventBus.addListener(NeoForgeClientSetup::registerKeys);
    }

    private static void registerKeys(RegisterKeyMappingsEvent event) {
        event.register(AIBotKeyBindings.openPanel());
        event.register(AIBotKeyBindings.openActions());
    }
}
