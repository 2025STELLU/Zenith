package net.atomos.zenith.wind;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import net.atomos.zenith.weather.FogModel;
import net.atomos.zenith.weather.ZenithRandom;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

/**
 * 行星尺度天气驱动，服务端跑的。
 * 每格风 = 基流 + 行星波 + 气旋 + 对流团，钳制 ±12 m/s。
 * 网格 384×384，每格 256 blocks，跟 L0 对齐。
 *
 * <p><b>周期化</b>：网格是环面（{@link #GRID_CELLS}），气旋/对流团跨过回卷边界时位置会突然
 * 从 383 跳回 0。采样必须用最小镜像距离 {@link #wrapDelta}，否则边界两侧的风场不连续、
 * 且世界坐标 {@code x<0} / {@code z<0} 的象限只能吃到高斯尾巴（DRV-01）。
 *
 * <p><b>龙卷不再在 L0 里算风</b>：核心 10–24 格、影响半径 48–128 格的涡旋落在 256 格网格中心
 * 采样等于抽签，命中也是 {@code exp(-(r/rc)²)} ≈ 0（DRV-03）。龙卷现在由
 * {@code weather.TornadoSystem} 逐点采样，本类只接收它的**大尺度**足迹（气压 + 加热）。
 */
public class WorldScaleDriver {
    public static final double SOLVER_STEP_SECONDS = 0.05;
    public static final double BASE_FLOW_RELAX_PER_SECOND = 1.0 / 900.0;
    public static final double STORM_RELAX_PER_SECOND = 1.0 / 600.0;
    public static final double MAX_DRIVER_WIND_MPS = 12.0;
    public static final double SYNOPTIC_LULL_MIN_FACTOR = 0.18;
    public static final double CYCLONE_CELL_MAX_SWIRL_MPS = 10.0;
    public static final double CYCLONE_CELL_MAX_PRESSURE_ANOMALY_PA = 1350.0;
    public static final double CONVECTIVE_CLUSTER_MAX_CONVERGENCE_MPS = 4.0;
    public static final double CONVECTIVE_CLUSTER_THERMAL_LOW_PA = 240.0;
    public static final int DEFAULT_CYCLONE_CELL_COUNT = 6;
    public static final int MAX_CONVECTIVE_CLUSTERS = 6;
    public static final double DRIVER_SPATIAL_SCALE_X = 0.11;
    public static final double DRIVER_SPATIAL_SCALE_Z = 0.09;
    public static final int GRID_CELLS = 384;
    public static final double CELL_SIZE_BLOCKS = 256.0;

    /**
     * 龙卷在 L0 上的足迹半径（格）。龙卷本身（48–128 格）远小于 256 格网格，
     * L0 只该看到"这一带有强对流"的一团平滑低压，不该试图解析涡旋本身（DRV-03）。
     */
    public static final double TORNADO_L0_FOOTPRINT_BLOCKS = 1500.0;
    /** 上述足迹中心的气压亏损（Pa），比气旋小两个量级。 */
    public static final double TORNADO_L0_PRESSURE_PA = 90.0;

