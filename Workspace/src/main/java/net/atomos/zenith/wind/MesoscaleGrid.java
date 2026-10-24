package net.atomos.zenith.wind;

import net.atomos.zenith.weather.FogModel;

/**
 * L1 中尺度网格，服务端权威：33×33×8 格，水平每格 64 格方块，
 * 垂直 8 层按**离地高度**分层（2/6/14/30/60/110/190/300 格，近地密、高层疏）。
 *
 * 每刷新的流程：从 L0 拉一格样本 → 按 ABL 垂直廓线（对数律 + Ekman 偏转）
 * 铺开 8 层 → 表层过一遍地形（迎风坡减速、坡面热力风、地形抬升）→
 * 阵风和湍流诊断 → 输出诊断摘要反哺驱动器。
 *
 * <p>层必须用 AGL 定义、也必须按 AGL 采样：对数廓线在近地 0–30 格变化最剧烈，
 * 用等距的绝对高度层会在平地把整段近地风压成同一个常系数（见 L1-01）。
 */
public class MesoscaleGrid {
    public static final int RADIUS_CELLS = 16;
    public static final int SIZE = RADIUS_CELLS * 2 + 1; // 33
    public static final double CELL_SIZE_BLOCKS = 64.0;
    public static final int MAX_LAYERS = 8;

    /**
     * 各层中心的**离地**高度（格），严格递增。
     * <p>等比拉伸而不是等距：对数廓线在近地变化最快，2/6/14 格的分辨率比 40 格等距有用得多；
     * 顶层 300 格覆盖中性 ABL（280）与不稳定 ABL（460）的大部分。
     */
    private static final double[] LAYER_AGL_BLOCKS = {2, 6, 14, 30, 60, 110, 190, 300};

    /** 第 {@code layer} 层中心的离地高度（格）。 */
    public static double layerAglBlocks(int layer) {
        return LAYER_AGL_BLOCKS[Math.max(0, Math.min(MAX_LAYERS - 1, layer))];
    }

    /** 坡风/坡面减速的垂直衰减尺度（格）：约 1/3 个近地层厚度。 */
    private static final double SLOPE_FORCING_SCALE_BLOCKS = 30.0;
    /** 对流/龙卷强迫的垂直衰减尺度（格）：热泡与上升气流可及的高度。 */
    private static final double CONVECTIVE_FORCING_SCALE_BLOCKS = 120.0;
    /**
     * 廓线的离地高度下限（格）：≈ 1 个方块玩家的脚踝高度。
     * 无边界的对数律在 h→0 时发散到 −∞，这里取 0.3 格作数值地板（对应风速 ≈0.34×L0）。
     */
    private static final double MIN_AGL_BLOCKS = 0.3;

    public static final double ABL_NEUTRAL_HEIGHT_BLOCKS = 280.0;
    public static final double ABL_STABLE_HEIGHT_BLOCKS = 120.0;
    public static final double ABL_UNSTABLE_HEIGHT_BLOCKS = 460.0;
    public static final double ABL_EKMAN_MAX_TURN_RADIANS = 0.52;
    public static final double L1_TERRAIN_FORM_DRAG = 0.45;
    public static final double L1_THERMAL_SLOPE_WIND_MPS = 1.10;

    public interface TerrainProbe {
        double terrainHeightBlocks(double blockX, double blockZ);
        double roughness01(double blockX, double blockZ);
        /** 地形坡度向量 (dh/dx, dh/dz)，无量纲。 */
        double[] slopeVector(double blockX, double blockZ);
    }

    public static final class CellColumn {
        public final double[] windX = new double[MAX_LAYERS];
        public final double[] windY = new double[MAX_LAYERS];
        public final double[] windZ = new double[MAX_LAYERS];
        public final double[] temperatureK = new double[MAX_LAYERS];
        public final double[] humidity01 = new double[MAX_LAYERS];
        public double turbulenceIntensity;
        public double gustX, gustY, gustZ;
        public double shearXPerBlock, shearZPerBlock;
        public double ablStability;      // <0 不稳定，>0 稳定
        public double ablMixingStrength;
        public double surfaceTempK;
        public double terrainHeight;
        /**
         * 该列的地表粗糙元高度 z0 与廓线归一化分母。{@code sample()} 在最低层（2 格 AGL）以下
         * 直接按对数律求值，而不是把值夹成第 0 层（L1-01）。
         */
        public double profileZ0 = 0.5;
        public double profileDenom = 1.0;
        public double liftProxy;
        public double instabilityProxy;
    }

