package net.atomos.zenith.api;

import java.util.List;

/** 天气运行时提供者（服务端实现，API 门面调用）。 */
public interface ZenithWeatherRuntimeProvider {
    List<ZenithFrontInfo> fronts(ZenithWorldRef world);
    List<ZenithDustDevilInfo> dustDevils(ZenithWorldRef world);
    List<ZenithSquallLineInfo> squallLines(ZenithWorldRef world);
    List<ZenithTyphoonInfo> typhoons(ZenithWorldRef world);
    double hailAt(ZenithWorldRef world, ZenithBlockPos pos);
    double fogDensityAt(ZenithWorldRef world, ZenithBlockPos pos);
    ZenithSeasonInfo season(ZenithWorldRef world);
    ZenithVec3 monsoonVector(ZenithWorldRef world);
    double mountainWaveLift(ZenithWorldRef world, ZenithVec3 pos);
}