    /** 采样结果：L0 消费的全部强迫场。 */
    public record Sample(
            double targetWindX, double targetWindZ,
            double pressureAnomalyPa,
            double temperatureBiasKelvin,
            double humidity01,
            double stormActivity,
            double convectiveHeatingKelvin, double convectiveMoistening,
            double convectiveInflowX, double convectiveInflowZ, double convectiveEnvelope,
            double tornadoWindX, double tornadoWindZ,
            double tornadoHeatingKelvin, double tornadoMoistening, double tornadoUpdraftProxy) {

        public static Sample calm() {
            return new Sample(0, 0, 0, 0, 0.5, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
        }
    }

    /** L1 反馈给驱动器的诊断量。 */
    public record Feedback(double maxInstabilityProxy, double meanLowLevelShear,
                           double maxPositiveMoistureConvergence) {
        public static Feedback none() { return new Feedback(0, 0, 0); }
    }

    /** 对流团宿主（供 {@code TornadoSystem} 决定在哪里生龙卷）。方块坐标。 */
    public record ConvectiveHost(double x, double z, double radiusBlocks, double intensity01) {}

    /**
     * 位置相关的基础流偏置（WX-MS-01）：季风这类"大尺度、沿海岸加权"的环流，
     * 直接加到基流上，而不是当作一个逐点叠加的现象。
     *
     * <p>参数是**方块坐标**（驱动器内部会从格坐标换算），返回 {@code {biasX, biasZ}}（m/s）；
     * 域外/无偏置时返回 {@code null} 或 {@code {0,0}}。
     */
    public interface BaseFlowBias {
        double[] at(double blockX, double blockZ);
    }

    private final ZenithRandom random;

    private BaseFlowBias baseFlowBias;

    private double baseFlowX = 3.0;
    private double baseFlowZ = 0.5;
    private double forcedBaseFlowX = 3.0;
    private double forcedBaseFlowZ = 0.5;
    private double stormActivity = 0.1;
    private double synopticLull = 1.0;
    private double timeSeconds = 0;

    private final List<CycloneCell> cyclones = new ArrayList<>();
    private final List<ConvectiveCluster> clusters = new ArrayList<>();

    /**
     * 龙卷上报给 L0 的大尺度足迹，元素为 {@code {xBlocks, zBlocks, strength01}}。
     * 每 tick 由 {@link #advance} 清空、{@code TornadoSystem.tick} 重新写入。
     */
    private final List<double[]> tornadoFootprints = new ArrayList<>();

    public WorldScaleDriver(long seed) {
        this.random = new ZenithRandom(seed ^ 0x9E3779B97F4A7C15L);
        // 半球（科里奥利符号）统一由 HemisphereConvention 提供，默认北半球
        for (int i = 0; i < DEFAULT_CYCLONE_CELL_COUNT; i++) {
            cyclones.add(CycloneCell.spawn(random, GRID_CELLS));
        }
        for (int i = 0; i < MAX_CONVECTIVE_CLUSTERS; i++) {
            clusters.add(ConvectiveCluster.spawn(random, GRID_CELLS, cyclones));
        }
    }

    // advance

    public void advance(double dtSeconds, double timeOfDay01, boolean raining, boolean thundering,
                        Feedback feedback) {
        timeSeconds += dtSeconds;
        tornadoFootprints.clear();

        // 风暴活动度：向目标松弛，目标由 L1 反馈的不稳定/切变/水汽辐合拼出来
        double target = 0.12
                + 0.55 * clamp01(feedback.maxInstabilityProxy())
                + 0.25 * clamp01(feedback.meanLowLevelShear() / 0.02)
                + 0.35 * clamp01(feedback.maxPositiveMoistureConvergence() / 0.01);
        if (thundering) target = Math.max(target, 0.8);
        else if (raining) target = Math.max(target, 0.45);
        // 晚上对流歇一歇：正午最强、午夜最弱（太阳时间统一走 FogModel.solarFactor）
        double diurnal = 0.65 + 0.35 * FogModel.solarFactor(timeOfDay01);
        target *= 0.7 + 0.3 * diurnal;
        stormActivity += (clamp01(target) - stormActivity)
                * Math.min(1.0, dtSeconds * STORM_RELAX_PER_SECOND);

        // 基流目标：每隔几分钟随机换个风向风速，慢慢靠过去
        if (random.nextDouble() < dtSeconds / 300.0) {
            double ang = random.nextDouble() * Math.PI * 2.0;
            double spd = 1.5 + random.nextDouble() * 4.5;
            forcedBaseFlowX = Math.cos(ang) * spd;
            forcedBaseFlowZ = Math.sin(ang) * spd;
        }
        double k = Math.min(1.0, dtSeconds * BASE_FLOW_RELAX_PER_SECOND);
        baseFlowX += (forcedBaseFlowX - baseFlowX) * k;
        baseFlowZ += (forcedBaseFlowZ - baseFlowZ) * k;

        // 天气间歇因子：OU 过程（均值回归 + sqrt(dt) 噪声）。
        // 旧式 synopticLull += (rand-0.5)*dt*0.02 的方差 ∝ dt²，改 tick 步长就改统计（DRV-07）
        double lullNoise = (random.nextGaussian()) * 0.02 * Math.sqrt(dtSeconds);
        double lullPull = (0.62 - synopticLull) * Math.min(1.0, dtSeconds / 900.0);
        synopticLull = clamp(synopticLull + lullNoise + lullPull,
                SYNOPTIC_LULL_MIN_FACTOR, 1.0);

        for (CycloneCell c : cyclones) c.advance(dtSeconds, random, GRID_CELLS);
        for (ConvectiveCluster c : clusters) c.advance(dtSeconds, random, GRID_CELLS, cyclones, stormActivity);
    }

    /** 龙卷把自己的位置报给 L0（方块坐标 + 0..1 强度）。 */
    public void reportTornadoFootprint(double xBlocks, double zBlocks, double strength01) {
        if (tornadoFootprints.size() >= 8) return;
        tornadoFootprints.add(new double[]{xBlocks, zBlocks, clamp01(strength01)});
    }

    /** 装上/卸掉位置相关的基础流偏置（季风，WX-MS-01）。传 {@code null} 表示关闭。 */
    public void setBaseFlowBias(BaseFlowBias bias) {
        this.baseFlowBias = bias;
    }

    /** 供 {@code TornadoSystem} 挑宿主：返回当前对流团的中心与半径（方块坐标）。 */
    public ConvectiveHost pickConvectiveHost(Random rnd) {
        if (clusters.isEmpty()) return null;
        ConvectiveCluster c = clusters.get(rnd.nextInt(clusters.size()));
        return new ConvectiveHost(c.x * CELL_SIZE_BLOCKS, c.z * CELL_SIZE_BLOCKS,
                c.radius, c.intensity);
    }

    // sample

    public Sample sample(int cellX, int cellZ) {
        double phi = cellX * DRIVER_SPATIAL_SCALE_X + timeSeconds * 0.002;
        double psi = cellZ * DRIVER_SPATIAL_SCALE_Z - timeSeconds * 0.0013;
        double waveScale = synopticLull;

        double u = baseFlowX + waveScale * (0.90 * Math.sin(phi) + 0.35 * Math.sin(psi));
        double w = baseFlowZ + waveScale * (0.90 * Math.sin(psi + 1.7) + 0.35 * Math.sin(phi - 0.6));

        // 季风（WX-MS-01）：作为**基础流偏置**按位置加权，而不是维度均匀的现象风。
        // L0 会对整格风做双线性插值，所以这里用格中心求值就够，采样点仍拿到连续场。
        if (baseFlowBias != null) {
            double[] bias = baseFlowBias.at(cellX * CELL_SIZE_BLOCKS, cellZ * CELL_SIZE_BLOCKS);
            if (bias != null) {
                u += bias[0];
                w += bias[1];
            }
        }
        double pressure = 0;
        double tempBias = 0;
        double humidity = 0.5;
        double convHeat = 0, convMoist = 0, convInX = 0, convInZ = 0, convEnv = 0;
        double torHeat = 0, torMoist = 0, torUp = 0;

        for (CycloneCell c : cyclones) {
            // 最小镜像距离：网格是 384 格的环面，跨回卷边界的气旋不能瞬移（DRV-01）
            double dx = wrapDelta(cellX - c.x, GRID_CELLS) * CELL_SIZE_BLOCKS;
            double dz = wrapDelta(cellZ - c.z, GRID_CELLS) * CELL_SIZE_BLOCKS;
            double r = Math.sqrt(dx * dx + dz * dz);
            double outerNorm = r / c.outerRadius;
            double coreNorm = r / c.coreRadius;
            double gOuter = Math.exp(-outerNorm * outerNorm);
            double gCore = Math.exp(-coreNorm * coreNorm);
            double swirl = CYCLONE_CELL_MAX_SWIRL_MPS * c.intensity
                    * (0.55 * gOuter + 1.40 * gCore);
            // 径向：rx/rz 是"中心→采样点"的外向单位向量，
            // radialSign 让低压辐合(指向中心)、高压辐散（DRV-08：旧符号反了）
            double radial = 3.0 * c.intensity * (0.70 * gOuter + 1.20 * gCore)
                    * c.radialSign();
            double inv = r > 1e-3 ? 1.0 / r : 0;
            double[] tangent = HemisphereConvention.tangential(dx, dz, c.lowPressure);
            u += tangent[0] * inv * swirl + dx * inv * radial;
            w += tangent[1] * inv * swirl + dz * inv * radial;
            double env = Math.max(gOuter, gCore);
            pressure += c.lowPressure
                    ? -CYCLONE_CELL_MAX_PRESSURE_ANOMALY_PA * c.intensity * env
                    : CYCLONE_CELL_MAX_PRESSURE_ANOMALY_PA * c.intensity * env;
            tempBias += (c.lowPressure ? 1.2 : -1.0) * c.intensity * env;
            humidity += (c.lowPressure ? 0.25 : -0.15) * c.intensity * env;
        }

        for (ConvectiveCluster c : clusters) {
            double dx = wrapDelta(cellX - c.x, GRID_CELLS) * CELL_SIZE_BLOCKS;
            double dz = wrapDelta(cellZ - c.z, GRID_CELLS) * CELL_SIZE_BLOCKS;
            double r = Math.sqrt(dx * dx + dz * dz);
            double norm = r / c.radius;
            if (norm > 3.0) continue;
            double g = Math.exp(-norm * norm);
            double inv = r > 1e-3 ? 1.0 / r : 0;
            double inflow = CONVECTIVE_CLUSTER_MAX_CONVERGENCE_MPS * c.intensity * g;
            convInX += -dx * inv * inflow;
            convInZ += -dz * inv * inflow;
            u += -dx * inv * inflow;
            w += -dz * inv * inflow;
            pressure += -CONVECTIVE_CLUSTER_THERMAL_LOW_PA * c.intensity * g;
            convHeat += 2.5 * c.intensity * g;
            convMoist += 0.3 * c.intensity * g;
            convEnv = Math.max(convEnv, g * c.intensity);
            humidity += 0.2 * c.intensity * g;
        }

        // 龙卷：L0 只收它的**大尺度**足迹。逐个涡旋在 256 格网格上采样是混叠的（DRV-03），
        // 切向风全部交给 TornadoSystem 逐点算，这里只有一团平滑的低压 + 加热。
        double cellBlockX = cellX * CELL_SIZE_BLOCKS;
        double cellBlockZ = cellZ * CELL_SIZE_BLOCKS;
        for (double[] t : tornadoFootprints) {
            // 足迹也用最小镜像距离（DRV-01 同类）：龙卷用的是世界方块坐标，可以和
            // L0 格点隔着一个环面周期。不环绕的话，边界另一侧的龙卷对气压毫无贡献。
            double dx = wrapDelta((cellBlockX - t[0]) / CELL_SIZE_BLOCKS, GRID_CELLS)
                    * CELL_SIZE_BLOCKS;
            double dz = wrapDelta((cellBlockZ - t[1]) / CELL_SIZE_BLOCKS, GRID_CELLS)
                    * CELL_SIZE_BLOCKS;
            double g = Math.exp(-(dx * dx + dz * dz)
                    / (2.0 * TORNADO_L0_FOOTPRINT_BLOCKS * TORNADO_L0_FOOTPRINT_BLOCKS));
            if (g < 1e-4) continue;
            double s = g * t[2];
            pressure += -TORNADO_L0_PRESSURE_PA * s;
            torHeat += 0.8 * s;
            torMoist += 0.05 * s;
            torUp = Math.max(torUp, 0.35 * s);
        }

        // 按模长钳制：分量各自钳到 ±12 会让对角方向最到 17 m/s，并扭转风向（DRV-06）
        double speed = Math.hypot(u, w);
        if (speed > MAX_DRIVER_WIND_MPS) {
            double s = MAX_DRIVER_WIND_MPS / speed;
            u *= s;
            w *= s;
        }
        humidity = clamp01(humidity);

        return new Sample(u, w, pressure, tempBias, humidity, stormActivity,
                convHeat, convMoist, convInX, convInZ, convEnv,
                0, 0, torHeat, torMoist, torUp);
    }

    public double stormActivity() { return stormActivity; }

    public int activeConvectiveClusterCount() { return clusters.size(); }

    /**
     * 环面上的最小镜像位移，返回 {@code [-period/2, period/2)}（DRV-01）。
     *
     * <p>{@code delta = 383.5}、{@code period = 384} → {@code -0.5}：离 0 号格只差半格，
     * 而不是 383.5 格。
     */
    public static double wrapDelta(double delta, int period) {
        double p = period;
        return delta - p * Math.floor(delta / p + 0.5);
    }

    private static double clamp(double v, double lo, double hi) {
        return v < lo ? lo : Math.min(v, hi);
    }

    private static double clamp01(double v) { return clamp(v, 0, 1); }

    // 存档（SYS-08）

    /**
     * 驱动器状态 → NBT。
     *
     * <p>{@link ZenithRandom} 是可序列化的 SplitMix64，状态就是一个 {@code long}，所以
     * 连随机游走的位置一起存——重启后天气从原处继续，同一存档可复现（SYS-08）。
     * 旧实现用 {@code java.util.Random}，48 位种子拿不出来，只能丢掉游走状态。
     */
    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putLong("rngState", random.state());
        tag.putDouble("baseFlowX", baseFlowX);
        tag.putDouble("baseFlowZ", baseFlowZ);
        tag.putDouble("forcedBaseFlowX", forcedBaseFlowX);
        tag.putDouble("forcedBaseFlowZ", forcedBaseFlowZ);
        tag.putDouble("stormActivity", stormActivity);
        tag.putDouble("synopticLull", synopticLull);
        tag.putDouble("timeSeconds", timeSeconds);

        ListTag cyc = new ListTag();
        for (CycloneCell c : cyclones) cyc.add(c.save());
        tag.put("cyclones", cyc);

        ListTag cl = new ListTag();
        for (ConvectiveCluster c : clusters) cl.add(c.save());
        tag.put("clusters", cl);
        return tag;
    }

