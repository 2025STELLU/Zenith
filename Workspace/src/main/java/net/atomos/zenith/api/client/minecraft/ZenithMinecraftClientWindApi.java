package net.atomos.zenith.api.client.minecraft;

import net.atomos.zenith.api.SamplePolicy;
import net.atomos.zenith.api.ZenithId;
import net.atomos.zenith.api.ZenithWorldRef;
import net.atomos.zenith.api.ZenithWindSample;
import net.atomos.zenith.api.client.ZenithClientWindApi;
import net.atomos.zenith.api.minecraft.ZenithMinecraftVectors;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

/** 客户端 MC 类型采样桥（粒子/视觉用）。 */
public final class ZenithMinecraftClientWindApi {
    private ZenithMinecraftClientWindApi() {}

    public static ZenithWorldRef clientWorldRef() {
        var level = Minecraft.getInstance().level;
        if (level == null) return ZenithWorldRef.client(ZenithId.of("minecraft", "overworld"));
        return ZenithWorldRef.client(ZenithId.parse(level.dimension().location().toString()));
    }

    public static ZenithWindSample sample(Vec3 pos) {
        return ZenithClientWindApi.sample(clientWorldRef(),
                ZenithMinecraftVectors.fromMinecraft(pos), SamplePolicy.CLIENT_LOCAL_PREFERRED);
    }

    public static ZenithWindSample sample(Vec3 pos, SamplePolicy policy) {
        return ZenithClientWindApi.sample(clientWorldRef(),
                ZenithMinecraftVectors.fromMinecraft(pos), policy);
    }

    public static ZenithWindSample sample(BlockPos pos, SamplePolicy policy) {
        return ZenithClientWindApi.sample(clientWorldRef(),
                ZenithMinecraftVectors.fromMinecraft(pos), policy);
    }
}
