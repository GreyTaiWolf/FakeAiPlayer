package io.github.greytaiwolf.fakeaiplayer.platform.neoforge.client;

import io.github.greytaiwolf.fakeaiplayer.FakeAiPlayer;
import io.github.greytaiwolf.fakeaiplayer.client.BotChatCapture;
import io.github.greytaiwolf.fakeaiplayer.client.FakeAiPlayerClient;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientChatReceivedEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;

@EventBusSubscriber(modid = FakeAiPlayer.MOD_ID, value = Dist.CLIENT)
public final class NeoForgeClientEvents {
    private NeoForgeClientEvents() {
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        FakeAiPlayerClient.onClientTick(Minecraft.getInstance());
    }

    @SubscribeEvent
    public static void onChat(ClientChatReceivedEvent.System event) {
        BotChatCapture.handle(event.getMessage());
    }
}
