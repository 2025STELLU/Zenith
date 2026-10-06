package net.atomos.zenith.api;

/** 地形 API 外观。 */
public final class ZenithTerrainApi {
    private static volatile ZenithTerrainProvider provider;

    private ZenithTerrainApi() {}

    public static void registerProvider(ZenithTerrainProvider p) { provider = p; }
    public static boolean isAvailable() { return provider != null; }

    public static ZenithTerrainSample sample(ZenithWorldRef world, ZenithBlockPos pos) {
        ZenithTerrainProvider p = provider;
        return p == null ? ZenithTerrainSample.unknown() : p.sample(world, pos);
    }
}
