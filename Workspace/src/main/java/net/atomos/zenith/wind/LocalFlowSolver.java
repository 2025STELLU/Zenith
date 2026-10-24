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

    /**
     * 地面高度探针：返回该列**最高固体方块的 y**（没有方块返回 {@code minBuildHeight-1}）。
     *
     * <p>L2 的 9³ 扫描必须把"脚下这层地"排除在障碍物之外，否则平地上每一格地面都会
     * 以 {@code push ∝ 1/d²} 把空气往上顶：{@code y=63} 的方块对 {@code y=65.6} 的采样点
     * 贡献 11 m/s 的伪上升（L2-01）。允许为 {@code null}（无地面信息时退回旧行为）。
     */
    public interface GroundProbe {
        int groundY(int x, int z);
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
    private final GroundProbe ground;

    public LocalFlowSolver(ObstacleProbe obstacles, HeatProbe heat, JetProbe jets) {
        this(obstacles, heat, jets, null);
    }

    /**
     * @param ground 地面探测；给 {@code null} 时退回"所有固体都是障碍"的旧行为
     *               （会把地面当障碍，平地出现伪上升，见 L2-01）
     */
    public LocalFlowSolver(ObstacleProbe obstacles, HeatProbe heat, JetProbe jets, GroundProbe ground) {
        this.obstacles = obstacles;
        this.heat = heat;
        this.jets = jets;
        this.ground = ground;
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

            // 局地地面：5×5 的最小值。地面是"支撑面"不是障碍物，
            // 否则平地每一格地砖都在把空气往上顶（L2-01）。
            int groundY = Integer.MIN_VALUE;
            if (ground != null) {
                int best = Integer.MAX_VALUE;
                for (int gx = -2; gx <= 2; gx++)
                    for (int gz = -2; gz <= 2; gz++) {
                        int gy = ground.groundY(bx + gx, bz + gz);
                        // MIN_VALUE = 该列未知/未加载：跳过，别让一列未加载把整片探针废掉
                        if (gy != Integer.MIN_VALUE) best = Math.min(best, gy);
                    }
                groundY = best;
            }

            double pushX = 0, pushY = 0, pushZ = 0;
            double wakeX = 0, wakeZ = 0;
            for (int dx = -r; dx <= r; dx++)
                for (int dy = -r; dy <= r; dy++)
                    for (int dz = -r; dz <= r; dz++) {
                        int sy = by + dy;
                        // 地面及以下一律不算障碍：脚下那层地砖不是"绕流物体"
                        if (sy <= groundY) continue;
                        if (!obstacles.isSolid(bx + dx, sy, bz + dz)) continue;
                        double px = x - (bx + dx + 0.5);
                        double py = y - (sy + 0.5);
                        double pz = z - (bz + dz + 0.5);
                        double d2 = px * px + py * py + pz * pz;
                        if (d2 < 0.25) { // 采样点在固体里，直接算遮蔽
                            sheltered = true;
                            continue;
                        }
                        // 扫描边缘的窗函数：避免 9³ 边界上的硬切（0.6r 内权重=1，之后 smoothstep 收敛到 0）
                        double edge = smoothstep((r + 0.5 - Math.sqrt(dx * dx + dy * dy + dz * dz)) / 1.5);
                        if (edge <= 0) continue;

                        double d = Math.sqrt(d2);
                        double push = Math.min(1.2, 1.0 / d2) * baseSpeed * 0.35 * edge;
                        pushX += px / d * push;
                        pushY += py / d * push * 0.7;
                        pushZ += pz / d * push;
                        // 尾流亏损：下风方向的锥形区
                        double along = px * dirX + pz * dirZ; // >0 在方块下风侧
                        double across = Math.abs(px * dirZ - pz * dirX) + Math.abs(py) * 0.7;
                        if (along > 0 && along < 14) {
                            double coneR = 0.8 + along * 0.35;
                            if (across < coneR) {
                                double deficit = Math.exp(-(across * across) / (coneR * coneR))
                                        * Math.exp(-along / 9.0) * edge;
                                wakeX -= dirX * baseSpeed * deficit * 0.55;
                                wakeZ -= dirZ * baseSpeed * deficit * 0.55;
                                if (deficit > 0.45) sheltered = true;
                            }
                        }
                    }

            // 两项分别限幅：旧实现把 729 格贡献直接相加，平地也能凑出 11 m/s（L2-01）
            double pushCap = 0.5 * baseSpeed;
            double[] push = clampVec(pushX, pushY, pushZ, pushCap);
            modX += push[0];
            modY += push[1];
            modZ += push[2];
            double wakeCap = 0.9 * baseSpeed;
            double wakeMag = Math.hypot(wakeX, wakeZ);
            if (wakeMag > wakeCap) {
                wakeX *= wakeCap / wakeMag;
                wakeZ *= wakeCap / wakeMag;
            }
            modX += wakeX;
            modZ += wakeZ;
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

    /** 平滑窗：0..1 之间用 3t²−2t³。 */
    private static double smoothstep(double t) {
        if (t <= 0) return 0;
        if (t >= 1) return 1;
        return t * t * (3 - 2 * t);
    }

    /** 把三维向量按模长限幅。 */
    private static double[] clampVec(double x, double y, double z, double cap) {
        double m = Math.sqrt(x * x + y * y + z * z);
        if (m <= cap || m <= 1e-9) return new double[]{x, y, z};
        double s = cap / m;
        return new double[]{x * s, y * s, z * s};
    }
}
