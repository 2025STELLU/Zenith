package net.atomos.zenith.weather;

/**
 * 雾浓度模型（服务端/客户端共享的纯函数）。
 *
 * <p>三种雾：</p>
 * <ul>
 *   <li>辐射雾：夜间晴空 + 弱风 + 高湿，浓度最高</li>
 *   <li>平流雾：暖湿气流经过冷水面</li>
 *   <li>雨雾：降雨 + 近饱和湿度，浓度较低</li>
 * </ul>
 */
public final class FogModel {
    private FogModel() {}

    /**
     * @param solar01        太阳高度因子 [0,1]（0=夜）
     * @param raining        是否降雨
     * @param windSpeedMps   地面风速
     * @param humidity01     相对湿度 [0,1]
     * @param overWater      是否在水面上方
     * @return 雾浓度 [0,1]
     */
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
