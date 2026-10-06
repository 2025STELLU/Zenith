package net.atomos.zenith.weather;

import net.minecraft.server.level.ServerLevel;

/**
 * 季风系统：季节性大尺度环流反转。
 *
 * <p>模型：年循环正弦驱动盛行风反转——夏季风由东南向西北（暖湿），
 * 冬季风由西北向东南（干冷），春秋过渡。风速 3–5 m/s，全维度均匀作用，
 * 低空略强。</p>
 */
public class MonsoonSystem implements WeatherPhenomenon {
    /** 当前季风向量（m/s），供 API 查询。 */
    private double monsoonX, monsoonZ;

    @Override
    public void tick(ServerLevel level, double dtSeconds, WeatherContext ctx) {
        var season = ctx.seasonInfo();
        // 年循环：夏季 +1 → 冬季 −1（与季节温度偏置同相）
        double annual = season.tempBiasKelvin() / 7.0;
        // 夏季风：由东南向西北；冬季风反向
        double strength = 4.0 * Math.abs(annual) + 1.0;
        double dir = annual >= 0 ? 1 : -1;
        // 缓慢平滑（季节变化是渐进的）
        double k = Math.min(1, dtSeconds / 3600.0);
        double targetX = -3.2 * dir * (0.5 + 0.5 * Math.abs(annual)) * strength / 4.0;
        double targetZ = -2.4 * dir * (0.5 + 0.5 * Math.abs(annual)) * strength / 4.0;
        monsoonX += (targetX - monsoonX) * k;
        monsoonZ += (targetZ - monsoonZ) * k;
    }

    @Override
    public WindContribution sample(double x, double y, double z, double baseWindX, double baseWindZ) {
        if (Math.abs(monsoonX) < 0.05 && Math.abs(monsoonZ) < 0.05) return WindContribution.NONE;
        // 低空略强
        double hFactor = y < 150 ? 1.0 : Math.max(0.4, 1.0 - (y - 150) / 500.0);
        return new WindContribution(monsoonX * hFactor, 0, monsoonZ * hFactor, 0.03, 0);
    }

    @Override
    public boolean hasActive() {
        return Math.hypot(monsoonX, monsoonZ) > 0.3;
    }

    public double monsoonX() { return monsoonX; }
    public double monsoonZ() { return monsoonZ; }
}
