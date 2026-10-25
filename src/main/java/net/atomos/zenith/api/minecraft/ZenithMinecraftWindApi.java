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
 * 直接使用 MC 类型的采样桥。
 *
 * <p><b>客户端分派</b>（API-06）：{@link ZenithWindApi} 的 provider 在 {@code ZenithMod}
 * 构造里被绑到服务端 runtime，**客户端也执行这段**。所以旧实现里
 * {@code sample(clientLevel, pos)} 在专用服客户端上会落到 {@code sampleInternal}，
 * {@code getCurrentServer()} 为 {@code null} → 直接返回 {@code Source.NONE} 的空风：
 * 同一个 API 在单人有风、多人无风。
 *
 * <p>现在 {@code level.isClientSide} 一律转发到 {@link ZenithMinecraftClientWindApi}。
 * 客户端类被包在 {@link ClientBridge} 里，只有当真的在客户端调用时才加载，
 * 专用服不会因为这一个 import 就碰到 {@code net.minecraft.client.*}。
 */
public final class ZenithMinecraftWindApi {
    private ZenithMinecraftWindApi() {}

    public static ZenithWorldRef worldRefOf(Level level) {
        return level.isClientSide
                ? ZenithWorldRef.client(ZenithId.parse(level.dimension().location().toString()))
                : ZenithWorldRef.server(ZenithId.parse(level.dimension().location().toString()));
    }

    public static ZenithWindSample sample(Level level, Vec3 pos) {
        return sample(level, pos, SamplePolicy.VISUAL_LOCAL_FIRST);
    }

    public static ZenithWindSample sample(Level level, Vec3 pos, SamplePolicy policy) {
        if (level.isClientSide) {
            ZenithWindSample client = ClientBridge.sample(pos, policy);
            if (client != null) return client;
        }
        return ZenithWindApi.sample(worldRefOf(level), ZenithMinecraftVectors.fromMinecraft(pos), policy);
    }

    public static ZenithWindSample sample(Level level, BlockPos pos) {
        return sample(level, pos, SamplePolicy.VISUAL_LOCAL_FIRST);
    }

    public static ZenithWindSample sample(Level level, BlockPos pos, SamplePolicy policy) {
        if (level.isClientSide) {
            ZenithWindSample client = ClientBridge.sample(
                    Vec3.atCenterOf(pos), policy);
            if (client != null) return client;
        }
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

    /**
     * 客户端桥的延迟加载壳：把对 {@code net.minecraft.client.*} 的引用关在这里，
     * 专用服永远不会初始化这个类。客户端 runtime 没绑定时返回 {@code null}，
     * 调用方退回服务端 provider（单人下的旧行为）。
     */
    private static final class ClientBridge {
        static ZenithWindSample sample(Vec3 pos, SamplePolicy policy) {
            try {
                if (!net.atomos.zenith.api.client.ZenithClientWindApi.isAvailable()) return null;
                return net.atomos.zenith.api.client.minecraft.ZenithMinecraftClientWindApi.sample(pos, policy);
            } catch (RuntimeException | LinkageError e) {
                return null;
            }
        }
    }
}
