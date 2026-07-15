package io.github.greytaiwolf.fakeaiplayer.platform.fabric.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import io.github.greytaiwolf.fakeaiplayer.building.plan.BlockStateResolver;
import io.github.greytaiwolf.fakeaiplayer.building.plan.BlockStateSpec;
import io.github.greytaiwolf.fakeaiplayer.building.plan.CellOperation;
import io.github.greytaiwolf.fakeaiplayer.building.plan.ReplacePolicy;
import io.github.greytaiwolf.fakeaiplayer.building.preview.client.BuildingPreviewClientState;
import io.github.greytaiwolf.fakeaiplayer.network.payload.BuildingPreviewChunkS2C;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShapeRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Fabric world renderer for the immutable, server-issued building projection. */
public final class FabricBuildingPreviewRenderer {
    private static final double MAX_RENDER_DISTANCE_SQUARED = 192.0D * 192.0D;
    private static UUID cachedSession;
    private static int cachedRevision = -1;
    private static String cachedPreviewHash = "";
    private static List<ResolvedPaletteState> resolvedPalette = List.of();

    private FabricBuildingPreviewRenderer() {
    }

    static void register() {
        WorldRenderEvents.AFTER_ENTITIES.register(FabricBuildingPreviewRenderer::render);
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> clearState());
    }

    private static void render(WorldRenderContext context) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || minecraft.player == null) {
            return;
        }
        BuildingPreviewClientState.Snapshot preview =
                BuildingPreviewClientState.INSTANCE.active().orElse(null);
        if (preview == null) {
            return;
        }
        refreshPalette(preview);
        String currentDimension = minecraft.level.dimension().location().toString();
        if (!currentDimension.equals(preview.dimension())) {
            // Preserve the reviewed snapshot while temporarily visiting another dimension. It is
            // hidden here and becomes visible again on return, matching the server-side session.
            return;
        }

        MultiBufferSource buffers = context.consumers();
        Camera camera = context.camera();
        PoseStack poseStack = context.matrixStack();
        if (buffers == null || camera == null || poseStack == null) {
            return;
        }
        VertexConsumer lines = buffers.getBuffer(RenderType.lines());
        Vec3 cameraPosition = camera.getPosition();
        for (BuildingPreviewChunkS2C.Cell cell : preview.cells()) {
            BlockPos worldPos = preview.anchor().offset(cell.dx(), cell.dy(), cell.dz());
            if (minecraft.player.distanceToSqr(Vec3.atCenterOf(worldPos)) > MAX_RENDER_DISTANCE_SQUARED) {
                continue;
            }
            if (!minecraft.level.hasChunkAt(worldPos)) {
                // Do not render an unloaded cell as apparent air. Confirmation performs the same
                // loaded-chunk check against the authoritative server snapshot.
                continue;
            }
            AABB worldBox = new AABB(worldPos).inflate(0.002D);
            if (context.frustum() != null && !context.frustum().isVisible(worldBox)) {
                continue;
            }
            PreviewColor color = classify(
                    minecraft.level.getBlockState(worldPos),
                    resolvedPalette.get(cell.paletteIndex()),
                    cell.operation(),
                    cell.replacePolicy());
            if (color == null) {
                continue;
            }
            AABB cameraRelativeBox = worldBox.move(
                    -cameraPosition.x, -cameraPosition.y, -cameraPosition.z);
            ShapeRenderer.renderLineBox(
                    poseStack, lines, cameraRelativeBox,
                    color.red(), color.green(), color.blue(), color.alpha());
        }
    }

    private static void clearState() {
        BuildingPreviewClientState.INSTANCE.clearAll("disconnected");
        cachedSession = null;
        cachedRevision = -1;
        cachedPreviewHash = "";
        resolvedPalette = List.of();
    }

    private static void refreshPalette(BuildingPreviewClientState.Snapshot preview) {
        if (preview.sessionId().equals(cachedSession)
                && preview.transformRevision() == cachedRevision
                && preview.previewHash().equals(cachedPreviewHash)) {
            return;
        }
        List<ResolvedPaletteState> resolved = new ArrayList<>(preview.palette().size());
        for (BlockStateSpec spec : preview.palette()) {
            try {
                resolved.add(new ResolvedPaletteState(spec, BlockStateResolver.resolve(spec)));
            } catch (IllegalArgumentException exception) {
                resolved.add(new ResolvedPaletteState(spec, null));
            }
        }
        resolvedPalette = List.copyOf(resolved);
        cachedSession = preview.sessionId();
        cachedRevision = preview.transformRevision();
        cachedPreviewHash = preview.previewHash();
    }

    private static PreviewColor classify(BlockState actual,
                                         ResolvedPaletteState expected,
                                         CellOperation operation,
                                         ReplacePolicy replacePolicy) {
        if (operation == CellOperation.CLEAR) {
            return actual.isAir() ? null : PreviewColor.CLEAR_CONFLICT;
        }
        boolean matches = expected.state() != null
                && actual.is(expected.state().getBlock())
                && BlockStateResolver.matchesProperties(actual, expected.spec().properties());
        if (operation == CellOperation.PRESERVE) {
            return matches ? null : PreviewColor.PRESERVE_CONFLICT;
        }
        if (matches) {
            return null;
        }
        if (expected.state() != null && actual.is(expected.state().getBlock())) {
            return PreviewColor.WRONG_STATE;
        }
        boolean executableDestination = switch (replacePolicy) {
            case REQUIRE_EMPTY -> actual.isAir();
            // The server executor accepts only replaceable cells here. Solid natural terrain still
            // requires an explicit CLEAR cell and remains a red conflict.
            case REPLACE_REPLACEABLE, REPLACE_NATURAL -> actual.isAir()
                    || actual.canBeReplaced() && actual.getFluidState().isEmpty();
            case CLEAR_AUTHORIZED, PRESERVE_EXISTING, FORCE_AUTHORIZED -> false;
        };
        if (!executableDestination) {
            return PreviewColor.WRONG_BLOCK;
        }
        return operation == CellOperation.TEMPORARY
                ? PreviewColor.TEMPORARY
                : PreviewColor.MISSING;
    }

    private record ResolvedPaletteState(BlockStateSpec spec, BlockState state) {
    }

    private record PreviewColor(float red, float green, float blue, float alpha) {
        private static final PreviewColor MISSING = new PreviewColor(0.18F, 0.76F, 0.95F, 0.92F);
        private static final PreviewColor WRONG_STATE = new PreviewColor(1.00F, 0.56F, 0.06F, 0.95F);
        private static final PreviewColor WRONG_BLOCK = new PreviewColor(1.00F, 0.18F, 0.20F, 0.95F);
        private static final PreviewColor CLEAR_CONFLICT = new PreviewColor(1.00F, 0.20F, 0.92F, 0.95F);
        private static final PreviewColor PRESERVE_CONFLICT = new PreviewColor(0.62F, 0.22F, 0.90F, 0.95F);
        private static final PreviewColor TEMPORARY = new PreviewColor(0.35F, 0.88F, 1.00F, 0.78F);
    }
}
