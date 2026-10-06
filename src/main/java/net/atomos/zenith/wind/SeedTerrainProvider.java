package net.atomos.zenith.wind;

/** 种子地形提供者：不强制加载区块即可估计地形。 */
public interface SeedTerrainProvider {
    /** 地形高度（blocks，世界种子确定性）。 */
    double heightAt(double blockX, double blockZ);

    /** 地表粗糙度 [0,1]。 */
    double roughnessAt(double blockX, double blockZ);

    /** 是否为海洋（用于海风/台风登陆判定）。 */
    boolean isOceanAt(double blockX, double blockZ);

    /** 地表类型。 */
    default net.atomos.zenith.api.ZenithTerrainSurfaceClass surfaceAt(double blockX, double blockZ) {
        return isOceanAt(blockX, blockZ)
                ? net.atomos.zenith.api.ZenithTerrainSurfaceClass.OCEAN
                : net.atomos.zenith.api.ZenithTerrainSurfaceClass.GRASS;
    }
}
