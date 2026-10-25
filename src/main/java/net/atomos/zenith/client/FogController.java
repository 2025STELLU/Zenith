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
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.FogType;
import net.neoforged.neoforge.client.event.ViewportEvent;

import java.util.Random;

/** 客户端雾控制器：按气象条件调雾 + 撒雾气粒子。 */
public final class FogController {
    private static final Random RANDOM = new Random();
    private static int tickCounter = 0;
    /** 一阶平滑每 tick 的跟进系数：k = 1 − e^(−1/60)，τ = 60 tick（3 秒）。 */
    private static final double SMOOTH_K = 1 - Math.exp(-1.0 / 60);

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
        // 平滑在 clientTick 里按 tick 做，这里只管读数设雾参
        double density = currentDensity * net.atomos.zenith.ZenithConfig.fogMultiplier();
        if (density < 0.02) return;

        // 浓雾时能见度压到约 24 格；1.21.1 不 cancel 的话改了也白改
        float far = Mth.lerp((float) Math.min(1.0, density), event.getFarPlaneDistance(), 24f);
        event.setFarPlaneDistance(far);
        event.setNearPlaneDistance(Math.min(event.getNearPlaneDistance(), far * 0.1f));
        event.setCanceled(true);
    }

    public static void clientTick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return;
        // 暂停菜单里雾不演化：ClientTickEvent 暂停时也会进来，这里直接跳过
        if (!mc.isPaused()) smoothDensity(mc);
        tickCounter++;
        // 雨雾目标值就是 0.3x 档，门槛卡 0.35 的话一阶平滑渐近永远到不了，改 0.3
        if (currentDensity < 0.3 || tickCounter % 6 != 0) return;

        var p = mc.player;
        for (int i = 0; i < 3; i++) {
            double x = p.getX() + (RANDOM.nextDouble() - 0.5) * 36;
            double z = p.getZ() + (RANDOM.nextDouble() - 0.5) * 36;
            // 贴着地表 +1.5 格撒：2–6 格的大面片不会被地面切出硬边
            int surfaceY = mc.level.getHeight(Heightmap.Types.WORLD_SURFACE,
                    Mth.floor(x), Mth.floor(z));
            mc.level.addParticle(ZenithParticles.FOG.get(), x, surfaceY + 1.5, z, 0, 0, 0);
        }
    }

    /** 一阶平滑：按 tick 推进，τ = 60 tick。走到水边不会"啪"地起雾，离开也不会瞬间散。 */
    private static void smoothDensity(Minecraft mc) {
        var level = mc.level;
        var p = mc.player;

        var wind = ClientWindState.get().sampleCoarse(p.getX(), p.getY(), p.getZ());
        double windSpeed = Math.hypot(wind.x(), wind.z());

        // 湿度从天气快照取，包没到之前先按 0.6 估
        double humidity = 0.6;
        var snap = ClientWindState.get().snapshot();
        if (snap != null) humidity = snap.humidity01();

        boolean raining = level.isRaining();
        double solar = FogModel.solarFactor(level.getDayTime() / 24000.0);
        // 平流雾看的是"风从哪来"：上风向 32–96 格的水面占比，而不是脚下一格
        // （不然刚上船/下水就大雾，纯属误伤）
        double waterFrac = upwindWaterFraction(level, p.getX(), p.getZ(), wind.x(), wind.z());
        boolean overWater = waterFrac > 0.5;

        double target = FogModel.density(solar, raining, windSpeed, humidity, overWater);
        currentDensity += (target - currentDensity) * SMOOTH_K;
    }

    /** 上风向 32–96 格采 8 个点，算水面占比 [0,1]。每个点看自己的地表，站在高处也不会误判。 */
    private static double upwindWaterFraction(ClientLevel level, double x, double z,
                                              double wx, double wz) {
        double wlen = Math.hypot(wx, wz);
        // 上风向 = 风矢量的反方向；风太弱时方向没意义，随便取个东向
        double ux = wlen > 0.3 ? -wx / wlen : 1.0;
        double uz = wlen > 0.3 ? -wz / wlen : 0.0;
        int water = 0;
        for (int i = 0; i < 8; i++) {
            double d = 32 + i * 8; // 32, 40, …, 96
            int bx = Mth.floor(x + ux * d);
            int bz = Mth.floor(z + uz * d);
            // 该点地表那一格（WORLD_SURFACE 把水面也算进去），看是不是水
            var pos = new BlockPos(bx, level.getHeight(Heightmap.Types.WORLD_SURFACE, bx, bz) - 1, bz);
            if (level.getFluidState(pos).is(FluidTags.WATER)) water++;
        }
        return water / 8.0;
    }
}
