package net.atomos.zenith.wind;

/**
 * 嵌套边界耦合：海绵层混合（移植自原版 {@code NestedBoundaryCoupler}）。
 *
 * <p>在局部域边缘用二次混合把 L2 细节平滑过渡到 L1 基流，防止反射与截断伪影。</p>
 */
public final class NestedBoundaryCoupler {
    private NestedBoundaryCoupler() {}

    /**
     * 海绵混合系数 eta：中心 →0（保留模拟态），边缘 →1（强制趋向基流）。
     *
     * @param distFromCenter 距局部域中心距离
     * @param domainRadius   局部域半径
     * @param spongeLayers   海绵层厚度
     */
    public static double spongeBlend(double distFromCenter, double domainRadius, double spongeLayers) {
        double inner = domainRadius - spongeLayers;
        if (distFromCenter <= inner) return 0.0;
        if (distFromCenter >= domainRadius) return 1.0;
        double t = (distFromCenter - inner) / spongeLayers;
        return t * t;
    }

    /** 混合：result = lerp(detail, base, eta)。 */
    public static double blend(double detail, double base, double eta) {
        return detail + (base - detail) * eta;
    }
}
