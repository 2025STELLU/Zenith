package net.atomos.zenith.weather;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * 海风：海陆温差搞出来的日循环局地环流。
 *
 * 白天（10 点到 16 点最强）海风从海上往陆上吹，最大 5 m/s；
 * 夜间反过来，陆风往海上吹，弱一些（2 m/s）。
 * 白天还有一条"海风锋"在往内陆推进——辐合线，锋面附近湍流大、容易起积云。
 * 只在近岸 {@link #MAX_INLAND_DISTANCE} 格内、150 格 AGL 以下有效。
 *
 * <p>实现：每 {@link #REFRESH_TICKS} tick 在玩家周围算一张 33×33（64 格步长）的海陆掩膜，
 * 对它做一次倒角距离变换得到有符号距离场 {@code D}（陆地为正、海上为负），
 * 再用 {@code ∇D} 求岸线法向。采样时双线性插值。
 *
 * <p>旧实现用 8 方向 × 32 格步长射线找"最近的海陆跃变"，法向只有 8 个可能值、
 * 距离量化到 32 格（WX-SB-02），且每 100 tick 要打约 13k 次区块查询（WX-SB-04）。
 * 距离变换只查 1089 次，法向是连续的梯度。
 */
public class SeaBreezeSystem implements WeatherPhenomenon {
    public static final int GRID = 33;
    public static final double CELL = 64.0;
    public static final double MAX_BREEZE_MPS = 5.0;
    public static final double MAX_LANDBREEZE_MPS = 2.0;
    public static final double INLAND_DECAY_BLOCKS = 170.0;
    public static final double MAX_HEIGHT_AGL = 150.0;
    public static final double FRONT_SPEED_MPS = 1.6;
    /**
     * 海风影响的最远离岸距离，同时也是距离场能表达的上限。
     * 旧实现注释写 400、判定写 900、射线上限写 480，三处不一致（WX-SB-06）。
     */
    public static final double MAX_INLAND_DISTANCE = 480.0;
    /** 距离场与地形的重算周期（tick）。 */
    public static final int REFRESH_TICKS = 100;
    /** 距离场饱和值：超过它的距离一律视作"离岸极远"，避免整片同质时出现无穷大。 */
    private static final double SATURATED_DISTANCE = 1.0e6;

    /**
     * 网格中心的下标（SYS-06）。
     *
     * <p>旧代码建场用 {@code GRID / 2}（整数除法 = 16），采样用 {@code GRID / 2.0}（16.5）：
     * 33 格网格的真实中心是下标 16，采样却按 16.5 换算，整张场系统性偏移半个格 = 32 格。
     * 现在统一用 {@code (GRID - 1) / 2.0}。
     */
    private static final double CENTER_INDEX = (GRID - 1) / 2.0;

    private final double[][] breezeU = new double[GRID][GRID];
    private final double[][] breezeW = new double[GRID][GRID];
    private final double[][] frontFactor = new double[GRID][GRID];
    /**
     * 每个格点的地表 y（AGL 基准）。
     * 旧实现用 {@code agl = max(0, y - 64)}：地表 100 的高原海岸上，贴着地面的点
     * 就已经算出 36 格 AGL，海风还没吹到地面就衰减掉一半；地表 40 的低地则
     * 在地面以下仍算 0 AGL（WX-SB-05 / SYS-03）。
     */
    private final double[][] groundY = new double[GRID][GRID];

    /** 海陆掩膜：{@code true} = 海洋。 */
    private final boolean[][] oceanMask = new boolean[GRID][GRID];
    /** 有符号距离（blocks）：陆地为正、海上为负。 */
    private final double[][] signedDist = new double[GRID][GRID];

    private double focusX, focusZ;
    /**
     * 建场时用的锚点（SYS-06）。
     *
     * <p>旧实现每 tick 更新 {@link #focusX}/{@link #focusZ}，但场每 {@value #REFRESH_TICKS} tick
     * 才重算一次：两次重算之间，采样用**新焦点**去解**旧场**的索引，海风跟着玩家滑走。
     * 现在把建场锚点单独存下来，采样只认它；焦点变了就只等下一次重算。
     */
    private double fieldFocusX, fieldFocusZ;
    private boolean hasField = false;
    /** 海风锋离岸距离（blocks），随白天推进、夜间回退。 */
    private double frontInlandDistance = 0;

    @Override
    public void tick(ServerLevel level, double dtSeconds, WeatherContext ctx) {
        if (!updateFocus(level)) { hasField = false; return; }

        // 海陆风随太阳走：正午海风最强、午夜陆风最强（太阳时间统一走 FogModel.solarFactor）
        double solar = FogModel.solarFactor(ctx.timeOfDay01());
        double dayFactor = clamp01(solar * 1.6);          // 白天强度
        double nightFactor = clamp01(-solar * 1.8);      // 夜间强度
        // 白天海风锋往内陆推，晚上再缩回去
        if (dayFactor > 0.15) frontInlandDistance = Math.min(320, frontInlandDistance + FRONT_SPEED_MPS * dtSeconds * dayFactor);
        else frontInlandDistance = Math.max(0, frontInlandDistance - FRONT_SPEED_MPS * 2.2 * dtSeconds);

        // 海岸线不会动，这场每 REFRESH_TICKS tick 重算一次就行（全量重算挺贵的）
        if (hasField && ctx.tick() % REFRESH_TICKS != 0) return;

        rebuildField(ctx, dayFactor, nightFactor);
        hasField = true;
    }

    /**
     * 重建海风场：一次 33×33 的海陆采样 → 倒角距离变换 → 梯度求法向。
     *
     * <p>旧实现是 1,089 个格点各打 8×15 次 {@code isOceanAt}（最坏 13 万次区块查询），
     * 这里是 1,089 次（WX-SB-04）。
     */
    private void rebuildField(WeatherContext ctx, double dayFactor, double nightFactor) {
        // 1) 海陆掩膜 + 地表高度
        for (int i = 0; i < GRID; i++) {
            for (int j = 0; j < GRID; j++) {
                double bx = focusX + (i - CENTER_INDEX) * CELL;
                double bz = focusZ + (j - CENTER_INDEX) * CELL;
                oceanMask[i][j] = ctx.terrain().isOceanAt(bx, bz);
                groundY[i][j] = ctx.terrain().heightAt(bx, bz);
            }
        }

        // 2) 有符号距离场：陆地为正、海上为负（与季风共用同一份实现，WX-MS-01）
        double[][] signed = CoastDistance.signedDistanceBlocks(oceanMask, CELL);
        for (int i = 0; i < GRID; i++)
            for (int j = 0; j < GRID; j++)
                signedDist[i][j] = signed[i][j];

        // 建场锚点记下来（SYS-06）：之后采样只认它，直到下一次重算
        fieldFocusX = focusX;
        fieldFocusZ = focusZ;

        // 3) 由 ∇D 求岸线法向 + 风场
        for (int i = 0; i < GRID; i++) {
            for (int j = 0; j < GRID; j++) {
                double distToCoast = signedDist[i][j];
                double u = 0, w = 0, front = 0;
                double[] landward = landwardDirection(i, j);
                if (landward != null && Math.abs(distToCoast) < MAX_INLAND_DISTANCE) {
                    double decay = Math.exp(-Math.abs(distToCoast) / INLAND_DECAY_BLOCKS);
                    // 白天（breeze>0）吹向陆地 = +landward；夜间（breeze<0）吹向海 = −landward。
                    // 海上近岸同样按这个方向：海风环流在近岸水面上也是向岸的补偿流。
                    double breeze = distToCoast >= 0
                            ? MAX_BREEZE_MPS * dayFactor - MAX_LANDBREEZE_MPS * nightFactor
                            : MAX_BREEZE_MPS * 0.4 * dayFactor;
                    u = landward[0] * breeze * decay;
                    w = landward[1] * breeze * decay;
                    if (distToCoast >= 0) {
                        // 海风锋：锋面附近辐合
                        double dFront = Math.abs(distToCoast - frontInlandDistance);
                        front = Math.exp(-dFront * dFront / (60 * 60)) * dayFactor;
                    }
                }
                breezeU[i][j] = u;
                breezeW[i][j] = w;
                frontFactor[i][j] = front;
            }
        }
    }

    /**
     * 岸线法向（指向内陆）的单位向量：{@code ∇D/|∇D|}。
     *
     * <p>{@code D} 陆地为正、海上为负且向内陆单调增大，所以 {@code ∇D} 恒指内陆；
     * 白天海风就是沿它吹（吹向陆地），夜里取反（吹向海）。
     * 梯度平坦（整片同质：内陆腹地或外海）时返回 {@code null}，不再像旧实现那样
     * 用第一个射线方向（+X）冒充"海的方向"（WX-SB-03）。
     */
    private double[] landwardDirection(int i, int j) {
        int i0 = Math.max(0, i - 1), i1 = Math.min(GRID - 1, i + 1);
        int j0 = Math.max(0, j - 1), j1 = Math.min(GRID - 1, j + 1);
        double gradX = (signedDist[i1][j] - signedDist[i0][j]) / Math.max(1, i1 - i0);
        double gradZ = (signedDist[i][j1] - signedDist[i][j0]) / Math.max(1, j1 - j0);
        double len = Math.hypot(gradX, gradZ);
        if (len < 1e-6) return null;
        return new double[]{gradX / len, gradZ / len};
    }

    /**
     * 倒角距离变换（chamfer 1:√2），返回每个格点到最近的"异类"格点的距离（格为单位）。
     *
     * @deprecated 已并入 {@link CoastDistance#chamfer}（与季风共用），保留仅为兼容外部调用。
     */
    @Deprecated
    static double[][] chamferDistance(boolean[][] mask, boolean wantSea) {
        return CoastDistance.chamfer(mask, wantSea);
    }

    /**
     * 锚点：全体在线玩家的平均位置（质心）。
     *
     * <p>旧实现取 {@code players.get(0)}——登录顺序，不是"离谁近"，于是多人服里
     * 海风永远围着第一个登录的人转，其他人要么被 clamp 到网格边缘拿到错位的风
     * （WX-SB-01）。取质心后棋盘覆盖所有在线玩家，配合 {@link #sample} 的域外
     * 直接返回 0，不会再张冠李戴。
     *
     * @return 是否至少有一个玩家（没有则本 tick 无海风）
     */
    private boolean updateFocus(ServerLevel level) {
        var players = level.players();
        if (players.isEmpty()) return false;
        double sx = 0, sz = 0;
        for (ServerPlayer p : players) { sx += p.getX(); sz += p.getZ(); }
        focusX = sx / players.size();
        focusZ = sz / players.size();
        return true;
    }

    @Override
    public WindContribution sample(double x, double y, double z, double baseWindX, double baseWindZ) {
        if (!hasField) return WindContribution.NONE;
        // SYS-06：索引用**建场锚点**，不是当前焦点。两次重算之间焦点会变，
        // 拿新焦点去解旧场的索引会让海风跟着玩家滑动。
        double fi = (x - fieldFocusX) / CELL + CENTER_INDEX;
        double fj = (z - fieldFocusZ) / CELL + CENTER_INDEX;
        // 域外不采样：旧实现 clamp 到边缘，会把网格另一头的风套在这个点上（WX-SB-01）
        if (fi < 0 || fj < 0 || fi >= GRID - 1 || fj >= GRID - 1) return WindContribution.NONE;
        int i0 = clamp((int) Math.floor(fi), 0, GRID - 2);
        int j0 = clamp((int) Math.floor(fj), 0, GRID - 2);
        double fx = clamp01(fi - i0), fz = clamp01(fj - j0);
        double u = bilerp(breezeU, i0, j0, fx, fz);
        double w = bilerp(breezeW, i0, j0, fx, fz);
        double front = bilerp(frontFactor, i0, j0, fx, fz);
        if (u == 0 && w == 0 && front == 0) return WindContribution.NONE;
        // AGL 用该格点真实地表 y，而不是写死的海平面 64（WX-SB-05）
        double agl = Math.max(0, y - bilerp(groundY, i0, j0, fx, fz));
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

    /** 供调试：某个世界坐标的有符号离岸距离（陆正海负）。 */
    public double signedDistanceAt(double x, double z) {
        double fi = (x - fieldFocusX) / CELL + CENTER_INDEX;
        double fj = (z - fieldFocusZ) / CELL + CENTER_INDEX;
        if (fi < 0 || fj < 0 || fi >= GRID - 1 || fj >= GRID - 1) return Double.NaN;
        int i0 = clamp((int) Math.floor(fi), 0, GRID - 2);
        int j0 = clamp((int) Math.floor(fj), 0, GRID - 2);
        return bilerp(signedDist, i0, j0, clamp01(fi - i0), clamp01(fj - j0));
    }

    private static double clamp01(double v) { return v < 0 ? 0 : Math.min(v, 1); }
    private static int clamp(int v, int lo, int hi) { return v < lo ? lo : Math.min(v, hi); }
}
