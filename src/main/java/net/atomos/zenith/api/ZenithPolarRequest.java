package net.atomos.zenith.api;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 极线计算请求。 */
public final class ZenithPolarRequest {
    private final ZenithSurfaceDescriptor surface;
    private final double minAngleDegrees;
    private final double maxAngleDegrees;
    private final double stepDegrees;
    private final List<Double> reynoldsNumbers;

    private ZenithPolarRequest(Builder b) {
        this.surface = b.surface;
        this.minAngleDegrees = b.minAngleDegrees;
        this.maxAngleDegrees = b.maxAngleDegrees;
        this.stepDegrees = b.stepDegrees;
        this.reynoldsNumbers = Collections.unmodifiableList(new ArrayList<>(b.reynoldsNumbers));
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
        return (int) Math.floor((maxAngleDegrees - minAngleDegrees) / stepDegrees) + 1;
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