    /** 诊断摘要（反馈给 WorldScaleDriver）。 */
    public record DiagnosticsSummary(double maxInstabilityProxy, double maxLiftProxy,
                                     double meanLowLevelShear, double meanHumidity,
                                     double maxPositiveMoistureConvergence) {}

    public record Sample(double windX, double windY, double windZ,
                         double turbulenceIntensity,
                         double gustX, double gustY, double gustZ,
                         double shearPerBlock,
                         double airTempK, double humidity01,
                         double pressureAnomalyPa,
                         double ablStability, boolean sheltered) {}

    private final CellColumn[][] columns = new CellColumn[SIZE][SIZE];
    private double focusBlockX, focusBlockZ;
    private long seed;

    public MesoscaleGrid(long seed) {
        this.seed = seed;
        for (int i = 0; i < SIZE; i++)
            for (int j = 0; j < SIZE; j++)
                columns[i][j] = new CellColumn();
    }

    public void setFocus(double blockX, double blockZ) {
        this.focusBlockX = blockX;
        this.focusBlockZ = blockZ;
    }

    private double cellBlockX(int i) { return focusBlockX + (i - RADIUS_CELLS) * CELL_SIZE_BLOCKS; }
    private double cellBlockZ(int j) { return focusBlockZ + (j - RADIUS_CELLS) * CELL_SIZE_BLOCKS; }