    public void load(CompoundTag tag) {
        if (tag.contains("rngState")) random.setState(tag.getLong("rngState"));
        baseFlowX = tag.getDouble("baseFlowX");
        baseFlowZ = tag.getDouble("baseFlowZ");
        forcedBaseFlowX = tag.getDouble("forcedBaseFlowX");
        forcedBaseFlowZ = tag.getDouble("forcedBaseFlowZ");
        stormActivity = clamp01(tag.getDouble("stormActivity"));
        synopticLull = clamp(tag.getDouble("synopticLull"),
                SYNOPTIC_LULL_MIN_FACTOR, 1.0);
        timeSeconds = tag.getDouble("timeSeconds");

        ListTag cyc = tag.getList("cyclones", Tag.TAG_COMPOUND);
        if (!cyc.isEmpty()) {
            cyclones.clear();
            for (int i = 0; i < cyc.size(); i++) cyclones.add(CycloneCell.load(cyc.getCompound(i)));
        }
        ListTag cl = tag.getList("clusters", Tag.TAG_COMPOUND);
        if (!cl.isEmpty()) {
            clusters.clear();
            for (int i = 0; i < cl.size(); i++) clusters.add(ConvectiveCluster.load(cl.getCompound(i)));
        }
        // 坏档兜底：列表全空时重新播种，别让整个维度从此没有天气
        if (cyclones.isEmpty()) {
            for (int i = 0; i < DEFAULT_CYCLONE_CELL_COUNT; i++) {
                cyclones.add(CycloneCell.spawn(random, GRID_CELLS));
            }
        }
        if (clusters.isEmpty()) {
            for (int i = 0; i < MAX_CONVECTIVE_CLUSTERS; i++) {
                clusters.add(ConvectiveCluster.spawn(random, GRID_CELLS, cyclones));
            }
        }
    }

