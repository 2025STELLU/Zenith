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

    private static ZenithAirfoilDefinition nacaPreset(String digits, double m, double p, double t) {
        List<ZenithAirfoilCoordinate> pts = new ArrayList<>();
        int n = 60;
        for (int i = 0; i <= n; i++) {
            double x = 0.5 * (1 - Math.cos(Math.PI * i / n));
            double yt = 5 * t * (0.2969 * Math.sqrt(x) - 0.1260 * x - 0.3516 * x * x
                    + 0.2843 * x * x * x - 0.1036 * x * x * x * x);
            double yc, dyc;
            if (x < p) {
                yc = m / (p * p) * (2 * p * x - x * x);
                dyc = 2 * m / (p * p) * (p - x);
            } else {
                yc = m / ((1 - p) * (1 - p)) * ((1 - 2 * p) + 2 * p * x - x * x);
                dyc = 2 * m / ((1 - p) * (1 - p)) * (p - x);
            }
            double theta = Math.atan(dyc);
            pts.add(new ZenithAirfoilCoordinate(x + yt * Math.sin(theta), yc - yt * Math.cos(theta)));
        }
        for (int i = n; i >= 0; i--) {
            double x = 0.5 * (1 - Math.cos(Math.PI * i / n));
            double yt = 5 * t * (0.2969 * Math.sqrt(x) - 0.1260 * x - 0.3516 * x * x
                    + 0.2843 * x * x * x - 0.1036 * x * x * x * x);
            double yc, dyc;
            if (x < p) {
                yc = m / (p * p) * (2 * p * x - x * x);
                dyc = 2 * m / (p * p) * (p - x);
            } else {
                yc = m / ((1 - p) * (1 - p)) * ((1 - 2 * p) + 2 * p * x - x * x);
                dyc = 2 * m / ((1 - p) * (1 - p)) * (p - x);
            }
            double theta = Math.atan(dyc);
            pts.add(new ZenithAirfoilCoordinate(x - yt * Math.sin(theta), yc + yt * Math.cos(theta)));
        }
        return new ZenithAirfoilDefinition(ZenithId.of("zenith", "naca_" + digits),
                "NACA " + digits, pts);
    }
}
