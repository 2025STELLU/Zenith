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

    // ---- L2 / 极线 ----

    public static ZenithL2Result runL2(ZenithL2Request request) {
        return require().runL2(request);
    }

    public static ZenithPolarResult runPolar(ZenithPolarRequest request) {
        return require().runPolar(request);
    }
}
