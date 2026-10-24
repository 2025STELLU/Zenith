package net.atomos.zenith.wind;

import net.atomos.zenith.weather.FogModel;

/**
 * L0 背景天气网格，服务端权威：41×41 格，每格 256 格方块，单层。
 *
 * 每次刷新的流水线：半拉格朗日平流 → 扩散 → 地转调整 → 地形形阻 →
 * 松弛到目标 → 温度/湿度收支。简单说就是先让风自己动起来，
 * 再慢慢往驱动器给的目标风靠，山挡一下、太阳晒一下。
 *
 * <p><b>这里是自由大气</b>（L0-06）：近地摩擦全部由 L1 的对数廓线负责。
 * 旧实现在这里乘一次 {@code (1-roughDrag)}、L1 又用 z0 削一次，地表风被扣两遍，
 * 稳态只剩应有值的 64%（详见报告 L0-06）。
 *
 * <p><b>所有松弛/扩散都是速率形式</b>（L0-03）：{@code 1-exp(-dt/τ)}，
 * 不再"每次刷新乘一个固定比例"。固定比例等于把扩散系数绑死在刷新周期上——
 * 把 256 tick 改成 64 tick，扩散就变成 4 倍、温度偏差变成 15 K。
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

    /**
     * 风场邻域混合的时间常数（秒）。旧值"每次刷新 0.16"在 dt=12.8 s 时等效 τ≈73 s，
     * 现在写成显式 τ，换刷新周期不再改平滑度（L0-03）。
     */
    public static final double WIND_DIFFUSION_TAU_SECONDS = 70.0;
    /** 气压邻域混合的时间常数（秒）。旧 0.10/12.8 s ≈ 122 s，取 180 s 让低压更"耐看"。 */
    public static final double PRESSURE_DIFFUSION_TAU_SECONDS = 180.0;
    /** 气压向驱动器目标靠拢的时间常数（秒）。旧 0.35/12.8 s ≈ 30 s。 */
    public static final double PRESSURE_RELAX_TAU_SECONDS = 30.0;

    /**
     * 温度收支速率（K/s，单位强度）。旧实现每次刷新直接 {@code ±0.01 K}，
     * 在 dt=12.8 s 下等价于这些速率；写成速率后与刷新周期无关（L0-03）。
     */
    public static final double SOLAR_HEATING_K_PER_SECOND = 0.01 / 12.8;
    public static final double CLEAR_SKY_COOLING_K_PER_SECOND = 0.01 / 12.8;

    /**
     * 生物群系基础温度（MC 的 0..2 无量纲值）→ 开尔文的分段映射（L0-05）。
     *
     * <p>旧实现 {@code 273.15 + t*30} 把沙漠（t=2.0）算成 333 K = 60 °C，
     * 直接把 ABL 稳定度钉成"永远不稳定"、混合层高度恒 460 格。
     * 这里按 MC 实际下垫面标定：雪原 −0.5 → 255 K（−18 °C）、温带 0.8 → 284 K（11 °C）、
     * 沙漠 2.0 → 310 K（37 °C）。
     */
    public static final double BIOME_TEMP_MIN_K = 255.0;
    public static final double BIOME_TEMP_MAX_K = 310.0;
    public static final double BIOME_TEMP_MIN_INPUT = -0.5;
    public static final double BIOME_TEMP_MAX_INPUT = 2.0;

    /** 地形探针：高度/粗糙度/生物群系气温。运行时注入，走探针而不是读区块，免得触发区块加载。 */
    public interface TerrainProbe {
        double terrainHeightBlocks(double blockX, double blockZ);
        double roughness01(double blockX, double blockZ);
        double biomeTemperatureKelvin(double blockX, double blockZ);

        /** 海平面（方块 y）：高度递减率只对**海平面以上**的部分生效（L0-05）。 */
        default double seaLevelBlocks() {
            return 64.0;
        }
    }

    public static final class CellState {
        public double windX, windZ;
        public double geoWindX, geoWindZ;
        public double pressureAnomalyPa;
        public double airTempK = 288.0;
        public double surfaceTempK = 288.0;
        public double deepGroundTempK = 285.0;
        public double humidity01 = 0.5;
        // 对流/龙卷强迫：从驱动器原样透传下来
        public double convHeatK, convMoist, convInX, convInZ, convEnv;
        public double torWindX, torWindZ, torHeatK, torMoist, torUpdraft;
        public double terrainHeight;
        public double roughness01 = 0.3;
    }

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
    /**
     * 该格还没有有效历史：下一次 {@link #refresh} 直接从驱动器冷启动，
     * 而不是从"被平移进来的邻居"平流（L0-02）。
     */
    private final boolean[][] coldStart = new boolean[SIZE][SIZE];

    private double focusBlockX, focusBlockZ;
    private boolean initialized = false;

    // 每格复用的暂存对象：旧实现每格 new 2 个 CellState + 2 个 double[]，
    // 一次刷新 1089 格就是约 4,400 个临时对象（L0-08）。
    private final CellState scratchNeighbor = new CellState();
    private final CellState scratchAdv = new CellState();
    private final double[] scratchGrad = new double[2];
    private final double[] scratchGeo = new double[2];

    public BackgroundMetGrid() {
        for (int i = 0; i < SIZE; i++)
            for (int j = 0; j < SIZE; j++) {
                cells[i][j] = new CellState();
                prev[i][j] = new CellState();
                coldStart[i][j] = true;
            }
    }

    /**
     * 设置网格中心（方块坐标）。中心会被**对齐到 256 格整数倍**，网格内容跟着整数格平移。
     *
     * <p>旧实现只改 {@code focusBlockX/Z}、数组内容不平移（L0-02）：下标到地理坐标的映射
     * 整体跳变，A 地的风场被当成 B 地的风，玩家跑图时背景风"贴在人身上"。
     * 现在对齐后：
     * <ul>
     *   <li>{@code cellBlockX(i)} 恒为 256 的整数倍，驱动器采样点不再随焦点漂移；</li>
     *   <li>中心移动时按整数格平移 {@code cells}/{@code prev}，地理配准保持不变；</li>
     *   <li>新暴露的格子标 {@link #coldStart}，下一 tick 用驱动器目标冷启动，
     *       而不是把边缘值当"从域外平流进来的风"。</li>
     * </ul>
     */
    public void setFocus(double blockX, double blockZ) {
        double alignedX = Math.floor(blockX / CELL_SIZE_BLOCKS) * CELL_SIZE_BLOCKS;
        double alignedZ = Math.floor(blockZ / CELL_SIZE_BLOCKS) * CELL_SIZE_BLOCKS;
        if (!initialized) {
            focusBlockX = alignedX;
            focusBlockZ = alignedZ;
            initialized = true;
            markAllColdStart();
            return;
        }
        int di = (int) Math.round((alignedX - focusBlockX) / CELL_SIZE_BLOCKS);
        int dj = (int) Math.round((alignedZ - focusBlockZ) / CELL_SIZE_BLOCKS);
        if (di == 0 && dj == 0) return;

        focusBlockX = alignedX;
        focusBlockZ = alignedZ;

        // 平移超过整整一张网格：重叠区为空，全部冷启动
        if (Math.abs(di) >= SIZE || Math.abs(dj) >= SIZE) {
            markAllColdStart();
            return;
        }

        CellState[][] newCells = new CellState[SIZE][SIZE];
        CellState[][] newPrev = new CellState[SIZE][SIZE];
        boolean[][] newCold = new boolean[SIZE][SIZE];
        for (int i = 0; i < SIZE; i++) {
            for (int j = 0; j < SIZE; j++) {
                int si = i + di, sj = j + dj;
                if (si >= 0 && si < SIZE && sj >= 0 && sj < SIZE) {
                    newCells[i][j] = cells[si][sj];
                    newPrev[i][j] = prev[si][sj];
                    newCold[i][j] = coldStart[si][sj];
                } else {
                    newCells[i][j] = new CellState();
                    newPrev[i][j] = new CellState();
                    newCold[i][j] = true;
                }
            }
        }
        for (int i = 0; i < SIZE; i++) {
            for (int j = 0; j < SIZE; j++) {
                cells[i][j] = newCells[i][j];
                prev[i][j] = newPrev[i][j];
                coldStart[i][j] = newCold[i][j];
            }
        }
    }

    private void markAllColdStart() {
        for (int i = 0; i < SIZE; i++)
            for (int j = 0; j < SIZE; j++) coldStart[i][j] = true;
    }

    private double cellBlockX(int i) { return focusBlockX + (i - RADIUS_CELLS) * CELL_SIZE_BLOCKS; }
    private double cellBlockZ(int j) { return focusBlockZ + (j - RADIUS_CELLS) * CELL_SIZE_BLOCKS; }

    /** 每 BACKGROUND_MET_REFRESH_TICKS 调用一次。 */
    public void refresh(WorldScaleDriver driver, TerrainProbe terrain,
                        double dtSeconds, double timeOfDay01, boolean raining,
                        double seasonTempBiasK) {
        for (int i = 0; i < SIZE; i++)
            for (int j = 0; j < SIZE; j++) {
                copyInto(cells[i][j], prev[i][j]);
            }

        // 速率形式的混合系数（L0-03）：与刷新周期解耦
        double windMix = rateFactor(dtSeconds, WIND_DIFFUSION_TAU_SECONDS);
        double pressureMix = rateFactor(dtSeconds, PRESSURE_DIFFUSION_TAU_SECONDS);
        double pressureRelax = rateFactor(dtSeconds, PRESSURE_RELAX_TAU_SECONDS);
        double flowRelax = rateFactor(dtSeconds, 1.0 / FLOW_RELAXATION_PER_SECOND);
        double solarK = SOLAR_HEATING_K_PER_SECOND * dtSeconds;
        double coolingK = CLEAR_SKY_COOLING_K_PER_SECOND * dtSeconds;
        double seaLevel = terrain.seaLevelBlocks();

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

                // 0) 冷启动：新暴露的格子没有历史，直接用驱动器目标，别把边缘值当平流输入
                if (coldStart[i][j]) {
                    coldStart[i][j] = false;
                    c.windX = drv.targetWindX();
                    c.windZ = drv.targetWindZ();
                    c.pressureAnomalyPa = drv.pressureAnomalyPa();
                    c.airTempK = drv.temperatureBiasKelvin() + 288.0;
                    c.surfaceTempK = c.airTempK;
                    c.humidity01 = drv.humidity01();
                    copyInto(c, p);
                }

                // 1) 半拉格朗日平流：顺着上一步的风往回找
                double backX = i - p.windX * dtSeconds / CELL_SIZE_BLOCKS;
                double backZ = j - p.windZ * dtSeconds / CELL_SIZE_BLOCKS;
                bilinear(prev, backX, backZ, scratchAdv);

                double windX = scratchAdv.windX, windZ = scratchAdv.windZ;
                double pressure = scratchAdv.pressureAnomalyPa;

                // 2) 扩散：跟四个邻居掺一掺，抹平尖峰
                neighborMean(prev, i, j, scratchNeighbor);
                windX = lerp(windX, scratchNeighbor.windX, windMix);
                windZ = lerp(windZ, scratchNeighbor.windZ, windMix);
                pressure = lerp(pressure, scratchNeighbor.pressureAnomalyPa, pressureMix);

                // 驱动器目标叠加
                pressure = lerp(pressure, drv.pressureAnomalyPa(), pressureRelax);

                // 3) 地转调整：气压梯度 + 科里奥利掐出来的地转风
                computeGeostrophicWind(i, j, scratchGeo);
                c.geoWindX = scratchGeo[0];
                c.geoWindZ = scratchGeo[1];
                double geoMag = Math.hypot(scratchGeo[0], scratchGeo[1]);
                double blend = GEOSTROPHIC_DIRECT_WIND_BLEND
                        + 0.14 * Math.min(1.0, geoMag / 8.0); // 落在 [0.18, 0.32]
                double targetX = lerp(drv.targetWindX(), scratchGeo[0], blend);
                double targetZ = lerp(drv.targetWindZ(), scratchGeo[1], blend);

                // 4) 地形：迎风坡减速（形阻），再沿等高线拐个弯
                terrainGradient(terrain, bx, bz, scratchGrad);
                double gx = scratchGrad[0], gz = scratchGrad[1];
                double gradMag = Math.hypot(gx, gz);
                if (gradMag > 1e-6) {
                    // 先缓存入射风速，再分别乘两个分量：旧代码第二次用的
                    // Math.hypot(targetX, targetZ) 已经被第一次改过，衰减量随 gx 符号不对称（L0-04）
                    double targetMag = Math.hypot(targetX, targetZ);
                    double uphill = (targetX * gx + targetZ * gz) / gradMag;
                    double drag = smoothstep01(clamp(uphill * TERRAIN_FORM_DRAG_SCALE, 0, 0.55));
                    targetX -= gx / gradMag * drag * targetMag;
                    targetZ -= gz / gradMag * drag * targetMag;
                    double deflect = smoothstep01(clamp(gradMag * TERRAIN_FLOW_DEFLECTION_SCALE, 0, 0.55));
                    double tx = -gz / gradMag, tz = gx / gradMag; // 等高线切向
                    double along = targetX * tx + targetZ * tz;
                    targetX = lerp(targetX, tx * along, deflect);
                    targetZ = lerp(targetZ, tz * along, deflect);
                }

                // 5) 松弛：慢慢往目标风靠，别硬掰
                windX = lerp(windX, targetX, flowRelax);
                windZ = lerp(windZ, targetZ, flowRelax);

                // 6) 不再做地面粗糙度衰减（L0-06）：这里是自由大气，
                // 近地摩擦交给 L1 的对数廓线。两层各削一次会让地表风只剩 64%。

                // 风速硬上限，免得数值炸了
                double spd = Math.hypot(windX, windZ);
                if (spd > MAX_DYNAMIC_WIND_MPS) {
                    windX *= MAX_DYNAMIC_WIND_MPS / spd;
                    windZ *= MAX_DYNAMIC_WIND_MPS / spd;
                }

                c.windX = windX;
                c.windZ = windZ;
                c.pressureAnomalyPa = pressure;

                // 7) 温度/湿度收支：往生物群系基准 + 高度递减率靠，再按**速率**加日照/长波
                double biomeT = terrain.biomeTemperatureKelvin(bx, bz) + seasonTempBiasK;
                // 递减率只对海平面以上生效：海平面附近的低地不该被扣 0.42 K（L0-05）
                double elevationAboveSea = Math.max(0.0, c.terrainHeight - seaLevel);
                double targetAirT = biomeT + drv.temperatureBiasKelvin()
                        - elevationAboveSea * ALTITUDE_LAPSE_RATE_K_PER_BLOCK;
                // 日照加热只看太阳高度：正午最强、午夜归零（太阳时间统一走 FogModel.solarFactor）
                double solarHeating = 6.0 * Math.max(0, FogModel.solarFactor(timeOfDay01))
                        * (raining ? 0.25 : 1.0);
                double clearCooling = 2.0 * (raining ? 0.2 : 1.0);
                double tRelax = rateFactor(dtSeconds, 1200.0);
                c.airTempK = lerp(scratchAdv.airTempK, targetAirT, tRelax)
                        + solarK * solarHeating - coolingK * clearCooling;
                c.surfaceTempK = lerp(scratchAdv.surfaceTempK, targetAirT + solarHeating * 0.4, tRelax);
                c.deepGroundTempK = lerp(scratchAdv.deepGroundTempK, biomeT - 3.0,
                        rateFactor(dtSeconds, 7200.0));

                double hRelax = rateFactor(dtSeconds, 900.0);
                double targetH = clamp01(drv.humidity01()
                        + (c.surfaceTempK - c.airTempK) * 0.01 + (raining ? 0.05 : 0));
                c.humidity01 = clamp01(lerp(scratchAdv.humidity01, targetH, hRelax));

                // 本格的对流/龙卷强迫：驱动器透传 + **按面积加权平均，不是取最大**
                // （L0-09 的另一个问题：旧实现直接覆盖，重叠区的强迫会被最后处理的格盖掉）。
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

                // 对流加热/加湿**真的进热力场**（L0-09）：旧实现只有写入没有读取，
                // convHeatK/torHeatK 是死数据，对流与温度场完全解耦。
                // 现在作为"目标温度的增量"叠加——加热不改变平衡点以外的任何东西，
                // 但积雨云下方的空气确实会比周围暖一点。
                c.airTempK += c.convHeatK + c.torHeatK;
                c.humidity01 = clamp01(c.humidity01 + c.convMoist + c.torMoist);
            }
        }
    }

    /** 一阶松弛的离散步长系数：{@code 1-exp(-dt/τ)}，无论 dt 多大都落在 [0,1)。 */
    private static double rateFactor(double dtSeconds, double tauSeconds) {
        if (tauSeconds <= 0) return 1.0;
        return 1.0 - Math.exp(-dtSeconds / tauSeconds);
    }

    /** 0..1 的 C¹ 过渡：旧实现用硬 clamp，形阻在迎/背风交界处折角（L0-04）。 */
    private static double smoothstep01(double t) {
        if (t <= 0) return 0;
        if (t >= 1) return 1;
        return t * t * (3 - 2 * t);
    }

    private void computeGeostrophicWind(int i, int j, double[] out) {
        double pe = pressureAt(i + 1, j), pw = pressureAt(i - 1, j);
        double ps = pressureAt(i, j + 1), pn = pressureAt(i, j - 1);
        double dPdx = (pe - pw) / (2 * CELL_SIZE_BLOCKS);
        double dPdz = (ps - pn) / (2 * CELL_SIZE_BLOCKS);
        // 地转风：北半球 u=+∂p/∂z·K、w=−∂p/∂x·K（见 HemisphereConvention#geostrophic）。
        // 旧代码写成 (-∂p/∂z, +∂p/∂x)，两个分量都反，等于整体旋转 180°（SYS-01）。
        double[] g = HemisphereConvention.geostrophic(dPdx, dPdz, GEOSTROPHIC_WIND_SCALE);
        out[0] = g[0];
        out[1] = g[1];
    }

    private double pressureAt(int i, int j) {
        i = clamp(i, 0, SIZE - 1);
        j = clamp(j, 0, SIZE - 1);
        return prev[i][j].pressureAnomalyPa;
    }

    private void terrainGradient(TerrainProbe terrain, double bx, double bz, double[] out) {
        double e = terrain.terrainHeightBlocks(bx + CELL_SIZE_BLOCKS, bz);
        double w = terrain.terrainHeightBlocks(bx - CELL_SIZE_BLOCKS, bz);
        double s = terrain.terrainHeightBlocks(bx, bz + CELL_SIZE_BLOCKS);
        double n = terrain.terrainHeightBlocks(bx, bz - CELL_SIZE_BLOCKS);
        out[0] = (e - w) / (2 * CELL_SIZE_BLOCKS);
        out[1] = (s - n) / (2 * CELL_SIZE_BLOCKS);
    }

    /** 四邻域均值，写进可复用的 {@code m}（L0-08）。 */
    private void neighborMean(CellState[][] g, int i, int j, CellState m) {
        m.windX = 0;
        m.windZ = 0;
        m.pressureAnomalyPa = 0;
        int cnt = 0;
        if (i + 1 < SIZE) { m.windX += g[i + 1][j].windX; m.windZ += g[i + 1][j].windZ; m.pressureAnomalyPa += g[i + 1][j].pressureAnomalyPa; cnt++; }
        if (i - 1 >= 0) { m.windX += g[i - 1][j].windX; m.windZ += g[i - 1][j].windZ; m.pressureAnomalyPa += g[i - 1][j].pressureAnomalyPa; cnt++; }
        if (j + 1 < SIZE) { m.windX += g[i][j + 1].windX; m.windZ += g[i][j + 1].windZ; m.pressureAnomalyPa += g[i][j + 1].pressureAnomalyPa; cnt++; }
        if (j - 1 >= 0) { m.windX += g[i][j - 1].windX; m.windZ += g[i][j - 1].windZ; m.pressureAnomalyPa += g[i][j - 1].pressureAnomalyPa; cnt++; }
        if (cnt > 0) {
            m.windX /= cnt;
            m.windZ /= cnt;
            m.pressureAnomalyPa /= cnt;
        }
    }

    /**
     * 半拉格朗日回溯采样，结果写进可复用的 {@code out}（L0-08）。
     * <p>只插值被平流/扩散/热力用到的字段；强迫项（conv/tor）不平流——它们是驱动器每 tick
     * 现算的瞬时值，平流会让雷暴一旦离开格子就永远拖一条尾巴。
     */
    private void bilinear(CellState[][] g, double x, double z, CellState out) {
        int x0 = clamp((int) Math.floor(x), 0, SIZE - 2);
        int z0 = clamp((int) Math.floor(z), 0, SIZE - 2);
        double fx = clamp01(x - x0), fz = clamp01(z - z0);
        CellState a = g[x0][z0], b = g[x0 + 1][z0], c = g[x0][z0 + 1], d = g[x0 + 1][z0 + 1];
        out.windX = bilerp(a.windX, b.windX, c.windX, d.windX, fx, fz);
        out.windZ = bilerp(a.windZ, b.windZ, c.windZ, d.windZ, fx, fz);
        out.pressureAnomalyPa = bilerp(a.pressureAnomalyPa, b.pressureAnomalyPa,
                c.pressureAnomalyPa, d.pressureAnomalyPa, fx, fz);
        out.airTempK = bilerp(a.airTempK, b.airTempK, c.airTempK, d.airTempK, fx, fz);
        out.surfaceTempK = bilerp(a.surfaceTempK, b.surfaceTempK, c.surfaceTempK, d.surfaceTempK, fx, fz);
        out.humidity01 = bilerp(a.humidity01, b.humidity01, c.humidity01, d.humidity01, fx, fz);
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

    /** 双线性插值采样，输入方块坐标。 */
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
