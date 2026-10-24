package net.atomos.zenith.api.client;

import net.atomos.zenith.api.SamplePolicy;
import net.atomos.zenith.api.ZenithBlockPos;
import net.atomos.zenith.api.ZenithVec3;
import net.atomos.zenith.api.ZenithWindSample;
import net.atomos.zenith.api.ZenithWorldRef;

/**
 * 客户端风场 API：供粒子、视觉覆盖层、工程诊断使用。
 * 返回的数据为客户端本地细节，禁止用于服务端游戏玩法。
 */
public final class ZenithClientWindApi {
    private static volatile ZenithClientWindRuntimeProvider provider;

    private ZenithClientWindApi() {}

    public static void bindRuntime(ZenithClientWindRuntimeProvider p) {
        provider = p;
    }

    public static boolean isAvailable() {
        return provider != null;
    }

    public static ZenithWindSample sample(ZenithWorldRef world, ZenithVec3 position) {
        return sample(world, position, SamplePolicy.CLIENT_LOCAL_PREFERRED);
    }

    public static ZenithWindSample sample(ZenithWorldRef world, ZenithVec3 position, SamplePolicy policy) {
        ZenithClientWindRuntimeProvider p = provider;
        if (p == null) throw new IllegalStateException("Zenith client wind runtime not bound");
        return p.sample(world, position, policy);
    }

    public static ZenithWindSample sample(ZenithWorldRef world, ZenithBlockPos position, SamplePolicy policy) {
        return sample(world, position.center(), policy);
    }
}
