package net.atomos.zenith.client.render.tornado;

import com.mojang.blaze3d.vertex.VertexConsumer;
import net.atomos.zenith.client.render.ClientRenderHelper;
import net.minecraft.client.Camera;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * 龙卷网格画质（低 / 中 / 高）：贴图壳层 + 云团精灵。
 *
 * <h2>由远到近的五个图层</h2>
 * <ol>
 *   <li><b>云底盘</b>：母体超级单体的云底，中心下沉成墙云，边缘淡出。从下往上看是一整片压下来的暗色天花板，
 *       这是"风暴感"最大的来源，旧版完全没有；</li>
 *   <li><b>漏斗背面</b>：内外两层壳的远侧内壁；</li>
 *   <li><b>远侧云团</b>：比漏斗轴更远的碎屑、漏斗团块、墙云、入流碎云；</li>
 *   <li><b>漏斗正面</b>：两层壳的近侧外壁；</li>
 *   <li><b>近侧云团</b>。</li>
 * </ol>
 * 每层自己由远到近排序，层与层之间 {@code endBatch} 保证叠加顺序。
 *
 * <h2>为什么看上去像云而不是塑料</h2>
 * <ul>
 *   <li><b>贴图 + 滚动 UV</b>：u 随时间转（绕轴旋转），v 随时间往上走（核心上升气流），
 *       内层快、外层慢，两层视差产生"在翻滚"的体积感；</li>
 *   <li><b>边缘透明</b>：alpha ∝ |N·V|，侧面掠射处变薄。真实的烟柱在轮廓处光程最短、最透明，
 *       反过来做就是旧版那种硬边圆锥；</li>
 *   <li><b>团块精灵</b>打碎轮廓：漏斗表面、墙云、地面碎屑都有随时间生灭的软云团；</li>
 *   <li><b>光照</b>：向阳面亮、背阴面暗；逆光时轮廓加一道"银边"（前向散射，美术强化）；
 *       下段混入地表颜色（沙漠是黄的、平原是褐的）；云底在闪电时整片亮起来。</li>
 * </ul>
 */
public final class TornadoMeshRenderer {
    private TornadoMeshRenderer() {}

    public enum Detail {
        LOW(14, 18, 10, 14, 8, 6, false),
        MEDIUM(24, 28, 22, 30, 18, 10, true),
        HIGH(36, 40, 36, 48, 28, 14, true);

        final int rings, segs, funnelPuffs, debrisPuffs, wallPuffs, scudPuffs;
        final boolean doubleDeck;

        Detail(int rings, int segs, int funnelPuffs, int debrisPuffs, int wallPuffs, int scudPuffs,
               boolean doubleDeck) {
            this.rings = rings; this.segs = segs; this.funnelPuffs = funnelPuffs;
            this.debrisPuffs = debrisPuffs; this.wallPuffs = wallPuffs; this.scudPuffs = scudPuffs;
            this.doubleDeck = doubleDeck;
        }

        Detail lower() {
            return this == HIGH ? MEDIUM : LOW;
        }
    }

    // ---- 调色板（线性 0..1）：凝结漏斗偏冷灰，云底偏暗、带一点超级单体的青绿
    private static final float FR = 0.47f, FG = 0.49f, FB = 0.53f;
    private static final float CR = 0.30f, CG = 0.33f, CB = 0.36f;
    private static final float GR = 0.27f, GG = 0.35f, GB = 0.34f;

    private static final QuadList DECK = new QuadList();
    private static final QuadList BACK = new QuadList();
    private static final QuadList FRONT = new QuadList();
    private static final QuadList FAR_PUFFS = new QuadList();
    private static final QuadList NEAR_PUFFS = new QuadList();

    private static final int MAX_RINGS = 37, MAX_SEGS = 41;
    private static final float[][] VX = new float[MAX_RINGS][MAX_SEGS];
    private static final float[][] VY = new float[MAX_RINGS][MAX_SEGS];
    private static final float[][] VZ = new float[MAX_RINGS][MAX_SEGS];
    private static final float[][] NX = new float[MAX_RINGS][MAX_SEGS];
    private static final float[][] NZ = new float[MAX_RINGS][MAX_SEGS];
    private static final float[][] VU = new float[MAX_RINGS][MAX_SEGS];
    private static final float[][] VV = new float[MAX_RINGS][MAX_SEGS];
    private static final float[][] VR = new float[MAX_RINGS][MAX_SEGS];
    private static final float[][] VG = new float[MAX_RINGS][MAX_SEGS];
    private static final float[][] VB = new float[MAX_RINGS][MAX_SEGS];
    private static final float[][] VA = new float[MAX_RINGS][MAX_SEGS];
    private static final float[] RING_ZETA = new float[MAX_RINGS];
    private static final float[] AX = new float[2];

