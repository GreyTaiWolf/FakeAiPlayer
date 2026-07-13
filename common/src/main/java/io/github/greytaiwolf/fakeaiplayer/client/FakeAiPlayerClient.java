package io.github.greytaiwolf.fakeaiplayer.client;

import io.github.greytaiwolf.fakeaiplayer.client.screen.BotPanelScreen;
import net.minecraft.client.Minecraft;

/** Loader-neutral client tick handler. */
public final class FakeAiPlayerClient {
    private FakeAiPlayerClient() {
    }

    public static void onClientTick(Minecraft client) {
        BotPanelScreen.Mode mode = AIBotKeyBindings.pollToggle(client);
        if (mode == null) {
            return;
        }
        if (client.screen instanceof BotPanelScreen panel && panel.mode() == mode) {
            client.setScreen(null);
        } else {
            client.setScreen(new BotPanelScreen(mode));
        }
    }
}
