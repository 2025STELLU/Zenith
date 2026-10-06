package net.atomos.zenith.wind;

import java.util.List;

/**
 * L2 本地流场求解器（纯 Java）。
 *
 * <p>原版 Aerodynamics4MC-Core 的 L2 是 D3Q27 cumulant LBM 原生求解器（JNI），
 * Zenith 改为纯 Java 解析/诊断模型，无需编译 native 代码：</p>
 * <ul>
 *   <li>障碍物绕流：附近固体方块产生排挤偏转 + 下风尾流亏损（高斯恢复锥）</li>
 *   <li>浮力：热源（熔岩/火焰/火把/营火/风扇加热）产生上升气流</li>
 *   <li>风扇/风道：定向射流</li>
 *   <li>海绵层：{@link NestedBoundaryCoupler} 把 L2 细节向 L1 基流混合</li>
 * </ul>
 * <p>求解器是无状态的逐点诊断模型，游戏玩法与视觉采样都走这里。</p>
 */
public class LocalFlowSolver {
    /** 局部域半径（blocks），超出后细节衰减为 0。 */
    public static final double LOCAL_DOMAIN_RADIUS = 48.0;
    /** 海绵层厚度（blocks）。 */
    public static final double SPONGE_THICKNESS = 16.0;

    /** 障碍物探针。 */
    public interface ObstacleProbe {
        boolean isSolid(int x, int y, int z);
    }

    /** 热源。 */
    public record HeatSource(double x, double y, double z, double powerWatts, double radiusBlocks) {}

    public interface HeatProbe {
        List<HeatSource> heatSourcesNear(double x, double y, double z, double radius);
    }

    /** 定向射流（风扇/风道）。 */
    public record Jet(double x, double y, double z,
                      double dirX, double dirY, double dirZ,
                      double speedMps, double radiusBlocks, double lengthBlocks) {}

    public interface JetProbe {
        List<Jet> jetsNear(double x, double y, double z, double radius);
    }

    /** L2 求解输出。 */
    public record LocalFlow(double modX, double modY, double modZ,
                            boolean sheltered, double confidence) {}

    private final ObstacleProbe obstacles;
    private final HeatProbe heat;
    private final JetProbe jets;

    public LocalFlowSolver(ObstacleProbe obstacles, HeatProbe heat, JetProbe jets) {
        this.obstacles = obstacles;
        this.heat = heat;
        this.jets = jets;
    }

    /**
     * 计算给定点的本地流场修正。
     *
     * @param baseX/Y/Z L1 基流 (m/s)
     * @param focusX/Y/Z 局部域中心（海绵层参考）
     */
    public LocalFlow solve(double x, double y, double z,
                           double baseX, double baseY, double baseZ,
                           double focusX, double focusY, double focusZ) {
        double distFocus = Math.sqrt(sqr(x - focusX) + sqr(y - focusY) + sqr(z - focusZ));
        double sponge = NestedBoundaryCoupler.spongeBlend(distFocus, LOCAL_DOMAIN_RADIUS, SPONGE_THICKNESS);
        // sponge=1 at center → full L2 detail; →0 at edge → pure L1
        double detail = 1.0 - sponge;

        double modX = 0, modY = 0, modZ = 0;
        boolean sheltered = false;

        if (detail > 0.01) {
            // ---- 障碍物绕流 ----
            double baseSpeed = Math.sqrt(sqr(baseX) + sqr(baseZ));
            if (baseSpeed > 0.05) {
                double dirX = baseX / baseSpeed, dirZ = baseZ / baseSpeed;
                int r = 4; // 9³ 扫描，兼顾性能与绕流精度
                int bx = (int) Math.floor(x), by = (int) Math.floor(y), bz = (int) Math.floor(z);
                for (int dx = -r; dx <= r; dx++)
                    for (int dy = -r; dy <= r; dy++)
                        for (int dz = -r; dz <= r; dz++) {
                            if (!obstacles.isSolid(bx + dx, by + dy, bz + dz)) continue;
                            double px = x - (bx + dx + 0.5);
                            double py = y - (by + dy + 0.5);
                            double pz = z - (bz + dz + 0.5);
                            double d2 = px * px + py * py + pz * pz;
                            if (d2 < 0.25) { // 在固体内部
                                sheltered = true;
                                continue;
                            }
                            double d = Math.sqrt(d2);
                            // 排挤：沿径向向外推
                            double push = Math.min(1.2, 1.0 / d2) * baseSpeed * 0.35;
                            modX += px / d * push;
                            modY += py / d * push * 0.7;
                            modZ += pz / d * push;
                            // 尾流亏损：下风方向锥形区
                            double along = px * dirX + pz * dirZ; // >0 表示在方块下风
                            double across = Math.abs(px * dirZ - pz * dirX) + Math.abs(py) * 0.7;
                            if (along > 0 && along < 14) {
                                double coneR = 0.8 + along * 0.35;
                                if (across < coneR) {
                                    double deficit = Math.exp(-(across * across) / (coneR * coneR))
                                            * Math.exp(-along / 9.0);
                                    modX -= dirX * baseSpeed * deficit * 0.55;
                                    modZ -= dirZ * baseSpeed * deficit * 0.55;
                                    if (deficit > 0.45) sheltered = true;
                                }
                            }
                        }
            }

            // ---- 热浮力 ----
            for (HeatSource h : heat.heatSourcesNear(x, y, z, 24)) {
                double dx = x - h.x(), dy = y - h.y(), dz = z - h.z();
                double d2 = dx * dx + dy * dy + dz * dz;
                double rr = h.radiusBlocks();
                // 上升气流柱：热源上方最强
                double up = h.powerWatts() / 1500.0; // 归一化
                double fall = 1.0 / (1.0 + d2 / (rr * rr));
                if (dy > -2) {
                    modY += Math.min(6.0, up * 4.0) * fall * detail;
                    // 热泡夹带：轻微向内辐合
                    double dh = Math.sqrt(dx * dx + dz * dz);
                    if (dh > 0.5) {
                        modX += -dx / dh * up * 0.8 * fall;
                        modZ += -dz / dh * up * 0.8 * fall;
                    }
                }
            }

            // ---- 风扇射流 ----
            for (Jet jet : jets.jetsNear(x, y, z, 40)) {
                double px = x - jet.x(), py = y - jet.y(), pz = z - jet.z();
                double along = px * jet.dirX() + py * jet.dirY() + pz * jet.dirZ();
                if (along < 0 || along > jet.lengthBlocks()) continue;
                double across2 = (px * px + py * py + pz * pz) - along * along;
                double radius = jet.radiusBlocks() * (1 + along * 0.06);
                if (across2 > radius * radius) continue;
                double core = Math.exp(-across2 / (radius * radius * 0.5));
                double decay = 1.0 / (1.0 + along / (jet.lengthBlocks() * 0.5));
                double s = jet.speedMps() * core * decay;
                modX += jet.dirX() * s;
                modY += jet.dirY() * s;
                modZ += jet.dirZ() * s;
            }

            modX *= detail;
            modY *= detail;
            modZ *= detail;
        }

        return new LocalFlow(modX, modY, modZ, sheltered, detail);
    }

    private static double sqr(double v) { return v * v; }
}
