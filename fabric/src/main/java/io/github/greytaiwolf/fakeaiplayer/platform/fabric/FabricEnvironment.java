package io.github.greytaiwolf.fakeaiplayer.platform.fabric;

import io.github.greytaiwolf.fakeaiplayer.FakeAiPlayer;
import io.github.greytaiwolf.fakeaiplayer.platform.PlatformEnvironment;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Path;

final class FabricEnvironment implements PlatformEnvironment {
    @Override
    public Path gameDirectory() {
        return FabricLoader.getInstance().getGameDir();
    }

    @Override
    public Path configDirectory() {
        return FabricLoader.getInstance().getConfigDir();
    }

    @Override
    public String modVersion() {
        return FabricLoader.getInstance()
                .getModContainer(FakeAiPlayer.MOD_ID)
                .map(container -> container.getMetadata().getVersion().getFriendlyString())
                .orElse("unknown");
    }

    @Override
    public String loaderName() {
        return "Fabric";
    }
}
