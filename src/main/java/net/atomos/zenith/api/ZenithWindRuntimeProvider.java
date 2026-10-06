package net.atomos.zenith.api;

/**
 * 风场运行时提供者（服务端）。Zenith 主模组在启动时注册实现，
 * 其他模组仅通过 {@link ZenithWindApi} 调用，不直接依赖实现类。
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

    /** L2 请求（精细流场查询），可选实现。 */
    default ZenithL2Result runL2(ZenithL2Request request) {
        return ZenithL2Result.unsupported();
    }

    /** 翼型极线计算（薄翼型理论），可选实现。 */
    default ZenithPolarResult runPolar(ZenithPolarRequest request) {
        return ZenithPolarResult.unsupported();
    }

    /** 地形采样（可选）。 */
    default ZenithTerrainSample sampleTerrain(ZenithWorldRef world, ZenithBlockPos pos) {
        return ZenithTerrainSample.unknown();
    }
}
