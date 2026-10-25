package net.atomos.zenith.api;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 极线计算请求。 */
public final class ZenithPolarRequest {
    /** 步长下限：防止 step=0/负数/NaN 让 {@link #angleSampleCount()} 除零或死循环（API-03）。 */
    private static final double MIN_STEP_DEGREES = 0.01;
    /** 采样数上限：10,000 点已经远超任何极线绘制的需要，同时是恶意请求的天花板。 */
    private static final int MAX_SAMPLES = 10_000;
    private static final double MAX_ABS_ANGLE_DEGREES = 180.0;

    private final ZenithSurfaceDescriptor surface;
    private final double minAngleDegrees;
    private final double maxAngleDegrees;
    private final double stepDegrees;
    private final List<Double> reynoldsNumbers;

    private ZenithPolarRequest(Builder b) {
        this.surface = b.surface;
        // 非有限值回落到默认，角度限制在 ±180°（默认扫描是 −15…15，不能夹成非负）
        this.minAngleDegrees = finiteOr(b.minAngleDegrees, -15.0, -MAX_ABS_ANGLE_DEGREES, MAX_ABS_ANGLE_DEGREES);
        this.maxAngleDegrees = finiteOr(b.maxAngleDegrees, 15.0, -MAX_ABS_ANGLE_DEGREES, MAX_ABS_ANGLE_DEGREES);
        this.stepDegrees = finiteOr(b.stepDegrees, 1.0, MIN_STEP_DEGREES, MAX_ABS_ANGLE_DEGREES);
        this.reynoldsNumbers = Collections.unmodifiableList(new ArrayList<>(b.reynoldsNumbers));
    }

    private static double finiteOr(double value, double fallback, double min, double max) {
        if (!Double.isFinite(value)) return fallback;
        return Math.max(min, Math.min(max, value));
    }

    public static Builder builder(ZenithSurfaceDescriptor surface) {
        return new Builder(surface);
    }

    public ZenithSurfaceDescriptor surface() { return surface; }
    public double minAngleDegrees() { return minAngleDegrees; }
    public double maxAngleDegrees() { return maxAngleDegrees; }
    public double stepDegrees() { return stepDegrees; }
    public List<Double> reynoldsNumbers() { return reynoldsNumbers; }

    public int angleSampleCount() {
        double span = maxAngleDegrees - minAngleDegrees;
        if (span <= 0) return 1;                       // 反向/零跨度：只给一个点，别返回负数
        double count = Math.floor(span / stepDegrees) + 1;
        return (int) Math.max(1, Math.min(MAX_SAMPLES, count));
    }

    public double angleAt(int index) {
        return minAngleDegrees + index * stepDegrees;
    }

    public static final class Builder {
        private final ZenithSurfaceDescriptor surface;
        private double minAngleDegrees = -15;
        private double maxAngleDegrees = 15;
        private double stepDegrees = 1;
        private final List<Double> reynoldsNumbers = new ArrayList<>(List.of(1.0e6));

        private Builder(ZenithSurfaceDescriptor surface) {
            this.surface = surface;
        }

        public Builder angleSweep(double minDegrees, double maxDegrees, double stepDegrees) {
            this.minAngleDegrees = minDegrees;
            this.maxAngleDegrees = maxDegrees;
            this.stepDegrees = stepDegrees;
            return this;
        }

        public Builder reynoldsNumbers(List<Double> re) {
            this.reynoldsNumbers.clear();
            this.reynoldsNumbers.addAll(re);
            return this;
        }

        public ZenithPolarRequest build() {
            return new ZenithPolarRequest(this);
        }
    }
}
