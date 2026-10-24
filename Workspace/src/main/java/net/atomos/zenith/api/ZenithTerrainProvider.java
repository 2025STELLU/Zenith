package net.atomos.zenith.api;

/** 地形提供者 SPI（供自定义地形/高度图接入）。 */
public interface ZenithTerrainProvider {
    ZenithTerrainSample sample(ZenithWorldRef world, ZenithBlockPos pos);
}
