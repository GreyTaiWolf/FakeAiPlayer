package io.github.greytaiwolf.fakeaiplayer.platform.fabric.client;

import io.github.greytaiwolf.fakeaiplayer.building.preview.client.BuildingPreviewClientState;
import io.github.greytaiwolf.fakeaiplayer.building.preview.client.BuildingPreviewWorldRenderer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;

/** Fabric event adapter for the shared baked-model building projection renderer. */
public final class FabricBuildingPreviewRenderer {
    private static final BuildingPreviewWorldRenderer RENDERER = new BuildingPreviewWorldRenderer();

    private FabricBuildingPreviewRenderer() {
    }

    static void register() {
        WorldRenderEvents.AFTER_ENTITIES.register(FabricBuildingPreviewRenderer::render);
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> clearState());
    }

    private static void render(WorldRenderContext context) {
        RENDERER.render(context.matrixStack(), context.camera(), context.frustum());
    }

    private static void clearState() {
        BuildingPreviewClientState.INSTANCE.clearAll("disconnected");
        RENDERER.clear();
    }
}
