package net.atomos.zenith.client;

import net.atomos.zenith.particle.ZenithParticles;
import net.atomos.zenith.weather.FogModel;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.material.FogType;
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
        // 水下/岩浆/粉雪的雾是原版的，不碰
        if (event.getType() != FogType.NONE) return;
        var cam = event.getCamera().getEntity();
        // 失明/黑暗效果自带雾，覆盖了就是帮倒忙
        if (cam instanceof LivingEntity le
                && (le.hasEffect(MobEffects.BLINDNESS) || le.hasEffect(MobEffects.DARKNESS))) return;

        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null) return;

        var wind = ClientWindState.get().sampleCoarse(cam.getX(), cam.getY(), cam.getZ());
        double windSpeed = Math.hypot(wind.x(), wind.z());

        // 湿度从天气快照取，包没到之前先按 0.6 估
        double humidity = 0.6;
        var snap = ClientWindState.get().snapshot();
        if (snap != null) humidity = snap.humidity01();

        boolean raining = level.isRaining();
        double solar = FogModel.solarFactor(level.getDayTime() / 24000.0);
        boolean overWater = isOverWater(level, (int) cam.getX(), (int) cam.getY(), (int) cam.getZ());

        currentDensity = FogModel.density(solar, raining, windSpeed, humidity, overWater);
        if (currentDensity < 0.02) return;

        // 浓雾时能见度压到约 24 格；1.21.1 不 cancel 的话改了也白改
        float far = Mth.lerp((float) currentDensity, event.getFarPlaneDistance(), 24f);
        event.setFarPlaneDistance(far);
        event.setNearPlaneDistance(Math.min(event.getNearPlaneDistance(), far * 0.1f));
        event.setCanceled(true);
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
