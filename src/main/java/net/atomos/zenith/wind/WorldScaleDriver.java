package net.atomos.zenith.wind;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * 行星尺度天气驱动（服务端权威）。
 *
 * <p>移植自 Aerodynamics4MC-Core 的 {@code WorldScaleDriver}（MIT），包名已更改。
 * 每格风 = 基流 + 行星波 + 气旋涡旋/辐散 + 对流团辐合 + 龙卷贡献，钳制 ±12 m/s。</p>
 *
 * <p>网格：384×384 pressure-domain cells，每格 256 blocks（与 L0 对齐）。</p>
 */
public class WorldScaleDriver {
    // ---- 可调常量（对应原版 §2.3） ----
    public static final double SOLVER_STEP_SECONDS = 0.05;
    public static final double BASE_FLOW_RELAX_PER_SECOND = 1.0 / 900.0;
    public static final double STORM_RELAX_PER_SECOND = 1.0 / 600.0;
    public static final double MAX_DRIVER_WIND_MPS = 12.0;
    public static final double SYNOPTIC_LULL_MIN_FACTOR = 0.18;
    public static final double CYCLONE_CELL_MAX_SWIRL_MPS = 10.0;
    public static final double CYCLONE_CELL_MAX_PRESSURE_ANOMALY_PA = 1350.0;
    public static final double CONVECTIVE_CLUSTER_MAX_CONVERGENCE_MPS = 4.0;
    public static final double CONVECTIVE_CLUSTER_THERMAL_LOW_PA = 240.0;
    public static final double TORNADO_MIN_STORM_ACTIVITY = 0.35;
    public static final double TORNADO_MIN_SUPPORT = 0.75;
    public static final double TORNADO_MIN_LIFETIME_SECONDS = 45.0;
    public static final double TORNADO_MAX_LIFETIME_SECONDS = 140.0;
    public static final int DEFAULT_CYCLONE_CELL_COUNT = 6;
    public static final int MAX_CONVECTIVE_CLUSTERS = 6;
    public static final int MAX_ACTIVE_TORNADO_VORTICES = 2;
    public static final double DRIVER_SPATIAL_SCALE_X = 0.11;
    public static final double DRIVER_SPATIAL_SCALE_Z = 0.09;
    public static final int GRID_CELLS = 384;
    public static final double CELL_SIZE_BLOCKS = 256.0;

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

    private final Random random;
    private final double coriolisSign;

    private double baseFlowX = 3.0;
    private double baseFlowZ = 0.5;
    private double forcedBaseFlowX = 3.0;
    private double forcedBaseFlowZ = 0.5;
    private double stormActivity = 0.1;
    private double synopticLull = 1.0;
    private double timeSeconds = 0;

    private final List<CycloneCell> cyclones = new ArrayList<>();
    private final List<ConvectiveCluster> clusters = new ArrayList<>();
    private final List<TornadoVortex> tornadoes = new ArrayList<>();

    public WorldScaleDriver(long seed) {
        this.random = new Random(seed ^ 0x9E3779B97F4A7C15L);
        // 北半球为正；南半球（z 大范围）取反，简单按纬度带模拟
        this.coriolisSign = 1.0;
        for (int i = 0; i < DEFAULT_CYCLONE_CELL_COUNT; i++) {
            cyclones.add(CycloneCell.spawn(random, GRID_CELLS));
        }
        for (int i = 0; i < MAX_CONVECTIVE_CLUSTERS; i++) {
            clusters.add(ConvectiveCluster.spawn(random, GRID_CELLS, cyclones));
        }
    }

    // ---------------- advance ----------------