    /**
     * 每 MESOSCALE_REFRESH_TICKS（64 tick）调用一次。
     */
    public DiagnosticsSummary refresh(BackgroundMetGrid l0, TerrainProbe terrain,
                                       double timeOfDay01, boolean raining, long tick) {
        double maxInstability = 0, maxLift = 0, shearSum = 0, humSum = 0, maxMoistConv = 0;
        int count = 0;

        for (int i = 0; i < SIZE; i++) {
            for (int j = 0; j < SIZE; j++) {
                double bx = cellBlockX(i), bz = cellBlockZ(j);
                BackgroundMetGrid.Sample base = l0.sample(bx, bz);
                CellColumn col = columns[i][j];

                col.terrainHeight = terrain.terrainHeightBlocks(bx, bz);
                double roughness = terrain.roughness01(bx, bz);
                double[] slope = terrain.slopeVector(bx, bz);
                col.surfaceTempK = base.surfaceTempK();

                // ABL 稳定度：地表比空气暖 → 不稳定（取负）
                double dT = base.surfaceTempK() - base.airTempK();
                col.ablStability = clamp(-dT / 8.0, -1, 1);
                double ablHeight = col.ablStability < -0.15 ? ABL_UNSTABLE_HEIGHT_BLOCKS
                        : col.ablStability > 0.15 ? ABL_STABLE_HEIGHT_BLOCKS
                        : ABL_NEUTRAL_HEIGHT_BLOCKS;
                col.ablMixingStrength = clamp01(0.4 - col.ablStability * 0.6
                        + base.convEnv() * 0.8 + base.torUpdraft() * 0.6);

                // 垂直廓线：对数律 + Ekman 偏转（层中心是离地高度，不是绝对 y）
                double u0 = base.windX(), w0 = base.windZ();
                double speed0 = Math.hypot(u0, w0);
                double dir0 = Math.atan2(w0, u0);
                double z0 = roughnessToZ0(roughness); // 粗糙元高度（m），由粗糙度映射
                // 廓线归一化的分母只跟 ABL 顶与 z0 有关，逐层不变
                double profDenom = Math.log((Math.max(ablHeight, z0 + 1) + z0) / z0);
                for (int l = 0; l < MAX_LAYERS; l++) {
                    double hAboveGround = LAYER_AGL_BLOCKS[l];
                    double prof = profileFactor(hAboveGround, z0, profDenom);
                    // 越往上风越大、偏转越多（Ekman 螺旋），到 ABL 顶收敛
                    double ekmanTurn = ABL_EKMAN_MAX_TURN_RADIANS
                            * clamp01(hAboveGround / ablHeight) * 0.9;
                    double dir = dir0 + ekmanTurn;
                    double spd = speed0 * prof;
                    col.windX[l] = Math.cos(dir) * spd;
                    col.windZ[l] = Math.sin(dir) * spd;
                    col.windY[l] = 0;
                    col.temperatureK[l] = base.airTempK()
                            - hAboveGround * BackgroundMetGrid.ALTITUDE_LAPSE_RATE_K_PER_BLOCK;
                    col.humidity01[l] = clamp01(base.humidity01() - l * 0.04);
                }
                // 2 格以下的廓线参数：留到 sample() 里按对数律求值
                col.profileZ0 = z0;
                col.profileDenom = profDenom;

                // 表层地形：迎风坡减速、坡面热力风、地形抬升。
                // 强迫按 exp(-AGL/h_scale) 分配到各层，而不是只写第 0 层：
                // 层中心现在是 2/6/14… 格 AGL，只改第 0 层会让 6 格处突然没有坡风（L1-06）。
                double slopeMag = Math.hypot(slope[0], slope[1]);
                if (slopeMag > 1e-4) {
                    double drag = clamp(L1_TERRAIN_FORM_DRAG * slopeMag * 3.0, 0, 0.6);
                    // 白天风往坡上爬、晚上往坡下溜（山谷风环流）：正午最强上坡风、午夜最强下坡风
                    double diurnal = FogModel.solarFactor(timeOfDay01);
                    double slopeWind = L1_THERMAL_SLOPE_WIND_MPS * diurnal
                            * clamp01(slopeMag * 4.0);
                    for (int l = 0; l < MAX_LAYERS; l++) {
                        double decay = Math.exp(-LAYER_AGL_BLOCKS[l] / SLOPE_FORCING_SCALE_BLOCKS);
                        if (decay < 0.02) break;
                        col.windX[l] *= (1 - drag * decay);
                        col.windZ[l] *= (1 - drag * decay);
                        col.windX[l] += slope[0] / slopeMag * slopeWind * decay;
                        col.windZ[l] += slope[1] / slopeMag * slopeWind * decay;
                        // 风撞上坡面，被顶起来
                        col.windY[l] += (col.windX[l] * slope[0] + col.windZ[l] * slope[1]) * 0.8;
                    }
                }

                // 驱动器的对流/龙卷强迫：同样按高度衰减，否则上升气流在 6 格处断掉
                for (int l = 0; l < MAX_LAYERS; l++) {
                    double decay = Math.exp(-LAYER_AGL_BLOCKS[l] / CONVECTIVE_FORCING_SCALE_BLOCKS);
                    if (decay < 0.02) break;
                    col.windY[l] += (base.convEnv() * 2.0 + base.torUpdraft() * 6.0) * decay;
                    col.windX[l] += (base.convInX() * 0.7 + base.torWindX() * 0.5) * decay;
                    col.windZ[l] += (base.convInZ() * 0.7 + base.torWindZ() * 0.5) * decay;
                }

                // 湍流强度诊断：必须在阵风之前算，阵风幅度要用**本次**的值（L1-05-3）
                col.turbulenceIntensity = clamp01(0.08 + roughness * 0.25
                        + slopeMag * 1.2 + base.convEnv() * 0.5
                        + speed0 * 0.012 + col.ablMixingStrength * 0.2);

                // 阵风：种子噪声，随 tick 慢慢变，别用纯随机（会闪）
                double gustSeed = NoiseUtil.valueNoise2(bx * 0.01, bz * 0.01 + tick * 0.002, seed);
                double gustAmp = (0.6 + speed0 * 0.22 + base.convEnv() * 3.0)
                        * (1 + col.turbulenceIntensity);
                col.gustX = (gustSeed - 0.5) * 2 * gustAmp * Math.cos(dir0);
                col.gustZ = (gustSeed - 0.5) * 2 * gustAmp * Math.sin(dir0);
                col.gustY = (NoiseUtil.valueNoise2(bx * 0.013 + 7.3, bz * 0.013, seed + tick / 20) - 0.5)
                        * gustAmp * 0.5;

                // 水平风切变先置零，第二遍循环里再算（要用到邻居格）
                col.shearXPerBlock = 0;
                col.shearZPerBlock = 0;

                // 不稳定/举升代理
                col.instabilityProxy = clamp01(-col.ablStability * 0.7 + base.convEnv()
                        + base.humidity01() * 0.3 * (col.ablStability < 0 ? 1 : 0.2));
                col.liftProxy = clamp01(Math.max(0, col.windY[0]) / 4.0 + base.convEnv() * 0.5);

                maxInstability = Math.max(maxInstability, col.instabilityProxy);
                maxLift = Math.max(maxLift, col.liftProxy);
                humSum += base.humidity01();
                count++;
            }
        }

        // 第二遍：风切变与水汽辐合
        for (int i = 1; i < SIZE - 1; i++) {
            for (int j = 1; j < SIZE - 1; j++) {
                CellColumn c = columns[i][j];
                double du = (columns[i + 1][j].windX[0] - columns[i - 1][j].windX[0])
                        / (2 * CELL_SIZE_BLOCKS);
                double dw = (columns[i][j + 1].windZ[0] - columns[i][j - 1].windZ[0])
                        / (2 * CELL_SIZE_BLOCKS);
                c.shearXPerBlock = du;
                c.shearZPerBlock = dw;
                shearSum += Math.hypot(du, dw);
                double divergence = du + dw;
                if (divergence < 0) {
                    maxMoistConv = Math.max(maxMoistConv, -divergence * c.humidity01[0]);
                }
            }
        }

        return new DiagnosticsSummary(maxInstability, maxLift,
                count == 0 ? 0 : shearSum / count,
                count == 0 ? 0.5 : humSum / count,
                maxMoistConv);
    }

