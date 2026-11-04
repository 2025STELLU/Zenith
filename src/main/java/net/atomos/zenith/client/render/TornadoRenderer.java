package net.atomos.zenith.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import net.atomos.zenith.ZenithConfig;
import net.atomos.zenith.client.render.tornado.TornadoFrame;
import net.atomos.zenith.client.render.tornado.TornadoMeshRenderer;
import net.atomos.zenith.client.render.tornado.TornadoTracker;
import net.atomos.zenith.client.render.tornado.TornadoVolumeRenderer;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.List;

/**
 * 龙卷渲染入口（0.1.6 重写）。按画质分派：
 * <ul>
 *   <li>{@code OFF}：不画；</li>
 *   <li>{@code LOW / MEDIUM / HIGH}：贴图壳层 + 云团精灵 + 云底盘（{@link TornadoMeshRenderer}），默认 HIGH；</li>
 *   <li>{@code VOLUMETRIC}：漏斗、云底、碎屑交给光线步进着色器（{@link TornadoVolumeRenderer}），
 *       地面碎屑精灵仍用网格画（便宜且观感好）。着色器不可用或检测到光影包时自动退回 HIGH。</li>
 * </ul>
 *
 * <p>龙卷的位置、强度、生灭来自 {@link TornadoTracker}（客户端平滑），外形公式在
 * {@link net.atomos.zenith.client.render.tornado.TornadoShape}，两种画质共用。
 */
public final class TornadoRenderer {
    private TornadoRenderer() {}

    private static final TornadoFrame[] FRAMES = {new TornadoFrame(), new TornadoFrame(), new TornadoFrame(), new TornadoFrame()};
    private static final List<TornadoTracker.Tracked> SORTED = new ArrayList<>();

    /** 由 {@code ZenithClientSetup} 注册到 {@code NeoForge.EVENT_BUS}。 */
    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        RenderLevelStageEvent.Stage stage = event.getStage();
        boolean translucent = stage == RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS;
        boolean afterLevel = stage == RenderLevelStageEvent.Stage.AFTER_LEVEL;
        if (!translucent && !afterLevel) return;

        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null) return;
        ZenithConfig.TornadoQuality quality = ZenithConfig.tornadoQuality();
        if (quality == ZenithConfig.TornadoQuality.OFF) return;

        boolean volumetric = quality == ZenithConfig.TornadoQuality.VOLUMETRIC && TornadoVolumeRenderer.available();
        if (translucent) TornadoVolumeRenderer.captureFog();
        if (afterLevel && !volumetric) return;

        List<TornadoTracker.Tracked> list = TornadoTracker.tornadoes();
        if (list.isEmpty()) return;

        Camera cam = event.getCamera();
        Vec3 cp = cam.getPosition();
        float pt = event.getPartialTick().getGameTimeDeltaPartialTick(false);
        // 动画时间：每 2 小时回绕一次，免得 float 精度在长存档里让动画抖动
        double ticks = (level.getGameTime() % 144000L) + pt;
        float timeSec = (float) (ticks / 20.0);

        double maxDist = Math.max(256.0, mc.options.getEffectiveRenderDistance() * 16.0 + 96.0);
        SORTED.clear();
        for (TornadoTracker.Tracked t : list) {
            double dx = t.x(pt) - cp.x, dz = t.z(pt) - cp.z;
            if (dx * dx + dz * dz <= maxDist * maxDist && t.intensity(pt) > 0.005f) SORTED.add(t);
        }
        if (SORTED.isEmpty()) return;
        // 先按由近到远排序，只保留最近的 FRAMES.length 个；绘制时再倒过来由远到近（近的后画）
        SORTED.sort((a, b) -> Double.compare(d2(a, cp, pt), d2(b, cp, pt)));

        float time = timeSec;
        int n = Math.min(SORTED.size(), FRAMES.length);

        if (afterLevel) {
            if (!TornadoVolumeRenderer.beginFrame()) return;
            int steps = ZenithConfig.volumetricSteps();
            boolean fine = ZenithConfig.volumetricFineLighting();
            for (int i = n - 1; i >= 0; i--) {
                TornadoFrame f = FRAMES[i];
                f.compute(SORTED.get(i), level, pt, time);
                TornadoVolumeRenderer.render(f, level, cam, event.getProjectionMatrix(), pt, steps, fine);
            }
            return;
        }

        // 半透明阶段：网格（或体积画质下的碎屑精灵）
        PoseStack pose = event.getPoseStack();
        Matrix4f m = pose == null ? new Matrix4f() : pose.last().pose();
        var buffers = mc.renderBuffers().bufferSource();
        TornadoMeshRenderer.Detail detail = switch (quality) {
            case LOW -> TornadoMeshRenderer.Detail.LOW;
            case MEDIUM -> TornadoMeshRenderer.Detail.MEDIUM;
            default -> TornadoMeshRenderer.Detail.HIGH;
        };
        for (int i = n - 1; i >= 0; i--) {
            TornadoFrame f = FRAMES[i];
            f.compute(SORTED.get(i), level, pt, time);
            if (volumetric) {
                TornadoMeshRenderer.renderDebrisOnly(f, detail, level, cam, buffers, m, pt);
            } else {
                TornadoMeshRenderer.render(f, detail, level, cam, buffers, m, pt);
            }
        }
    }

    private static double d2(TornadoTracker.Tracked t, Vec3 cp, float pt) {
        double dx = t.x(pt) - cp.x, dz = t.z(pt) - cp.z;
        return dx * dx + dz * dz;
    }
}
