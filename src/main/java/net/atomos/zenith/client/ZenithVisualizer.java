package net.atomos.zenith.client;

import net.atomos.zenith.particle.ZenithParticles;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import java.util.Random;

/**
 * 风场可视化（调试）：开启后在玩家周围播撒流线粒子，
 * 沿 coarse 风场平流，直观显示风向与风速。
 */
@OnlyIn(Dist.CLIENT)
public final class ZenithVisualizer {
    private static final Random RANDOM = new Random();
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
        // 在 5×5×3 网格上播种
        for (int i = 0; i < 4; i++) {
            double x = px + (RANDOM.nextDouble() - 0.5) * 48;
            double y = py + (RANDOM.nextDouble() - 0.5) * 16;
            double z = pz + (RANDOM.nextDouble() - 0.5) * 48;
            var v = ClientWindState.get().sampleCoarse(x, y, z);
            if (v.horizontalLength() < 0.5) continue;
            level.addParticle(ZenithParticles.WIND_STREAK.get(), x, y, z, 0, 0, 0);
        }
    }
}
