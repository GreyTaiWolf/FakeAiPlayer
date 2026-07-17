package io.github.greytaiwolf.fakeaiplayer.platform.neoforge.client;

import io.github.greytaiwolf.fakeaiplayer.FakeAiPlayer;
import io.github.greytaiwolf.fakeaiplayer.building.preview.client.BuildingPreviewClientState;
import io.github.greytaiwolf.fakeaiplayer.building.preview.client.BuildingPreviewWorldRenderer;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

/** NeoForge event adapter for the shared baked-model building projection renderer. */
@EventBusSubscriber(modid = FakeAiPlayer.MOD_ID, value = Dist.CLIENT)
public final class NeoForgeBuildingPreviewRenderer {
    private static final BuildingPreviewWorldRenderer RENDERER = new BuildingPreviewWorldRenderer();

    private NeoForgeBuildingPreviewRenderer() {
    }

    @SubscribeEvent
    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        // The shared renderer uses the vanilla entity-translucent block-atlas type. Rendering at
        // AFTER_ENTITIES keeps it inside the normal fabulous entity target/composite lifetime.
        if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_ENTITIES) {
            RENDERER.render(event.getPoseStack(), event.getCamera(), event.getFrustum());
        }
    }

    @SubscribeEvent
    public static void onLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        BuildingPreviewClientState.INSTANCE.clearAll("disconnected");
        RENDERER.clear();
    }
}
