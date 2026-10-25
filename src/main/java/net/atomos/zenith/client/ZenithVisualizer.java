package net.atomos.zenith.client;

import net.atomos.zenith.particle.ZenithParticles;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.util.RandomSource;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * 风场可视化调试通道（{@code /zenithc visualizer}）。
 *
 * <p>在玩家周围撒风丝，用来看粗风场（L1）的方向与强度。默认关闭，是调试工具，
 * 不是主视觉——主视觉由 {@link ParticleWindController} 负责。
 *
 * <p><b>CLI-15 修正</b>：
 * <ul>
 *   <li>旧实现固定每 tick 4 个，与风速无关：20 m/s 和 2 m/s 画出来一样密。
 *       现在 {@code count = clamp(1, 12, (int) speed)}，和主通道的密度随速度一起变。</li>
 *   <li>旧实现判据 {@code horizontalLength() < 0.5} 与主通道的 {@code speed > 3.0}
 *       差 6 倍，两条通道"有没有风"的观感完全不一致。现在共用
 *       {@link ParticleWindController#WIND_PARTICLE_THRESHOLD_MPS}。</li>
 *   <li>旧实现采样盒是 {@code 48×16×48}，但 {@code sampleCoarse} 只做 x/z 插值，
 *       竖直方向 4 个粒子拿的是同一层风。现在盒子压到 {@code 48×8×48}
 *       并把采样高度定在玩家眼睛附近 + 少量抖动，明确"这是近地面一层的场"。</li>
 * </ul>
 *
 * <p><b>CLI-12</b>：静态 {@code Random} 改 {@link RandomSource}（不跨世界保留种子）。
 */
@OnlyIn(Dist.CLIENT)
public final class ZenithVisualizer {
    private static final int MAX_STREAKS = 12;
    private static boolean enabled = false;

    private ZenithVisualizer() {}

    public static boolean isEnabled() { return enabled; }
    public static void setEnabled(boolean e) { enabled = e; }
    public static void toggle() { enabled = !enabled; }

    public static void clientTick() {
        if (!enabled) return;
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null || mc.player == null || mc.isPaused()) return;

        double px = mc.player.getX(), py = mc.player.getY(), pz = mc.player.getZ();
        var wind = ClientWindState.get().sampleCoarse(px, py + 1.6, pz);
        double speed = wind.horizontalLength();
        if (speed < ParticleWindController.WIND_PARTICLE_THRESHOLD_MPS) return;

        int count = Math.max(1, Math.min(MAX_STREAKS, (int) speed));
        RandomSource random = level.random;
        for (int i = 0; i < count; i++) {
            double x = px + (random.nextDouble() - 0.5) * 48;
            double y = py + 0.5 + random.nextDouble() * 8;
            double z = pz + (random.nextDouble() - 0.5) * 48;
            var v = ClientWindState.get().sampleCoarse(x, y, z);
            if (v.horizontalLength() < ParticleWindController.WIND_PARTICLE_THRESHOLD_MPS) continue;
            level.addParticle(ZenithParticles.WIND_STREAK.get(), x, y, z, 0, 0, 0);
        }
    }
}