    // 每帧共享
    private static float sunX, sunY, sunZ, daylight;
    private static float camRelX, camRelY, camRelZ;   // 龙卷地面点相对相机
    private static int baseLight, skyLight;
    private static float lx, ly, lz, ux, uy, uz;      // 相机左、上向量

    /** 只画地面碎屑（体积云画质下，漏斗和云底交给体积着色器，碎屑仍用精灵更省）。 */
    public static void renderDebrisOnly(TornadoFrame f, Detail d, ClientLevel level, Camera cam,
                                        MultiBufferSource.BufferSource buffers, Matrix4f m, float pt) {
        setupFrame(f, level, cam, pt);
        FAR_PUFFS.clear();
        NEAR_PUFFS.clear();
        buildDebris(f, d);
        flushPuffs(FAR_PUFFS, buffers, m);
        flushPuffs(NEAR_PUFFS, buffers, m);
    }

    public static void render(TornadoFrame f, Detail d, ClientLevel level, Camera cam,
                              MultiBufferSource.BufferSource buffers, Matrix4f m, float pt) {
        setupFrame(f, level, cam, pt);
        double dist = Math.hypot(camRelX, camRelZ);
        if (dist > 192) d = d.lower();

        DECK.clear(); BACK.clear(); FRONT.clear(); FAR_PUFFS.clear(); NEAR_PUFFS.clear();

        buildDeck(f, d);
        buildFunnel(f, d);
        buildFunnelPuffs(f, d);
        buildDebris(f, d);
        buildWallCloud(f, d);

        flush(DECK, TornadoRenderTypes.DECK, buffers, m);
        flush(BACK, TornadoRenderTypes.FUNNEL, buffers, m);
        flushPuffs(FAR_PUFFS, buffers, m);
        flush(FRONT, TornadoRenderTypes.FUNNEL, buffers, m);
        flushPuffs(NEAR_PUFFS, buffers, m);
    }

    private static void flush(QuadList q, net.minecraft.client.renderer.RenderType type,
                              MultiBufferSource.BufferSource buffers, Matrix4f m) {
        if (q.size() == 0) return;
        q.sortBackToFront();
        VertexConsumer vc = buffers.getBuffer(type);
        q.emit(vc, m);
        buffers.endBatch(type);
    }

    private static void flushPuffs(QuadList q, MultiBufferSource.BufferSource buffers, Matrix4f m) {
        flush(q, TornadoRenderTypes.PUFF, buffers, m);
    }

    private static void setupFrame(TornadoFrame f, ClientLevel level, Camera cam, float pt) {
        Vec3 c = cam.getPosition();
        camRelX = (float) (f.cx - c.x);
        camRelY = (float) (f.groundY - c.y);
        camRelZ = (float) (f.cz - c.z);

        // 太阳方向：timeOfDay 0 = 正午；日出在东（+X）
        float a = level.getTimeOfDay(pt) * (float) (Math.PI * 2);
        sunX = -(float) Math.sin(a);
        sunY = (float) Math.cos(a);
        sunZ = 0.15f; // 稍微偏一点，正午不至于完全没有侧光
        float sl = (float) Math.sqrt(sunX * sunX + sunY * sunY + sunZ * sunZ);
        sunX /= sl; sunY /= sl; sunZ /= sl;
        daylight = TornadoShape.smoothstep(-0.1f, 0.25f, sunY);
        if (sunY < 0) { // 夜里用月亮当主光源，光很弱
            sunX = -sunX; sunY = -sunY; sunZ = -sunZ;
        }

        baseLight = ClientRenderHelper.packedLightAt(level, f.cx, f.groundY + 2.0, f.cz);
        skyLight = LightTexture.pack(0, 15);
        if (f.flash > 0.3f) {
            skyLight = LightTexture.FULL_BRIGHT;
        }

        Vector3f left = cam.getLeftVector(), up = cam.getUpVector();
        lx = left.x(); ly = left.y(); lz = left.z();
        ux = up.x(); uy = up.y(); uz = up.z();
    }

