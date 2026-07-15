package io.github.greytaiwolf.fakeaiplayer.platform.neoforge;

import io.github.greytaiwolf.fakeaiplayer.FakeAiPlayer;
import io.github.greytaiwolf.fakeaiplayer.brain.ChatCaptureListener;
import io.github.greytaiwolf.fakeaiplayer.building.preview.BuildingPreviewService;
import io.github.greytaiwolf.fakeaiplayer.network.AIBotServerNetworking;
import io.github.greytaiwolf.fakeaiplayer.inventory.BotInventorySessionManager;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.ServerChatEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

@EventBusSubscriber(modid = FakeAiPlayer.MOD_ID)
public final class NeoForgeGameEvents {
    private NeoForgeGameEvents() {
    }

    @SubscribeEvent
    public static void onCommands(RegisterCommandsEvent event) {
        FakeAiPlayer.registerCommands(event.getDispatcher(), event.getBuildContext());
    }

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        FakeAiPlayer.onServerStarted(event.getServer());
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        FakeAiPlayer.onServerStopping(event.getServer());
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        FakeAiPlayer.onServerTick(event.getServer());
    }

    @SubscribeEvent
    public static void onServerChat(ServerChatEvent event) {
        ChatCaptureListener.handle(event.getPlayer(), event.getRawText());
    }

    @SubscribeEvent
    public static void onPlayerLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            AIBotServerNetworking.INSTANCE.onDisconnect(player);
            BuildingPreviewService.INSTANCE.onDisconnect(player);
            BotInventorySessionManager.INSTANCE.onViewerDisconnect(player);
        }
    }
}
