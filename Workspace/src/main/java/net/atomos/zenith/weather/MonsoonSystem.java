package net.atomos.zenith.weather;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * 季风：季节性的风向大反转。
 *
 * <p>年循环驱动：夏季风从东南吹向西北（暖湿），冬季风反过来（干冷），春秋两季过渡。
 *
 * <p><b>为什么不再是一个 {@code WeatherPhenomenon}</b>（WX-MS-01）：旧实现的
 * {@code sample()} 只看高度、不看水平位置，整个维度共用同一个季风矢量——玩家走到哪里，
 * 头顶都是同一股风。真实季风是海陆热力差驱动的**区域环流**，海岸最强、深内陆很弱。
 * 现在季风作为 L0 的**基础流偏置**注入驱动器（{@link
 * net.atomos.zenith.wind.WorldScaleDriver#setBaseFlowBias}），强度按离岸距离加权：
 *
 * <pre>
 * w(x,z) = exp(-|D(x,z)| / COAST_DECAY_BLOCKS)     // D 陆正海负，岸线处 0、权重 1
 * bias   = V_season · w(x,z)
 * </pre>
 *
 * 这样它和海风（{@link SeaBreezeSystem}）就是同一套岸线定义下的两个时间尺度：
 * 季风是年循环的大尺度背景，海风是日循环的局地环流。
 *
 * <p>季节相位仍借用 {@code season.tempBiasKelvin()/7} 当"年循环相位"（+1 盛夏、−1 隆冬），
 * 转场用 1 小时时间常数慢慢跟，不会一天之内掉头。
 */
public class MonsoonSystem {
    /** 海岸距离的衰减尺度（格）：离岸 600 格处权重降到 1/e。 */
    public static final double COAST_DECAY_BLOCKS = 600.0;
    /** 偏置场的网格：33×33、每格 256 格（±4096 格），与 L0 的格距一致。 */
    public static final int GRID = 33;
    public static final double CELL = 256.0;
    /** 偏置场重算周期（tick）：季风是年循环，10 分钟一次足够。 */
    public static final int REFRESH_TICKS = 600;

    /** 夏季风向（单位向量，指向"吹向"）：东南风 → 朝西北。 */
    private static final double SUMMER_DIR_X = -0.8;
    private static final double SUMMER_DIR_Z = -0.6;

    /** 当前维度平均季风向量（m/s），供 API 查询。 */
    private double monsoonX, monsoonZ;
    /** 目标强度（m/s），由年循环相位决定，1–5。 */
    private double targetStrength = 1.0;

    private final double[][] biasX = new double[GRID][GRID];
    private final double[][] biasZ = new double[GRID][GRID];
    private double focusX, focusZ;
    private boolean hasField = false;
    private long lastRefreshTick = Long.MIN_VALUE;

    /**
     * 每 tick 推进年循环相位；每 {@link #REFRESH_TICKS} 重算一次空间偏置场。
     *
     * <p>由 {@code ZenithServerRuntime} 直接调用（不再走 {@code phenomena()} 列表）。
     */
    public void tick(ServerLevel level, double dtSeconds, WeatherContext ctx) {
        if (!net.atomos.zenith.ZenithConfig.enableMonsoon()) {
            hasField = false;
            monsoonX = 0;
            monsoonZ = 0;
            return;
        }
        // 借季节温度偏置当作年循环相位：夏季 +1，冬季 −1
        double annual = clamp(ctx.seasonInfo().tempBiasKelvin() / 7.0, -1.0, 1.0);
        double dir = annual >= 0 ? 1.0 : -1.0;
        // 冬夏季风都强、春秋转换期弱：|annual| 就是强度包络
        targetStrength = 1.0 + 4.0 * Math.abs(annual);

        // 季风转得慢，用一小时时间常数慢慢跟
        double k = Math.min(1.0, dtSeconds / 3600.0);
        double tx = SUMMER_DIR_X * dir * targetStrength;
        double tz = SUMMER_DIR_Z * dir * targetStrength;
        monsoonX += (tx - monsoonX) * k;
        monsoonZ += (tz - monsoonZ) * k;

        if (!updateFocus(level)) {
            hasField = false;
            return;
        }
        if (hasField && ctx.tick() - lastRefreshTick < REFRESH_TICKS) return;
        lastRefreshTick = ctx.tick();
        rebuildField(ctx);
        hasField = true;
    }

    /**
     * 位置相关的季风偏置（m/s）。域外返回 0：宁可没有季风，也不把网格另一头的风套过来。
     *
     * @return {@code {biasX, biasZ}}，始终非 null
     */
    public double[] biasAt(double x, double z) {
        if (!hasField) return new double[]{0, 0};
        double fi = (x - focusX) / CELL + GRID / 2.0;
        double fj = (z - focusZ) / CELL + GRID / 2.0;
        if (fi < 0 || fj < 0 || fi >= GRID - 1 || fj >= GRID - 1) return new double[]{0, 0};
        int i0 = (int) Math.floor(fi), j0 = (int) Math.floor(fj);
        return new double[]{bilerp(biasX, i0, j0, fi - i0, fj - j0),
                bilerp(biasZ, i0, j0, fi - i0, fj - j0)};
    }

    /** 当天季风方向上的平均强度（m/s），供 API/调试。 */
    public double monsoonStrength() {
        return Math.hypot(monsoonX, monsoonZ);
    }

    public double monsoonX() { return monsoonX; }

    public double monsoonZ() { return monsoonZ; }

    private void rebuildField(WeatherContext ctx) {
        boolean[][] ocean = new boolean[GRID][GRID];
        for (int i = 0; i < GRID; i++)
            for (int j = 0; j < GRID; j++)
                ocean[i][j] = ctx.terrain().isOceanAt(
                        focusX + (i - GRID / 2) * CELL, focusZ + (j - GRID / 2) * CELL);

        double[][] dist = CoastDistance.signedDistanceBlocks(ocean, CELL);
        for (int i = 0; i < GRID; i++) {
            for (int j = 0; j < GRID; j++) {
                // 全同质时距离被夹到饱和值，权重趋 0：深内陆/远洋没有季风偏置
                double weight = Math.exp(-Math.abs(dist[i][j]) / COAST_DECAY_BLOCKS);
                biasX[i][j] = monsoonX * weight;
                biasZ[i][j] = monsoonZ * weight;
            }
        }
    }

    /** 锚点：全体在线玩家质心（与海风一致）；没人则本 tick 无偏置场。 */
    private boolean updateFocus(ServerLevel level) {
        var players = level.players();
        if (players.isEmpty()) return false;
        double sx = 0, sz = 0;
        for (ServerPlayer p : players) {
            sx += p.getX();
            sz += p.getZ();
        }
        focusX = sx / players.size();
        focusZ = sz / players.size();
        return true;
    }

    private static double bilerp(double[][] g, int i, int j, double fx, double fz) {
        double a = g[i][j], b = g[i + 1][j], c = g[i][j + 1], d = g[i + 1][j + 1];
        double top = a + (b - a) * fx;
        double bottom = c + (d - c) * fx;
        return top + (bottom - top) * fz;
    }

    private static double clamp(double v, double lo, double hi) {
        return v < lo ? lo : Math.min(v, hi);
    }
}
