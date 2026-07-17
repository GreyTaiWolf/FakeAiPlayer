package io.github.greytaiwolf.fakeaiplayer.building.preview.client;

import com.mojang.blaze3d.vertex.PoseStack;
import io.github.greytaiwolf.fakeaiplayer.building.preview.client.GhostBlockRenderer.Color;
import io.github.greytaiwolf.fakeaiplayer.building.preview.client.GhostBlockRenderer.GhostModel;
import io.github.greytaiwolf.fakeaiplayer.building.preview.client.GhostBlockRenderer.Wireframe;
import io.github.greytaiwolf.fakeaiplayer.building.preview.client.PreviewCellClassifier.Kind;
import io.github.greytaiwolf.fakeaiplayer.building.preview.client.PreviewRenderBudget.Cost;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Shared Fabric/NeoForge world-preview renderer; loader classes only provide the render event. */
public final class BuildingPreviewWorldRenderer {
    private static final double MAX_RENDER_DISTANCE_SQUARED = 192.0D * 192.0D;

    private static final Color MISSING_MODEL = new Color(0.64F, 0.94F, 1.00F, 0.46F);
    private static final Color TEMPORARY_MODEL = new Color(0.72F, 0.98F, 1.00F, 0.34F);
    private static final Color MISSING_LINE = new Color(0.18F, 0.76F, 0.95F, 0.92F);
    private static final Color TEMPORARY_LINE = new Color(0.35F, 0.88F, 1.00F, 0.78F);
    private static final Color WRONG_STATE = new Color(1.00F, 0.56F, 0.06F, 0.95F);
    private static final Color WRONG_BLOCK = new Color(1.00F, 0.18F, 0.20F, 0.95F);
    private static final Color CLEAR_CONFLICT = new Color(1.00F, 0.20F, 0.92F, 0.95F);
    private static final Color PRESERVE_CONFLICT = new Color(0.62F, 0.22F, 0.90F, 0.95F);

    private final ResolvedPreviewPalette palette = new ResolvedPreviewPalette();
    private final PreviewSectionIndex sectionIndex = new PreviewSectionIndex();
    private GhostBlockRenderer ghostRenderer;

    public void render(PoseStack poseStack, Camera camera, Frustum frustum) {
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        if (level == null || minecraft.player == null || poseStack == null || camera == null) {
            return;
        }
        BuildingPreviewClientState.Snapshot preview =
                BuildingPreviewClientState.INSTANCE.active().orElse(null);
        if (preview == null
                || !level.dimension().location().toString().equals(preview.dimension())) {
            // Keep the reviewed snapshot while temporarily visiting another dimension. It becomes
            // visible again on return and remains bound to the server-side confirmation session.
            return;
        }

        List<ResolvedPreviewPalette.Entry> resolvedPalette = palette.resolve(preview);
        Vec3 cameraPosition = camera.getPosition();
        List<GhostModel> models = new ArrayList<>();
        List<Wireframe> lines = new ArrayList<>();
        PreviewRenderBudget budget = new PreviewRenderBudget();

        scan:
        for (PreviewSectionIndex.Section section : sectionIndex.orderedSections(preview, cameraPosition)) {
            if (distanceToBoxSquared(cameraPosition, section.bounds()) > MAX_RENDER_DISTANCE_SQUARED
                    || frustum != null && !frustum.isVisible(section.bounds())) {
                continue;
            }
            for (PreviewSectionIndex.IndexedCell indexed : section.cells()) {
                if (!budget.tryScan()) {
                    break scan;
                }
                BlockPos worldPos = indexed.worldPos();
                if (distanceToCenterSquared(cameraPosition, worldPos) > MAX_RENDER_DISTANCE_SQUARED
                        || !level.hasChunkAt(worldPos)) {
                    continue;
                }
                AABB worldBox = new AABB(worldPos).inflate(0.002D);
                if (frustum != null && !frustum.isVisible(worldBox)) {
                    continue;
                }

                int paletteIndex = indexed.cell().paletteIndex();
                if (paletteIndex < 0 || paletteIndex >= resolvedPalette.size()) {
                    // Atomic client assembly already rejects this. Keep the renderer defensive in
                    // case a future protocol revision supplies a malformed cached snapshot.
                    continue;
                }
                ResolvedPreviewPalette.Entry expected = resolvedPalette.get(paletteIndex);
                BlockState actual = level.getBlockState(worldPos);
                Kind kind = PreviewCellClassifier.classify(
                        actual,
                        expected,
                        indexed.cell().operation(),
                        indexed.cell().replacePolicy());
                if (kind == Kind.HIDDEN) {
                    continue;
                }

                if (kind.wantsGhostModel() && expected.resolved()) {
                    Color modelColor = kind == Kind.TEMPORARY ? TEMPORARY_MODEL : MISSING_MODEL;
                    Color fallbackColor = kind == Kind.TEMPORARY ? TEMPORARY_LINE : MISSING_LINE;
                    if (budget.tryRender(Cost.MODEL)) {
                        Wireframe fallback = new Wireframe(worldPos, fallbackColor);
                        models.add(new GhostModel(worldPos, expected.state(), modelColor, fallback));
                    } else if (budget.tryRender(Cost.LINE)) {
                        lines.add(new Wireframe(worldPos, fallbackColor));
                    }
                    continue;
                }

                if (budget.tryRender(Cost.LINE)) {
                    lines.add(new Wireframe(worldPos, lineColor(kind)));
                }
            }
        }

        if (!models.isEmpty() || !lines.isEmpty()) {
            GhostBlockRenderer renderer = rendererOrNull();
            if (renderer == null) {
                return;
            }
            poseStack.pushPose();
            try {
                renderer.render(poseStack, cameraPosition, models, lines);
            } catch (RuntimeException ignored) {
                // A private-buffer upload or a third-party baked model must not break the client
                // render loop forever. Drop all derived caches and replace the possibly poisoned
                // buffer without logging once per frame.
                resetAfterRenderFailure();
            } finally {
                poseStack.popPose();
            }
        }
    }

