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

    /**
     * 阵风的水平相关长度（格）。"冻结湍流"假设下的湍流涡尺度：阵风图样以平均风速整体平移，
     * 而不是原地每 64 tick 重新掷骰子（L1-05）。
     */
    private static final double GUST_LENGTH_SCALE_BLOCKS = 200.0;
    /** 阵风横向分量相对纵向的权重（L1-05：旧实现完全没有横向分量）。 */
    private static final double GUST_CROSS_WEIGHT = 0.35;
    /** 0–1 km 风切变的高度层：用第 0 层（2 AGL）与顶层（300 AGL）。 */
    private static final int SHEAR_LOW_LAYER = 0;
    private static final int SHEAR_HIGH_LAYER = MAX_LAYERS - 1;
    /** 用于 ∂u/∂z 的较高层：第 2 层（14 AGL），Δz = 12 格。 */
    private static final int SHEAR_VERTICAL_LAYER = 2;
    /** 连续方程积分出的垂直速度上限（m/s），防止数值放大。 */
    private static final double MAX_CONTINUITY_W_MPS = 3.0;
    /**
     * 连续方程补偿的耦合系数：显式源项（坡风/对流）已经给了地面垂直速度，
     * 连续方程补的是"辐合必须抬升"的约束，两者各占一部分，避免叠加成双倍。
     */
    private static final double CONTINUITY_COUPLING = 0.5;
    /** 域外回退 L0 的过渡宽度（格）：出界 512 格后完全用 L0（L1-02）。 */
    private static final double OUTSIDE_FADE_BLOCKS = 512.0;

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
        /** 冻结湍流的图样相位（噪声单位）：每刷新按 {@code U·dt/L} 推进（L1-05）。 */
        public double gustPhaseX, gustPhaseZ;
        /**
         * 0–1 km 矢量风切变 {@code |V(110 AGL) − V(2 AGL)| / Δz}（每格的 m/s 差）。
         *
         * <p>旧实现的 {@code shearXPerBlock}/{@code shearZPerBlock} 其实是 {@code ∂u/∂x}、{@code ∂w/∂z}，
         * 那是**正应变率**不是切变（L1-03）；龙卷/超级单体要的是不同高度的**矢量差**。
         */
        public double bulkShearPerBlock;
        /** 相对涡度 {@code ζ = ∂w/∂x − ∂u/∂z}（每格）。 */
        public double vorticityPerBlock;
        /** 水平散度 {@code δ = ∂u/∂x + ∂w/∂z}（每秒）。正为辐散、负为辐合。 */
        public double divergencePerSecond;
        public double ablStability;      // <0 不稳定，>0 稳定
        public double ablMixingStrength;
        public double surfaceTempK;
        public double terrainHeight;
        /** L0 透传的气压异常（Pa），API 层直接给出（L0-07 / API-07）。 */
        public double pressureAnomalyPa;
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

    /**
     * 单点采样结果。
     *
     * @param shearPerBlock 0–1 km 矢量风切变（每格 m/s）。旧实现给的是正应变率（L1-03）。
     * @param pressureAnomalyPa L0 透传的气压异常（Pa）。旧实现恒 0（L0-07 / API-07）。
     */
    public record Sample(double windX, double windY, double windZ,
                         double turbulenceIntensity,
                         double gustX, double gustY, double gustZ,
                         double shearPerBlock,
                         double airTempK, double humidity01,
                         double pressureAnomalyPa,
                         double ablStability, boolean sheltered,
                         double vorticityPerBlock, double divergencePerSecond) {}

    private final CellColumn[][] columns = new CellColumn[SIZE][SIZE];
    private double focusBlockX, focusBlockZ;
    private long seed;
    /** 域外采样回退用的 L0 网格（L1-02）：{@code refresh} 时挂上。 */
    private BackgroundMetGrid background;

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
     *
     * @param dtSeconds 距上次刷新的真实秒数，阵风的冻结湍流图样要按它推进（L1-05）
     */
    public DiagnosticsSummary refresh(BackgroundMetGrid l0, TerrainProbe terrain,
                                       double timeOfDay01, boolean raining, long tick,
                                       double dtSeconds) {
        this.background = l0;
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
                // L0 是**自由大气/地转风**，是 ABL 顶的参考而不是地面风（L1-04）。
                // 地面风相对它向低压偏转，随高度回正：t=1 在 h=0、t=0 在 h=ABL。
                double ekmanSign = HemisphereConvention.coriolisSign();
                for (int l = 0; l < MAX_LAYERS; l++) {
                    double hAboveGround = LAYER_AGL_BLOCKS[l];
                    double prof = profileFactor(hAboveGround, z0, profDenom);
                    // 北半球地面风相对地转风逆时针（偏向低压），所以是 **减**；南半球取反
                    double ekmanTurn = ABL_EKMAN_MAX_TURN_RADIANS * ekmanSign
                            * Math.max(0.0, 1.0 - hAboveGround / ablHeight);
                    double dir = dir0 - ekmanTurn;
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
                // 气压从 L0 透传（L0-07 / API-07）：旧实现 Sample 里恒写 0
                col.pressureAnomalyPa = base.pressureAnomalyPa();

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

                // 阵风：冻结湍流（Taylor 假设），见图样随平均风平移（L1-05）
                computeGust(col, bx, bz, dir0, speed0, dtSeconds, seed);

                // 散度/涡度/风切变在第二遍循环里算（要用邻居格与不同高度的层）
                col.bulkShearPerBlock = 0;
                col.vorticityPerBlock = 0;
                col.divergencePerSecond = 0;

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

        // 第二遍：风切变、涡度、散度、水汽辐合
        //
        // 旧实现把 du=∂u/∂x、dw=∂w/∂z 当"切变"（L1-03），那是正应变率。现在分开：
        //   风切变 = |V(300 AGL) − V(2 AGL)| / Δz   ← 超级单体/龙卷要的量
        //   涡度   = ∂w/∂x − ∂u/∂z
        //   散度   = ∂u/∂x + ∂w/∂z                 ← 水汽辐合用它
        double dzBulk = LAYER_AGL_BLOCKS[SHEAR_HIGH_LAYER] - LAYER_AGL_BLOCKS[SHEAR_LOW_LAYER];
        double dzVert = LAYER_AGL_BLOCKS[SHEAR_VERTICAL_LAYER] - LAYER_AGL_BLOCKS[SHEAR_LOW_LAYER];
        for (int i = 1; i < SIZE - 1; i++) {
            for (int j = 1; j < SIZE - 1; j++) {
                CellColumn c = columns[i][j];
                double duDx = (columns[i + 1][j].windX[SHEAR_LOW_LAYER]
                        - columns[i - 1][j].windX[SHEAR_LOW_LAYER]) / (2 * CELL_SIZE_BLOCKS);
                double dwDz = (columns[i][j + 1].windZ[SHEAR_LOW_LAYER]
                        - columns[i][j - 1].windZ[SHEAR_LOW_LAYER]) / (2 * CELL_SIZE_BLOCKS);
                double dwDx = (columns[i + 1][j].windZ[SHEAR_LOW_LAYER]
                        - columns[i - 1][j].windZ[SHEAR_LOW_LAYER]) / (2 * CELL_SIZE_BLOCKS);
                double duDz = (c.windX[SHEAR_VERTICAL_LAYER] - c.windX[SHEAR_LOW_LAYER]) / dzVert;

                double shearX = c.windX[SHEAR_HIGH_LAYER] - c.windX[SHEAR_LOW_LAYER];
                double shearZ = c.windZ[SHEAR_HIGH_LAYER] - c.windZ[SHEAR_LOW_LAYER];
                c.bulkShearPerBlock = Math.hypot(shearX, shearZ) / dzBulk;
                c.vorticityPerBlock = dwDx - duDz;
                c.divergencePerSecond = duDx + dwDz;
                shearSum += c.bulkShearPerBlock;
                if (c.divergencePerSecond < 0) {
                    maxMoistConv = Math.max(maxMoistConv,
                            -c.divergencePerSecond * c.humidity01[SHEAR_LOW_LAYER]);
                }
            }
        }

        // 连续方程补上层垂直速度（L1-06）：必须在第二遍散度算完之后
        integrateContinuity();

        return new DiagnosticsSummary(maxInstability, maxLift,
                count == 0 ? 0 : shearSum / count,
                count == 0 ? 0.5 : humSum / count,
                maxMoistConv);
    }

    /**
     * 连续方程补出的垂直速度（L1-06）。
     *
     * <p>旧实现把 {@code windY[l]} 硬写 0，只有第 0 层在坡风/对流处被加了一点，
     * 于是"地面有上升气流、6 格处突然没有"——层间线性插值时垂直速度在 2 格处断崖。
     *
     * <p>真实边界层里水平辐合必然伴随上升：{@code ∂w/∂z = −(∂u/∂x + ∂w/∂z)} 从地面往上积分。
     * 这里逐层用邻居列算水平散度、逐层积分，**叠加**在已有的坡风/对流强迫上——
     * 那些是地形与浮力的显式源项，连续方程补的是"辐合必须抬升"这一约束。
     */
    private void integrateContinuity() {
        for (int i = 1; i < SIZE - 1; i++) {
            for (int j = 1; j < SIZE - 1; j++) {
                CellColumn c = columns[i][j];
                double w = 0;
                for (int l = 1; l < MAX_LAYERS; l++) {
                    double duDx = (columns[i + 1][j].windX[l] - columns[i - 1][j].windX[l])
                            / (2 * CELL_SIZE_BLOCKS);
                    double dwDz = (columns[i][j + 1].windZ[l] - columns[i][j - 1].windZ[l])
                            / (2 * CELL_SIZE_BLOCKS);
                    double dz = LAYER_AGL_BLOCKS[l] - LAYER_AGL_BLOCKS[l - 1];
                    w -= (duDx + dwDz) * dz;
                    w = clamp(w, -MAX_CONTINUITY_W_MPS, MAX_CONTINUITY_W_MPS);
                    c.windY[l] = clamp(c.windY[l] + CONTINUITY_COUPLING * w,
                            -MAX_CONTINUITY_W_MPS, MAX_CONTINUITY_W_MPS);
                }
            }
        }
    }

    /**
     * 单列垂直插值结果（可复用，避免每次采样 4 次 {@code new double[11]}）。
     */
    private static final class LayerSample {
        double windX, windY, windZ, tempK, humidity;
        double turbulence, gustX, gustY, gustZ;
        double shear, stability, pressureAnomalyPa, vorticity, divergence;
    }

    private final LayerSample scratchA = new LayerSample();
    private final LayerSample scratchB = new LayerSample();
    private final LayerSample scratchC = new LayerSample();
    private final LayerSample scratchD = new LayerSample();

    /**
     * 三维采样（方块坐标，y 为绝对高度）。
     *
     * <p>垂直按**离地高度**在 8 个 AGL 层之间线性插值（层中心见 {@link #layerAglBlocks}），
     * 水平双线性。旧实现把 {@code (blockY - terrain)} 直接除以 40 当层索引，
     * 而层是按绝对高度铺的——平地上 64–144 格全落进第 0→1 层，
     * 系数恒为 0.2775，与粗糙度/稳定度/坡度无关（L1-01）。
     *
     * <p>域外（网格 ±1024 格之外）不再把边缘格的值硬夹出去（L1-02 / SYS-05），
     * 而是按"出界距离"在 0..512 格内淡到 0：调用方看到的是一段平滑过渡，
     * 而不是一整片随风平移到天边的假风。
     *
     * @return 层顶以上不再插值，返回顶层的值（≈ 自由大气 L0，廓线系数已收敛到 1）
     */
    public Sample sample(double blockX, double blockY, double blockZ) {
        double fiRaw = (blockX - focusBlockX) / CELL_SIZE_BLOCKS + RADIUS_CELLS;
        double fjRaw = (blockZ - focusBlockZ) / CELL_SIZE_BLOCKS + RADIUS_CELLS;
        double fi = clamp(fiRaw, 0, SIZE - 1);
        double fj = clamp(fjRaw, 0, SIZE - 1);
        int i0 = clamp((int) Math.floor(fi), 0, SIZE - 2);
        int j0 = clamp((int) Math.floor(fj), 0, SIZE - 2);
        int i1 = i0 + 1, j1 = j0 + 1;
        double fx = clamp01(fi - i0), fz = clamp01(fj - j0);

        // 4 个角列各自的垂直插值结果（写进复用对象）
        LayerSample a = columnSample(columns[i0][j0], blockY, scratchA);
        LayerSample b = columnSample(columns[i1][j0], blockY, scratchB);
        LayerSample c = columnSample(columns[i0][j1], blockY, scratchC);
        LayerSample d = columnSample(columns[i1][j1], blockY, scratchD);

        double windX = bilerp(a.windX, b.windX, c.windX, d.windX, fx, fz);
        double windY = bilerp(a.windY, b.windY, c.windY, d.windY, fx, fz);
        double windZ = bilerp(a.windZ, b.windZ, c.windZ, d.windZ, fx, fz);
        double tempK = bilerp(a.tempK, b.tempK, c.tempK, d.tempK, fx, fz);
        double hum = bilerp(a.humidity, b.humidity, c.humidity, d.humidity, fx, fz);
        double turb = bilerp(a.turbulence, b.turbulence, c.turbulence, d.turbulence, fx, fz);
        double gX = bilerp(a.gustX, b.gustX, c.gustX, d.gustX, fx, fz);
        double gY = bilerp(a.gustY, b.gustY, c.gustY, d.gustY, fx, fz);
        double gZ = bilerp(a.gustZ, b.gustZ, c.gustZ, d.gustZ, fx, fz);
        double shear = bilerp(a.shear, b.shear, c.shear, d.shear, fx, fz);
        double stab = bilerp(a.stability, b.stability, c.stability, d.stability, fx, fz);
        double pressure = bilerp(a.pressureAnomalyPa, b.pressureAnomalyPa,
                c.pressureAnomalyPa, d.pressureAnomalyPa, fx, fz);
        double vorticity = bilerp(a.vorticity, b.vorticity, c.vorticity, d.vorticity, fx, fz);
        double divergence = bilerp(a.divergence, b.divergence, c.divergence, d.divergence, fx, fz);

        // 域外回退 L0（L1-02 / SYS-05）：旧实现把边缘格的值硬夹出去，等于让整片域外
        // 都吹"边界那一格"的风。现在按出界距离 0..512 格从 L1 淡到 L0 的自由大气风。
        double outsideCells = Math.max(Math.max(0, -fiRaw), Math.max(0, fiRaw - (SIZE - 1)));
        double outsideZCells = Math.max(Math.max(0, -fjRaw), Math.max(0, fjRaw - (SIZE - 1)));
        double outsideBlocks = Math.max(outsideCells, outsideZCells) * CELL_SIZE_BLOCKS;
        if (outsideBlocks > 0 && background != null) {
            double keep = clamp01(1.0 - outsideBlocks / OUTSIDE_FADE_BLOCKS);
            BackgroundMetGrid.Sample l0s = background.sample(blockX, blockZ);
            windX = lerp(l0s.windX(), windX, keep);
            windZ = lerp(l0s.windZ(), windZ, keep);
            windY *= keep;
            gX = lerp(0, gX, keep);
            gY = lerp(0, gY, keep);
            gZ = lerp(0, gZ, keep);
            turb = lerp(0.08, turb, keep);
            pressure = lerp(l0s.pressureAnomalyPa(), pressure, keep);
            tempK = lerp(l0s.airTempK(), tempK, keep);
            hum = lerp(l0s.humidity01(), hum, keep);
            shear *= keep;
            vorticity *= keep;
            divergence *= keep;
        }

        // 地面遮蔽：只对真的钻到地形以下的点生效。旧实现额外乘 0.6，
        // 与对数廓线自身的近地衰减叠加，把眼高风压到 L0 的 0.17（L1-01）。
        double terrainHere = bilerp(columns[i0][j0].terrainHeight, columns[i1][j0].terrainHeight,
                columns[i0][j1].terrainHeight, columns[i1][j1].terrainHeight, fx, fz);
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
                shear, tempK, hum, pressure, stab, sheltered, vorticity, divergence);
    }

    /**
     * 单列垂直插值：按 AGL 在 8 层之间线性插值，写进 {@code out}。
     *
     * <p>低层（2 格 AGL 以下）按同一套对数律直接求值；层顶以上视为自由大气。
     */
    private static LayerSample columnSample(CellColumn c, double blockY, LayerSample out) {
        double hAgl = blockY - c.terrainHeight;
        out.turbulence = c.turbulenceIntensity;
        out.gustX = c.gustX;
        out.gustY = c.gustY;
        out.gustZ = c.gustZ;
        out.shear = c.bulkShearPerBlock;
        out.stability = c.ablStability;
        out.pressureAnomalyPa = c.pressureAnomalyPa;
        out.vorticity = c.vorticityPerBlock;
        out.divergence = c.divergencePerSecond;

        if (hAgl < LAYER_AGL_BLOCKS[0]) {
            // 最低层中心（2 格）以下：按同一套对数律直接求值，而不是夹成第 0 层的值。
            // 夹值会让 1.6 格眼高只有 0.336×L0，而真实廓线是 0.41×L0（L1-01）。
            double ratio = profileFactor(hAgl, c.profileZ0, c.profileDenom)
                    / Math.max(1e-6, profileFactor(LAYER_AGL_BLOCKS[0], c.profileZ0, c.profileDenom));
            ratio = clamp01(ratio);
            out.windX = c.windX[0] * ratio;
            out.windY = c.windY[0] * ratio;
            out.windZ = c.windZ[0] * ratio;
            out.tempK = c.temperatureK[0];
            out.humidity = c.humidity01[0];
            return out;
        }

        int la, lb;
        double t;
        if (hAgl >= LAYER_AGL_BLOCKS[MAX_LAYERS - 1]) {
            la = lb = MAX_LAYERS - 1; t = 0;          // 层顶以上：自由大气
        } else {
            int l = 0;
            while (l < MAX_LAYERS - 2 && hAgl >= LAYER_AGL_BLOCKS[l + 1]) l++;
            la = l;
            lb = l + 1;
            t = (hAgl - LAYER_AGL_BLOCKS[la]) / (LAYER_AGL_BLOCKS[lb] - LAYER_AGL_BLOCKS[la]);
        }
        out.windX = lerp(c.windX[la], c.windX[lb], t);
        out.windY = lerp(c.windY[la], c.windY[lb], t);
        out.windZ = lerp(c.windZ[la], c.windZ[lb], t);
        out.tempK = lerp(c.temperatureK[la], c.temperatureK[lb], t);
        out.humidity = lerp(c.humidity01[la], c.humidity01[lb], t);
        return out;
    }

    private static double bilerp(double a, double b, double c, double d, double fx, double fz) {
        return lerp(lerp(a, b, fx), lerp(c, d, fx), fz);
    }

    /**
     * 阵风：**冻结湍流**（Taylor 假设），`L1-05`。
     *
     * <p>旧实现三个问题：① {@code gustY} 的种子含 {@code tick/20}，但列每 64 tick 才刷新一次，
     * 垂直阵风每次刷新完全重采样（会闪）；② {@code gustX/Z} 只沿平均风方向，没有横向分量；
     * ③ {@code gustAmp} 读的是**上一次**刷新的 {@code turbulenceIntensity}。
     *
     * <p>现在：噪声在"沿风向平移的坐标系"里求值——`x' = (x − U·t)/L`，即湍流涡以平均风速整体
     * 平移，采样点经过时看到的阵风是同一张图样，不会在 3.2 s 边界上跳变。纵向 + 横向各一个
     * 分量，幅度用本次刚算出的 {@code turbulenceIntensity}。
     *
     * <p>{@code gustPhaseX/Z} 累积"图样随平均风走了多远"：刷新间隔不固定时（RT-03 后按真实
     * 墙钟累积），相位也必须按累积位移推进。
     */
    private static void computeGust(CellColumn col, double bx, double bz,
                                    double dir0, double speed0, double dtSeconds, long seed) {
        col.gustPhaseX += Math.cos(dir0) * speed0 * dtSeconds / GUST_LENGTH_SCALE_BLOCKS;
        col.gustPhaseZ += Math.sin(dir0) * speed0 * dtSeconds / GUST_LENGTH_SCALE_BLOCKS;
        double nx = bx / GUST_LENGTH_SCALE_BLOCKS - col.gustPhaseX;
        double nz = bz / GUST_LENGTH_SCALE_BLOCKS - col.gustPhaseZ;
        double along = (NoiseUtil.valueNoise2(nx, nz, seed) - 0.5) * 2.0;
        double cross = (NoiseUtil.valueNoise2(nx + 31.7, nz - 17.3, seed + 991) - 0.5) * 2.0;
        double vertical = (NoiseUtil.valueNoise2(nx + 7.9, nz + 53.1, seed + 1777) - 0.5) * 2.0;

        double amp = (0.6 + speed0 * 0.22) * (0.6 + col.turbulenceIntensity * 1.6);
        double ux = Math.cos(dir0), uz = Math.sin(dir0);
        double tx = -uz, tz = ux; // 横向（左手）

        col.gustX = amp * (along * ux + cross * GUST_CROSS_WEIGHT * tx);
        col.gustZ = amp * (along * uz + cross * GUST_CROSS_WEIGHT * tz);
        col.gustY = amp * vertical * 0.4;
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
