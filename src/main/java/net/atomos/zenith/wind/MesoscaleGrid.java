package net.atomos.zenith.wind;

/**
 * L1 中尺度网格（服务端权威）：33×33×8 格，水平 64×64 blocks，垂直每层 40 blocks。
 *
 * <p>移植自 Aerodynamics4MC-Core 的 {@code MesoscaleGrid}（MIT）。
 * 每刷新：拉取 L0 样本 → 构建 ABL 垂直廓线（Ekman 偏转）→ 地形反弹/阻力 →
 * 阵风与湍流诊断 → 输出诊断摘要（反馈给驱动器）。</p>
 */
public class MesoscaleGrid {
    public static final int RADIUS_CELLS = 16;
    public static final int SIZE = RADIUS_CELLS * 2 + 1; // 33
    public static final double CELL_SIZE_BLOCKS = 64.0;
    public static final double LAYER_HEIGHT_BLOCKS = 40.0;
    public static final int MAX_LAYERS = 8;

    public static final double ABL_NEUTRAL_HEIGHT_BLOCKS = 280.0;
    public static final double ABL_STABLE_HEIGHT_BLOCKS = 120.0;
    public static final double ABL_UNSTABLE_HEIGHT_BLOCKS = 460.0;
    public static final double ABL_EKMAN_MAX_TURN_RADIANS = 0.52;
    public static final double L1_TERRAIN_FORM_DRAG = 0.45;
    public static final double L1_THERMAL_SLOPE_WIND_MPS = 1.10;

    /** 地形探针（高度/粗糙度/坡度）。 */
    public interface TerrainProbe {
        double terrainHeightBlocks(double blockX, double blockZ);
        double roughness01(double blockX, double blockZ);
        /** 地形坡度向量（dh/dx, dh/dz），无量纲。 */
        double[] slopeVector(double blockX, double blockZ);
    }

