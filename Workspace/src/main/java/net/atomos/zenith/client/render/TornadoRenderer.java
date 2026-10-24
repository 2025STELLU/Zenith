package net.atomos.zenith.client.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.shaders.FogShape;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.atomos.zenith.client.ClientWindState;
import net.atomos.zenith.network.packet.WeatherSnapshotPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.List;

/**
 * 龙卷漏斗：32×20 的三角网格，一次 draw call 画完。
 *
 * <p><b>CLI-14 修正</b>：
 * <ul>
 *   <li>以前固定 {@code 32 × 20 = 640 quad}，{@code CULL_DIST = 256} 内**全部**提交，
 *       且每个顶点都去查 {@code RenderSystem} 的雾参数。现在按距离分三档 LOD：
 *       <ul>
 *         <li>≤ 64 格：20 层 × 32 段（全精度）</li>
 *         <li>≤ 128 格：12 层 × 20 段</li>
 *         <li>≤ 256 格：8 层 × 12 段</li>
 *       </ul>
 *       64 格外的漏斗在屏幕上不到 60 像素高，12 段已经看不出棱角。</li>
 *   <li>雾参数每帧只取一次（{@code FogParams}），不再每顶点 4 次 JNI 调用。</li>
 *   <li>不再自带 {@code @EventBusSubscriber}，改由 {@code ZenithClientSetup} 在
 *       {@code FMLClientSetupEvent} 里注册到 {@code NeoForge.EVENT_BUS}
 *       ——"世界里的临时几何体"由客户端初始化统一接线，找入口只需看一个文件。</li>
 *   <li>顶点/深度缓冲改成可复用的静态 scratch（渲染线程单线程访问），
 *       每帧不再分配 7 个二维数组。</li>
 * </ul>
 *
 * <p>mesh 用的是 POSITION_COLOR shader，不需要 GLSL 与贴图。
 */
public final class TornadoRenderer {
    private TornadoRenderer() {}

    /** LOD 档：距离上限（格）→ (层数, 段数)。 */
    private static final float LOD_NEAR_DIST = 64.0f;
    private static final float LOD_MID_DIST = 128.0f;
    private static final float LOD_FAR_DIST = 256.0f;
    private static final int NEAR_HEIGHT_SEGS = 20, NEAR_RADIAL_SEGS = 32;
    private static final int MID_HEIGHT_SEGS = 12, MID_RADIAL_SEGS = 20;
    private static final int FAR_HEIGHT_SEGS = 8, FAR_RADIAL_SEGS = 12;

    private static final float BOTTOM_R = 4.0f;     // 底部半径
    private static final float TOP_R = 16.0f;       // 顶部半径

    private static final int SPIRAL_BANDS = 3;      // 螺旋带数量
    private static final float SPIRAL_TWIST = 2.0f; // 螺旋扭转圈数
    private static final float ROT_SPEED = 1.2f;    // 旋转速度（圈/秒）

    private static final float BASE_R = 0.28f, BASE_G = 0.29f, BASE_B = 0.32f;
    private static final float BAND_R = 0.45f, BAND_G = 0.46f, BAND_B = 0.50f;
    private static final float BASE_ALPHA = 0.35f;
    private static final float BAND_ALPHA = 0.30f;

    /** 最大档的网格尺寸；scratch 只分配一次。 */
    private static final int MAX_H = NEAR_HEIGHT_SEGS + 1;
    private static final int MAX_R = NEAR_RADIAL_SEGS + 1;
    private static final float[][] PX = new float[MAX_H][MAX_R];
    private static final float[][] PY = new float[MAX_H][MAX_R];
    private static final float[][] PZ = new float[MAX_H][MAX_R];
    private static final float[][] CR = new float[MAX_H][MAX_R];
    private static final float[][] CG = new float[MAX_H][MAX_R];
    private static final float[][] CB = new float[MAX_H][MAX_R];
    private static final float[][] CA = new float[MAX_H][MAX_R];
    private static final float[] QUAD_DEPTH = new float[NEAR_HEIGHT_SEGS * NEAR_RADIAL_SEGS];
    private static final int[] QUAD_ORDER = new int[NEAR_HEIGHT_SEGS * NEAR_RADIAL_SEGS];

