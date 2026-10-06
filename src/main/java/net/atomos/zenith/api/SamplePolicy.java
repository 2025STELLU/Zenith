package net.atomos.zenith.api;

/**
 * 采样策略：采样时允许用哪些数据源。
 *
 * 玩法逻辑走保守的（只认服务端），客户端粒子/烟雾这类纯视觉可以本地优先，
 * 诊断、工程覆盖层就全开。按场景选，别乱用。
 */
public enum SamplePolicy {
    SERVER_COARSE_ONLY(false, false),
    GAMEPLAY_SERVER_ONLY(false, true),
    SERVER_AGGREGATED_PREFERRED(false, true),
    CLIENT_LOCAL_PREFERRED(true, false),
    VISUAL_LOCAL_FIRST(true, true),
    DIAGNOSTIC_ALL_SOURCES(true, true);

    private final boolean allowClientLocal;
    private final boolean allowAggregated;

    SamplePolicy(boolean allowClientLocal, boolean allowAggregated) {
        this.allowClientLocal = allowClientLocal;
        this.allowAggregated = allowAggregated;
    }

    public boolean allowClientLocal() { return allowClientLocal; }
    public boolean allowAggregated() { return allowAggregated; }
}
