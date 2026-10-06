package net.atomos.zenith.weather;

import net.atomos.zenith.wind.SeedTerrainProvider;
import net.minecraft.server.level.ServerLevel;

/**
 * 地形背风波系统：气流过山产生的驻波 + 转子。
 *
 * <p>模型（解析诊断，无离散对象）：</p>
 * <ul>
 *   <li>沿上风方向搜索 800 格内的最高地形点作为山脊</li>
 *   <li>山脊显著（高差 >25 格）且风速 >3 m/s 时形成驻波</li>
 *   <li>波长 λ = 2π·U/N（N≈0.012 s⁻¹，钳制 40–220 格）</li>
 *   <li>垂直速度 w = A·sin(2π·d/λ)·exp(−agl/350)，A ∝ 山高×风速</li>
 *   <li>山后近地面转子区：强湍流</li>
 *   <li>滑翔机可利用波峰上升气流（与热对流互补）</li>
 * </ul>
 */
public class MountainWaveSystem implements WeatherPhenomenon {
    private static final double BRUNT_VAISALA_N = 0.012; // s^-1

    private SeedTerrainProvider terrain;

    @Override
    public void tick(ServerLevel level, double dtSeconds, WeatherContext ctx) {
        this.terrain = ctx.terrain();
    }

    @Override
    public WindContribution sample(double x, double y, double z, double baseWindX, double baseWindZ) {
        if (terrain == null) return WindContribution.NONE;
        double speed = Math.hypot(baseWindX, baseWindZ);
        if (speed < 3.0) return WindContribution.NONE;

        double upX = -baseWindX / speed, upZ = -baseWindZ / speed;
        double hHere = terrain.heightAt(x, z);

        // 上风搜索山脊
        double crestH = hHere, crestDist = 0;
        double[] dists = {80, 160, 320, 560, 800};
        for (double d : dists) {
            double h = terrain.heightAt(x + upX * d, z + upZ * d);
            if (h > crestH) {
                crestH = h;
                crestDist = d;
            }
        }
        double ridgeRise = crestH - hHere;
        if (ridgeRise < 25 || crestDist < 40) return WindContribution.NONE;

        double wavelength = Math.max(40, Math.min(220, 2 * Math.PI * speed / BRUNT_VAISALA_N));
        double agl = Math.max(0, y - hHere);
        double amplitude = Math.min(6.0, ridgeRise * 0.04 * speed * 0.15);
        double phase = 2 * Math.PI * crestDist / wavelength;
        double hDecay = Math.exp(-agl / 350.0);

        double w = amplitude * Math.sin(phase) * hDecay;
        // 水平风速调制
        double speedMod = 1.0 + 0.15 * Math.cos(phase) * hDecay;

        // 转子：山后近地面强湍流
        double rotorTurb = 0;
        if (agl < 120 && crestDist < 600) {
            rotorTurb = 0.45 * Math.exp(-crestDist / 300.0) * (1 - agl / 120.0);
        }

        double vx = baseWindX * (speedMod - 1.0);
        double vz = baseWindZ * (speedMod - 1.0);
        return new WindContribution(vx, w, vz, Math.min(0.7, rotorTurb + 0.08), 0);
    }

    @Override
    public boolean hasActive() {
        return terrain != null; // 只要有地形数据就可能有波
    }
}
