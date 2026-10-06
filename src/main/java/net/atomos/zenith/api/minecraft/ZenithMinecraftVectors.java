package net.atomos.zenith.api.minecraft;

import net.atomos.zenith.api.ZenithBlockPos;
import net.atomos.zenith.api.ZenithVec3;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

/** MC 原生类型 ⇄ Zenith API 类型的转换。 */
public final class ZenithMinecraftVectors {
    private ZenithMinecraftVectors() {}

    public static ZenithVec3 fromMinecraft(Vec3 v) {
        return ZenithVec3.of(v.x, v.y, v.z);
    }

    public static Vec3 toMinecraft(ZenithVec3 v) {
        return new Vec3(v.x(), v.y(), v.z());
    }

    public static ZenithBlockPos fromMinecraft(BlockPos p) {
        return ZenithBlockPos.of(p.getX(), p.getY(), p.getZ());
    }

    public static BlockPos toMinecraft(ZenithBlockPos p) {
        return new BlockPos(p.x(), p.y(), p.z());
    }
}
