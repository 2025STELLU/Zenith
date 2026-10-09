package net.atomos.zenith.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.atomos.zenith.client.ClientWindState;
import net.atomos.zenith.network.packet.WeatherSnapshotPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.List;

/**
 * 龙卷 3D 特效：32×20 的漏斗 mesh，一次 draw call 画完。
 * 顶点颜色按螺旋函数算出 3 条亮带，相位随时间转——看起来在转，
 * 其实 mesh 没动。走原版 POSITION_COLOR shader，不写 GLSL，不用粒子。
 */
@EventBusSubscriber(value = Dist.CLIENT)
public final class TornadoRenderer {
    private TornadoRenderer() {}

    private static final int RADIAL_SEGS = 32;      // 一圈多少段
    private static final int HEIGHT_SEGS = 20;      // 高度多少层
    private static final float BOTTOM_R = 4.0f;     // 底部半径
    private static final float TOP_R = 16.0f;       // 顶部半径

    private static final int SPIRAL_BANDS = 3;      // 几条螺旋带
    private static final float SPIRAL_TWIST = 2.0f; // 从下到上拧几圈
    private static final float ROT_SPEED = 1.2f;    // 旋转速度（弧度/秒）

    private static final float BASE_R = 0.28f, BASE_G = 0.29f, BASE_B = 0.32f;
    private static final float BAND_R = 0.45f, BAND_G = 0.46f, BAND_B = 0.50f;
    private static final float BASE_ALPHA = 0.35f;
    private static final float BAND_ALPHA = 0.30f;

    private static final float CULL_DIST = 256.0f;  // 超过这个距离不画

