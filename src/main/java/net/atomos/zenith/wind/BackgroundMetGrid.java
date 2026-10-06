package net.atomos.zenith.wind;

/**
 * L0 背景天气网格（服务端权威）：41×41 格 @256 blocks/格，单层。
 *
 * <p>移植自 Aerodynamics4MC-Core 的 {@code BackgroundMetGrid}（MIT）。
 * 每次刷新：半拉格朗日平流 → 扩散 → 地转调整 → 地形阻力 → 松弛 → 粗糙度拖曳 →
 * 温度/湿度松弛。</p>
 */
public class BackgroundMetGrid {
    public static final int RADIUS_CELLS = 20;
    public static final int SIZE = RADIUS_CELLS * 2 + 1; // 41
    public static final double CELL_SIZE_BLOCKS = 256.0;

    public static final double FLOW_RELAXATION_PER_SECOND = 1.0 / 90.0;
    public static final double GEOSTROPHIC_WIND_SCALE = 12.0; // m^2/(Pa·s)
    public static final double GEOSTROPHIC_DIRECT_WIND_BLEND = 0.18;
    public static final double TERRAIN_FORM_DRAG_SCALE = 0.65;
    public static final double TERRAIN_FLOW_DEFLECTION_SCALE = 0.45;
    public static final double MAX_DYNAMIC_WIND_MPS = 18.0;
    public static final double ALTITUDE_LAPSE_RATE_K_PER_BLOCK = 0.0065;
    public static final double WIND_DIFFUSE_BLEND = 0.16;
    public static final double PRESSURE_DIFFUSE_BLEND = 0.10;

    /** 地形探针：高度/粗糙度/生物群系温度（由运行时注入，避免区块加载）。 */
    public interface TerrainProbe {
        double terrainHeightBlocks(double blockX, double blockZ);
        double roughness01(double blockX, double blockZ);
        /** 生物群系基准气温（开尔文）。 */
        double biomeTemperatureKelvin(double blockX, double blockZ);
    }

    /** 单格状态。 */
    public static final class CellState {
        public double windX, windZ;
        public double geoWindX, geoWindZ;
        public double pressureAnomalyPa;
        public double airTempK = 288.0;
        public double surfaceTempK = 288.0;
        public double deepGroundTempK = 285.0;
        public double humidity01 = 0.5;
        // 对流/龙卷强迫（透传）
        public double convHeatK, convMoist, convInX, convInZ, convEnv;
        public double torWindX, torWindZ, torHeatK, torMoist, torUpdraft;
        public double terrainHeight;
        public double roughness01 = 0.3;
    }

    /** 采样输出。 */
    public record Sample(double windX, double windZ,
                         double pressureAnomalyPa,
                         double airTempK, double surfaceTempK,
                         double humidity01,
                         double convHeatK, double convMoist,
                         double convInX, double convInZ, double convEnv,
                         double torWindX, double torWindZ,
                         double torHeatK, double torMoist, double torUpdraft,
                         double turbulenceHint) {}

    private final CellState[][] cells = new CellState[SIZE][SIZE];
    private final CellState[][] prev = new CellState[SIZE][SIZE];
    private double focusBlockX, focusBlockZ;
    private boolean initialized = false;

    public BackgroundMetGrid() {
        for (int i = 0; i < SIZE; i++)
            for (int j = 0; j < SIZE; j++) {
                cells[i][j] = new CellState();
                prev[i][j] = new CellState();
            }
    }

    public void setFocus(double blockX, double blockZ) {
        if (!initialized || Math.abs(blockX - focusBlockX) > CELL_SIZE_BLOCKS
                || Math.abs(blockZ - focusBlockZ) > CELL_SIZE_BLOCKS) {
            focusBlockX = blockX;
            focusBlockZ = blockZ;
            initialized = true;
        }
    }

    private double cellBlockX(int i) { return focusBlockX + (i - RADIUS_CELLS) * CELL_SIZE_BLOCKS; }
    private double cellBlockZ(int j) { return focusBlockZ + (j - RADIUS_CELLS) * CELL_SIZE_BLOCKS; }

