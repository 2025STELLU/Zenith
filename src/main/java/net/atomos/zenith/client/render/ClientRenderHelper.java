package net.atomos.zenith.client.render;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import org.joml.Matrix4f;

/** 客户端渲染小工具：纯色几何（POSITION_COLOR，无贴图）。 */
@OnlyIn(Dist.CLIENT)
public final class ClientRenderHelper {
    private ClientRenderHelper() {}

    /** 纯色无光照 RenderType（双面）。 */
    public static final RenderType FLAT_COLOR = RenderType.create(
            "zenith_flat_color",
            DefaultVertexFormat.POSITION_COLOR, VertexFormat.Mode.TRIANGLES, 256,
            false, false,
            RenderType.CompositeState.builder()
                    .setShaderState(RenderStateShard.POSITION_COLOR_SHADER)
                    .setTransparencyState(RenderStateShard.NO_TRANSPARENCY)
                    .setCullState(RenderStateShard.NO_CULL)
                    .setLightmapState(RenderStateShard.NO_LIGHTMAP)
                    .setOverlayState(RenderStateShard.NO_OVERLAY)
                    .createCompositeState(false));

    /** 渲染轴对齐纯色立方体。 */
    public static void renderBox(PoseStack pose, VertexConsumer vc,
                                 float x0, float y0, float z0, float x1, float y1, float z1,
                                 float r, float g, float b, float a) {
        Matrix4f m = pose.last().pose();
        quad(vc, m, x0, y0, z1, x1, y0, z1, x1, y1, z1, x0, y1, z1, r, g, b, a);
        quad(vc, m, x1, y0, z0, x0, y0, z0, x0, y1, z0, x1, y1, z0, r, g, b, a);
        quad(vc, m, x1, y0, z1, x1, y0, z0, x1, y1, z0, x1, y1, z1, r, g, b, a);
        quad(vc, m, x0, y0, z0, x0, y0, z1, x0, y1, z1, x0, y1, z0, r, g, b, a);
        quad(vc, m, x0, y1, z1, x1, y1, z1, x1, y1, z0, x0, y1, z0, r, g, b, a);
        quad(vc, m, x0, y0, z0, x1, y0, z0, x1, y0, z1, x0, y0, z1, r, g, b, a);
    }

    private static void quad(VertexConsumer vc, Matrix4f m,
                             float ax, float ay, float az, float bx, float by, float bz,
                             float cx, float cy, float cz, float dx, float dy, float dz,
                             float r, float g, float b, float a) {
        tri(vc, m, ax, ay, az, bx, by, bz, cx, cy, cz, r, g, b, a);
        tri(vc, m, ax, ay, az, cx, cy, cz, dx, dy, dz, r, g, b, a);
    }

    private static void tri(VertexConsumer vc, Matrix4f m,
                            float ax, float ay, float az, float bx, float by, float bz,
                            float cx, float cy, float cz,
                            float r, float g, float b, float a) {
        vc.addVertex(m, ax, ay, az).setColor(r, g, b, a);
        vc.addVertex(m, bx, by, bz).setColor(r, g, b, a);
        vc.addVertex(m, cx, cy, cz).setColor(r, g, b, a);
    }

    /** 渲染双面三角形（帆）。 */
    public static void renderTriangle(PoseStack pose, VertexConsumer vc,
                                      float ax, float ay, float az,
                                      float bx, float by, float bz,
                                      float cx, float cy, float cz,
                                      float r, float g, float b, float a) {
        Matrix4f m = pose.last().pose();
        tri(vc, m, ax, ay, az, bx, by, bz, cx, cy, cz, r, g, b, a);
        tri(vc, m, ax, ay, az, cx, cy, cz, bx, by, bz, r, g, b, a);
    }
}
