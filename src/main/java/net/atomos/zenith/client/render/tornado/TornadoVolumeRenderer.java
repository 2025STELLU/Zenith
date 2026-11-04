package net.atomos.zenith.client.render.tornado;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.slf4j.Logger;

import javax.annotation.Nullable;

/**
 * "体积云"画质：每个龙卷画一个代理圆柱盒，片元着色器里做光线步进（{@code tornado_volume.fsh}）。
 *
 * <h2>渲染时机与深度</h2>
 * 在 {@code AFTER_LEVEL} 画（整个世界画完、手还没画），此时主帧缓冲已经是最终画面（含 Fabulous 合成）。
 * 着色器需要场景深度来让地形挡住龙卷：先把主帧缓冲深度 blit 到自己的 {@link TextureTarget}，
 * 再当纹理采样（直接采样正在绑定的深度附件是未定义行为）。
 *
 * <h2>每像素只着色一次</h2>
 * 深度测试关掉（遮挡在着色器里按深度纹理自己判），CPU 只提交<b>背向相机</b>的盒面：
 * 相机在盒外时是 3 个面，在盒内时 6 个面全是背面，覆盖率都恰好一次。
 *
 * <h2>雾</h2>
 * 到 {@code AFTER_LEVEL} 时原版已经 {@code setupNoFog()}，所以雾参数由
 * {@link #captureFog()} 在半透明阶段先抓下来。
 *
 * <h2>失败保护</h2>
 * 着色器没加载、光影包在用、深度拷贝出错，都返回 false，调用方退回网格画质。
 */
public final class TornadoVolumeRenderer {
    private TornadoVolumeRenderer() {}

    private static final Logger LOG = LogUtils.getLogger();

    @Nullable private static TextureTarget depthCopy;
    private static boolean failed;
    private static int frame;

    private static float fogStart = 0, fogEnd = 1e5f, fogShape;
    private static final float[] FOG_COLOR = {0.5f, 0.5f, 0.5f, 0f};

    /** 半透明阶段调用：记下这一帧的雾。 */
    public static void captureFog() {
        fogStart = RenderSystem.getShaderFogStart();
        fogEnd = RenderSystem.getShaderFogEnd();
        fogShape = RenderSystem.getShaderFogShape().getIndex();
        float[] c = RenderSystem.getShaderFogColor();
        System.arraycopy(c, 0, FOG_COLOR, 0, 4);
    }

    public static boolean available() {
        return !failed && TornadoShaders.volumeShader() != null;
    }

    /** 准备深度拷贝，每帧调用一次（不管有几个龙卷）。 */
    public static boolean beginFrame() {
        if (!available()) return false;
        try {
            RenderTarget main = Minecraft.getInstance().getMainRenderTarget();
            if (depthCopy == null || depthCopy.width != main.width || depthCopy.height != main.height
                    || depthCopy.isStencilEnabled() != main.isStencilEnabled()) {
                if (depthCopy != null) depthCopy.destroyBuffers();
                depthCopy = new TextureTarget(main.width, main.height, true, Minecraft.ON_OSX);
                if (main.isStencilEnabled()) depthCopy.enableStencil();
            }
            depthCopy.copyDepthFrom(main);
            main.bindWrite(false);
            frame++;
            return true;
        } catch (RuntimeException e) {
            failed = true;
            LOG.warn("[Zenith] volumetric tornado disabled: depth copy failed", e);
            return false;
        }
    }

