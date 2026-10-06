package net.atomos.zenith.api;

import java.util.Collections;
import java.util.List;

/** 极线表：按迎角排序的采样点，支持线性插值查询。 */
public final class ZenithPolarTable {
    private final List<ZenithPolarSample> samples;

    public ZenithPolarTable(List<ZenithPolarSample> samples) {
        var sorted = samples.stream()
                .sorted((a, b) -> Double.compare(a.angleDegrees(), b.angleDegrees()))
                .toList();
        this.samples = Collections.unmodifiableList(sorted);
    }

    public List<ZenithPolarSample> samples() { return samples; }

    /** 线性插值查询指定迎角的系数；超出范围则钳制到端点。 */
    public ZenithPolarSample sampleAt(double angleDegrees) {
        if (samples.isEmpty()) return new ZenithPolarSample(angleDegrees, 0, 0, 0);
        if (samples.size() == 1) {
            var s = samples.get(0);
            return new ZenithPolarSample(angleDegrees, s.cl(), s.cd(), s.cm());
        }
        ZenithPolarSample lo = samples.get(0), hi = samples.get(samples.size() - 1);
        if (angleDegrees <= lo.angleDegrees()) return withAngle(lo, angleDegrees);
        if (angleDegrees >= hi.angleDegrees()) return withAngle(hi, angleDegrees);
        for (int i = 0; i < samples.size() - 1; i++) {
            var a = samples.get(i);
            var b = samples.get(i + 1);
            if (angleDegrees >= a.angleDegrees() && angleDegrees <= b.angleDegrees()) {
                double t = (angleDegrees - a.angleDegrees()) / (b.angleDegrees() - a.angleDegrees());
                return new ZenithPolarSample(angleDegrees,
                        lerp(a.cl(), b.cl(), t), lerp(a.cd(), b.cd(), t), lerp(a.cm(), b.cm(), t));
            }
        }
        return withAngle(hi, angleDegrees);
    }

    private static ZenithPolarSample withAngle(ZenithPolarSample s, double angle) {
        return new ZenithPolarSample(angle, s.cl(), s.cd(), s.cm());
    }

    private static double lerp(double a, double b, double t) { return a + (b - a) * t; }
}
