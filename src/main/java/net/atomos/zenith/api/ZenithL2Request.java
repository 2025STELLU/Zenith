package net.atomos.zenith.api;

/** L2 精细流场查询请求（诊断/工程用途）。 */
public final class ZenithL2Request {
    private final ZenithWorldRef world;
    private final ZenithVec3 center;
    private final float radiusBlocks;

    private ZenithL2Request(ZenithWorldRef world, ZenithVec3 center, float radiusBlocks) {
        this.world = world;
        this.center = center;
        this.radiusBlocks = radiusBlocks;
    }

    public static ZenithL2Request around(ZenithWorldRef world, ZenithVec3 center, float radiusBlocks) {
        return new ZenithL2Request(world, center, radiusBlocks);
    }

    public ZenithWorldRef world() { return world; }
    public ZenithVec3 center() { return center; }
    public float radiusBlocks() { return radiusBlocks; }
}
