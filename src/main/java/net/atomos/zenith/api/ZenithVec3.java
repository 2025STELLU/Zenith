package net.atomos.zenith.api;

import java.util.Objects;

/** 不可变三维双精度向量，单位由调用方约定（风场中为 m/s）。 */
public final class ZenithVec3 {
    public static final ZenithVec3 ZERO = new ZenithVec3(0, 0, 0);

    private final double x;
    private final double y;
    private final double z;

    private ZenithVec3(double x, double y, double z) {
        this.x = x;
        this.y = y;
        this.z = z;
    }

    public static ZenithVec3 of(double x, double y, double z) {
        return new ZenithVec3(x, y, z);
    }

    public double x() { return x; }
    public double y() { return y; }
    public double z() { return z; }

    public double length() { return Math.sqrt(x * x + y * y + z * z); }

    public double horizontalLength() { return Math.sqrt(x * x + z * z); }

    public ZenithVec3 add(ZenithVec3 o) { return of(x + o.x, y + o.y, z + o.z); }

    public ZenithVec3 scale(double s) { return of(x * s, y * s, z * s); }

    @Override public boolean equals(Object o) {
        if (!(o instanceof ZenithVec3 v)) return false;
        return Double.compare(x, v.x) == 0 && Double.compare(y, v.y) == 0 && Double.compare(z, v.z) == 0;
    }

    @Override public int hashCode() { return Objects.hash(x, y, z); }

    @Override public String toString() {
        return String.format("ZenithVec3[%.3f, %.3f, %.3f]", x, y, z);
    }
}
