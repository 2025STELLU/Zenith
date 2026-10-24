package net.atomos.zenith.api.minecraft;

import net.atomos.zenith.api.GameplayWindSample;
import net.atomos.zenith.api.SamplePolicy;
import net.atomos.zenith.api.ZenithId;
import net.atomos.zenith.api.ZenithVec3;
import net.atomos.zenith.api.ZenithWindApi;
import net.atomos.zenith.api.ZenithWindSample;
import net.atomos.zenith.api.ZenithWorldRef;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * 直接使用 MC 类型的服务端采样桥。仅在服务端逻辑线程调用。
 */
public final class ZenithMinecraftWindApi {
    private ZenithMinecraftWindApi() {}

    public static ZenithWorldRef worldRefOf(Level level) {
        return level.isClientSide
                ? ZenithWorldRef.client(ZenithId.parse(level.dimension().location().toString()))
                : ZenithWorldRef.server(ZenithId.parse(level.dimension().location().toString()));
    }

    public static ZenithWindSample sample(Level level, Vec3 pos) {
        return ZenithWindApi.sample(worldRefOf(level), ZenithMinecraftVectors.fromMinecraft(pos));
    }

    public static ZenithWindSample sample(Level level, Vec3 pos, SamplePolicy policy) {
        return ZenithWindApi.sample(worldRefOf(level), ZenithMinecraftVectors.fromMinecraft(pos), policy);
    }

    public static ZenithWindSample sample(Level level, BlockPos pos) {
        return ZenithWindApi.sample(worldRefOf(level), ZenithMinecraftVectors.fromMinecraft(pos));
    }

    public static ZenithWindSample sample(Level level, BlockPos pos, SamplePolicy policy) {
        return ZenithWindApi.sample(worldRefOf(level), ZenithMinecraftVectors.fromMinecraft(pos), policy);
    }

    public static GameplayWindSample sampleGameplay(ServerLevel level, Vec3 pos) {
        return ZenithWindApi.sampleGameplay(worldRefOf(level), ZenithMinecraftVectors.fromMinecraft(pos));
    }

    public static GameplayWindSample sampleGameplay(ServerLevel level, BlockPos pos) {
        return ZenithWindApi.sampleGameplay(worldRefOf(level), ZenithMinecraftVectors.fromMinecraft(pos));
    }

    public static GameplayWindSample sampleGameplay(ServerPlayer player, Vec3 pos) {
        ServerLevel level = player.serverLevel();
        return ZenithWindApi.sampleGameplay(worldRefOf(level), ZenithMinecraftVectors.fromMinecraft(pos));
    }

    /** 玩家位置处的有效风速（已做玩法信任检查，不可信返回零向量）。 */
    public static Vec3 gameplayWindAt(Player player, Vec3 pos) {
        if (!(player.level() instanceof ServerLevel level)) return Vec3.ZERO;
        GameplayWindSample s = sampleGameplay(level, pos);
        if (!s.isTrustedForGameplay()) return Vec3.ZERO;
        ZenithVec3 v = s.effectiveVelocityVector();
        return new Vec3(v.x(), v.y(), v.z());
    }
}