    /**
     * 三维采样（方块坐标，y 为绝对高度）。
     *
     * <p>垂直按**离地高度**在 8 个 AGL 层之间线性插值（层中心见 {@link #layerAglBlocks}），
     * 水平双线性。旧实现把 {@code (blockY - terrain)} 直接除以 40 当层索引，
     * 而层是按绝对高度铺的——平地上 64–144 格全落进第 0→1 层，
     * 系数恒为 0.2775，与粗糙度/稳定度/坡度无关（L1-01）。
     *
     * @return 层顶以上不再插值，返回顶层的值（≈ 自由大气 L0，廓线系数已收敛到 1）
     */
    public Sample sample(double blockX, double blockY, double blockZ) {
        double fi = clamp((blockX - focusBlockX) / CELL_SIZE_BLOCKS + RADIUS_CELLS, 0, SIZE - 1);
        double fj = clamp((blockZ - focusBlockZ) / CELL_SIZE_BLOCKS + RADIUS_CELLS, 0, SIZE - 1);
        int i0 = clamp((int) Math.floor(fi), 0, SIZE - 2);
        int j0 = clamp((int) Math.floor(fj), 0, SIZE - 2);
        int i1 = i0 + 1, j1 = j0 + 1;
        double fx = clamp01(fi - i0), fz = clamp01(fj - j0);

        // 4 个角列各自的垂直插值结果
        double[] a = columnSample(columns[i0][j0], blockY);
        double[] b = columnSample(columns[i1][j0], blockY);
        double[] c = columnSample(columns[i0][j1], blockY);
        double[] d = columnSample(columns[i1][j1], blockY);

        double windX = lerp(lerp(a[0], b[0], fx), lerp(c[0], d[0], fx), fz);
        double windY = lerp(lerp(a[1], b[1], fx), lerp(c[1], d[1], fx), fz);
        double windZ = lerp(lerp(a[2], b[2], fx), lerp(c[2], d[2], fx), fz);
        double tempK = lerp(lerp(a[3], b[3], fx), lerp(c[3], d[3], fx), fz);
        double hum = lerp(lerp(a[4], b[4], fx), lerp(c[4], d[4], fx), fz);
        double turb = lerp(lerp(a[5], b[5], fx), lerp(c[5], d[5], fx), fz);
        double gX = lerp(lerp(a[6], b[6], fx), lerp(c[6], d[6], fx), fz);
        double gY = lerp(lerp(a[7], b[7], fx), lerp(c[7], d[7], fx), fz);
        double gZ = lerp(lerp(a[8], b[8], fx), lerp(c[8], d[8], fx), fz);
        double shear = lerp(lerp(a[9], b[9], fx), lerp(c[9], d[9], fx), fz);
        double stab = lerp(lerp(a[10], b[10], fx), lerp(c[10], d[10], fx), fz);

        // 地面遮蔽：只对真的钻到地形以下的点生效。旧实现额外乘 0.6，
        // 与对数廓线自身的近地衰减叠加，把眼高风压到 L0 的 0.17（L1-01）。
        double terrainHere = lerp(lerp(columns[i0][j0].terrainHeight, columns[i1][j0].terrainHeight, fx),
                lerp(columns[i0][j1].terrainHeight, columns[i1][j1].terrainHeight, fx), fz);
        double hAboveGround = blockY - terrainHere;
        boolean sheltered = false;
        if (hAboveGround < 0.0) {
            double f = clamp01(1.0 + hAboveGround / 2.0); // 地下 2 格内线性衰减到 0
            windX *= f;
            windY *= f;
            windZ *= f;
            sheltered = true;
        }

        return new Sample(windX, windY, windZ,
                turb, gX, gY, gZ,
                shear, tempK, hum, 0, stab, sheltered);
    }