    /** 每 BACKGROUND_MET_REFRESH_TICKS 调用一次。 */
    public void refresh(WorldScaleDriver driver, TerrainProbe terrain,
                        double dtSeconds, double timeOfDay01, boolean raining,
                        double seasonTempBiasK) {
        // 交换 prev/current
        for (int i = 0; i < SIZE; i++)
            for (int j = 0; j < SIZE; j++) {
                copyInto(cells[i][j], prev[i][j]);
            }

        for (int i = 0; i < SIZE; i++) {
            for (int j = 0; j < SIZE; j++) {
                int cellX = (int) Math.floor(cellBlockX(i) / WorldScaleDriver.CELL_SIZE_BLOCKS);
                int cellZ = (int) Math.floor(cellBlockZ(j) / WorldScaleDriver.CELL_SIZE_BLOCKS);
                WorldScaleDriver.Sample drv = driver.sample(cellX, cellZ);
                CellState c = cells[i][j];
                CellState p = prev[i][j];

                double bx = cellBlockX(i), bz = cellBlockZ(j);
                c.terrainHeight = terrain.terrainHeightBlocks(bx, bz);
                c.roughness01 = terrain.roughness01(bx, bz);

                // 1) 半拉格朗日平流：沿上一步风回溯
                double backX = i - p.windX * dtSeconds / CELL_SIZE_BLOCKS;
                double backZ = j - p.windZ * dtSeconds / CELL_SIZE_BLOCKS;
                CellState adv = bilinear(prev, backX, backZ);

                double windX = adv.windX, windZ = adv.windZ;
                double pressure = adv.pressureAnomalyPa;

                // 2) 扩散：与四邻均值混合
                CellState n = neighborMean(prev, i, j);
                windX = lerp(windX, n.windX, WIND_DIFFUSE_BLEND);
                windZ = lerp(windZ, n.windZ, WIND_DIFFUSE_BLEND);
                pressure = lerp(pressure, n.pressureAnomalyPa, PRESSURE_DIFFUSE_BLEND);

                // 驱动器目标叠加
                pressure = lerp(pressure, drv.pressureAnomalyPa(), 0.35);

                // 3) 地转调整
                double[] geo = computeGeostrophicWind(i, j);
                c.geoWindX = geo[0];
                c.geoWindZ = geo[1];
                double geoMag = Math.hypot(geo[0], geo[1]);
                double blend = GEOSTROPHIC_DIRECT_WIND_BLEND
                        + 0.14 * Math.min(1.0, geoMag / 8.0); // ∈ [0.18, 0.32]
                double targetX = lerp(drv.targetWindX(), geo[0], blend);
                double targetZ = lerp(drv.targetWindZ(), geo[1], blend);

                // 4) 地形阻力与偏转
                double[] grad = terrainGradient(terrain, bx, bz);
                double gx = grad[0], gz = grad[1];
                double gradMag = Math.hypot(gx, gz);
                if (gradMag > 1e-6) {
                    double uphill = (targetX * gx + targetZ * gz) / gradMag;
                    double drag = clamp(uphill * TERRAIN_FORM_DRAG_SCALE, 0, 0.55);
                    targetX -= gx / gradMag * drag * Math.hypot(targetX, targetZ);
                    targetZ -= gz / gradMag * drag * Math.hypot(targetX, targetZ);
                    double deflect = clamp(gradMag * TERRAIN_FLOW_DEFLECTION_SCALE, 0, 0.55);
                    // 沿等高线偏转
                    double tx = -gz / gradMag, tz = gx / gradMag;
                    double along = targetX * tx + targetZ * tz;
                    targetX = lerp(targetX, tx * along, deflect);
                    targetZ = lerp(targetZ, tz * along, deflect);
                }

                // 5) 松弛到目标
                double k = Math.min(1.0, dtSeconds * FLOW_RELAXATION_PER_SECOND);
                windX = lerp(windX, targetX, k);
                windZ = lerp(windZ, targetZ, k);

                // 6) 粗糙度拖曳
                double roughDrag = clamp(dtSeconds * (0.0025 + c.roughness01 * 0.01), 0, 0.22);
                windX *= (1 - roughDrag);
                windZ *= (1 - roughDrag);

                // 风速硬上限
                double spd = Math.hypot(windX, windZ);
                if (spd > MAX_DYNAMIC_WIND_MPS) {
                    windX *= MAX_DYNAMIC_WIND_MPS / spd;
                    windZ *= MAX_DYNAMIC_WIND_MPS / spd;
                }

                c.windX = windX;
                c.windZ = windZ;
                c.pressureAnomalyPa = pressure;

                // 7) 温度/湿度松弛
                double biomeT = terrain.biomeTemperatureKelvin(bx, bz) + seasonTempBiasK;
                double targetAirT = biomeT + drv.temperatureBiasKelvin()
                        - c.terrainHeight * ALTITUDE_LAPSE_RATE_K_PER_BLOCK;
                double solarHeating = 6.0 * Math.max(0, Math.sin(timeOfDay01 * Math.PI * 2 - Math.PI / 2))
                        * (raining ? 0.25 : 1.0);
                double clearCooling = 2.0 * (raining ? 0.2 : 1.0);
                double tRelax = Math.min(1.0, dtSeconds / 1200.0);
                c.airTempK = lerp(adv.airTempK, targetAirT, tRelax)
                        + solarHeating * 0.01 - clearCooling * 0.01;
                c.surfaceTempK = lerp(adv.surfaceTempK, targetAirT + solarHeating * 0.4, tRelax);
                c.deepGroundTempK = lerp(adv.deepGroundTempK, biomeT - 3.0,
                        Math.min(1.0, dtSeconds / 7200.0));

                double hRelax = Math.min(1.0, dtSeconds / 900.0);
                double targetH = clamp01(drv.humidity01()
                        + (c.surfaceTempK - c.airTempK) * 0.01 + (raining ? 0.05 : 0));
                c.humidity01 = clamp01(lerp(adv.humidity01, targetH, hRelax));

                // 强迫透传
                c.convHeatK = drv.convectiveHeatingKelvin();
                c.convMoist = drv.convectiveMoistening();
                c.convInX = drv.convectiveInflowX();
                c.convInZ = drv.convectiveInflowZ();
                c.convEnv = drv.convectiveEnvelope();
                c.torWindX = drv.tornadoWindX();
                c.torWindZ = drv.tornadoWindZ();
                c.torHeatK = drv.tornadoHeatingKelvin();
                c.torMoist = drv.tornadoMoistening();
                c.torUpdraft = drv.tornadoUpdraftProxy();
            }
        }
    }

