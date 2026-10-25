package net.atomos.zenith.api;

/**
 * 风场运行时提供者（服务端）。主模组启动时注册实现，
 * 其他模组只许调 ZenithWindApi，不许直接依赖实现类。
 */
public interface ZenithWindRuntimeProvider {
    ZenithWindSample sample(ZenithWorldRef world, ZenithVec3 position, SamplePolicy policy);

    ZenithWindSample sample(ZenithWorldRef world, ZenithBlockPos position, SamplePolicy policy);

    default ZenithWindSample sample(ZenithPlayerRef player, ZenithVec3 position, SamplePolicy policy) {
        return sample(player.world(), position, policy);
    }

    default ZenithWindSample sample(ZenithPlayerRef player, ZenithBlockPos position, SamplePolicy policy) {
        return sample(player.world(), position, policy);
    }

    /** L2 精细流场查询（可选实现，不支持返回 unsupported）。 */
    default ZenithL2Result runL2(ZenithL2Request request) {
        return ZenithL2Result.unsupported();
    }

    /** 翼型极线计算，薄翼型理论（可选实现）。 */
    default ZenithPolarResult runPolar(ZenithPolarRequest request) {
        return ZenithPolarResult.unsupported();
    }

    /** 地形采样（可选实现）。 */
    default ZenithTerrainSample sampleTerrain(ZenithWorldRef world, ZenithBlockPos pos) {
        return ZenithTerrainSample.unknown();
    }

    /**
     * 批量采样（API-05）：{@code positions} 是 {@code [x,y,z,...]} 展平数组。
     *
     * <p>默认实现逐点调 {@link #sample}；有能力的实现应重写它，把维度查找
     * （{@code levelFor}）与 L0/L1 的公共准备工作提到循环外——一次算一片时
     * 那是 n 倍的重复。
     */
    default ZenithWindSample[] sampleBatch(ZenithWorldRef world, double[] positions, SamplePolicy policy) {
        if (positions == null || positions.length % 3 != 0) {
            throw new IllegalArgumentException("positions must be a flat [x,y,z,...] array");
        }
        int n = positions.length / 3;
        ZenithWindSample[] out = new ZenithWindSample[n];
        for (int i = 0; i < n; i++) {
            out[i] = sample(world,
                    ZenithVec3.of(positions[i * 3], positions[i * 3 + 1], positions[i * 3 + 2]), policy);
        }
        return out;
    }
}