    public void advance(double dtSeconds, double timeOfDay01, boolean raining, boolean thundering,
                        Feedback feedback) {
        timeSeconds += dtSeconds;

        // 风暴活动度：松弛 + L1 反馈调制
        double target = 0.12
                + 0.55 * clamp01(feedback.maxInstabilityProxy())
                + 0.25 * clamp01(feedback.meanLowLevelShear() / 0.02)
                + 0.35 * clamp01(feedback.maxPositiveMoistureConvergence() / 0.01);
        if (thundering) target = Math.max(target, 0.8);
        else if (raining) target = Math.max(target, 0.45);
        // 夜间对流减弱
        double diurnal = 0.65 + 0.35 * Math.sin(timeOfDay01 * Math.PI * 2.0 - Math.PI / 2.0);
        target *= 0.7 + 0.3 * diurnal;
        stormActivity += (clamp01(target) - stormActivity)
                * Math.min(1.0, dtSeconds * STORM_RELAX_PER_SECOND);

        // 基流目标缓慢漂移
        if (random.nextDouble() < dtSeconds / 300.0) {
            double ang = random.nextDouble() * Math.PI * 2.0;
            double spd = 1.5 + random.nextDouble() * 4.5;
            forcedBaseFlowX = Math.cos(ang) * spd;
            forcedBaseFlowZ = Math.sin(ang) * spd;
        }
        double k = Math.min(1.0, dtSeconds * BASE_FLOW_RELAX_PER_SECOND);
        baseFlowX += (forcedBaseFlowX - baseFlowX) * k;
        baseFlowZ += (forcedBaseFlowZ - baseFlowZ) * k;

        // 天气间歇因子缓慢变化
        synopticLull += (random.nextDouble() - 0.5) * dtSeconds * 0.02;
        synopticLull = clamp(synopticLull, SYNOPTIC_LULL_MIN_FACTOR, 1.0);

        for (CycloneCell c : cyclones) c.advance(dtSeconds, random, GRID_CELLS);
        for (ConvectiveCluster c : clusters) c.advance(dtSeconds, random, GRID_CELLS, cyclones, stormActivity);
        tornadoes.removeIf(t -> !t.advance(dtSeconds));

        // 龙卷生成
        if (tornadoes.size() < MAX_ACTIVE_TORNADO_VORTICES
                && stormActivity > TORNADO_MIN_STORM_ACTIVITY
                && random.nextDouble() < dtSeconds * 0.02 * stormActivity) {
            double support = 0.5 * stormActivity
                    + 0.5 * clamp01(feedback.meanLowLevelShear() / 0.015);
            if (support >= TORNADO_MIN_SUPPORT && !clusters.isEmpty()) {
                ConvectiveCluster host = clusters.get(random.nextInt(clusters.size()));
                tornadoes.add(TornadoVortex.spawn(random, host, support));
            }
        }
    }

    // ---------------- sample ----------------