    public void clear() {
        palette.clear();
        sectionIndex.clear();
        GhostBlockRenderer active = ghostRenderer;
        ghostRenderer = null;
        try {
            if (active != null) {
                active.close();
            }
        } catch (RuntimeException ignored) {
            // Disconnect cleanup remains fail-closed even after a graphics-driver failure.
        }
    }

    private void resetAfterRenderFailure() {
        palette.clear();
        sectionIndex.clear();
        GhostBlockRenderer failed = ghostRenderer;
        ghostRenderer = null;
        try {
            if (failed != null) {
                failed.close();
            }
        } catch (RuntimeException ignored) {
            // The old private native buffer is abandoned either way.
        }
    }

    private GhostBlockRenderer rendererOrNull() {
        if (ghostRenderer == null) {
            try {
                ghostRenderer = new GhostBlockRenderer();
            } catch (RuntimeException ignored) {
                // Native-buffer allocation can be retried on a later frame without a log storm.
                return null;
            }
        }
        return ghostRenderer;
    }

    private static Color lineColor(Kind kind) {
        return switch (kind) {
            case MISSING -> MISSING_LINE;
            case TEMPORARY -> TEMPORARY_LINE;
            case WRONG_STATE -> WRONG_STATE;
            case WRONG_BLOCK -> WRONG_BLOCK;
            case CLEAR_CONFLICT -> CLEAR_CONFLICT;
            case PRESERVE_CONFLICT -> PRESERVE_CONFLICT;
            case HIDDEN -> throw new IllegalArgumentException("hidden preview cell has no line color");
        };
    }

    private static double distanceToCenterSquared(Vec3 point, BlockPos pos) {
        double dx = point.x - (pos.getX() + 0.5D);
        double dy = point.y - (pos.getY() + 0.5D);
        double dz = point.z - (pos.getZ() + 0.5D);
        return dx * dx + dy * dy + dz * dz;
    }

    private static double distanceToBoxSquared(Vec3 point, AABB box) {
        double dx = axisDistance(point.x, box.minX, box.maxX);
        double dy = axisDistance(point.y, box.minY, box.maxY);
        double dz = axisDistance(point.z, box.minZ, box.maxZ);
        return dx * dx + dy * dy + dz * dz;
    }

    private static double axisDistance(double value, double min, double max) {
        if (value < min) {
            return min - value;
        }
        return value > max ? value - max : 0.0D;
    }
}
