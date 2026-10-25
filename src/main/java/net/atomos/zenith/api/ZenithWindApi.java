package net.atomos.zenith.api;

/**
 * 服务端风场 API 外观。想采样风就调这里，别去翻内部网格类。
 *
 * 玩法逻辑（实体受力、红石、伤害判定）走 sampleGameplay，用之前必须检查
 * GameplayWindSample#isTrustedForGameplay——客户端传来的数据不可信。
 */
public final class ZenithWindApi {
    private static volatile ZenithWindRuntimeProvider provider;

    private ZenithWindApi() {}

    /** 主模组启动时调一次，用来绑定运行时实现。 */
    public static void bindRuntime(ZenithWindRuntimeProvider p) {
        provider = p;
    }

    public static boolean isAvailable() {
        return provider != null;
    }

    private static ZenithWindRuntimeProvider require() {
        ZenithWindRuntimeProvider p = provider;
        if (p == null) throw new IllegalStateException("Zenith wind runtime not bound");
        return p;
    }

    // ---- 通用采样 ----

    public static ZenithWindSample sample(ZenithWorldRef world, ZenithVec3 position) {
        return sample(world, position, SamplePolicy.SERVER_AGGREGATED_PREFERRED);
    }

    public static ZenithWindSample sample(ZenithWorldRef world, ZenithVec3 position, SamplePolicy policy) {
        return require().sample(world, position, policy);
    }

    public static ZenithWindSample sample(ZenithWorldRef world, ZenithBlockPos position) {
        return sample(world, position, SamplePolicy.SERVER_AGGREGATED_PREFERRED);
    }

    public static ZenithWindSample sample(ZenithWorldRef world, ZenithBlockPos position, SamplePolicy policy) {
        return require().sample(world, position, policy);
    }

    public static ZenithWindSample sample(ZenithPlayerRef player, ZenithVec3 position) {
        return sample(player, position, SamplePolicy.SERVER_AGGREGATED_PREFERRED);
    }

    public static ZenithWindSample sample(ZenithPlayerRef player, ZenithVec3 position, SamplePolicy policy) {
        return require().sample(player, position, policy);
    }

    public static ZenithWindSample sample(ZenithPlayerRef player, ZenithBlockPos position) {
        return sample(player, position, SamplePolicy.SERVER_AGGREGATED_PREFERRED);
    }

    public static ZenithWindSample sample(ZenithPlayerRef player, ZenithBlockPos position, SamplePolicy policy) {
        return require().sample(player, position, policy);
    }

    // ---- 游戏玩法采样（服务端权威） ----

    public static GameplayWindSample sampleGameplay(ZenithWorldRef world, ZenithVec3 position) {
        return sampleGameplay(world, position, SamplePolicy.GAMEPLAY_SERVER_ONLY);
    }

    public static GameplayWindSample sampleGameplay(ZenithWorldRef world, ZenithVec3 position,
                                                    SamplePolicy policy) {
        ZenithWindSample s = require().sample(world, position, policy);
        return ZenithWindSamplingRules.trustedForGameplay(s, policy)
                ? GameplayWindSample.of(s) : GameplayWindSample.empty();
    }

    public static GameplayWindSample sampleGameplay(ZenithWorldRef world, ZenithBlockPos position) {
        return sampleGameplay(world, position, SamplePolicy.GAMEPLAY_SERVER_ONLY);
    }

    public static GameplayWindSample sampleGameplay(ZenithWorldRef world, ZenithBlockPos position,
                                                    SamplePolicy policy) {
        ZenithWindSample s = require().sample(world, position, policy);
        return ZenithWindSamplingRules.trustedForGameplay(s, policy)
                ? GameplayWindSample.of(s) : GameplayWindSample.empty();
    }

    public static GameplayWindSample sampleGameplay(ZenithPlayerRef player, ZenithVec3 position) {
        return sampleGameplay(player, position, SamplePolicy.GAMEPLAY_SERVER_ONLY);
    }

    public static GameplayWindSample sampleGameplay(ZenithPlayerRef player, ZenithVec3 position,
                                                    SamplePolicy policy) {
        ZenithWindSample s = require().sample(player, position, policy);
        return ZenithWindSamplingRules.trustedForGameplay(s, policy)
                ? GameplayWindSample.of(s) : GameplayWindSample.empty();
    }