    // ================================================================ 云底盘

    private static void buildDeck(TornadoFrame f, Detail d) {
        float vis = f.vis * TornadoShape.smoothstep(0.0f, 0.25f, f.strength + 0.15f);
        if (vis < 0.01f) return;
        int rings = d == Detail.LOW ? 6 : 9;
        int segs = d == Detail.LOW ? 24 : 40;
        float cloudY = camRelY + f.height;
        float rot = -f.spin * 0.018f * f.time * (float) (Math.PI * 2);
        boolean above = cloudY < 0; // 相机在云底之上
        int layers = d.doubleDeck ? 2 : 1;

        for (int layer = 0; layer < layers; layer++) {
            float yOff = layer * 7f;
            float aMul = layer == 0 ? 0.92f : 0.55f;
            float uvOff = layer * 0.37f;
            float layerRot = rot * (layer == 0 ? 1f : 0.7f);
            for (int k = 0; k < rings; k++) {
                float r0 = f.baseR * (float) Math.pow((float) k / rings, 1.6f);
                float r1 = f.baseR * (float) Math.pow((float) (k + 1) / rings, 1.6f);
                for (int i = 0; i < segs; i++) {
                    float t0 = (float) i / segs * (float) (Math.PI * 2);
                    float t1 = (float) (i + 1) / segs * (float) (Math.PI * 2);
                    int base = DECK.begin(skyLight);
                    deckVertex(f, base, 0, r0, t0, cloudY + yOff, layerRot, uvOff, aMul * vis, above);
                    deckVertex(f, base, 1, r1, t0, cloudY + yOff, layerRot, uvOff, aMul * vis, above);
                    deckVertex(f, base, 2, r1, t1, cloudY + yOff, layerRot, uvOff, aMul * vis, above);
                    deckVertex(f, base, 3, r0, t1, cloudY + yOff, layerRot, uvOff, aMul * vis, above);
                    DECK.end(base);
                }
            }
        }
    }

    private static void deckVertex(TornadoFrame f, int base, int k, float r, float theta, float y,
                                   float rot, float uvOff, float alpha, boolean above) {
        // 云底盘中心对准漏斗顶端（轴线在云底处的偏移）
        float ox = f.axisX(1f), oz = f.axisZ(1f);
        float x = camRelX + ox + (float) Math.cos(theta) * r;
        float z = camRelZ + oz + (float) Math.sin(theta) * r;
        float lumpy = 1.5f * (float) Math.sin(theta * 5 + r * 0.05f + f.time * 0.2f);
        float yy = y - f.baseLoweringAt(r) + lumpy * TornadoShape.smoothstep(0, 40, r);
        float u = (float) Math.cos(theta - rot) * r / 96f + uvOff;
        float v = (float) Math.sin(theta - rot) * r / 96f;
        float edge = 1f - TornadoShape.smoothstep(0.5f * f.baseR, f.baseR, r);
        // 中心最暗、带青绿（雹暴下的绿天），往外过渡到普通暗灰
        float g = 1f - TornadoShape.smoothstep(0f, f.topR * 3f, r);
        float cr = CR + (GR - CR) * g * 0.6f;
        float cg = CG + (GG - CG) * g * 0.6f;
        float cb = CB + (GB - CB) * g * 0.6f;
        // 从下往上看：中心（墙云、雨幕上方）最暗，往外渐亮——母云底的压迫感来自这个明暗梯度
        float shade = above ? 1.9f : (0.62f + 0.38f * (1f - g) * (0.6f + 0.4f * edge));
        shade *= 0.55f + 0.45f * daylight;
        float fl = f.flash * (0.6f + 0.6f * g);
        DECK.vtx(base, k, x, yy, z, u, v,
                cr * shade + fl * 0.85f, cg * shade + fl * 0.85f, cb * shade + fl * 0.95f,
                alpha * edge);
    }

    // ================================================================ 漏斗

