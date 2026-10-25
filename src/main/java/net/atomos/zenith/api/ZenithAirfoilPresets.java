package net.atomos.zenith.api;

import java.util.ArrayList;
import java.util.List;

/** 内置翼型预设（NACA 四位数字系列 + 平板）。 */
public final class ZenithAirfoilPresets {
    private ZenithAirfoilPresets() {}

    public static final ZenithAirfoilDefinition NACA_0012 = nacaPreset("0012", 0.00, 0.00, 0.12);
    public static final ZenithAirfoilDefinition NACA_2412 = nacaPreset("2412", 0.02, 0.40, 0.12);
    public static final ZenithAirfoilDefinition NACA_4412 = nacaPreset("4412", 0.04, 0.40, 0.12);
    public static final ZenithAirfoilDefinition FLAT_PLATE = new ZenithAirfoilDefinition(
            ZenithId.of("zenith", "flat_plate"), "Flat plate",
            List.of(new ZenithAirfoilCoordinate(0, 0), new ZenithAirfoilCoordinate(1, 0)));

    public static List<ZenithAirfoilDefinition> defaults() {
        return List.of(NACA_0012, NACA_2412, NACA_4412, FLAT_PLATE);
    }

    /**
     * 生成 NACA 四位数字翼型点序列。
     *
     * <p><b>API-10</b>：旧实现两圈循环把上下表面对调了——第一圈是 {@code yc - yt·cosθ}
     * （下表面）却排在前面，而 {@link ZenithAirfoilDefinition} 的约定是
     * "上表面前缘→后缘，下表面后缘→前缘"。同时第二圈从 {@code i=n} 到 {@code i=0}，
     * 后缘与前缘各重复一个点，多边形多两条退化边。
     *
     * <p>现在：上表面 {@code i=0..n}（含前缘与后缘），下表面 {@code i=n-1..1}
     * （端点已在上表面出现过，不重复），首尾隐式闭合。
     */
    private static ZenithAirfoilDefinition nacaPreset(String digits, double m, double p, double t) {
        List<ZenithAirfoilCoordinate> pts = new ArrayList<>(2 * NACA_SAMPLES);
        // 上表面：前缘 → 后缘
        for (int i = 0; i <= NACA_SAMPLES; i++) {
            double x = cosineSpacing(i, NACA_SAMPLES);
            double yt = thickness(x, t);
            double yc = camber(x, m, p);
            double dyc = camberSlope(x, m, p);
            double theta = Math.atan(dyc);
            pts.add(new ZenithAirfoilCoordinate(x - yt * Math.sin(theta), yc + yt * Math.cos(theta)));
        }
        // 下表面：后缘 → 前缘（去掉已在上表面出现过的两个端点）
        for (int i = NACA_SAMPLES - 1; i >= 1; i--) {
            double x = cosineSpacing(i, NACA_SAMPLES);
            double yt = thickness(x, t);
            double yc = camber(x, m, p);
            double dyc = camberSlope(x, m, p);
            double theta = Math.atan(dyc);
            pts.add(new ZenithAirfoilCoordinate(x + yt * Math.sin(theta), yc - yt * Math.cos(theta)));
        }
        return new ZenithAirfoilDefinition(ZenithId.of("zenith", "naca_" + digits),
                "NACA " + digits, pts);
    }

    /** 每个表面的采样段数（上表面 n+1 点、下表面 n-1 点）。 */
    private static final int NACA_SAMPLES = 60;

    /** 余弦加密：前缘密、后缘疏。 */
    private static double cosineSpacing(int i, int n) {
        return 0.5 * (1 - Math.cos(Math.PI * i / n));
    }

    /** NACA 四位数字的厚度分布（末项 −0.1036 为闭合后缘）。 */
    private static double thickness(double x, double t) {
        return 5 * t * (0.2969 * Math.sqrt(x) - 0.1260 * x - 0.3516 * x * x
                + 0.2843 * x * x * x - 0.1036 * x * x * x * x);
    }

    /** 中弧线高度；{@code p == 0}（对称翼型）时恒 0。 */
    private static double camber(double x, double m, double p) {
        if (m == 0 || p <= 0 || p >= 1) return 0;
        if (x < p) return m / (p * p) * (2 * p * x - x * x);
        return m / ((1 - p) * (1 - p)) * ((1 - 2 * p) + 2 * p * x - x * x);
    }

    /** 中弧线斜率。 */
    private static double camberSlope(double x, double m, double p) {
        if (m == 0 || p <= 0 || p >= 1) return 0;
        if (x < p) return 2 * m / (p * p) * (p - x);
        return 2 * m / ((1 - p) * (1 - p)) * (p - x);
    }
}
