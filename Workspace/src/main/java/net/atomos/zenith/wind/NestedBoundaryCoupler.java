package net.atomos.zenith.wind;

/**
 * 嵌套边界耦合：海绵层混合。
 *
 * 局部域边缘用二次混合把 L2 细节平滑过渡到 L1 基流，
 * 不然边界上会有反射和截断的伪影。
 */
public final class NestedBoundaryCoupler {
    private NestedBoundaryCoupler() {}

    /**
     * 海绵混合系数：中心 →0（保留细节），边缘 →1（强制回到基流）。
     */
    public static double spongeBlend(double distFromCenter, double domainRadius, double spongeLayers) {
        double inner = domainRadius - spongeLayers;
        if (distFromCenter <= inner) return 0.0;
        if (distFromCenter >= domainRadius) return 1.0;
        double t = (distFromCenter - inner) / spongeLayers;
        return t * t;
    }

    public static double blend(double detail, double base, double eta) {
        return detail + (base - detail) * eta;
    }
}
