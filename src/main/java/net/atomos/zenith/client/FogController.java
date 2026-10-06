package net.atomos.zenith.client;

import com.mojang.blaze3d.shaders.FogShape;
import net.atomos.zenith.particle.ZenithParticles;
import net.atomos.zenith.weather.FogModel;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.neoforged.neoforge.client.event.ViewportEvent;

import java.util.Random;

/** 客户端雾控制器：按气象条件调雾 + 撒雾气粒子。 */
public final class FogController {
    private static final Random RANDOM = new Random();
    private static int tickCounter = 0;

    private FogController() {}

    /** 相机处雾浓度 0~1，给别的客户端模块查。 */
    public static double currentDensity = 0;

    public static void onComputeFog(ViewportEvent.RenderFog event) {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null || mc.getCameraEntity() == null) return;

        var cam = mc.getCameraEntity();
        double x = cam.getX(), y = cam.getY(), z = cam.getZ();
        var wind = ClientWindState.get().sampleCoarse(x, y, z);
        double windSpeed = Math.hypot(wind.x(), wind.z());

        // 湿度从天气快照取，包没到之前先按 0.6 估
        double humidity = 0.6;
        var snap = ClientWindState.get().snapshot();
        if (snap != null) humidity = snap.humidity01();

        boolean raining = level.isRaining();
        double solar = FogModel.solarFactor(level.getDayTime() / 24000.0);
        boolean overWater = isOverWater(level, (int) x, (int) y, (int) z);

        currentDensity = FogModel.density(solar, raining, windSpeed, humidity, overWater);

        if (currentDensity > 0.02) {
            float far = event.getFarPlaneDistance();
            float near = event.getNearPlaneDistance();
            // 浓雾时能见度可降至约 20 格
            float farScale = (float) (1 - 0.93 * currentDensity);
            event.setFarPlaneDistance(Math.max(16, far * farScale));
            event.setNearPlaneDistance(Math.max(2, near * farScale));
            if (currentDensity > 0.5) {
                event.setFogShape(FogShape.SPHERE);
            }
        }
    }

    private static boolean isOverWater(ClientLevel level, int x, int y, int z) {
        var pos = new net.minecraft.core.BlockPos(x, y - 1, z);
        var state = level.getBlockState(pos);
        return state.getFluidState().is(net.minecraft.tags.FluidTags.WATER);
    }

    public static void clientTick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return;
        tickCounter++;
        if (currentDensity < 0.35 || tickCounter % 6 != 0) return;

        var p = mc.player;
        for (int i = 0; i < 3; i++) {
            double x = p.getX() + (RANDOM.nextDouble() - 0.5) * 36;
            double z = p.getZ() + (RANDOM.nextDouble() - 0.5) * 36;
            double y = p.getY() + RANDOM.nextDouble() * 6 - 1;
            mc.level.addParticle(ZenithParticles.SPRAY.get(), x, y, z, 0.15, 0.02, 0.15);
        }
    }
}