    private double[] computeGeostrophicWind(int i, int j) {
        double pe = pressureAt(i + 1, j), pw = pressureAt(i - 1, j);
        double ps = pressureAt(i, j + 1), pn = pressureAt(i, j - 1);
        double dPdx = (pe - pw) / (2 * CELL_SIZE_BLOCKS);
        double dPdz = (ps - pn) / (2 * CELL_SIZE_BLOCKS);
        // 北半球 Coriolis 符号为正
        double ug = -dPdz * GEOSTROPHIC_WIND_SCALE;
        double vg = dPdx * GEOSTROPHIC_WIND_SCALE;
        return new double[]{ug, vg};
    }

    private double pressureAt(int i, int j) {
        i = clamp(i, 0, SIZE - 1);
        j = clamp(j, 0, SIZE - 1);
        return prev[i][j].pressureAnomalyPa;
    }

    private double[] terrainGradient(TerrainProbe terrain, double bx, double bz) {
        double e = terrain.terrainHeightBlocks(bx + CELL_SIZE_BLOCKS, bz);
        double w = terrain.terrainHeightBlocks(bx - CELL_SIZE_BLOCKS, bz);
        double s = terrain.terrainHeightBlocks(bx, bz + CELL_SIZE_BLOCKS);
        double n = terrain.terrainHeightBlocks(bx, bz - CELL_SIZE_BLOCKS);
        return new double[]{(e - w) / (2 * CELL_SIZE_BLOCKS), (s - n) / (2 * CELL_SIZE_BLOCKS)};
    }

    private CellState neighborMean(CellState[][] g, int i, int j) {
        CellState m = new CellState();
        int cnt = 0;
        int[][] d = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (int[] dd : d) {
            int ii = i + dd[0], jj = j + dd[1];
            if (ii >= 0 && ii < SIZE && jj >= 0 && jj < SIZE) {
                m.windX += g[ii][jj].windX;
                m.windZ += g[ii][jj].windZ;
                m.pressureAnomalyPa += g[ii][jj].pressureAnomalyPa;
                cnt++;
            }
        }
        if (cnt > 0) {
            m.windX /= cnt;
            m.windZ /= cnt;
            m.pressureAnomalyPa /= cnt;
        }
        return m;
    }

    private CellState bilinear(CellState[][] g, double x, double z) {
        int x0 = clamp((int) Math.floor(x), 0, SIZE - 2);
        int z0 = clamp((int) Math.floor(z), 0, SIZE - 2);
        double fx = clamp01(x - x0), fz = clamp01(z - z0);
        CellState a = g[x0][z0], b = g[x0 + 1][z0], c = g[x0][z0 + 1], d = g[x0 + 1][z0 + 1];
        CellState r = new CellState();
        r.windX = bilerp(a.windX, b.windX, c.windX, d.windX, fx, fz);
        r.windZ = bilerp(a.windZ, b.windZ, c.windZ, d.windZ, fx, fz);
        r.pressureAnomalyPa = bilerp(a.pressureAnomalyPa, b.pressureAnomalyPa,
                c.pressureAnomalyPa, d.pressureAnomalyPa, fx, fz);
        r.airTempK = bilerp(a.airTempK, b.airTempK, c.airTempK, d.airTempK, fx, fz);
        r.surfaceTempK = bilerp(a.surfaceTempK, b.surfaceTempK, c.surfaceTempK, d.surfaceTempK, fx, fz);
        r.humidity01 = bilerp(a.humidity01, b.humidity01, c.humidity01, d.humidity01, fx, fz);
        return r;
    }

