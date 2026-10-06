package net.atomos.zenith.weather;

import net.atomos.zenith.wind.SeedTerrainProvider;
import net.minecraft.server.level.ServerLevel;

/**
 * 地形背风波：风翻过山脊之后在山后激起的驻波，还有山脚下的转子湍流。
 *
 * 解析式诊断，没有离散对象：沿上风方向扫 800 格找最高点当山脊；
 * 山够高（高差 >25 格）且风够快（>3 m/s）才起波。
 * 波长按内重力波公式 λ = 2π·U/N 来算（N 取 0.012 s⁻¹，钳在 40–220 格），
 * 垂直速度 w = A·sin(2π·d/λ)·exp(−agl/350)，振幅跟山高和风速成正比。
 * 山后近地面还有转子区——一坨强湍流，滑翔机飞进去会很难受，
 * 但波峰的上升气流倒是可以借来爬升，跟热对流互补。
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
        double speedMod = 1.0 + 0.15 * Math.cos(phase) * hDecay;

        // 转子：山后低空的一坨强湍流，离山越近越低越颠
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
