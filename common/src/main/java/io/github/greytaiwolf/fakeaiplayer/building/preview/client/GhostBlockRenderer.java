package io.github.greytaiwolf.fakeaiplayer.building.preview.client;

import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShapeRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Renders exact baked block-state models through a private translucent buffer.
 *
 * <p>The private buffer is important: a preview frame never ends or flushes Minecraft's global
 * {@link RenderType#lines()} batch. Unknown, non-model, or failing states degrade to their supplied
 * wireframe instead of taking down the client render loop.</p>
 */
public final class GhostBlockRenderer {
    private static final int INITIAL_BUFFER_BYTES = 1_048_576;
    // renderSingleBlock emits the entity/item vertex format (including overlay UVs), so keep the
    // block atlas but route it through the matching translucent entity format.
    private static final RenderType GHOST_TYPE = RenderType.entityTranslucent(InventoryMenu.BLOCK_ATLAS);

    private final ByteBufferBuilder byteBuffer = new ByteBufferBuilder(INITIAL_BUFFER_BYTES);
    private final MultiBufferSource.BufferSource buffers = MultiBufferSource.immediate(byteBuffer);

    /** Releases this renderer after a failed private-buffer upload; the instance must not be reused. */
    void close() {
        byteBuffer.close();
    }

    public void render(PoseStack poseStack,
                       Vec3 camera,
                       List<GhostModel> models,
                       List<Wireframe> wireframes) {
        List<Wireframe> lines = new ArrayList<>(wireframes.size() + 8);
        lines.addAll(wireframes);
        try {
            for (GhostModel model : models) {
                if (!renderModel(poseStack, camera, model)) {
                    lines.add(model.fallback());
                }
            }

            if (!lines.isEmpty()) {
                VertexConsumer lineBuffer = buffers.getBuffer(RenderType.lines());
                for (Wireframe line : lines) {
                    AABB box = new AABB(line.worldPos()).inflate(0.002D).move(
                            -camera.x, -camera.y, -camera.z);
                    Color color = line.color();
                    ShapeRenderer.renderLineBox(
                            poseStack, lineBuffer, box,
                            color.red(), color.green(), color.blue(), color.alpha());
                }
            }
        } finally {
            // This is our private BufferSource. Ending it cannot flush terrain, another mod's
            // debug lines, or a loader-owned world batch.
            buffers.endBatch();
        }
    }

    private boolean renderModel(PoseStack poseStack, Vec3 camera, GhostModel model) {
        BlockState state = model.state();
        if (state == null || state.getRenderShape() != RenderShape.MODEL) {
            return false;
        }

        BlockPos pos = model.worldPos();
        Color color = model.color();
        MultiBufferSource translucent = ignored -> new TintedAlphaVertexConsumer(
                buffers.getBuffer(GHOST_TYPE), color);
        poseStack.pushPose();
        poseStack.translate(
                pos.getX() - camera.x,
                pos.getY() - camera.y,
                pos.getZ() - camera.z);
        try {
            Minecraft.getInstance().getBlockRenderer().renderSingleBlock(
                    state,
                    poseStack,
                    translucent,
                    LightTexture.FULL_BRIGHT,
                    OverlayTexture.NO_OVERLAY);
            return true;
        } catch (RuntimeException exception) {
            return false;
        } finally {
            poseStack.popPose();
        }
    }

    public record GhostModel(BlockPos worldPos, BlockState state, Color color, Wireframe fallback) {
        public GhostModel {
            worldPos = worldPos.immutable();
            if (color == null || fallback == null) {
                throw new IllegalArgumentException("ghost model style is missing");
            }
        }
    }

    public record Wireframe(BlockPos worldPos, Color color) {
        public Wireframe {
            worldPos = worldPos.immutable();
            if (color == null) {
                throw new IllegalArgumentException("wireframe color is missing");
            }
        }
    }

    public record Color(float red, float green, float blue, float alpha) {
        public Color {
            if (!inUnitRange(red) || !inUnitRange(green)
                    || !inUnitRange(blue) || !inUnitRange(alpha)) {
                throw new IllegalArgumentException("preview color is outside 0..1");
            }
        }

        private static boolean inUnitRange(float value) {
            return Float.isFinite(value) && value >= 0.0F && value <= 1.0F;
        }
    }

    private static final class TintedAlphaVertexConsumer implements VertexConsumer {
        private final VertexConsumer delegate;
        private final Color color;

        private TintedAlphaVertexConsumer(VertexConsumer delegate, Color color) {
            this.delegate = delegate;
            this.color = color;
        }

        @Override
        public VertexConsumer addVertex(float x, float y, float z) {
            delegate.addVertex(x, y, z);
            return this;
        }

        @Override
        public VertexConsumer setColor(int red, int green, int blue, int alpha) {
            delegate.setColor(
                    channel(red, color.red()),
                    channel(green, color.green()),
                    channel(blue, color.blue()),
                    channel(alpha, color.alpha()));
            return this;
        }

        @Override
        public VertexConsumer setUv(float u, float v) {
            delegate.setUv(u, v);
            return this;
        }

        @Override
        public VertexConsumer setUv1(int u, int v) {
            delegate.setUv1(u, v);
            return this;
        }

        @Override
        public VertexConsumer setUv2(int u, int v) {
            delegate.setUv2(u, v);
            return this;
        }

        @Override
        public VertexConsumer setNormal(float x, float y, float z) {
            delegate.setNormal(x, y, z);
            return this;
        }

        private static int channel(int value, float multiplier) {
            return Math.max(0, Math.min(255, Math.round(value * multiplier)));
        }
    }
}
