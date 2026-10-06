package net.atomos.zenith.api.client;

import net.atomos.zenith.api.SamplePolicy;
import net.atomos.zenith.api.ZenithBlockPos;
import net.atomos.zenith.api.ZenithVec3;
import net.atomos.zenith.api.ZenithWindSample;
import net.atomos.zenith.api.ZenithWorldRef;

/** 客户端风场运行时提供者（本地细节/可视化）。 */
public interface ZenithClientWindRuntimeProvider {
    ZenithWindSample sample(ZenithWorldRef world, ZenithVec3 position, SamplePolicy policy);

    default ZenithWindSample sample(ZenithWorldRef world, ZenithBlockPos position, SamplePolicy policy) {
        return sample(world, position.center(), policy);
    }
}