    private static void buildFunnel(TornadoFrame f, Detail d) {
        if (f.bottom >= 0.97f || f.vis < 0.01f) return;
        int rings = Math.min(MAX_RINGS - 1, d.rings);
        int segs = Math.min(MAX_SEGS - 1, d.segs);
        // 三层：暗核（细、浓、转得最快）→ 主壳 → 外层薄纱（大一圈、转得慢）。
        // 三层不同速度的视差是"在翻滚"的主要来源；暗核让漏斗中轴有深度，不再像一根灰管子。
        buildShell(f, rings, segs, 0.62f, 0f, 0.95f, 1.35f, 4f, 26f, 0.61f, 0.70f);
        buildShell(f, rings, segs, 1.0f, 0f, 1.0f, 1.0f, 3f, 34f, 0.0f, 0.92f);
        buildShell(f, rings, segs, 1.30f, 1.2f, 0.50f, 0.62f, 2f, 52f, 0.37f, 1.08f);
    }

    private static void buildShell(TornadoFrame f, int rings, int segs, float rMul, float rAdd,
                                   float alphaMul, float rotMul, float uRepeat, float vTile,
                                   float uvOff, float shadeMul) {
        float zb = f.bottom;
        float t = f.time;
        float twist = 0.85f * f.spin;
        float updraft = 0.25f + 0.45f * Math.min(1f, f.strength);

        for (int j = 0; j <= rings; j++) {
            float s = (float) j / rings;
            // 底部加密：漏斗下段变化最快，也是玩家盯着看的地方
            float zeta = zb + (1f - zb) * (float) Math.pow(s, 1.25f);
            RING_ZETA[j] = zeta;
            float r = f.radiusAt(zeta);
            if (zb <= 0f) r = Math.max(r, f.coreR * 0.35f);
            r = r * rMul + rAdd * TornadoShape.smoothstep(zb, zb + 0.15f, zeta);
            f.axisAt(zeta, AX);
            float y = camRelY + zeta * f.height;
            for (int i = 0; i <= segs; i++) {
                float th = (float) i / segs * (float) (Math.PI * 2);
                float lump = 1f
                        + 0.08f * (float) Math.sin(3 * th + 9 * zeta - 1.7f * t * f.spin + f.seed)
                        + 0.05f * (float) Math.sin(7 * th - 13 * zeta + 2.3f * t);
                float c = (float) Math.cos(th), sn = (float) Math.sin(th);
                VX[j][i] = camRelX + AX[0] + c * r * lump;
                VY[j][i] = y;
                VZ[j][i] = camRelZ + AX[1] + sn * r * lump;
                NX[j][i] = c;
                NZ[j][i] = sn;
            }
        }

        // 每个顶点只算一次 UV / 颜色 / alpha（四边形共享顶点，逐角重算要多花 4 倍）
        float bottomFade = zb <= 0f ? 0.03f : 0.10f;
        // 整层刚体旋转：每圈各转各的会让纹理随时间被无限剪切，几分钟后变成水平条纹。
        // 差速旋转的观感交给几层壳之间的速度差和表面云团（每个云团按自己高度的角速度转）。
        float rot = TornadoShape.rotationRate(f.strength, f.spin,
                TornadoShape.radiusFactor(0.5f, f.strength)) * rotMul;
        for (int j = 0; j <= rings; j++) {
            float zeta = RING_ZETA[j];
            float fadeZ = TornadoShape.smoothstep(f.bottom, f.bottom + bottomFade, zeta)
                    // 喇叭口在掠射角下会显出扇形条纹：顶部完全淡出，交给云底盘和墙云
                    * (1f - TornadoShape.smoothstep(0.72f, 0.96f, zeta));
            float v = (zeta * f.height) / vTile - updraft * f.time / vTile * 8f + uvOff;
            for (int i = 0; i <= segs; i++) {
                float x = VX[j][i], y = VY[j][i], z = VZ[j][i];
                float nx = NX[j][i], nz = NZ[j][i];
                // spin=+1（俯视逆时针）时，图案沿 θ 减小方向走 → u 的相位随时间增加
                VU[j][i] = (float) i / segs * uRepeat + twist * zeta + rot * f.time + uvOff;
                VV[j][i] = v;
                // 视线（顶点→相机），相机在原点
                float len = Math.max(1e-3f, (float) Math.sqrt(x * x + y * y + z * z));
                float vx = -x / len, vy = -y / len, vz = -z / len;
                float hl = (float) Math.sqrt(vx * vx + vz * vz);
                float ndv = hl < 1e-4f ? 1f : Math.abs((nx * vx + nz * vz) / hl);
                // 轮廓处变薄：真实烟柱在边缘光程最短、最透明
                float rim = 0.22f + 0.78f * TornadoShape.smoothstep(0.0f, 0.75f, ndv);
                VA[j][i] = alphaMul * rim * f.vis * fadeZ;
                float[] col = colorAt(f, zeta, nx, nz, vx, vy, vz, ndv);
                VR[j][i] = col[0] * shadeMul;
                VG[j][i] = col[1] * shadeMul;
                VB[j][i] = col[2] * shadeMul;
            }
        }

        for (int j = 0; j < rings; j++) {
            int light = RING_ZETA[j] < zb + 0.2f ? baseLight : skyLight;
            for (int i = 0; i < segs; i++) {
                // 质心与外法线，判断这块是正面（看到外壁）还是背面（看到内壁）
                float qx = (VX[j][i] + VX[j + 1][i] + VX[j + 1][i + 1] + VX[j][i + 1]) * 0.25f;
                float qz = (VZ[j][i] + VZ[j + 1][i] + VZ[j + 1][i + 1] + VZ[j][i + 1]) * 0.25f;
                float nx = NX[j][i] + NX[j][i + 1], nz = NZ[j][i] + NZ[j][i + 1];
                boolean back = nx * (-qx) + nz * (-qz) < 0;
                QuadList dst = back ? BACK : FRONT;
                int base = dst.begin(light);
                shellVertex(dst, base, 0, j, i);
                shellVertex(dst, base, 1, j + 1, i);
                shellVertex(dst, base, 2, j + 1, i + 1);
                shellVertex(dst, base, 3, j, i + 1);
                dst.end(base);
            }
        }
    }

