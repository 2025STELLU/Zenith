package net.atomos.zenith.weather;

/**
 * 雾浓度模型，服务端客户端共用，纯函数。
 *
 * 三种雾怎么来的：
 *   辐射雾：夜间晴空 + 风弱 + 湿度高，地表辐射降温凝起来的，最浓；
 *   平流雾：暖湿空气吹过冷水面被冷却，沿海常见；
 *   雨雾：下雨 + 湿度近饱和时顺带起一点，不浓。
 */
public final class FogModel {
    private FogModel() {}

    /** 输入都归一化过，输出雾浓度 [0,1]。 */
    public static double density(double solar01, boolean raining,
                                 double windSpeedMps, double humidity01,
                                 boolean overWater) {
        double d = 0;
        // 辐射雾：夜间晴空、弱风、高湿
        if (!raining && solar01 < 0.03 && windSpeedMps < 3.0 && humidity01 > 0.6) {
            d = Math.max(d, 0.8 * (humidity01 - 0.6) / 0.4
                    * (1 - windSpeedMps / 3.0));
        }
        // 平流雾：暖湿气流过冷水面
        if (overWater && humidity01 > 0.7 && windSpeedMps > 1.5 && windSpeedMps < 9) {
            d = Math.max(d, 0.6);
        }
        // 雨雾
        if (raining && humidity01 > 0.85) {
            d = Math.max(d, 0.35);
        }
        return Math.max(0, Math.min(1, d));
    }

    /** 太阳高度因子（与 ThermalSystem 一致的近似）。 */
    public static double solarFactor(double timeOfDay01) {
        return Math.max(0, Math.sin(timeOfDay01 * Math.PI * 2.0 - Math.PI / 2.0));
    }
}
