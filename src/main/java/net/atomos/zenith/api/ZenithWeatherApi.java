package net.atomos.zenith.api;

import java.util.List;

/**
 * 开放天气 API：查 Zenith 的天气现象和大气状态。
 *
 * 返回的都是服务端可信数据（跟玩法采样同一来源）。客户端要用只能走服务端
 * 转发或快照，别拿来做反作弊判定——客户端数据不可信。
 */
public final class ZenithWeatherApi {
    private static volatile ZenithWeatherRuntimeProvider provider;

    private ZenithWeatherApi() {}

    /** 主模组启动时调一次，用来绑定运行时实现。 */
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

    public static List<ZenithFrontInfo> fronts(ZenithWorldRef world) {
        return require().fronts(world);
    }

    public static List<ZenithDustDevilInfo> dustDevils(ZenithWorldRef world) {
        return require().dustDevils(world);
    }

    public static List<ZenithSquallLineInfo> squallLines(ZenithWorldRef world) {
        return require().squallLines(world);
    }

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

    public static ZenithSeasonInfo season(ZenithWorldRef world) {
        return require().season(world);
    }

    /** 季风向量（m/s，世界坐标）。 */
    public static ZenithVec3 monsoonVector(ZenithWorldRef world) {
        return require().monsoonVector(world);
    }

    /** 背风波垂直速度（m/s，正数=上升气流）。 */
    public static double mountainWaveLift(ZenithWorldRef world, ZenithVec3 pos) {
        return require().mountainWaveLift(world, pos);
    }
}
