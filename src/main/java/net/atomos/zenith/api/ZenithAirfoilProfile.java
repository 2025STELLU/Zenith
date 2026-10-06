package net.atomos.zenith.api;

import java.util.List;

/** 翼型截面采样：按弦向位置给出上下表面 y。 */
public final class ZenithAirfoilProfile {
    private final ZenithAirfoilDefinition definition;

    public ZenithAirfoilProfile(ZenithAirfoilDefinition definition) {
        this.definition = definition;
    }

    public ZenithAirfoilDefinition definition() { return definition; }

    /** 在弦向 x 处插值厚度（上下表面 y 差的一半的两倍≈相对厚度）。 */
    public double thicknessAt(double x) {
        List<ZenithAirfoilCoordinate> pts = definition.coordinates();
        double upper = Double.NaN, lower = Double.NaN;
        for (ZenithAirfoilCoordinate c : pts) {
            if (Math.abs(c.x() - x) < 0.02) {
                if (c.y() >= 0 && (Double.isNaN(upper) || c.y() > upper)) upper = c.y();
                if (c.y() <= 0 && (Double.isNaN(lower) || c.y() < lower)) lower = c.y();
            }
        }
        if (Double.isNaN(upper)) upper = 0;
        if (Double.isNaN(lower)) lower = 0;
        return upper - lower;
    }
}
