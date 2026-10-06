package net.atomos.zenith.api;

import java.util.Objects;

/** 不可变整数方块坐标。 */
public final class ZenithBlockPos {
    private final int x;
    private final int y;
    private final int z;

    private ZenithBlockPos(int x, int y, int z) {
        this.x = x;
        this.y = y;
        this.z = z;
    }

    public static ZenithBlockPos of(int x, int y, int z) {
        return new ZenithBlockPos(x, y, z);
    }

    public int x() { return x; }
    public int y() { return y; }
    public int z() { return z; }

    public ZenithVec3 center() { return ZenithVec3.of(x + 0.5, y + 0.5, z + 0.5); }

    @Override public boolean equals(Object o) {
        if (!(o instanceof ZenithBlockPos p)) return false;
        return x == p.x && y == p.y && z == p.z;
    }

    @Override public int hashCode() { return Objects.hash(x, y, z); }

    @Override public String toString() { return "ZenithBlockPos[" + x + ", " + y + ", " + z + "]"; }
}
