package net.atomos.zenith.weather;

import net.atomos.zenith.wind.NoiseUtil;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * 海风：海陆温差搞出来的日循环局地环流。
 *
 * 白天（10 点到 16 点最强）海风从海上往陆上吹，最大 5 m/s；
 * 夜间反过来，陆风往海上吹，弱一些（2 m/s）。
 * 白天还有一条"海风锋"在往内陆推进——辐合线，锋面附近湍流大、容易起积云。
 * 只在近岸 400 格内、150 格 AGL 以下有效。
 *
 * 实现上偷个懒：每 tick 在玩家周围算一张 33×33（64 格步长）的海风场，
 * 采样时双线性插值，海岸线反正不会动，100 tick 重算一次就行。
 */
public class SeaBreezeSystem implements WeatherPhenomenon {
    public static final int GRID = 33;
    public static final double CELL = 64.0;
    public static final double MAX_BREEZE_MPS = 5.0;
    public static final double MAX_LANDBREEZE_MPS = 2.0;
    public static final double INLAND_DECAY_BLOCKS = 170.0;
    public static final double MAX_HEIGHT_AGL = 150.0;
    public static final double FRONT_SPEED_MPS = 1.6;

    private final double[][] breezeU = new double[GRID][GRID];
    private final double[][] breezeW = new double[GRID][GRID];
    private final double[][] frontFactor = new double[GRID][GRID];
    private double focusX, focusZ;
    private boolean hasField = false;
    /** 海风锋离岸距离（blocks），随白天推进、夜间回退。 */
    private double frontInlandDistance = 0;

    @Override
    public void tick(ServerLevel level, double dtSeconds, WeatherContext ctx) {
        ServerPlayer anchor = pickAnchor(level);
        if (anchor == null) { hasField = false; return; }
        focusX = anchor.getX();
        focusZ = anchor.getZ();

        double solar = Math.sin(ctx.timeOfDay01() * Math.PI * 2.0 - Math.PI / 2.0);
        double dayFactor = clamp01(solar * 1.6);          // 白天强度
        double nightFactor = clamp01(-solar * 1.8);      // 夜间强度
        // 白天海风锋往内陆推，晚上再缩回去
        if (dayFactor > 0.15) frontInlandDistance = Math.min(320, frontInlandDistance + FRONT_SPEED_MPS * dtSeconds * dayFactor);
        else frontInlandDistance = Math.max(0, frontInlandDistance - FRONT_SPEED_MPS * 2.2 * dtSeconds);

        // 海岸线不会动，这场每 100 tick 重算一次就行（全量重算挺贵的）
        if (hasField && ctx.tick() % 100 != 0) return;

        for (int i = 0; i < GRID; i++) {
            for (int j = 0; j < GRID; j++) {
                double bx = focusX + (i - GRID / 2) * CELL;
                double bz = focusZ + (j - GRID / 2) * CELL;
                double[] coast = findCoast(ctx, bx, bz);
                double distToCoast = coast[0]; // 陆地为正、海上为负的离岸距离
                double seaDirX = coast[1], seaDirZ = coast[2];
                double u = 0, w = 0, front = 0;
                if (distToCoast < 900) {
                    double decay = Math.exp(-Math.abs(distToCoast) / INLAND_DECAY_BLOCKS);
                    if (distToCoast >= 0) {
                        // 陆地侧：白天海风从海上吹进来，晚上陆风吹出去
                        double breeze = MAX_BREEZE_MPS * dayFactor - MAX_LANDBREEZE_MPS * nightFactor;
                        u = -seaDirX * breeze * decay;
                        w = -seaDirZ * breeze * decay;
                        // 海风锋：锋面附近辐合
                        double dFront = Math.abs(distToCoast - frontInlandDistance);
                        front = Math.exp(-dFront * dFront / (60 * 60)) * dayFactor;
                    } else {
                        // 海上是海风环流的补偿下沉区，风弱
                        double breeze = MAX_BREEZE_MPS * 0.4 * dayFactor;
                        u = -seaDirX * breeze * decay;
                        w = -seaDirZ * breeze * decay;
                    }
                }
                breezeU[i][j] = u;
                breezeW[i][j] = w;
                frontFactor[i][j] = front;
            }
        }
        hasField = true;
    }

    /** 8 个方向放射找最近的海岸线。返回 {离岸距离（陆地正/海上负）, 指向海洋的单位向量 x, z}。 */
    private double[] findCoast(WeatherContext ctx, double bx, double bz) {
        boolean land = !ctx.terrain().isOceanAt(bx, bz);
        double bestDist = Double.MAX_VALUE;
        double seaX = 0, seaZ = 0;
        for (int d = 0; d < 8; d++) {
            double ang = d * Math.PI / 4.0;
            double dx = Math.cos(ang), dz = Math.sin(ang);
            for (double r = 32; r <= 480; r += 32) {
                boolean ocean = ctx.terrain().isOceanAt(bx + dx * r, bz + dz * r);
                if (ocean == land) { // 扫到海陆分界
                    if (r < bestDist) {
                        bestDist = r;
                        if (land) { seaX = dx; seaZ = dz; }
                        else { seaX = -dx; seaZ = -dz; }
                    }
                    break;
                }
            }
        }
        if (bestDist == Double.MAX_VALUE) return new double[]{1e9, 0, 0};
        double dist = land ? bestDist : -bestDist;
        double n = Math.hypot(seaX, seaZ);
        if (n < 1e-6) { seaX = 1; seaZ = 0; n = 1; }
        return new double[]{dist, seaX / n, seaZ / n};
    }

    private ServerPlayer pickAnchor(ServerLevel level) {
        var players = level.players();
        return players.isEmpty() ? null : players.get(0);
    }

    @Override
    public WindContribution sample(double x, double y, double z, double baseWindX, double baseWindZ) {
        if (!hasField) return WindContribution.NONE;
        double fi = (x - focusX) / CELL + GRID / 2.0;
        double fj = (z - focusZ) / CELL + GRID / 2.0;
        int i0 = clamp((int) Math.floor(fi), 0, GRID - 2);
        int j0 = clamp((int) Math.floor(fj), 0, GRID - 2);
        double fx = clamp01(fi - i0), fz = clamp01(fj - j0);
        double u = bilerp(breezeU, i0, j0, fx, fz);
        double w = bilerp(breezeW, i0, j0, fx, fz);
        double front = bilerp(frontFactor, i0, j0, fx, fz);
        if (u == 0 && w == 0 && front == 0) return WindContribution.NONE;
        // AGL 用 y-64 近似
        double agl = Math.max(0, y - 64);
        double hDecay = Math.exp(-agl / MAX_HEIGHT_AGL);
        double turb = front * 0.35 * hDecay;
        double vy = front * 1.2 * hDecay; // 锋面辐合，顶出一点上升气流
        return new WindContribution(u * hDecay, vy, w * hDecay, turb, front * 0.3);
    }

    private static double bilerp(double[][] g, int i, int j, double fx, double fz) {
        double a = g[i][j], b = g[i + 1][j], c = g[i][j + 1], d = g[i + 1][j + 1];
        return (a + (b - a) * fx) + ((c + (d - c) * fx) - (a + (b - a) * fx)) * fz;
    }

    @Override
    public boolean hasActive() { return hasField; }

    /** 当前海风锋离岸距离（blocks），供气象图/调试显示。 */
    public double frontInlandDistance() { return frontInlandDistance; }

    private static double clamp01(double v) { return v < 0 ? 0 : Math.min(v, 1); }
    private static int clamp(int v, int lo, int hi) { return v < lo ? lo : Math.min(v, hi); }
}
