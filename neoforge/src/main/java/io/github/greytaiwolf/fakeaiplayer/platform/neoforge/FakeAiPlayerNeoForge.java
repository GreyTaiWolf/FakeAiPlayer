package io.github.greytaiwolf.fakeaiplayer.platform.neoforge;

import io.github.greytaiwolf.fakeaiplayer.FakeAiPlayer;
import io.github.greytaiwolf.fakeaiplayer.platform.neoforge.client.NeoForgeClientSetup;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLLoader;

@Mod(FakeAiPlayer.MOD_ID)
public final class FakeAiPlayerNeoForge {
    public FakeAiPlayerNeoForge(IEventBus modEventBus) {
        NeoForgeMenuRegistry.register(modEventBus);
        FakeAiPlayer.initialize(new NeoForgeEnvironment(), new NeoForgeServerNetworkTransport());
        modEventBus.addListener(NeoForgePayloads::register);
        if (FMLLoader.getDist() == Dist.CLIENT) {
            NeoForgeClientSetup.initialize(modEventBus);
        }
    }
}