    private static void shellVertex(QuadList dst, int base, int k, int j, int i) {
        dst.vtx(base, k, VX[j][i], VY[j][i], VZ[j][i], VU[j][i], VV[j][i],
                VR[j][i], VG[j][i], VB[j][i], VA[j][i]);
    }

    private static final float[] COL = new float[3];

    /** 漏斗某点颜色：基色（下段混碎屑、上段混云底）× 光照 + 逆光银边 + 闪电。 */
    private static float[] colorAt(TornadoFrame f, float zeta, float nx, float nz,
                                   float vx, float vy, float vz, float ndv) {
        float r = FR, g = FG, b = FB;
        // 上段才混云底色：混得太早，漏斗和云底一个颜色，远看就分不出来
        float up = TornadoShape.smoothstep(0.7f, 1.0f, zeta) * 0.45f;
        r += (CR - r) * up; g += (CG - g) * up; b += (CB - b) * up;
        float dust = (1f - TornadoShape.smoothstep(0f, 0.32f, zeta)) * 0.65f * f.debris;
        r += (f.dr - r) * dust; g += (f.dg - g) * dust; b += (f.db - b) * dust;

        float ns = nx * sunX + nz * sunZ;
        float wrap = Math.max(0f, Math.min(1f, (ns + 0.5f) / 1.5f));
        float shade = 0.62f + 0.38f * wrap * daylight;
        shade *= 0.55f + 0.45f * daylight;
        // 逆光：视线方向（-V）与太阳同向时，轮廓处亮起一道银边
        float toward = Math.max(0f, -(vx * sunX + vy * sunY + vz * sunZ));
        float sil = (float) Math.pow(toward, 6) * (1f - ndv) * daylight * 0.55f;
        float fl = f.flash * zeta * zeta * 0.7f;

        COL[0] = r * shade + sil + fl;
        COL[1] = g * shade + sil + fl;
        COL[2] = b * shade + sil * 1.02f + fl * 1.1f;
        return COL;
    }

    // ================================================================ 云团精灵

