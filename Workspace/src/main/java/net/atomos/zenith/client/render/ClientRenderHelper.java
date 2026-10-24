package net.atomos.zenith.client.render;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockAndTintGetter;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import org.joml.Matrix4f;

/**
 * 客户端渲染小工具：纯色几何（POSITION_COLOR_LIGHTMAP，无贴图、受光照）。
 *
 * <p><b>CLI-09</b>：旧实现用 {@code POSITION_COLOR} + {@code NO_LIGHTMAP}，
 * 于是帆船、风向标、龙卷漏斗在深夜与洞穴里都是**自发光**的纯色块，和周围方块
 * 完全脱节。现在改用 {@code POSITION_COLOR_LIGHTMAP} + {@link RenderStateShard#LIGHTMAP}：
 * 顶点里带 {@code uv2} 的打包光照，shader 会按原版光照贴图乘一遍颜色。
 *
 * <p>代价是每个顶点都要知道自己的打包光照。为了不引入每顶点一次
 * {@code getLightColor} 的开销（那要走一遍区块查询），这里提供两种用法：
 * <ul>
 *   <li>整块几何共用一个光照值（船/风向标/一个龙卷）：调用方算一次传进来；</li>
 *   <li>不传时退回"全亮"（{@code 0x00F000F0}），等价于旧行为，用于确实该发光的调试几何。</li>
 * </ul>
 */
@OnlyIn(Dist.CLIENT)
public final class ClientRenderHelper {
    private ClientRenderHelper() {}

    /** 全亮打包光照：旧 {@code NO_LIGHTMAP} 的等价物，仅在确实需要自发光时用。 */
    public static final int FULL_BRIGHT = 0x00F000F0;

    /** 纯色 RenderType（双面、受光照）。 */
    public static final RenderType FLAT_COLOR = RenderType.create(
            "zenith_flat_color",
            DefaultVertexFormat.POSITION_COLOR_LIGHTMAP, VertexFormat.Mode.TRIANGLES, 256,
            false, false,
            RenderType.CompositeState.builder()
                    .setShaderState(RenderStateShard.POSITION_COLOR_LIGHTMAP_SHADER)
                    .setTransparencyState(RenderStateShard.NO_TRANSPARENCY)
                    .setCullState(RenderStateShard.NO_CULL)
                    .setLightmapState(RenderStateShard.LIGHTMAP)
                    .setOverlayState(RenderStateShard.NO_OVERLAY)
                    .createCompositeState(false));

    /** 纯色半透明 RenderType（双面、受光照，深度测试开、不写深度）——龙卷漏斗 mesh 用。 */
    public static final RenderType FLAT_TRANSLUCENT = RenderType.create(
            "zenith_flat_translucent",
            DefaultVertexFormat.POSITION_COLOR_LIGHTMAP, VertexFormat.Mode.TRIANGLES, 8192,
            false, false,
            RenderType.CompositeState.builder()
                    .setShaderState(RenderStateShard.POSITION_COLOR_LIGHTMAP_SHADER)
                    .setTransparencyState(RenderStateShard.TRANSLUCENT_TRANSPARENCY)
                    .setCullState(RenderStateShard.NO_CULL)
                    .setLightmapState(RenderStateShard.LIGHTMAP)
                    .setOverlayState(RenderStateShard.NO_OVERLAY)
                    .setDepthTestState(RenderStateShard.LEQUAL_DEPTH_TEST)
                    // 半透明不写深度，不然跟水、玻璃、云交叉时排序全错
                    .setWriteMaskState(RenderStateShard.COLOR_WRITE)
                    .createCompositeState(false));

    /**
     * 取某个方块位置处的打包光照（含天空光 + 方块光）。
     * 只该在渲染线程调用，且每个物体调一次即可。
     */
    public static int packedLightAt(BlockAndTintGetter level, double x, double y, double z) {
        return LevelRenderer.getLightColor(level, BlockPos.containing(x, y, z));
    }