    public static GameplayWindSample sampleGameplay(ZenithPlayerRef player, ZenithBlockPos position) {
        return sampleGameplay(player, position, SamplePolicy.GAMEPLAY_SERVER_ONLY);
    }

    public static GameplayWindSample sampleGameplay(ZenithPlayerRef player, ZenithBlockPos position,
                                                    SamplePolicy policy) {
        ZenithWindSample s = require().sample(player, position, policy);
        return ZenithWindSamplingRules.trustedForGameplay(s, policy)
                ? GameplayWindSample.of(s) : GameplayWindSample.empty();
    }

    // ---- 便捷采样 ----

    public static ZenithVec3 sampleMeanVelocity(ZenithWorldRef world, ZenithVec3 position) {
        return sample(world, position).meanVelocityVector();
    }

    public static ZenithVec3 sampleMeanVelocity(ZenithWorldRef world, ZenithBlockPos position) {
        return sample(world, position).meanVelocityVector();
    }

    public static ZenithVec3 sampleEffectiveVelocity(ZenithWorldRef world, ZenithVec3 position) {
        return sample(world, position).effectiveVelocityVector();
    }

    public static ZenithVec3 sampleEffectiveVelocity(ZenithWorldRef world, ZenithBlockPos position) {
        return sample(world, position).effectiveVelocityVector();
    }

    public static ZenithVec3 sampleGameplayMeanVelocity(ZenithWorldRef world, ZenithVec3 position) {
        return sampleGameplay(world, position).meanVelocityVector();
    }

    public static ZenithVec3 sampleGameplayMeanVelocity(ZenithWorldRef world, ZenithBlockPos position) {
        return sampleGameplay(world, position).meanVelocityVector();
    }

    public static ZenithVec3 sampleGameplayEffectiveVelocity(ZenithWorldRef world, ZenithVec3 position) {
        return sampleGameplay(world, position).effectiveVelocityVector();
    }

    public static ZenithVec3 sampleGameplayEffectiveVelocity(ZenithWorldRef world, ZenithBlockPos position) {
        return sampleGameplay(world, position).effectiveVelocityVector();
    }

    // ---- 批量采样（API-05） ----

    /**
     * 一次采一片点。
     *
     * <p>旧 API 只有单点 {@code sample(world, pos)}：{@code WeatherSnapshotPacket} 内部
     * 要算 81 点只能自己写 private 循环；第三方要算一片区域时每次都要重新进
     * {@code levelFor()} 与维度查找。
     *
     * <p>约定：{@code positions} 是 {@code [x,y,z, x,y,z, ...]} 展平数组（长度必须是 3 的倍数），
     * 返回数组与点数一一对应。**单点失败不影响其它点**——那一项是 {@code Source.NONE} 的空样本。
     *
     * @throws IllegalArgumentException 长度不是 3 的倍数
     */
    public static ZenithWindSample[] sampleBatch(ZenithWorldRef world, double[] positions,
                                                SamplePolicy policy) {
        if (positions == null || positions.length % 3 != 0) {
            throw new IllegalArgumentException("positions must be a flat [x,y,z,...] array");
        }
        ZenithWindRuntimeProvider p = require();
        int n = positions.length / 3;
        ZenithWindSample[] out = new ZenithWindSample[n];
        for (int i = 0; i < n; i++) {
            ZenithVec3 pos = ZenithVec3.of(positions[i * 3], positions[i * 3 + 1], positions[i * 3 + 2]);
            try {
                out[i] = p.sample(world, pos, policy);
            } catch (RuntimeException e) {
                out[i] = ZenithWindSample.builder()
                        .authority(ZenithWindSample.Authority.SERVER_AUTHORITATIVE)
                        .build();
            }
        }
        return out;
    }

    public static ZenithWindSample[] sampleBatch(ZenithWorldRef world, double[] positions) {
        return sampleBatch(world, positions, SamplePolicy.SERVER_AGGREGATED_PREFERRED);
    }

    /** 当前 API 版本（API-05），例如 {@code "0.2"}。 */
    public static String apiVersion() {
        return ZenithApiVersion.API_VERSION;
    }

    // ---- L2 / 极线 ----

    public static ZenithL2Result runL2(ZenithL2Request request) {
        return require().runL2(request);
    }

    public static ZenithPolarResult runPolar(ZenithPolarRequest request) {
        return require().runPolar(request);
    }
}
