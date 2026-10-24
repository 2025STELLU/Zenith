package net.atomos.zenith.api;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** 翼型定义：标识 + 有序坐标点（上表面前缘→后缘→下表面后缘→前缘）。 */
public final class ZenithAirfoilDefinition {
    private final ZenithId id;
    private final String displayName;
    private final List<ZenithAirfoilCoordinate> coordinates;

    public ZenithAirfoilDefinition(ZenithId id, String displayName,
                                   List<ZenithAirfoilCoordinate> coordinates) {
        this.id = Objects.requireNonNull(id);
        this.displayName = Objects.requireNonNull(displayName);
        this.coordinates = Collections.unmodifiableList(coordinates);
    }

    public ZenithId id() { return id; }
    public String displayName() { return displayName; }
    public List<ZenithAirfoilCoordinate> coordinates() { return coordinates; }

    /** 最大相对厚度估计。 */
    public double maxThickness() {
        double max = 0;
        for (ZenithAirfoilCoordinate c : coordinates) max = Math.max(max, Math.abs(c.y()));
        return max * 2.0;
    }
}