    /** 一帧的雾参数：每个顶点都要用，但没必要每顶点查一次 RenderSystem。 */
    private record FogParams(float start, float end, float span, float r, float g, float b,
                             boolean cylinder) {}

    /** 由 {@code ZenithClientSetup} 注册到 {@code NeoForge.EVENT_BUS}。 */
    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) return;

        var level = Minecraft.getInstance().level;
        if (level == null) return;

        List<WeatherSnapshotPacket.TornadoInfo> tornadoes = ClientWindState.get().tornadoes();
        if (tornadoes.isEmpty()) return;

        Vec3 camPos = event.getCamera().getPosition();
        // 由远到近排：近的后画，透明叠加顺序更自然
        List<WeatherSnapshotPacket.TornadoInfo> sorted = new ArrayList<>(tornadoes);
        sorted.sort((a, b) -> Double.compare(distSq(b, camPos), distSq(a, camPos)));

        float timeSec = (level.getGameTime() + event.getPartialTick().getGameTimeDeltaTicks()) / 20.0f;
        FogParams fog = captureFog(camPos);

        Minecraft mc = Minecraft.getInstance();
        PoseStack pose = event.getPoseStack();
        var buffers = mc.renderBuffers().bufferSource();
        VertexConsumer vc = buffers.getBuffer(ClientRenderHelper.FLAT_TRANSLUCENT);

        pose.pushPose();
        pose.translate(-camPos.x, -camPos.y, -camPos.z);

        for (WeatherSnapshotPacket.TornadoInfo t : sorted) {
            double dx = t.x() - camPos.x, dz = t.z() - camPos.z;
            double d2 = dx * dx + dz * dz;
            if (d2 > LOD_FAR_DIST * LOD_FAR_DIST) continue;
            if (t.intensity01() < 0.05f) continue;

            int heightSegs, radialSegs;
            if (d2 <= LOD_NEAR_DIST * LOD_NEAR_DIST) {
                heightSegs = NEAR_HEIGHT_SEGS; radialSegs = NEAR_RADIAL_SEGS;
            } else if (d2 <= LOD_MID_DIST * LOD_MID_DIST) {
                heightSegs = MID_HEIGHT_SEGS; radialSegs = MID_RADIAL_SEGS;
            } else {
                heightSegs = FAR_HEIGHT_SEGS; radialSegs = FAR_RADIAL_SEGS;
            }

            double groundY = level.getHeight(Heightmap.Types.MOTION_BLOCKING, (int) t.x(), (int) t.z());
            float height = 60.0f + 40.0f * t.intensity01();   // 60~100 格
            float rotPhase = timeSec * ROT_SPEED * t.spin();

            renderFunnel(pose, vc, (float) t.x(), (float) groundY, (float) t.z(),
                    height, rotPhase, t.intensity01(), fog, heightSegs, radialSegs);
            renderDustRing(pose, vc, (float) t.x(), (float) groundY, (float) t.z(),
                    rotPhase, t.intensity01(), fog);
        }
        pose.popPose();
        // 这是世界空间的临时几何体，必须在自己的阶段内 flush
        buffers.endBatch(ClientRenderHelper.FLAT_TRANSLUCENT);
    }

    private static FogParams captureFog(Vec3 camPos) {
        float[] fc = RenderSystem.getShaderFogColor();
        return new FogParams(RenderSystem.getShaderFogStart(), RenderSystem.getShaderFogEnd(),
                RenderSystem.getShaderFogEnd() - RenderSystem.getShaderFogStart(),
                fc[0], fc[1], fc[2],
                RenderSystem.getShaderFogShape() == FogShape.CYLINDER);
    }

    private static double distSq(WeatherSnapshotPacket.TornadoInfo t, Vec3 cam) {
        double dx = t.x() - cam.x, dz = t.z() - cam.z;
        return dx * dx + dz * dz;
    }

    /** 漏斗 mesh：先建顶点，再按到相机的距离从远到近提交 quad。 */
    private static void renderFunnel(PoseStack pose, VertexConsumer vc,
                                     float cx, float baseY, float cz,
                                     float height, float rotPhase, float intensity,
                                     FogParams fog, int heightSegs, int radialSegs) {
        for (int j = 0; j <= heightSegs; j++) {
            float frac = (float) j / heightSegs;               // 0=底 1=顶
            float radius = BOTTOM_R + (TOP_R - BOTTOM_R) * frac;
            float y = baseY + frac * height;
            float vertFade = 1.0f - frac * frac * 0.3f;         // 顶部淡出 30%
            float aMul = (0.7f + 0.5f * intensity) * vertFade;

            for (int i = 0; i <= radialSegs; i++) {
                float ang = (float) i / radialSegs * (float) (Math.PI * 2);
                PX[j][i] = cx + (float) Math.cos(ang) * radius;
                PY[j][i] = y;
                PZ[j][i] = cz + (float) Math.sin(ang) * radius;

                // 螺旋带：角度随高度扭 + 随时间转
                float spiral = ang + frac * SPIRAL_TWIST * (float) (Math.PI * 2) - rotPhase;
                float bandPhase = spiral / (float) (Math.PI * 2) * SPIRAL_BANDS;
                float bandFrac = bandPhase - (float) Math.floor(bandPhase);
                float band = smoothstep(0.3f, 0.5f, bandFrac)
                        * (1.0f - smoothstep(0.5f, 0.7f, bandFrac));

                CR[j][i] = BASE_R + (BAND_R - BASE_R) * band;
                CG[j][i] = BASE_G + (BAND_G - BASE_G) * band;
                CB[j][i] = BASE_B + (BAND_B - BASE_B) * band;
                CA[j][i] = (BASE_ALPHA + BAND_ALPHA * band) * aMul;
            }
        }

        // quad 排序：远的先画（透明体后画的覆盖先画的）。
        // pose 已把相机平移到原点，所以顶点坐标本身就是"相机相对坐标"。
        int quadCount = heightSegs * radialSegs;
        for (int q = 0; q < quadCount; q++) {
            int j = q / radialSegs, i = q % radialSegs;
            float qx = (PX[j][i] + PX[j + 1][i] + PX[j + 1][i + 1] + PX[j][i + 1]) * 0.25f;
            float qy = (PY[j][i] + PY[j + 1][i] + PY[j + 1][i + 1] + PY[j][i + 1]) * 0.25f;
            float qz = (PZ[j][i] + PZ[j + 1][i] + PZ[j + 1][i + 1] + PZ[j][i + 1]) * 0.25f;
            QUAD_DEPTH[q] = qx * qx + qy * qy + qz * qz;
            QUAD_ORDER[q] = q;
        }
        // 插入排序：最多 640 个 quad，几乎已经接近有序
        for (int a = 1; a < quadCount; a++) {
            int key = QUAD_ORDER[a];
            float keyDepth = QUAD_DEPTH[key];
            int b = a - 1;
            while (b >= 0 && QUAD_DEPTH[QUAD_ORDER[b]] < keyDepth) {
                QUAD_ORDER[b + 1] = QUAD_ORDER[b];
                b--;
            }
            QUAD_ORDER[b + 1] = key;
        }

        Matrix4f m = pose.last().pose();
        for (int qi = 0; qi < quadCount; qi++) {
            int q = QUAD_ORDER[qi];
            int j = q / radialSegs, i = q % radialSegs;
            vert(vc, m, PX[j][i], PY[j][i], PZ[j][i], CR[j][i], CG[j][i], CB[j][i], CA[j][i], fog);
            vert(vc, m, PX[j + 1][i], PY[j + 1][i], PZ[j + 1][i],
                    CR[j + 1][i], CG[j + 1][i], CB[j + 1][i], CA[j + 1][i], fog);
            vert(vc, m, PX[j + 1][i + 1], PY[j + 1][i + 1], PZ[j + 1][i + 1],
                    CR[j + 1][i + 1], CG[j + 1][i + 1], CB[j + 1][i + 1], CA[j + 1][i + 1], fog);
            vert(vc, m, PX[j][i], PY[j][i], PZ[j][i], CR[j][i], CG[j][i], CB[j][i], CA[j][i], fog);
            vert(vc, m, PX[j + 1][i + 1], PY[j + 1][i + 1], PZ[j + 1][i + 1],
                    CR[j + 1][i + 1], CG[j + 1][i + 1], CB[j + 1][i + 1], CA[j + 1][i + 1], fog);
            vert(vc, m, PX[j][i + 1], PY[j][i + 1], PZ[j][i + 1],
                    CR[j][i + 1], CG[j][i + 1], CB[j][i + 1], CA[j][i + 1], fog);
        }
    }

    /** 地面尘环：6 条旋转条纹。 */
    private static void renderDustRing(PoseStack pose, VertexConsumer vc,
                                       float cx, float baseY, float cz,
                                       float rotPhase, float intensity, FogParams fog) {
        int segs = 24;
        float innerR = 5.0f, outerR = 14.0f;
        float y = baseY + 0.5f;
        float aScale = 0.5f + 0.5f * intensity;
        Matrix4f m = pose.last().pose();

        for (int i = 0; i < segs; i++) {
            float a0 = (float) i / segs * (float) (Math.PI * 2);
            float a1 = (float) (i + 1) / segs * (float) (Math.PI * 2);
            float mid = (a0 + a1) * 0.5f;
            float stripePhase = (mid + rotPhase) / (float) (Math.PI * 2) * 6.0f;
            float stripeFrac = stripePhase - (float) Math.floor(stripePhase);
            float stripe = smoothstep(0.25f, 0.45f, stripeFrac)
                    * (1.0f - smoothstep(0.55f, 0.75f, stripeFrac));
            float glow = 0.35f + 0.65f * stripe;
            float aInner = 0.40f * glow * aScale;
            float aOuter = 0.15f * glow * aScale;

            float c0 = (float) Math.cos(a0), s0 = (float) Math.sin(a0);
            float c1 = (float) Math.cos(a1), s1 = (float) Math.sin(a1);
            vert(vc, m, cx + c0 * innerR, y, cz + s0 * innerR, BASE_R, BASE_G, BASE_B, aInner, fog);
            vert(vc, m, cx + c0 * outerR, y, cz + s0 * outerR, BASE_R, BASE_G, BASE_B, aOuter, fog);
            vert(vc, m, cx + c1 * outerR, y, cz + s1 * outerR, BASE_R, BASE_G, BASE_B, aOuter, fog);
            vert(vc, m, cx + c0 * innerR, y, cz + s0 * innerR, BASE_R, BASE_G, BASE_B, aInner, fog);
            vert(vc, m, cx + c1 * outerR, y, cz + s1 * outerR, BASE_R, BASE_G, BASE_B, aOuter, fog);
            vert(vc, m, cx + c1 * innerR, y, cz + s1 * innerR, BASE_R, BASE_G, BASE_B, aInner, fog);
        }
    }

    private static float smoothstep(float e0, float e1, float x) {
        float t = Math.min(1.0f, Math.max(0.0f, (x - e0) / (e1 - e0)));
        return t * t * (3.0f - 2.0f * t);
    }

    private static void vert(VertexConsumer vc, Matrix4f m,
                             float x, float y, float z,
                             float r, float g, float b, float a,
                             FogParams fog) {
        // POSITION_COLOR 格式不带法线，雾只能自己算
        float dx = x, dy = y, dz = z; // pose 已经把相机平移到原点
        float dist;
        if (fog.cylinder()) {
            dist = Math.max((float) Math.hypot(dx, dz), Math.abs(dy));
        } else {
            dist = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        }
        float f = 0.0f;
        if (fog.span() > 1e-3f) {
            f = Mth.clamp((dist - fog.start()) / fog.span(), 0.0f, 1.0f);
        }
        if (f > 0.0f) {
            r = Mth.lerp(f, r, fog.r());
            g = Mth.lerp(f, g, fog.g());
            b = Mth.lerp(f, b, fog.b());
            a *= 1.0f - f * 0.8f;
        }
        vc.addVertex(m, x, y, z).setColor(r, g, b, a);
    }
}
