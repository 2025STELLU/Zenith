package net.atomos.zenith.weather;

import net.minecraft.server.level.ServerLevel;

/**
 * 季风：季节性的风向大反转。
 *
 * 年循环驱动：夏季风从东南吹向西北（暖湿），冬季风反过来（干冷），
 * 春秋两季过渡。强度 3–5 m/s，整个维度均匀作用，贴地面稍微强一点。
 */
public class MonsoonSystem implements WeatherPhenomenon {
    /** 当前季风向量（m/s），供 API 查询。 */
    private double monsoonX, monsoonZ;

    @Override
    public void tick(ServerLevel level, double dtSeconds, WeatherContext ctx) {
        var season = ctx.seasonInfo();
        // 借季节温度偏置当作年循环相位：夏季 +1，冬季 −1
        double annual = season.tempBiasKelvin() / 7.0;
        double strength = 4.0 * Math.abs(annual) + 1.0;
        double dir = annual >= 0 ? 1 : -1;
        // 季风转得慢，用一小时时间常数慢慢跟
        double k = Math.min(1, dtSeconds / 3600.0);
        double targetX = -3.2 * dir * (0.5 + 0.5 * Math.abs(annual)) * strength / 4.0;
        double targetZ = -2.4 * dir * (0.5 + 0.5 * Math.abs(annual)) * strength / 4.0;
        monsoonX += (targetX - monsoonX) * k;
        monsoonZ += (targetZ - monsoonZ) * k;
    }

    @Override
    public WindContribution sample(double x, double y, double z, double baseWindX, double baseWindZ) {
        if (Math.abs(monsoonX) < 0.05 && Math.abs(monsoonZ) < 0.05) return WindContribution.NONE;
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