    // ---------------------------------------------------------------- 不发光照的旧签名
    // 保留给"确实不该被光照影响"的调试几何；正常实体/方块渲染请传 light。

    public static void renderBox(PoseStack pose, VertexConsumer vc,
                                 float x0, float y0, float z0, float x1, float y1, float z1,
                                 float r, float g, float b, float a) {
        renderBox(pose, vc, x0, y0, z0, x1, y1, z1, r, g, b, a, FULL_BRIGHT);
    }

    public static void renderBox(PoseStack pose, VertexConsumer vc,
                                 float x0, float y0, float z0, float x1, float y1, float z1,
                                 float r, float g, float b, float a, int packedLight) {
        Matrix4f m = pose.last().pose();
        quad(vc, m, x0, y0, z1, x1, y0, z1, x1, y1, z1, x0, y1, z1, r, g, b, a, packedLight);
        quad(vc, m, x1, y0, z0, x0, y0, z0, x0, y1, z0, x1, y1, z0, r, g, b, a, packedLight);
        quad(vc, m, x1, y0, z1, x1, y0, z0, x1, y1, z0, x1, y1, z1, r, g, b, a, packedLight);
        quad(vc, m, x0, y0, z0, x0, y0, z1, x0, y1, z1, x0, y1, z0, r, g, b, a, packedLight);
        quad(vc, m, x0, y1, z1, x1, y1, z1, x1, y1, z0, x0, y1, z0, r, g, b, a, packedLight);
        quad(vc, m, x0, y0, z0, x1, y0, z0, x1, y0, z1, x0, y0, z1, r, g, b, a, packedLight);
    }

    /** 渲染双面三角形（帆）。 */
    public static void renderTriangle(PoseStack pose, VertexConsumer vc,
                                      float ax, float ay, float az,
                                      float bx, float by, float bz,
                                      float cx, float cy, float cz,
                                      float r, float g, float b, float a) {
        renderTriangle(pose, vc, ax, ay, az, bx, by, bz, cx, cy, cz, r, g, b, a, FULL_BRIGHT);
    }

    /** 渲染双面三角形（帆），带打包光照。 */
    public static void renderTriangle(PoseStack pose, VertexConsumer vc,
                                      float ax, float ay, float az,
                                      float bx, float by, float bz,
                                      float cx, float cy, float cz,
                                      float r, float g, float b, float a, int packedLight) {
        Matrix4f m = pose.last().pose();
        tri(vc, m, ax, ay, az, bx, by, bz, cx, cy, cz, r, g, b, a, packedLight);
        tri(vc, m, ax, ay, az, cx, cy, cz, bx, by, bz, r, g, b, a, packedLight);
    }

    private static void quad(VertexConsumer vc, Matrix4f m,
                             float ax, float ay, float az, float bx, float by, float bz,
                             float cx, float cy, float cz, float dx, float dy, float dz,
                             float r, float g, float b, float a, int light) {
        tri(vc, m, ax, ay, az, bx, by, bz, cx, cy, cz, r, g, b, a, light);
        tri(vc, m, ax, ay, az, cx, cy, cz, dx, dy, dz, r, g, b, a, light);
    }

    private static void tri(VertexConsumer vc, Matrix4f m,
                            float ax, float ay, float az, float bx, float by, float bz,
                            float cx, float cy, float cz,
                            float r, float g, float b, float a, int light) {
        int lu = light & 0xFFFF;
        int lv = (light >> 16) & 0xFFFF;
        vc.addVertex(m, ax, ay, az).setColor(r, g, b, a).setUv2(lu, lv);
        vc.addVertex(m, bx, by, bz).setColor(r, g, b, a).setUv2(lu, lv);
        vc.addVertex(m, cx, cy, cz).setColor(r, g, b, a).setUv2(lu, lv);
    }
}
