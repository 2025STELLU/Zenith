package net.atomos.zenith.client;

import net.atomos.zenith.particle.ZenithParticles;
import net.atomos.zenith.weather.FogModel;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
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
        // 平流雾看的是"风从哪来"：上风向 32–96 格的水面占比，而不是脚下一格
        // （不然刚上船/下水就大雾，纯属误伤）
        double waterFrac = upwindWaterFraction(level, cam.getX(), cam.getY(), cam.getZ(),
                wind.x(), wind.z());
        boolean overWater = waterFrac > 0.5;

        double target = FogModel.density(solar, raining, windSpeed, humidity, overWater);
        // 一阶平滑，τ≈3 秒：走到水边不会"啪"地起雾，离开也不会瞬间散
        float frameDt = mc.getTimer().getGameTimeDeltaTicks() / 20.0f;
        if (frameDt <= 0 || frameDt > 1) frameDt = 1 / 20f; // 暂停/切屏回来钳一下
        currentDensity += (target - currentDensity) * (1 - Math.exp(-frameDt / 3.0));
        if (currentDensity < 0.02) return;

        // 浓雾时能见度压到约 24 格；1.21.1 不 cancel 的话改了也白改
        float far = Mth.lerp((float) currentDensity, event.getFarPlaneDistance(), 24f);
        event.setFarPlaneDistance(far);
        event.setNearPlaneDistance(Math.min(event.getNearPlaneDistance(), far * 0.1f));
        event.setCanceled(true);
    }

    /** 上风向 32–96 格采 8 个点，算水面占比 [0,1]。 */
    private static double upwindWaterFraction(ClientLevel level, double x, double y, double z,
                                              double wx, double wz) {
        double wlen = Math.hypot(wx, wz);
        // 上风向 = 风矢量的反方向；风太弱时方向没意义，随便取个东向
        double ux = wlen > 0.3 ? -wx / wlen : 1.0;
        double uz = wlen > 0.3 ? -wz / wlen : 0.0;
        int water = 0;
        int feetY = Mth.floor(y) - 1;
        for (int i = 0; i < 8; i++) {
            double d = 32 + i * 8; // 32, 40, …, 96
            var pos = new BlockPos(Mth.floor(x + ux * d), feetY, Mth.floor(z + uz * d));
            if (level.getFluidState(pos).is(FluidTags.WATER)) water++;
        }
        return water / 8.0;
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
            mc.level.addParticle(ZenithParticles.FOG.get(), x, y, z, 0, 0, 0);
        }
    }
}
