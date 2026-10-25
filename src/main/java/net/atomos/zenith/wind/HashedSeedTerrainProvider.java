package net.atomos.zenith.wind;

import net.atomos.zenith.api.ZenithTerrainSurfaceClass;

/**
 * 哈希种子地形：用世界种子 + fbm 噪声生成确定性地形估计。
 * 精度不如真实区块数据，但零加载开销；运行时优先用真实区块高度覆盖。
 */
public class HashedSeedTerrainProvider implements SeedTerrainProvider {
    private final long seed;

    public HashedSeedTerrainProvider(long seed) {
        this.seed = seed;
    }

    @Override
    public double heightAt(double blockX, double blockZ) {
        double continent = NoiseUtil.fbm2(blockX * 0.00012, blockZ * 0.00012, 4, seed);
        double hills = NoiseUtil.fbm2(blockX * 0.0011, blockZ * 0.0011, 3, seed + 77);
        double mountains = Math.max(0, NoiseUtil.fbm2(blockX * 0.00045 + 31.7, blockZ * 0.00045, 3, seed + 913) - 0.62) * 3.2;
        double h = 62 + (continent - 0.5) * 36 + (hills - 0.5) * 22 + mountains * 60;
        // 洋面洼地：噪声超过阈值的地方扣成海
        double oceanMask = NoiseUtil.fbm2(blockX * 0.00008 + 911.2, blockZ * 0.00008, 2, seed + 551);
        if (oceanMask > 0.62) {
            h = 62 - (oceanMask - 0.62) * 90;
        }
        return h;
    }

    @Override
    public double roughnessAt(double blockX, double blockZ) {
        double veg = NoiseUtil.fbm2(blockX * 0.002, blockZ * 0.002, 2, seed + 313);
        double r = 0.25 + veg * 0.45;
        if (isOceanAt(blockX, blockZ)) r = 0.02;
        double h = heightAt(blockX, blockZ);
        if (h > 100) r = Math.min(1.0, r + (h - 100) * 0.008);
        return clamp01(r);
    }

    @Override
    public boolean isOceanAt(double blockX, double blockZ) {
        return heightAt(blockX, blockZ) < 60.5;
    }

    @Override
    public ZenithTerrainSurfaceClass surfaceAt(double blockX, double blockZ) {
        double h = heightAt(blockX, blockZ);
        if (h < 60.5) return ZenithTerrainSurfaceClass.OCEAN;
        if (h < 63) return ZenithTerrainSurfaceClass.BEACH;
        double temp = NoiseUtil.fbm2(blockX * 0.0002 + 40, blockZ * 0.0002, 2, seed + 717);
        double dry = NoiseUtil.fbm2(blockX * 0.0006 + 500, blockZ * 0.0006, 2, seed + 171);
        if (h > 110) return ZenithTerrainSurfaceClass.ROCK;
        if (temp < 0.32) return ZenithTerrainSurfaceClass.SNOW;
        if (dry > 0.62 && temp > 0.55) return ZenithTerrainSurfaceClass.DESERT_SAND;
        if (dry < 0.45 && temp > 0.4 && temp < 0.7) return ZenithTerrainSurfaceClass.FOREST;
        return ZenithTerrainSurfaceClass.GRASS;
    }

    private static double clamp01(double v) { return v < 0 ? 0 : Math.min(v, 1); }
}
