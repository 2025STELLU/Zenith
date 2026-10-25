package net.atomos.zenith.api;

/** 气动表面描述：翼型 + 几何参数。 */
public record ZenithSurfaceDescriptor(
        ZenithId airfoilId,
        double chordMeters,
        double spanMeters,
        double controlDeflectionDegrees) {

    public ZenithSurfaceDescriptor {
        if (chordMeters <= 0) throw new IllegalArgumentException("chord must be > 0");
        if (spanMeters <= 0) throw new IllegalArgumentException("span must be > 0");
    }

    public double areaSquareMeters() { return chordMeters * spanMeters; }
    public double aspectRatio() { return spanMeters / chordMeters; }
}