    // 气旋单体

    static final class CycloneCell {
        double x, z;                 // 格坐标
        double intensity;            // 0..1
        double outerRadius = 9000;   // blocks
        double coreRadius = 3500;    // blocks
        boolean lowPressure;
        double phase;

        /**
         * 旋向已经和气压绑定：低压=气旋式、高压=反气旋式，由
         * {@link HemisphereConvention#tangential}(dx, dz, lowPressure) 统一给出。
         * 旧实现 {@code spin} 与 {@code lowPressure} 各自独立随机，
         * 导致一半低压是反气旋式（DRV-05）；符号也直接写死成顺时针（SYS-01）。
         */

        /** 径向分量符号：低压辐合 {@code -1}、高压辐散 {@code +1}（DRV-08）。 */
        double radialSign() {
            return HemisphereConvention.radialSign(lowPressure);
        }

        static CycloneCell spawn(Random random, int gridCells) {
            CycloneCell c = new CycloneCell();
            c.x = random.nextDouble() * gridCells;
            c.z = random.nextDouble() * gridCells;
            c.intensity = 0.35 + random.nextDouble() * 0.65;
            c.outerRadius = 7000 + random.nextDouble() * 6000;
            c.coreRadius = 2500 + random.nextDouble() * 2500;
            c.lowPressure = random.nextDouble() < 0.6;
            c.phase = random.nextDouble() * Math.PI * 2;
            return c;
        }

