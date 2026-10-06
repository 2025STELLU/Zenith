package net.atomos.zenith.api;

/** 采样信任规则：集中管理"什么数据可用于什么用途"。 */
public final class ZenithWindSamplingRules {
    private ZenithWindSamplingRules() {}

    /**
     * 给定策略与采样，判断该采样是否可被信任用于服务端游戏玩法。
     * 客户端本地数据永远不可用于服务端玩法。
     */
    public static boolean trustedForGameplay(ZenithWindSample sample, SamplePolicy policy) {
        if (sample == null || policy == null) return false;
        if (sample.isClientLocal()) return false;
        return switch (policy) {
            case GAMEPLAY_SERVER_ONLY, SERVER_AGGREGATED_PREFERRED, SERVER_COARSE_ONLY ->
                    sample.isTrustedForGameplay();
            default -> false;
        };
    }

    /** 该策略是否允许使用客户端本地细节。 */
    public static boolean mayUseClientLocal(SamplePolicy policy) {
        return policy != null && policy.allowClientLocal();
    }
}