    public Sample sample(int cellX, int cellZ) {
        double phi = cellX * DRIVER_SPATIAL_SCALE_X + timeSeconds * 0.002;
        double psi = cellZ * DRIVER_SPATIAL_SCALE_Z - timeSeconds * 0.0013;
        double waveScale = synopticLull;

        double u = baseFlowX + waveScale * (0.90 * Math.sin(phi) + 0.35 * Math.sin(psi));
        double w = baseFlowZ + waveScale * (0.90 * Math.sin(psi + 1.7) + 0.35 * Math.sin(phi - 0.6));
        double pressure = 0;
        double tempBias = 0;
        double humidity = 0.5;
        double convHeat = 0, convMoist = 0, convInX = 0, convInZ = 0, convEnv = 0;
        double torX = 0, torZ = 0, torHeat = 0, torMoist = 0, torUp = 0;

        for (CycloneCell c : cyclones) {
            double dx = (cellX - c.x) * CELL_SIZE_BLOCKS;
            double dz = (cellZ - c.z) * CELL_SIZE_BLOCKS;
            double r = Math.sqrt(dx * dx + dz * dz);
            double outerNorm = r / c.outerRadius;
            double coreNorm = r / c.coreRadius;
            double gOuter = Math.exp(-outerNorm * outerNorm);
            double gCore = Math.exp(-coreNorm * coreNorm);
            double swirl = CYCLONE_CELL_MAX_SWIRL_MPS * c.intensity
                    * (0.55 * gOuter + 1.40 * gCore) * coriolisSign * c.spin;
            double radial = 3.0 * c.intensity * (0.70 * gOuter + 1.20 * gCore) * c.inflowSign;
            double inv = r > 1e-3 ? 1.0 / r : 0;
            // 切向 (垂直于径向) + 径向
            u += -dz * inv * swirl + dx * inv * radial;
            w += dx * inv * swirl + dz * inv * radial;
            double env = Math.max(gOuter, gCore);
            pressure += c.lowPressure
                    ? -CYCLONE_CELL_MAX_PRESSURE_ANOMALY_PA * c.intensity * env
                    : CYCLONE_CELL_MAX_PRESSURE_ANOMALY_PA * c.intensity * env;
            tempBias += (c.lowPressure ? 1.2 : -1.0) * c.intensity * env;
            humidity += (c.lowPressure ? 0.25 : -0.15) * c.intensity * env;
        }

        for (ConvectiveCluster c : clusters) {
            double dx = (cellX - c.x) * CELL_SIZE_BLOCKS;
            double dz = (cellZ - c.z) * CELL_SIZE_BLOCKS;
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

        for (TornadoVortex t : tornadoes) {
            double dx = (cellX - t.x) * CELL_SIZE_BLOCKS;
            double dz = (cellZ - t.z) * CELL_SIZE_BLOCKS;
            double r = Math.sqrt(dx * dx + dz * dz);
            if (r > t.influenceRadius) continue;
            double coreG = Math.exp(-(r / t.coreRadius) * (r / t.coreRadius));
            double inv = r > 1e-3 ? 1.0 / r : 0;
            double tangential = t.maxWind * coreG;
            torX += -dz * inv * tangential * t.spin;
            torZ += dx * inv * tangential * t.spin;
            torHeat += 3.0 * coreG;
            torMoist += 0.25 * coreG;
            torUp = Math.max(torUp, coreG);
        }

        u = clamp(u, -MAX_DRIVER_WIND_MPS, MAX_DRIVER_WIND_MPS);
        w = clamp(w, -MAX_DRIVER_WIND_MPS, MAX_DRIVER_WIND_MPS);
        humidity = clamp01(humidity);

        return new Sample(u, w, pressure, tempBias, humidity, stormActivity,
                convHeat, convMoist, convInX, convInZ, convEnv,
                torX, torZ, torHeat, torMoist, torUp);
    }

    public double stormActivity() { return stormActivity; }
    public int activeTornadoCount() { return tornadoes.size(); }
    public List<TornadoVortex> tornadoes() { return List.copyOf(tornadoes); }

    private static double clamp(double v, double lo, double hi) {
        return v < lo ? lo : Math.min(v, hi);
    }

    private static double clamp01(double v) { return clamp(v, 0, 1); }

    // ---------------- 气旋单体 ----------------

    static final class CycloneCell {
        double x, z;                 // 格坐标
        double intensity;            // 0..1
        double outerRadius = 9000;   // blocks
        double coreRadius = 3500;    // blocks
        double spin;                 // ±1
        boolean lowPressure;
        double inflowSign;           // 低压辐合(+1)/高压辐散(-1)
        double phase;

        static CycloneCell spawn(Random random, int gridCells) {
            CycloneCell c = new CycloneCell();
            c.x = random.nextDouble() * gridCells;
            c.z = random.nextDouble() * gridCells;
            c.intensity = 0.35 + random.nextDouble() * 0.65;
            c.outerRadius = 7000 + random.nextDouble() * 6000;
            c.coreRadius = 2500 + random.nextDouble() * 2500;
            c.spin = random.nextBoolean() ? 1 : -1;
            c.lowPressure = random.nextDouble() < 0.6;
            c.inflowSign = c.lowPressure ? 1 : -1;
            c.phase = random.nextDouble() * Math.PI * 2;
            return c;
        }

        void advance(double dt, Random random, int gridCells) {
            // 生命史相位：周期约 24000*2/20 秒（原版公式）
            phase += dt * (Math.PI * 2.0) / (24000.0 * 2.0 / 20.0);
            intensity = 0.35 + 0.65 * (0.5 + 0.5 * Math.sin(phase));
            // 缓慢漂移
            x += dt * 0.004 * Math.cos(phase * 0.3);
            z += dt * 0.004 * Math.sin(phase * 0.23);
            if (x < 0) x += gridCells;
            if (x >= gridCells) x -= gridCells;
            if (z < 0) z += gridCells;
            if (z >= gridCells) z -= gridCells;
        }
    }

    // ---------------- 对流团 ----------------

    static final class ConvectiveCluster {
        double x, z;
        double intensity;
        double radius = 4000;
        double orbitAngle;
        double orbitRadius;

        static ConvectiveCluster spawn(Random random, int gridCells, List<CycloneCell> cyclones) {
            ConvectiveCluster c = new ConvectiveCluster();
            CycloneCell host = cyclones.get(random.nextInt(cyclones.size()));
            c.orbitRadius = 0.3 + random.nextDouble() * 0.5;
            c.orbitAngle = random.nextDouble() * Math.PI * 2;
            c.x = host.x + Math.cos(c.orbitAngle) * host.outerRadius * c.orbitRadius / CELL_SIZE_BLOCKS;
            c.z = host.z + Math.sin(c.orbitAngle) * host.outerRadius * c.orbitRadius / CELL_SIZE_BLOCKS;
            c.intensity = 0.3 + random.nextDouble() * 0.5;
            c.radius = 3000 + random.nextDouble() * 3000;
            return c;
        }

        void advance(double dt, Random random, int gridCells, List<CycloneCell> cyclones,
                     double stormActivity) {
            orbitAngle += dt * 0.01;
            if (!cyclones.isEmpty()) {
                CycloneCell host = cyclones.get((int) (Math.abs(orbitAngle) % cyclones.size()));
                x = host.x + Math.cos(orbitAngle) * host.outerRadius * orbitRadius / CELL_SIZE_BLOCKS;
                z = host.z + Math.sin(orbitAngle) * host.outerRadius * orbitRadius / CELL_SIZE_BLOCKS;
            }
            double target = 0.2 + 0.8 * stormActivity;
            intensity += (target - intensity) * Math.min(1.0, dt / 120.0);
            intensity += (random.nextDouble() - 0.5) * dt * 0.01;
            intensity = clamp01(intensity);
        }
    }

    // ---------------- 龙卷 ----------------

    public static final class TornadoVortex {
        public double x, z;             // 格坐标（double 精度）
        public double maxWind;          // m/s 18..42
        public double coreRadius;       // blocks 10..24
        public double influenceRadius;  // blocks 48..128
        public double spin;
        private double ageSeconds;
        private double lifetimeSeconds;

        static TornadoVortex spawn(Random random, ConvectiveCluster host, double support) {
            TornadoVortex t = new TornadoVortex();
            t.x = host.x + (random.nextDouble() - 0.5) * 4;
            t.z = host.z + (random.nextDouble() - 0.5) * 4;
            t.maxWind = 18 + random.nextDouble() * 24;
            t.coreRadius = 10 + random.nextDouble() * 14;
            t.influenceRadius = 48 + random.nextDouble() * 80;
            t.spin = random.nextBoolean() ? 1 : -1;
            t.lifetimeSeconds = TORNADO_MIN_LIFETIME_SECONDS
                    + random.nextDouble() * (TORNADO_MAX_LIFETIME_SECONDS - TORNADO_MIN_LIFETIME_SECONDS);
            t.ageSeconds = 0;
            return t;
        }

        /** @return false 表示生命结束 */
        boolean advance(double dt) {
            ageSeconds += dt;
            // 随宿主对流团缓慢移动
            x += dt * 0.01;
            z += dt * 0.006;
            // 强度包络：上升-维持-衰减
            double t = ageSeconds / lifetimeSeconds;
            return t < 1.0;
        }

        public double intensityEnvelope() {
            double t = clamp01(ageSeconds / lifetimeSeconds);
            if (t < 0.2) return t / 0.2;
            if (t > 0.7) return 1.0 - (t - 0.7) / 0.3;
            return 1.0;
        }

        public double ageSeconds() { return ageSeconds; }
        public double lifetimeSeconds() { return lifetimeSeconds; }
    }
}
