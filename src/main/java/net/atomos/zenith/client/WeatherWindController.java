package net.atomos.zenith.client;

import net.atomos.zenith.particle.ZenithParticles;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import java.util.Random;

/**
 * 让降水"看得见风"：下雨时撒随风倾斜的雨丝，雷暴/台风天雨丝更密更斜再加点水沫。
 * 原版雨的渲染管线动起来代价太大，这里只叠加自定义粒子，原版天气逻辑不动。
 */
@OnlyIn(Dist.CLIENT)
public final class WeatherWindController {
    private static final Random RANDOM = new Random();
    private static int tickCounter = 0;

    private WeatherWindController() {}

    public static void clientTick() {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null || mc.player == null || mc.isPaused()) return;
        if (!level.isRaining()) return;
        if (++tickCounter % 2 != 0) return;

        double px = mc.player.getX(), py = mc.player.getY(), pz = mc.player.getZ();
        boolean thundering = level.isThundering();
        var snap = ClientWindState.get().snapshot();
        boolean typhoonNear = snap != null && !snap.typhoons().isEmpty();

        int count = thundering || typhoonNear ? 10 : 4;
        for (int i = 0; i < count; i++) {
            double x = px + (RANDOM.nextDouble() - 0.5) * 40;
            double z = pz + (RANDOM.nextDouble() - 0.5) * 40;
            double y = py + 8 + RANDOM.nextDouble() * 10;
            // 雨丝：用 spray 粒子冒充雨滴，初速带上风和下落分量
            var wind = ClientWindState.get().sampleCoarse(x, y, z);
            double fallSpeed = thundering ? 2.2 : 1.6;
            level.addParticle(ZenithParticles.SPRAY.get(), x, y, z,
                    wind.x() * 0.35, -fallSpeed, wind.z() * 0.35);
        }

        // 冰雹：快照中有高雹强风暴单体在附近
        if (snap != null && tickCounter % 4 == 0) {
            for (var storm : snap.storms()) {
                if (storm.hail01() < 0.5) continue;
                double dx = storm.x() - px, dz = storm.z() - pz;
                if (dx * dx + dz * dz > storm.radiusBlocks() * storm.radiusBlocks() * 4) continue;
                for (int i = 0; i < 4; i++) {
                    double x = px + (RANDOM.nextDouble() - 0.5) * 30;
                    double z = pz + (RANDOM.nextDouble() - 0.5) * 30;
                    double y = py + 10 + RANDOM.nextDouble() * 8;
                    var wind = ClientWindState.get().sampleCoarse(x, y, z);
                    level.addParticle(ZenithParticles.HAIL.get(), x, y, z,
                            wind.x() * 0.2, 0, wind.z() * 0.2);
                }
            }
        }
    }
}