    private static void puff(QuadList dst, float x, float y, float z, float size, float rot, int tile,
                             float r, float g, float b, float a, int light) {
        if (a < 0.01f || size < 0.2f) return;
        float c = (float) Math.cos(rot) * size, s = (float) Math.sin(rot) * size;
        // 屏幕对齐：左向量 L、上向量 U，在屏幕平面里转 rot
        float ax = lx * c + ux * s, ay = ly * c + uy * s, az = lz * c + uz * s;     // "右"
        float bx = -lx * s + ux * c, by = -ly * s + uy * c, bz = -lz * s + uz * c;  // "上"
        float u0 = (tile & 1) * 0.5f, v0 = (tile >> 1) * 0.5f;
        float u1 = u0 + 0.5f, v1 = v0 + 0.5f;
        int base = dst.begin(light);
        dst.vtx(base, 0, x - ax - bx, y - ay - by, z - az - bz, u1, v1, r, g, b, a);
        dst.vtx(base, 1, x - ax + bx, y - ay + by, z - az + bz, u1, v0, r, g, b, a);
        dst.vtx(base, 2, x + ax + bx, y + ay + by, z + az + bz, u0, v0, r, g, b, a);
        dst.vtx(base, 3, x + ax - bx, y + ay - by, z + az - bz, u0, v1, r, g, b, a);
        dst.end(base);
    }

    /** 比漏斗轴更远的进 FAR，更近的进 NEAR。 */
    private static QuadList side(float x, float z, float axisX, float axisZ) {
        return x * x + z * z > axisX * axisX + axisZ * axisZ ? FAR_PUFFS : NEAR_PUFFS;
    }

    private static void buildFunnelPuffs(TornadoFrame f, Detail d) {
        if (f.bottom >= 0.97f || f.vis < 0.01f) return;
        float zb = f.bottom;
        for (int k = 0; k < d.funnelPuffs; k++) {
            float h1 = TornadoShape.hash01(k, 11), h2 = TornadoShape.hash01(k, 12),
                    h3 = TornadoShape.hash01(k, 13), h4 = TornadoShape.hash01(k, 14),
                    h5 = TornadoShape.hash01(k, 15);
            float life = fract(h1 + f.time * (0.025f + 0.03f * h2));
            // 只在漏斗主体上（不进喇叭口）：喇叭口处半径太大，大团块会堆成"蘑菇云"
            float zeta = zb + (0.86f - zb) * (0.04f + 0.92f * life);
            if (zeta <= zb) continue;
            float r = f.radiusAt(zeta);
            if (r < 0.5f) continue;
            float rf = TornadoShape.radiusFactor(zeta, f.strength);
            float th = 6.2832f * h3 - TornadoShape.rotationRate(f.strength, f.spin, rf) * 6.2832f * f.time;
            float rr = r * (0.85f + 0.3f * h4);
            f.axisAt(zeta, AX);
            float ax = camRelX + AX[0], az = camRelZ + AX[1];
            float x = ax + (float) Math.cos(th) * rr;
            float z = az + (float) Math.sin(th) * rr;
            float y = camRelY + zeta * f.height;
            float size = Math.min(Math.max(2.5f, r * (0.7f + 0.5f * h5)), 6f + f.coreR * 0.7f);
            float a = 0.42f * (float) Math.pow(Math.sin(Math.PI * life), 0.6) * f.vis;
            float[] c = colorAt(f, zeta, (float) Math.cos(th), (float) Math.sin(th), 0, 0, 0, 1f);
            int light = zeta < zb + 0.2f ? baseLight : skyLight;
            puff(side(x, z, ax, az), x, y, z, size, 6.2832f * h2 + f.time * 0.2f * (h4 - 0.5f),
                    k & 3, c[0], c[1], c[2], a, light);
        }
    }