        void advance(double dt, Random random, int gridCells) {
            // 气旋一生走完要 2 个游戏日：相位转满一圈 = 24000 tick × 2 ÷ 20
            phase += dt * (Math.PI * 2.0) / (24000.0 * 2.0 / 20.0);
            intensity = 0.35 + 0.65 * (0.5 + 0.5 * Math.sin(phase));
            x += dt * 0.004 * Math.cos(phase * 0.3);
            z += dt * 0.004 * Math.sin(phase * 0.23);
            if (x < 0) x += gridCells;
            if (x >= gridCells) x -= gridCells;
            if (z < 0) z += gridCells;
            if (z >= gridCells) z -= gridCells;
        }

        CompoundTag save() {
            CompoundTag t = new CompoundTag();
            t.putDouble("x", x);
            t.putDouble("z", z);
            t.putDouble("intensity", intensity);
            t.putDouble("outerRadius", outerRadius);
            t.putDouble("coreRadius", coreRadius);
            t.putBoolean("lowPressure", lowPressure);
            t.putDouble("phase", phase);
            return t;
        }

        static CycloneCell load(CompoundTag t) {
            CycloneCell c = new CycloneCell();
            c.x = t.getDouble("x");
            c.z = t.getDouble("z");
            c.intensity = t.getDouble("intensity");
            c.outerRadius = t.getDouble("outerRadius");
            c.coreRadius = t.getDouble("coreRadius");
            c.lowPressure = t.getBoolean("lowPressure");
            c.phase = t.getDouble("phase");
            return c;
        }
    }