    @SubscribeEvent
    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) return;

        var level = Minecraft.getInstance().level;
        if (level == null) return;

        List<WeatherSnapshotPacket.TornadoInfo> tornadoes = ClientWindState.get().tornadoes();
        if (tornadoes.isEmpty()) return;

        Vec3 camPos = event.getCamera().getPosition();
        // 远的先画：半透明混合是顺序相关的，不然钻进漏斗里看会闪
        List<WeatherSnapshotPacket.TornadoInfo> sorted = new ArrayList<>(tornadoes);
        sorted.sort((a, b) -> Double.compare(distSq(b, camPos), distSq(a, camPos)));

        float timeSec = (level.getGameTime() + event.getPartialTick().getGameTimeDeltaTicks()) / 20.0f;

        Minecraft mc = Minecraft.getInstance();
        PoseStack pose = event.getPoseStack();
        VertexConsumer vc = mc.renderBuffers().bufferSource()
                .getBuffer(ClientRenderHelper.FLAT_TRANSLUCENT);

        pose.pushPose();
        pose.translate(-camPos.x, -camPos.y, -camPos.z);

        for (WeatherSnapshotPacket.TornadoInfo t : sorted) {
            double dx = t.x() - camPos.x, dz = t.z() - camPos.z;
            if (dx * dx + dz * dz > CULL_DIST * CULL_DIST) continue;
            if (t.intensity01() < 0.05f) continue;

            double groundY = level.getHeight(Heightmap.Types.MOTION_BLOCKING, (int) t.x(), (int) t.z());
            float height = 60.0f + 40.0f * t.intensity01();   // 60~100 格
            float rotPhase = timeSec * ROT_SPEED * t.spin();

            renderFunnel(pose, vc, (float) t.x(), (float) groundY, (float) t.z(),
                    height, rotPhase, t.intensity01(), camPos);
            renderDustRing(pose, vc, (float) t.x(), (float) groundY, (float) t.z(),
                    rotPhase, t.intensity01());
        }
        pose.popPose();
    }

    private static double distSq(WeatherSnapshotPacket.TornadoInfo t, Vec3 cam) {
        double dx = t.x() - cam.x, dz = t.z() - cam.z;
        return dx * dx + dz * dz;
    }

    /** 漏斗 mesh：圆柱参数化 + 螺旋带颜色，quad 按到相机距离降序发射。 */
    private static void renderFunnel(PoseStack pose, VertexConsumer vc,
                                     float cx, float baseY, float cz,
                                     float height, float rotPhase, float intensity,
                                     Vec3 camPos) {
        float[][] px = new float[HEIGHT_SEGS + 1][RADIAL_SEGS + 1];
        float[][] py = new float[HEIGHT_SEGS + 1][RADIAL_SEGS + 1];
        float[][] pz = new float[HEIGHT_SEGS + 1][RADIAL_SEGS + 1];
        float[][] pr = new float[HEIGHT_SEGS + 1][RADIAL_SEGS + 1];
        float[][] pg = new float[HEIGHT_SEGS + 1][RADIAL_SEGS + 1];
        float[][] pb = new float[HEIGHT_SEGS + 1][RADIAL_SEGS + 1];
        float[][] pa = new float[HEIGHT_SEGS + 1][RADIAL_SEGS + 1];

        for (int j = 0; j <= HEIGHT_SEGS; j++) {
            float frac = (float) j / HEIGHT_SEGS;              // 0=底 1=顶
            float radius = BOTTOM_R + (TOP_R - BOTTOM_R) * frac;
            float y = baseY + frac * height;
            float vertFade = 1.0f - frac * frac * 0.3f;         // 顶部虚化 30%
            float aMul = (0.7f + 0.5f * intensity) * vertFade;

            for (int i = 0; i <= RADIAL_SEGS; i++) {
                float ang = (float) i / RADIAL_SEGS * (float) (Math.PI * 2);
                px[j][i] = cx + (float) Math.cos(ang) * radius;
                py[j][i] = y;
                pz[j][i] = cz + (float) Math.sin(ang) * radius;

                // 螺旋带：角度 + 高度拧转 - 时间旋转
                float spiral = ang + frac * SPIRAL_TWIST * (float) (Math.PI * 2) - rotPhase;
                float bandPhase = spiral / (float) (Math.PI * 2) * SPIRAL_BANDS;
                float bandFrac = bandPhase - (float) Math.floor(bandPhase);
                float band = smoothstep(0.3f, 0.5f, bandFrac)
                        * (1.0f - smoothstep(0.5f, 0.7f, bandFrac));

                pr[j][i] = BASE_R + (BAND_R - BASE_R) * band;
                pg[j][i] = BASE_G + (BAND_G - BASE_G) * band;
                pb[j][i] = BASE_B + (BAND_B - BASE_B) * band;
                pa[j][i] = (BASE_ALPHA + BAND_ALPHA * band) * aMul;
            }
        }

        // quad 按中心到相机距离平方降序（远的先画）
        int quadCount = HEIGHT_SEGS * RADIAL_SEGS;
        float[] quadDepth = new float[quadCount];
        int[] quadOrder = new int[quadCount];
        float camX = (float) camPos.x, camY = (float) camPos.y, camZ = (float) camPos.z;
        for (int q = 0; q < quadCount; q++) {
            int j = q / RADIAL_SEGS, i = q % RADIAL_SEGS;
            float qx = (px[j][i] + px[j + 1][i] + px[j + 1][i + 1] + px[j][i + 1]) * 0.25f - camX;
            float qy = (py[j][i] + py[j + 1][i] + py[j + 1][i + 1] + py[j][i + 1]) * 0.25f - camY;
            float qz = (pz[j][i] + pz[j + 1][i] + pz[j + 1][i + 1] + pz[j][i + 1]) * 0.25f - camZ;
            quadDepth[q] = qx * qx + qy * qy + qz * qz;
            quadOrder[q] = q;
        }
        // 插入排序：640 个 quad，这个量级够用了
        for (int a = 1; a < quadCount; a++) {
            int key = quadOrder[a];
            float keyDepth = quadDepth[key];
            int b = a - 1;
            while (b >= 0 && quadDepth[quadOrder[b]] < keyDepth) {
                quadOrder[b + 1] = quadOrder[b];
                b--;
            }
            quadOrder[b + 1] = key;
        }

        Matrix4f m = pose.last().pose();
        for (int qi = 0; qi < quadCount; qi++) {
            int q = quadOrder[qi];
            int j = q / RADIAL_SEGS, i = q % RADIAL_SEGS;
            vert(vc, m, px[j][i], py[j][i], pz[j][i], pr[j][i], pg[j][i], pb[j][i], pa[j][i]);
            vert(vc, m, px[j + 1][i], py[j + 1][i], pz[j + 1][i], pr[j + 1][i], pg[j + 1][i], pb[j + 1][i], pa[j + 1][i]);
            vert(vc, m, px[j + 1][i + 1], py[j + 1][i + 1], pz[j + 1][i + 1], pr[j + 1][i + 1], pg[j + 1][i + 1], pb[j + 1][i + 1], pa[j + 1][i + 1]);
            vert(vc, m, px[j][i], py[j][i], pz[j][i], pr[j][i], pg[j][i], pb[j][i], pa[j][i]);
            vert(vc, m, px[j + 1][i + 1], py[j + 1][i + 1], pz[j + 1][i + 1], pr[j + 1][i + 1], pg[j + 1][i + 1], pb[j + 1][i + 1], pa[j + 1][i + 1]);
            vert(vc, m, px[j][i + 1], py[j][i + 1], pz[j][i + 1], pr[j][i + 1], pg[j][i + 1], pb[j][i + 1], pa[j][i + 1]);
        }
    }

    /** 地面尘环：扁平圆环，6 条旋转条纹跟漏斗的螺旋带呼应。 */
    private static void renderDustRing(PoseStack pose, VertexConsumer vc,
                                       float cx, float baseY, float cz,
                                       float rotPhase, float intensity) {
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
            vert(vc, m, cx + c0 * innerR, y, cz + s0 * innerR, BASE_R, BASE_G, BASE_B, aInner);
            vert(vc, m, cx + c0 * outerR, y, cz + s0 * outerR, BASE_R, BASE_G, BASE_B, aOuter);
            vert(vc, m, cx + c1 * outerR, y, cz + s1 * outerR, BASE_R, BASE_G, BASE_B, aOuter);
            vert(vc, m, cx + c0 * innerR, y, cz + s0 * innerR, BASE_R, BASE_G, BASE_B, aInner);
            vert(vc, m, cx + c1 * outerR, y, cz + s1 * outerR, BASE_R, BASE_G, BASE_B, aOuter);
            vert(vc, m, cx + c1 * innerR, y, cz + s1 * innerR, BASE_R, BASE_G, BASE_B, aInner);
        }
    }

    private static float smoothstep(float e0, float e1, float x) {
        float t = Math.min(1.0f, Math.max(0.0f, (x - e0) / (e1 - e0)));
        return t * t * (3.0f - 2.0f * t);
    }

    private static void vert(VertexConsumer vc, Matrix4f m,
                             float x, float y, float z,
                             float r, float g, float b, float a) {
        vc.addVertex(m, x, y, z).setColor(r, g, b, a);
    }
}