    /**
     * 单列的垂直插值：按 AGL 在 8 层之间线性插值，返回
     * {@code [windX, windY, windZ, tempK, humidity, turbulence, gustX, gustY, gustZ, shear, ablStability]}。
     */
    private static double[] columnSample(CellColumn c, double blockY) {
        double hAgl = blockY - c.terrainHeight;
        int la, lb;
        double t;
        if (hAgl < LAYER_AGL_BLOCKS[0]) {
            // 最低层中心（2 格）以下：按同一套对数律直接求值，而不是夹成第 0 层的值。
            // 夹值会让 1.6 格眼高只有 0.336×L0，而真实廓线是 0.41×L0（L1-01）。
            double ratio = profileFactor(hAgl, c.profileZ0, c.profileDenom)
                    / Math.max(1e-6, profileFactor(LAYER_AGL_BLOCKS[0], c.profileZ0, c.profileDenom));
            ratio = Math.max(0.0, Math.min(1.0, ratio));
            return new double[]{
                    c.windX[0] * ratio, c.windY[0] * ratio, c.windZ[0] * ratio,
                    c.temperatureK[0], c.humidity01[0],
                    c.turbulenceIntensity,
                    c.gustX, c.gustY, c.gustZ,
                    Math.hypot(c.shearXPerBlock, c.shearZPerBlock),
                    c.ablStability
            };
        }
        if (hAgl >= LAYER_AGL_BLOCKS[MAX_LAYERS - 1]) {
            la = lb = MAX_LAYERS - 1; t = 0;          // 层顶以上：自由大气
        } else {
            int l = 0;
            while (l < MAX_LAYERS - 2 && hAgl >= LAYER_AGL_BLOCKS[l + 1]) l++;
            la = l;
            lb = l + 1;
            t = (hAgl - LAYER_AGL_BLOCKS[la]) / (LAYER_AGL_BLOCKS[lb] - LAYER_AGL_BLOCKS[la]);
        }
        return new double[]{
                lerp(c.windX[la], c.windX[lb], t),
                lerp(c.windY[la], c.windY[lb], t),
                lerp(c.windZ[la], c.windZ[lb], t),
                lerp(c.temperatureK[la], c.temperatureK[lb], t),
                lerp(c.humidity01[la], c.humidity01[lb], t),
                c.turbulenceIntensity,
                c.gustX, c.gustY, c.gustZ,
                Math.hypot(c.shearXPerBlock, c.shearZPerBlock),
                c.ablStability
        };
    }

    private static double lerp(double a, double b, double t) { return a + (b - a) * t; }

    /**
     * 粗糙度 0~1 → 空气动力粗糙长度 z0（m），对数插值。
     *
     * <p>{@code roughness01} 的取值：海面 0.03、草地/耕地 0.3、森林 0.75、山地 0.85。
     * 用 {@code 0.05 + r·1.5} 会让草地得到 z0=0.5 m——那是灌木/矮树的高度，
     * 草地的实测 z0 是 0.01–0.06 m；差异直接体现为近地风被低估约 15%（L1-01）。
     * 对数映射同时满足四类下垫面的标准量级。
     */
    private static double roughnessToZ0(double roughness01) {
        double r = clamp(roughness01, 0.0, 1.0);
        return 0.01 * Math.pow(200.0, r); // r=0.03→0.0117m, 0.3→0.049m, 0.75→0.53m, 0.85→0.90m
    }

    /**
     * 对数廓线系数：{@code ln((h+z0)/z0) / ln((ABL+z0)/z0)}，{@code h} 先夹到 {@link #MIN_AGL_BLOCKS}。
     *
     * <p>这里**没有** 0.15 的地板：真实近地层风速在 z→0 时按对数律趋于 0（无滑移边界）。
     * 旧实现用 {@code 0.15 + 0.85·prof} 兜底，等于给贴地风设了一个与粗糙度无关的下限，
     * 与"近地风依赖下垫面"直接矛盾（L1-01）。去掉后 1.6 格 / 10 格
     * 在草地上是 0.40 / 0.61 ×L0，正好落在报告 §5 的验收区间。
     */
    private static double profileFactor(double hAgl, double z0, double profDenom) {
        double h = Math.max(hAgl, MIN_AGL_BLOCKS);
        return Math.max(0.0, Math.min(1.0, Math.log((h + z0) / z0) / profDenom));
    }
    private static double clamp01(double v) { return v < 0 ? 0 : Math.min(v, 1); }
    private static double clamp(double v, double lo, double hi) { return v < lo ? lo : Math.min(v, hi); }
    private static int clamp(int v, int lo, int hi) { return v < lo ? lo : Math.min(v, hi); }
}
