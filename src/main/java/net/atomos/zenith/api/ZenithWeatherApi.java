package net.atomos.zenith.api;

import java.util.List;

/**
 * 开放天气 API：其他模组可查询 Zenith 的天气现象与大气状态。
 *
 * <p>所有查询均为服务端可信数据（与玩法采样同一来源）。客户端如需使用，
 * 应通过服务端转发或使用快照数据，不得直接用于反作弊敏感判定。</p>
 */
public final class ZenithWeatherApi {
    private static volatile ZenithWeatherRuntimeProvider provider;

    private ZenithWeatherApi() {}

    /** 由 Zenith 主模组在启动时调用。 */
    public static void bindRuntime(ZenithWeatherRuntimeProvider p) {
        provider = p;
    }

    public static boolean isAvailable() {
        return provider != null;
    }

    private static ZenithWeatherRuntimeProvider require() {
        ZenithWeatherRuntimeProvider p = provider;
        if (p == null) throw new IllegalStateException("Zenith weather runtime not bound");
        return p;
    }

    // ---- 天气现象 ----

    /** 活跃锋面列表。 */
    public static List<ZenithFrontInfo> fronts(ZenithWorldRef world) {
        return require().fronts(world);
    }

    /** 活跃尘卷风列表。 */
    public static List<ZenithDustDevilInfo> dustDevils(ZenithWorldRef world) {
        return require().dustDevils(world);
    }

    /** 活跃飑线列表。 */
    public static List<ZenithSquallLineInfo> squallLines(ZenithWorldRef world) {
        return require().squallLines(world);
    }

    /** 活跃台风列表。 */
    public static List<ZenithTyphoonInfo> typhoons(ZenithWorldRef world) {
        return require().typhoons(world);
    }

    /** 指定点冰雹强度 [0,1]。 */
    public static double hailAt(ZenithWorldRef world, ZenithBlockPos pos) {
        return require().hailAt(world, pos);
    }

    /** 指定点雾浓度 [0,1]。 */
    public static double fogDensityAt(ZenithWorldRef world, ZenithBlockPos pos) {
        return require().fogDensityAt(world, pos);
    }

    // ---- 大气状态 ----

    /** 当前季节信息。 */
    public static ZenithSeasonInfo season(ZenithWorldRef world) {
        return require().season(world);
    }

    /** 当前季风向量（m/s，世界坐标）。 */
    public static ZenithVec3 monsoonVector(ZenithWorldRef world) {
        return require().monsoonVector(world);
    }

    /** 指定点背风波垂直速度（m/s，正=上升）。 */
    public static double mountainWaveLift(ZenithWorldRef world, ZenithVec3 pos) {
        return require().mountainWaveLift(world, pos);
    }
}