    public static void render(TornadoFrame f, ClientLevel level, Camera cam, Matrix4f projection,
                              float pt, int maxSteps, boolean fineLight) {
        ShaderInstance sh = TornadoShaders.volumeShader();
        if (sh == null || depthCopy == null) return;
        Vec3 c = cam.getPosition();
        float rx = (float) (f.cx - c.x), ry = (float) (f.groundY - c.y), rz = (float) (f.cz - c.z);

        Matrix4f view = new Matrix4f().rotation(cam.rotation().conjugate(new Quaternionf()));

        // 太阳 / 月亮方向，同网格渲染器
        float a = level.getTimeOfDay(pt) * (float) (Math.PI * 2);
        float sx = -(float) Math.sin(a), sy = (float) Math.cos(a), sz = 0.15f;
        float daylight = TornadoShape.smoothstep(-0.1f, 0.25f, sy);
        if (sy < 0) { sx = -sx; sy = -sy; sz = -sz; }
        // 环境亮度：跟着原版天空变暗（夜晚、下雨、雷暴），再给月光留一点底
        float ambient = Math.max(0.08f, level.getSkyDarken(pt)) * (f.flash > 0 ? 1.0f + f.flash : 1.0f);
        float sunStrength = daylight * Math.max(0.35f, 1f - level.getRainLevel(pt) * 0.4f);

        Minecraft mc = Minecraft.getInstance();
        sh.safeGetUniform("ZViewMat").set(view);
        sh.safeGetUniform("ZProjMat").set(projection);
        sh.safeGetUniform("ZScreen").set((float) mc.getMainRenderTarget().width, (float) mc.getMainRenderTarget().height);
        sh.safeGetUniform("ZCenter").set(rx, ry, rz);
        sh.safeGetUniform("ZShape").set(f.coreR, f.strength, f.height, f.spin);
        sh.safeGetUniform("ZAnim").set(f.time, f.rope, f.debris, f.flash);
        sh.safeGetUniform("ZAxis").set(f.tiltX, f.tiltZ, f.wobble, f.seed);
        sh.safeGetUniform("ZDeck").set(f.topR, f.baseR, f.lowering, f.vis);
        sh.safeGetUniform("ZSun").set(sx, sy, sz, sunStrength);
        sh.safeGetUniform("ZDebris").set(f.dr, f.dg, f.db, ambient);
        sh.safeGetUniform("ZFog").set(fogStart, fogEnd, fogShape, 0f);
        sh.safeGetUniform("ZFogColor").set(FOG_COLOR[0], FOG_COLOR[1], FOG_COLOR[2], FOG_COLOR[3]);
        sh.safeGetUniform("ZQuality").set((float) maxSteps, fineLight ? 1f : 0f, (float) (frame & 1023), 0f);

        // 代理盒：与着色器里的包围圆柱一致（略大）
        float r = f.baseR + Math.abs(f.tiltX) * f.height + Math.abs(f.wobble) * 2f + 6f;
        float x0 = rx - r, x1 = rx + r, z0 = rz - r, z1 = rz + r;
        float y0 = ry - 4f, y1 = ry + f.height + 26f;

        RenderSystem.setShader(() -> sh);
        RenderSystem.setShaderTexture(0, depthCopy.getDepthTextureId());
        RenderSystem.enableBlend();
        RenderSystem.blendFuncSeparate(GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA,
                GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA);
        RenderSystem.disableDepthTest();
        RenderSystem.depthMask(false);
        RenderSystem.disableCull();

        BufferBuilder bb = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION);
        // 相机在原点：某个面"背向相机" ⇔ 相机在该面所在平面的内侧
        if (0 > x1 || 0 < x0 || 0 > y1 || 0 < y0 || 0 > z1 || 0 < z0) {
            // 相机在盒外：只画背面
            if (0 < x1) face(bb, x1, y0, z0, x1, y1, z0, x1, y1, z1, x1, y0, z1); // +X 面，相机在其内侧
            if (0 > x0) face(bb, x0, y0, z0, x0, y0, z1, x0, y1, z1, x0, y1, z0);
            if (0 < y1) face(bb, x0, y1, z0, x0, y1, z1, x1, y1, z1, x1, y1, z0);
            if (0 > y0) face(bb, x0, y0, z0, x1, y0, z0, x1, y0, z1, x0, y0, z1);
            if (0 < z1) face(bb, x0, y0, z1, x1, y0, z1, x1, y1, z1, x0, y1, z1);
            if (0 > z0) face(bb, x0, y0, z0, x0, y1, z0, x1, y1, z0, x1, y0, z0);
        } else {
            // 相机在盒内：六个面全是背面
            face(bb, x1, y0, z0, x1, y1, z0, x1, y1, z1, x1, y0, z1);
            face(bb, x0, y0, z0, x0, y0, z1, x0, y1, z1, x0, y1, z0);
            face(bb, x0, y1, z0, x0, y1, z1, x1, y1, z1, x1, y1, z0);
            face(bb, x0, y0, z0, x1, y0, z0, x1, y0, z1, x0, y0, z1);
            face(bb, x0, y0, z1, x1, y0, z1, x1, y1, z1, x0, y1, z1);
            face(bb, x0, y0, z0, x0, y1, z0, x1, y1, z0, x1, y0, z0);
        }
        MeshData mesh = bb.build();
        if (mesh != null) BufferUploader.drawWithShader(mesh);

        RenderSystem.enableDepthTest();
        RenderSystem.depthMask(true);
        RenderSystem.enableCull();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableBlend();
    }

    private static void face(BufferBuilder bb, float ax, float ay, float az, float bx, float by, float bz,
                             float cx, float cy, float cz, float dx, float dy, float dz) {
        bb.addVertex(ax, ay, az);
        bb.addVertex(bx, by, bz);
        bb.addVertex(cx, cy, cz);
        bb.addVertex(dx, dy, dz);
    }
}
