package net.atomos.zenith.wind;

import java.util.List;

/**
 * L2 本地流场求解器，纯 Java。
 *
 * 原版 Aerodynamics4MC-Core 的 L2 是 D3Q27 cumulant LBM 原生求解器（JNI），
 * 这里改成了纯 Java 的解析/诊断模型——不用编译 native 库，代价是精度打折，
 * 但对游戏里的风来说够用了。模型就三块：
 *   障碍物绕流：附近的固体方块把风挤开偏转，身后拖一条尾流亏损锥（高斯恢复）；
 *   热浮力：熔岩/火焰/火把/营火/风扇加热之类的热源顶出一股上升气流；
 *   风扇/风道：定向射流。
 * 求解器无状态，逐点诊断；玩法和视觉的采样都走这里。
 */
public class LocalFlowSolver {
    public interface ObstacleProbe {
        boolean isSolid(int x, int y, int z);
    }

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
     * 算给定点的本地流场修正。无状态：每次调用都把障碍物、热源、射流全算一遍。
     *
     * @param baseX/Y/Z L1 基流 (m/s)
     */
    public LocalFlow solve(double x, double y, double z,
                           double baseX, double baseY, double baseZ) {
        double modX = 0, modY = 0, modZ = 0;
        boolean sheltered = false;

        // 障碍物绕流
        double baseSpeed = Math.sqrt(sqr(baseX) + sqr(baseZ));
        if (baseSpeed > 0.05) {
            double dirX = baseX / baseSpeed, dirZ = baseZ / baseSpeed;
            int r = 4; // 9³ 扫描：格子数再大性能顶不住，再小绕流不准
            int bx = (int) Math.floor(x), by = (int) Math.floor(y), bz = (int) Math.floor(z);
            for (int dx = -r; dx <= r; dx++)
                for (int dy = -r; dy <= r; dy++)
                    for (int dz = -r; dz <= r; dz++) {
                        if (!obstacles.isSolid(bx + dx, by + dy, bz + dz)) continue;
                        double px = x - (bx + dx + 0.5);
                        double py = y - (by + dy + 0.5);
                        double pz = z - (bz + dz + 0.5);
                        double d2 = px * px + py * py + pz * pz;
                        if (d2 < 0.25) { // 采样点在固体里，直接算遮蔽
                            sheltered = true;
                            continue;
                        }
                        double d = Math.sqrt(d2);
                        double push = Math.min(1.2, 1.0 / d2) * baseSpeed * 0.35;
                        modX += px / d * push;
                        modY += py / d * push * 0.7;
                        modZ += pz / d * push;
                        // 尾流亏损：下风方向的锥形区
                        double along = px * dirX + pz * dirZ; // >0 在方块下风侧
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

        // 热浮力
        for (HeatSource h : heat.heatSourcesNear(x, y, z, 24)) {
            double dx = x - h.x(), dy = y - h.y(), dz = z - h.z();
            double d2 = dx * dx + dy * dy + dz * dz;
            double rr = h.radiusBlocks();
            double up = h.powerWatts() / 1500.0; // 按 1500 W 归一化
            double fall = 1.0 / (1.0 + d2 / (rr * rr));
            if (dy > -2) { // 热源上方才有上升柱
                modY += Math.min(6.0, up * 4.0) * fall;
                // 热泡夹带：周围空气被往里吸一点
                double dh = Math.sqrt(dx * dx + dz * dz);
                if (dh > 0.5) {
                    modX += -dx / dh * up * 0.8 * fall;
                    modZ += -dz / dh * up * 0.8 * fall;
                }
            }
        }

        // 风扇射流
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

        return new LocalFlow(modX, modY, modZ, sheltered, 1.0);
    }

    private static double sqr(double v) { return v * v; }
}