    // 对流团

    static final class ConvectiveCluster {
        double x, z;
        double intensity;
        double radius = 4000;
        double orbitAngle;
        double orbitRadius;
        /**
         * 宿主气旋下标：生成时定下来就不再改。
         * 旧实现每帧用 {@code (int)(Math.abs(orbitAngle) % size)} 现算，{@code orbitAngle} 以
         * 0.01 rad/s 增长，每约 628 秒宿主 +1，对流团瞬移数万格（DRV-02）。
         */
        int hostIndex;

        static ConvectiveCluster spawn(Random random, int gridCells, List<CycloneCell> cyclones) {
            ConvectiveCluster c = new ConvectiveCluster();
            c.hostIndex = cyclones.isEmpty() ? 0 : random.nextInt(cyclones.size());
            CycloneCell host = cyclones.isEmpty() ? null : cyclones.get(c.hostIndex);
            c.orbitRadius = 0.3 + random.nextDouble() * 0.5;
            c.orbitAngle = random.nextDouble() * Math.PI * 2;
            double hx = host == null ? gridCells * 0.5 : host.x;
            double hz = host == null ? gridCells * 0.5 : host.z;
            double or = host == null ? 4000 : host.outerRadius;
            c.x = hx + Math.cos(c.orbitAngle) * or * c.orbitRadius / CELL_SIZE_BLOCKS;
            c.z = hz + Math.sin(c.orbitAngle) * or * c.orbitRadius / CELL_SIZE_BLOCKS;
            c.intensity = 0.3 + random.nextDouble() * 0.5;
            c.radius = 3000 + random.nextDouble() * 3000;
            return c;
        }

