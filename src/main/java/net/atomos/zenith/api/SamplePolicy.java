package net.atomos.zenith.api;

/**
 * 采样策略：决定采样可使用的数据源。
 *
 * <ul>
 *   <li>{@link #SERVER_COARSE_ONLY} —— 仅服务端粗网格（L0/L1），最保守</li>
 *   <li>{@link #GAMEPLAY_SERVER_ONLY} —— 游戏玩法：仅服务端权威数据</li>
 *   <li>{@link #SERVER_AGGREGATED_PREFERRED} —— 服务端聚合优先</li>
 *   <li>{@link #CLIENT_LOCAL_PREFERRED} —— 客户端粒子/烟雾等视觉：本地优先</li>
 *   <li>{@link #VISUAL_LOCAL_FIRST} —— 工程覆盖层/诊断：本地细节优先</li>
 *   <li>{@link #DIAGNOSTIC_ALL_SOURCES} —— 诊断：所有数据源</li>
 * </ul>
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
