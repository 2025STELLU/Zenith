package net.atomos.zenith.weather;

/**
 * 雾浓度模型，服务端客户端共用，纯函数。
 *
 * <p>三种雾怎么来的：
 * <ul>
 *   <li>辐射雾：夜间晴空 + 风弱 + 湿度高，地表辐射降温凝起来的，最浓；</li>
 *   <li>平流雾：暖湿空气吹过冷水面被冷却，沿海常见；</li>
 *   <li>雨雾：下雨 + 湿度近饱和时顺带起一点，不浓。</li>
 * </ul>
 *
 * <p>三者都是连续函数（WX-FOG-01）：旧实现的平流雾是 {@code d = max(d, 0.6)} 的阶跃，
 * 湿度跨过 0.70 的瞬间雾浓度从 0 跳到 0.6、风速到 9 m/s 又瞬间归零，走两步雾就闪一下。
 * 现在平流雾写成"湿度饱和不足 × 风速窗口"的平滑乘积，并与辐射雾共享同一个
 * {@link #saturationDeficit} 口径。
 */
public final class FogModel {
    private FogModel() {}

    /** 辐射雾：起雾的湿度门槛与风速上限。 */
    public static final double RADIATION_FOG_HUMIDITY_LO = 0.60;
    public static final double RADIATION_FOG_WIND_MAX = 3.0;
    /** 平流雾：湿度窗口与风速窗口（m/s），两端都是平滑过渡。 */
    public static final double ADVECTION_FOG_HUMIDITY_LO = 0.70;
    public static final double ADVECTION_FOG_HUMIDITY_HI = 0.95;
    public static final double ADVECTION_FOG_WIND_LO = 1.5;
    public static final double ADVECTION_FOG_WIND_HI = 9.0;
    public static final double ADVECTION_FOG_WIND_FADE = 3.0;
    public static final double ADVECTION_FOG_PEAK = 0.60;
    /** 雨雾：湿度门槛与浓度。 */
    public static final double RAIN_FOG_HUMIDITY_LO = 0.85;
    public static final double RAIN_FOG_PEAK = 0.35;

    /** 输入都归一化过，输出雾浓度 [0,1]。 */
    public static double density(double solar01, boolean raining,
                                 double windSpeedMps, double humidity01,
                                 boolean overWater) {
        double rh = clamp01(humidity01);
        double d = 0;

        // 辐射雾：夜间晴空、弱风、高湿。风越小越浓，连续到 0
        if (!raining && solar01 < 0.03 && rh > RADIATION_FOG_HUMIDITY_LO
                && windSpeedMps < RADIATION_FOG_WIND_MAX) {
            d = Math.max(d, 0.8 * saturationDeficit(rh, RADIATION_FOG_HUMIDITY_LO)
                    * (1.0 - clamp01(windSpeedMps / RADIATION_FOG_WIND_MAX)));
        }

        // 平流雾：暖湿气流过冷水面被冷却。
        //   湿度项：0.70 起、0.95 饱和（smoothstep）
        //   风速项：1.5 m/s 以上开始起雾，9 m/s 前 3 m/s 内平滑衰减到 0
        // 旧实现是 if (rh > 0.7 && 1.5 < wind < 9) d = max(d, 0.6)：两端都是硬边（WX-FOG-01）
        if (overWater) {
            double humidityTerm = smoothstep(rh, ADVECTION_FOG_HUMIDITY_LO, ADVECTION_FOG_HUMIDITY_HI);
            double windTerm = smoothstep(windSpeedMps, ADVECTION_FOG_WIND_LO,
                    ADVECTION_FOG_WIND_LO + ADVECTION_FOG_WIND_FADE)
                    * (1.0 - smoothstep(windSpeedMps,
                            ADVECTION_FOG_WIND_HI - ADVECTION_FOG_WIND_FADE, ADVECTION_FOG_WIND_HI));
            if (humidityTerm > 0 && windTerm > 0) {
                d = Math.max(d, ADVECTION_FOG_PEAK * humidityTerm * windTerm);
            }
        }

        // 雨雾：湿度近饱和时顺带起一点，湿度越高越浓
        if (raining && rh > RAIN_FOG_HUMIDITY_LO) {
            d = Math.max(d, RAIN_FOG_PEAK
                    * saturationDeficit(rh, RAIN_FOG_HUMIDITY_LO));
        }
        return clamp01(d);
    }

    /**
     * 湿度饱和不足 [0,1]：{@code (rh − lo) / (1 − lo)}。
     *
     * <p>三种雾都用同一个口径，免得"0.6 起雾但浓度算 0.5"这类各写各的归一化
     * （旧实现辐射雾除以 0.4、雨雾直接给常数）。
     */
    public static double saturationDeficit(double humidity01, double threshold) {
        if (threshold >= 1.0) return 0;
        return clamp01((humidity01 - threshold) / (1.0 - threshold));
    }

    /**
     * 太阳高度因子 [-1,1]：正午 1、午夜 -1。
     * 输入 timeOfDay01 = dayTime/24000（0=日出、0.25=正午、0.5=日落、0.75=午夜），
     * 等价于 cos(level.getTimeOfDay(pt) * 2π)（getTimeOfDay 以正午为 0）。
     * 以前这里用 sin(2πt−π/2)，峰值落在日落，整整错了 6 小时。
     */
    public static double solarFactor(double timeOfDay01) {
        return Math.cos((timeOfDay01 - 0.25) * Math.PI * 2.0);
    }

    /** 0 → 1 的平滑过渡：{@code x <= lo} 为 0，{@code x >= hi} 为 1。 */
    public static double smoothstep(double x, double lo, double hi) {
        if (hi <= lo) return x >= hi ? 1.0 : 0.0;
        double t = clamp01((x - lo) / (hi - lo));
        return t * t * (3.0 - 2.0 * t);
    }

    private static double clamp01(double v) {
        return v < 0 ? 0 : Math.min(v, 1);
    }
}