    private static double bilerp(double a, double b, double c, double d, double fx, double fz) {
        return lerp(lerp(a, b, fx), lerp(c, d, fx), fz);
    }

    private static void copyInto(CellState src, CellState dst) {
        dst.windX = src.windX; dst.windZ = src.windZ;
        dst.geoWindX = src.geoWindX; dst.geoWindZ = src.geoWindZ;
        dst.pressureAnomalyPa = src.pressureAnomalyPa;
        dst.airTempK = src.airTempK; dst.surfaceTempK = src.surfaceTempK;
        dst.deepGroundTempK = src.deepGroundTempK; dst.humidity01 = src.humidity01;
        dst.convHeatK = src.convHeatK; dst.convMoist = src.convMoist;
        dst.convInX = src.convInX; dst.convInZ = src.convInZ; dst.convEnv = src.convEnv;
        dst.torWindX = src.torWindX; dst.torWindZ = src.torWindZ;
        dst.torHeatK = src.torHeatK; dst.torMoist = src.torMoist; dst.torUpdraft = src.torUpdraft;
        dst.terrainHeight = src.terrainHeight; dst.roughness01 = src.roughness01;
    }

    /** 双线性插值采样（方块坐标）。 */
    public Sample sample(double blockX, double blockZ) {
        double fi = (blockX - focusBlockX) / CELL_SIZE_BLOCKS + RADIUS_CELLS;
        double fj = (blockZ - focusBlockZ) / CELL_SIZE_BLOCKS + RADIUS_CELLS;
        int x0 = clamp((int) Math.floor(fi), 0, SIZE - 2);
        int z0 = clamp((int) Math.floor(fj), 0, SIZE - 2);
        double fx = clamp01(fi - x0), fz = clamp01(fj - z0);
        CellState a = cells[x0][z0], b = cells[x0 + 1][z0];
        CellState c = cells[x0][z0 + 1], d = cells[x0 + 1][z0 + 1];
        double turbHint = Math.hypot(
                bilerp(a.convInX, b.convInX, c.convInX, d.convInX, fx, fz),
                bilerp(a.convInZ, b.convInZ, c.convInZ, d.convInZ, fx, fz));
        return new Sample(
                bilerp(a.windX, b.windX, c.windX, d.windX, fx, fz),
                bilerp(a.windZ, b.windZ, c.windZ, d.windZ, fx, fz),
                bilerp(a.pressureAnomalyPa, b.pressureAnomalyPa, c.pressureAnomalyPa, d.pressureAnomalyPa, fx, fz),
                bilerp(a.airTempK, b.airTempK, c.airTempK, d.airTempK, fx, fz),
                bilerp(a.surfaceTempK, b.surfaceTempK, c.surfaceTempK, d.surfaceTempK, fx, fz),
                bilerp(a.humidity01, b.humidity01, c.humidity01, d.humidity01, fx, fz),
                bilerp(a.convHeatK, b.convHeatK, c.convHeatK, d.convHeatK, fx, fz),
                bilerp(a.convMoist, b.convMoist, c.convMoist, d.convMoist, fx, fz),
                bilerp(a.convInX, b.convInX, c.convInX, d.convInX, fx, fz),
                bilerp(a.convInZ, b.convInZ, c.convInZ, d.convInZ, fx, fz),
                bilerp(a.convEnv, b.convEnv, c.convEnv, d.convEnv, fx, fz),
                bilerp(a.torWindX, b.torWindX, c.torWindX, d.torWindX, fx, fz),
                bilerp(a.torWindZ, b.torWindZ, c.torWindZ, d.torWindZ, fx, fz),
                bilerp(a.torHeatK, b.torHeatK, c.torHeatK, d.torHeatK, fx, fz),
                bilerp(a.torMoist, b.torMoist, c.torMoist, d.torMoist, fx, fz),
                bilerp(a.torUpdraft, b.torUpdraft, c.torUpdraft, d.torUpdraft, fx, fz),
                turbHint);
    }

    private static double lerp(double a, double b, double t) { return a + (b - a) * t; }
    private static double clamp01(double v) { return v < 0 ? 0 : Math.min(v, 1); }
    private static int clamp(int v, int lo, int hi) { return v < lo ? lo : Math.min(v, hi); }
    private static double clamp(double v, double lo, double hi) { return v < lo ? lo : Math.min(v, hi); }
}