        void advance(double dt, Random random, int gridCells, List<CycloneCell> cyclones,
                     double stormActivity) {
            orbitAngle += dt * 0.01;
            if (!cyclones.isEmpty()) {
                CycloneCell host = cyclones.get(Math.floorMod(hostIndex, cyclones.size()));
                x = host.x + Math.cos(orbitAngle) * host.outerRadius * orbitRadius / CELL_SIZE_BLOCKS;
                z = host.z + Math.sin(orbitAngle) * host.outerRadius * orbitRadius / CELL_SIZE_BLOCKS;
            }
            double target = 0.2 + 0.8 * stormActivity;
            intensity += (target - intensity) * Math.min(1.0, dt / 120.0);
            intensity += (random.nextDouble() - 0.5) * dt * 0.01;
            intensity = clamp01(intensity);
            // 宿主 + 轨道偏移可能把位置推到 [0, GRID) 之外（DRV-01 同类问题）。
            // 不折回来，pickConvectiveHost() 会把龙卷放到世界边界外的方块坐标上，
            // 而 L0 是按环面采样它的——两边对不上，足迹永远命不中。
            x = wrapCell(x, gridCells);
            z = wrapCell(z, gridCells);
        }

        /** 把格坐标折回 {@code [0, gridCells)}。 */
        static double wrapCell(double v, int gridCells) {
            double m = v % gridCells;
            return m < 0 ? m + gridCells : m;
        }

        CompoundTag save() {
            CompoundTag t = new CompoundTag();
            t.putDouble("x", x);
            t.putDouble("z", z);
            t.putDouble("intensity", intensity);
            t.putDouble("radius", radius);
            t.putDouble("orbitAngle", orbitAngle);
            t.putDouble("orbitRadius", orbitRadius);
            t.putInt("hostIndex", hostIndex);
            return t;
        }

        static ConvectiveCluster load(CompoundTag t) {
            ConvectiveCluster c = new ConvectiveCluster();
            c.x = t.getDouble("x");
            c.z = t.getDouble("z");
            c.intensity = t.getDouble("intensity");
            c.radius = t.getDouble("radius");
            c.orbitAngle = t.getDouble("orbitAngle");
            c.orbitRadius = t.getDouble("orbitRadius");
            c.hostIndex = t.getInt("hostIndex");
            return c;
        }
    }
}