    private static void buildDebris(TornadoFrame f, Detail d) {
        if (f.debris < 0.01f || f.vis < 0.01f) return;
        int n = Math.max(1, Math.round(d.debrisPuffs * (0.4f + 0.6f * f.debris)));
        float sizeScale = (float) Math.sqrt(f.coreR / 12f);
        float axX = camRelX, axZ = camRelZ;
        for (int k = 0; k < n; k++) {
            float h1 = TornadoShape.hash01(k, 21), h2 = TornadoShape.hash01(k, 22),
                    h3 = TornadoShape.hash01(k, 23), h4 = TornadoShape.hash01(k, 24),
                    h5 = TornadoShape.hash01(k, 25), h6 = TornadoShape.hash01(k, 26);
            float p = fract(h2 + f.time * (0.10f + 0.12f * h1));
            float radius = f.coreR * (0.5f + 1.7f * p + 0.5f * h3);
            float hgt = (0.6f + 15f * (float) Math.pow(p, 1.4f)) * (0.6f + 0.5f * Math.min(1f, f.strength));
            float th = 6.2832f * h4 - f.spin * 6.2832f * (0.30f + 0.30f * (1f - p)) * f.time;
            float x = camRelX + (float) Math.cos(th) * radius;
            float z = camRelZ + (float) Math.sin(th) * radius;
            float y = camRelY + hgt;
            float size = (2.5f + 8f * p) * (0.7f + 0.5f * Math.min(1f, f.strength)) * sizeScale;
            float a = 0.62f * (float) Math.pow(Math.sin(Math.PI * p), 0.7) * f.debris * f.vis;
            float shade = (0.72f + 0.28f * p) * (0.8f + 0.25f * h5) * (0.55f + 0.45f * daylight);
            puff(side(x, z, axX, axZ), x, y, z, size, 6.2832f * h6 + f.time * 0.4f * (h3 - 0.5f),
                    k & 3, f.dr * shade, f.dg * shade, f.db * shade, a, baseLight);
        }
    }

    private static void buildWallCloud(TornadoFrame f, Detail d) {
        float vis = f.vis * TornadoShape.smoothstep(0.0f, 0.3f, f.strength + 0.1f);
        if (vis < 0.01f) return;
        float ox = f.axisX(1f), oz = f.axisZ(1f);
        float axX = camRelX + ox, axZ = camRelZ + oz;
        float cloudY = camRelY + f.height;
        float scale = Math.min(1.3f, Math.max(0.7f, f.topR / 30f));
        float shadeDay = 0.55f + 0.45f * daylight;
        float fl = f.flash;

        // 墙云：围着漏斗顶、缓慢旋转的低垂云团
        for (int k = 0; k < d.wallPuffs; k++) {
            float h1 = TornadoShape.hash01(k, 31), h2 = TornadoShape.hash01(k, 32),
                    h3 = TornadoShape.hash01(k, 33), h4 = TornadoShape.hash01(k, 34),
                    h5 = TornadoShape.hash01(k, 35);
            // 贴着云底下表面：精灵是圆的，中心放在云底附近，只露出下半截，看起来就是云底的起伏而不是挂着的棉花球
            float radius = f.topR * (0.9f + 1.4f * h1);
            float th = 6.2832f * h2 - f.spin * 6.2832f * 0.025f * f.time;
            float x = axX + (float) Math.cos(th) * radius;
            float z = axZ + (float) Math.sin(th) * radius;
            float y = cloudY - f.baseLoweringAt(radius) * 0.7f + 2f - 3f * h3;
            float size = (11f + 12f * h4) * scale;
            float s = (0.85f + 0.2f * h5) * shadeDay;
            puff(side(x, z, axX, axZ), x, y, z, size, 6.2832f * h3, k & 3,
                    CR * s * 0.85f + fl * 0.8f, CG * s * 0.85f + fl * 0.8f, CB * s * 0.85f + fl * 0.9f, 0.55f * vis, skyLight);
        }

        // 入流碎云：从外圈被吸向墙云，越靠近转得越快、抬得越高（美术强化，现实中也很典型）
        for (int k = 0; k < d.scudPuffs; k++) {
            float h1 = TornadoShape.hash01(k, 41), h2 = TornadoShape.hash01(k, 42),
                    h3 = TornadoShape.hash01(k, 43);
            float p = fract(h1 + f.time * 0.045f);
            float radius = f.topR * (3.4f - 2.5f * p);
            float th = 6.2832f * h2 - f.spin * 6.2832f * (0.02f + 0.07f * p) * f.time;
            float x = axX + (float) Math.cos(th) * radius;
            float z = axZ + (float) Math.sin(th) * radius;
            float y = cloudY - 14f - 8f * (1f - p) + 10f * p * p;
            float size = (3.5f + 4.5f * h3) * scale;
            float a = 0.5f * (float) Math.sin(Math.PI * p) * vis;
            float s = 1.15f * shadeDay;
            puff(side(x, z, axX, axZ), x, y, z, size, 6.2832f * h3, (k + 1) & 3,
                    CR * s + fl * 0.6f, CG * s + fl * 0.6f, CB * s + fl * 0.7f, a, skyLight);
        }
    }

    private static float fract(float v) {
        return v - (float) Math.floor(v);
    }
}
