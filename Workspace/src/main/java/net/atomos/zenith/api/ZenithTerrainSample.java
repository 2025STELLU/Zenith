package net.atomos.zenith.api;

/** 地形采样结果：高度、粗糙度、地表类型。 */
public final class ZenithTerrainSample {
    private final int heightBlocks;
    private final float roughness01;
    private final ZenithTerrainSurfaceClass surfaceClass;
    private final boolean known;

    private ZenithTerrainSample(int heightBlocks, float roughness01,
                                ZenithTerrainSurfaceClass surfaceClass, boolean known) {
        this.heightBlocks = heightBlocks;
        this.roughness01 = roughness01;
        this.surfaceClass = surfaceClass;
        this.known = known;
    }

    public static ZenithTerrainSample of(int heightBlocks, float roughness01,
                                         ZenithTerrainSurfaceClass surfaceClass) {
        return new ZenithTerrainSample(heightBlocks, roughness01, surfaceClass, true);
    }

    public static ZenithTerrainSample unknown() {
        return new ZenithTerrainSample(64, 0.5f, ZenithTerrainSurfaceClass.UNKNOWN, false);
    }

    public int heightBlocks() { return heightBlocks; }
    public float roughness01() { return roughness01; }
    public ZenithTerrainSurfaceClass surfaceClass() { return surfaceClass; }
    public boolean isKnown() { return known; }
}