    /** 单格气柱状态。 */
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
        public double liftProxy;
        public double instabilityProxy;
    }

    /** 诊断摘要（反馈给 WorldScaleDriver）。 */
    public record DiagnosticsSummary(double maxInstabilityProxy, double maxLiftProxy,
                                     double meanLowLevelShear, double meanHumidity,
                                     double maxPositiveMoistureConvergence) {}

    /** 采样输出。 */
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

                // ABL 稳定度：地表-气温差决定
                double dT = base.surfaceTempK() - base.airTempK();
                col.ablStability = clamp(-dT / 8.0, -1, 1); // 地表暖 → 不稳定（负）
                double ablHeight = col.ablStability < -0.15 ? ABL_UNSTABLE_HEIGHT_BLOCKS
                        : col.ablStability > 0.15 ? ABL_STABLE_HEIGHT_BLOCKS
                        : ABL_NEUTRAL_HEIGHT_BLOCKS;
                col.ablMixingStrength = clamp01(0.4 - col.ablStability * 0.6
                        + base.convEnv() * 0.8 + base.torUpdraft() * 0.6);

                // 垂直廓线：对数律 + Ekman 偏转
                double u0 = base.windX(), w0 = base.windZ();
                double speed0 = Math.hypot(u0, w0);
                double dir0 = Math.atan2(w0, u0);
                for (int l = 0; l < MAX_LAYERS; l++) {
                    double hMid = (l + 0.5) * LAYER_HEIGHT_BLOCKS;
                    double hAboveGround = hMid - col.terrainHeight;
                    // 对数廓线（z0 由粗糙度映射）
                    double z0 = 0.05 + roughness * 1.5;
                    double prof = hAboveGround <= z0 ? 0.15
                            : Math.min(1.0, Math.log(Math.max(hAboveGround, z0 + 0.01) / z0)
                                    / Math.log(Math.max(ablHeight, z0 + 1) / z0));
                    prof = 0.15 + 0.85 * prof;
                    // ABL 内风速随高度先增后稳；Ekman 偏转随高度增加
                    double ekmanTurn = ABL_EKMAN_MAX_TURN_RADIANS
                            * clamp01(hAboveGround / ablHeight) * 0.9;
                    double dir = dir0 + ekmanTurn;
                    double spd = speed0 * prof;
                    col.windX[l] = Math.cos(dir) * spd;
                    col.windZ[l] = Math.sin(dir) * spd;
                    col.windY[l] = 0;
                    col.temperatureK[l] = base.airTempK()
                            - hMid * BackgroundMetGrid.ALTITUDE_LAPSE_RATE_K_PER_BLOCK;
                    col.humidity01[l] = clamp01(base.humidity01() - l * 0.04);
                }

                // 地形反弹与阻力（表层）
                double slopeMag = Math.hypot(slope[0], slope[1]);
                if (slopeMag > 1e-4) {
                    double drag = clamp(L1_TERRAIN_FORM_DRAG * slopeMag * 3.0, 0, 0.6);
                    col.windX[0] *= (1 - drag);
                    col.windZ[0] *= (1 - drag);
                    // 坡面热力风（白天上坡/夜间下坡）
                    double diurnal = Math.sin(timeOfDay01 * Math.PI * 2 - Math.PI / 2);
                    double slopeWind = L1_THERMAL_SLOPE_WIND_MPS * diurnal
                            * clamp01(slopeMag * 4.0);
                    col.windX[0] += slope[0] / slopeMag * slopeWind;
                    col.windZ[0] += slope[1] / slopeMag * slopeWind;
                    // 地形抬升 → 垂直速度
                    col.windY[0] = (col.windX[0] * slope[0] + col.windZ[0] * slope[1]) * 0.8;
                }

                // 对流/龙卷强迫叠加到低层
                col.windY[0] += base.convEnv() * 2.0 + base.torUpdraft() * 6.0;
                col.windX[0] += base.convInX() * 0.7 + base.torWindX() * 0.5;
                col.windZ[0] += base.convInZ() * 0.7 + base.torWindZ() * 0.5;

                // 阵风：种子噪声（随 tick 缓变）
                double gustSeed = NoiseUtil.valueNoise2(bx * 0.01, bz * 0.01 + tick * 0.002, seed);
                double gustAmp = (0.6 + speed0 * 0.22 + base.convEnv() * 3.0)
                        * (1 + col.turbulenceIntensity);
                col.gustX = (gustSeed - 0.5) * 2 * gustAmp * Math.cos(dir0);
                col.gustZ = (gustSeed - 0.5) * 2 * gustAmp * Math.sin(dir0);
                col.gustY = (NoiseUtil.valueNoise2(bx * 0.013 + 7.3, bz * 0.013, seed + tick / 20) - 0.5)
                        * gustAmp * 0.5;

                // 湍流强度诊断
                col.turbulenceIntensity = clamp01(0.08 + roughness * 0.25
                        + slopeMag * 1.2 + base.convEnv() * 0.5
                        + speed0 * 0.012 + col.ablMixingStrength * 0.2);

                // 水平风切变（相邻格差分）
                // （在第二遍循环中计算，此处先置零）
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

    /** 三维采样（方块坐标，y 为绝对高度）。 */
    public Sample sample(double blockX, double blockY, double blockZ) {
        double fi = (blockX - focusBlockX) / CELL_SIZE_BLOCKS + RADIUS_CELLS;
        double fj = (blockZ - focusBlockZ) / CELL_SIZE_BLOCKS + RADIUS_CELLS;
        int i = clamp((int) Math.round(fi), 0, SIZE - 1);
        int j = clamp((int) Math.round(fj), 0, SIZE - 1);
        CellColumn c = columns[i][j];

        double hAboveGround = blockY - c.terrainHeight;
        int layer = clamp((int) (hAboveGround / LAYER_HEIGHT_BLOCKS), 0, MAX_LAYERS - 1);
        double frac = clamp01((hAboveGround - layer * LAYER_HEIGHT_BLOCKS) / LAYER_HEIGHT_BLOCKS);
        int l2 = Math.min(layer + 1, MAX_LAYERS - 1);

        double windX = lerp(c.windX[layer], c.windX[l2], frac);
        double windY = lerp(c.windY[layer], c.windY[l2], frac);
        double windZ = lerp(c.windZ[layer], c.windZ[l2], frac);
        double tempK = lerp(c.temperatureK[layer], c.temperatureK[l2], frac);
        double hum = lerp(c.humidity01[layer], c.humidity01[l2], frac);

        // 地面遮蔽：低于地形 → 强衰减并标记 sheltered
        boolean sheltered = false;
        if (hAboveGround < 2.0) {
            double f = clamp01((hAboveGround + 4) / 6.0);
            windX *= f * 0.6;
            windZ *= f * 0.6;
            sheltered = hAboveGround < 0.5;
        }

        return new Sample(windX, windY, windZ,
                c.turbulenceIntensity,
                c.gustX, c.gustY, c.gustZ,
                Math.hypot(c.shearXPerBlock, c.shearZPerBlock),
                tempK, hum, 0, c.ablStability, sheltered);
    }

    private static double lerp(double a, double b, double t) { return a + (b - a) * t; }
    private static double clamp01(double v) { return v < 0 ? 0 : Math.min(v, 1); }
    private static double clamp(double v, double lo, double hi) { return v < lo ? lo : Math.min(v, hi); }
    private static int clamp(int v, int lo, int hi) { return v < lo ? lo : Math.min(v, hi); }
}
