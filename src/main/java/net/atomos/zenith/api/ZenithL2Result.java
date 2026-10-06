package net.atomos.zenith.api;

/** L2 精细流场查询结果：合力/合力矩（诊断用）。 */
public final class ZenithL2Result {
    private final boolean supported;
    private final ZenithL2ForceMoment forceMoment;

    private ZenithL2Result(boolean supported, ZenithL2ForceMoment forceMoment) {
        this.supported = supported;
        this.forceMoment = forceMoment;
    }

    public static ZenithL2Result unsupported() {
        return new ZenithL2Result(false, ZenithL2ForceMoment.zero());
    }

    public static ZenithL2Result of(ZenithL2ForceMoment fm) {
        return new ZenithL2Result(true, fm);
    }

    public boolean isSupported() { return supported; }
    public ZenithL2ForceMoment forceMoment() { return forceMoment; }
}
