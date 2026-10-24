package net.atomos.zenith.weather;

import net.atomos.zenith.api.ZenithTerrainSurfaceClass;
import net.atomos.zenith.wind.MesoscaleGrid;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * 热对流：太阳晒热地面，热泡往上冒。
 *
 * 地表越吸热热泡越容易起：沙漠/岩石最快，草地中等，森林和水面最慢。
 * 白天在玩家附近生成热气流柱：半径 8–20 格，上升 2–6 m/s，活 5–15 分钟。
 * 柱子里往上走、稍微往里收；柱子外面围着一圈下沉补偿区。
 * 热泡强度会体现在 L1 不稳定度里（采样点上升气流就是这么来的）。
 */
public class ThermalSystem implements WeatherPhenomenon {
    public static final int MAX_THERMALS = 24;
    public static final double SPAWN_RADIUS_BLOCKS = 320.0;
    /**
     * 下沉补偿峰值系数。由质量守恒定出来的：
     *
     * <pre>
     * 上升通量   F↑ = ∫₀^R w₀(1-(r/R)²)·2πr dr = πR²w₀/2
     * 下沉环     r ∈ [R, 2.2R]，形状 g(t)=t(1-t)³/0.10547（峰值归一化，峰在 t=0.25 → r=1.3R）
     * 下沉通量   F↓ = 2πR²·S·w₀·∫₀¹ g(t)(1+1.2t)·1.2 dt = 2πR²·S·w₀·0.66370
     * 令 F↑ = F↓ ⇒ S = 0.5/(2·1.2·0.66370) = 0.31390
     * </pre>
     *
     * <p>旧实现固定 {@code -0.5 m/s}，与上升量无关：{@code w₀=6} 时下沉通量只有上升的
     * 1/5，热泡整体是个净质量源（WX-TH-02）。
     */
    public static final double SINK_PEAK_PER_UPDRAFT = 0.31390;
    /** 环带外缘 = 2.2R，与 {@code outerTaper} 一致。 */
    public static final double SINK_RING_OUTER = 2.2;
    /** 环带宽度 = 1.2R（R → 2.2R）。 */
    public static final double SINK_RING_WIDTH = 1.2;

    public static double heatingRate(ZenithTerrainSurfaceClass surface) {
        return switch (surface) {
            case DESERT_SAND -> 1.0;
            case ROCK -> 0.9;
            case BEACH -> 0.75;
            case GRASS -> 0.55;
            case URBAN -> 0.7;
            case FOREST -> 0.35;
            case SNOW -> 0.15;
            case OCEAN -> 0.1;
            default -> 0.4;
        };
    }

    public static final class Thermal {
        public double x, z;
        public double radiusBlocks;
        public double updraftMps;
        public double ageSeconds;
        public double lifetimeSeconds;
        /** 柱顶绝对 y（= {@code groundY + heightAgl}）。 */
        public double topY;
        /** 柱高（AGL）。旧实现直接用 120–240，与上升速度无关（WX-TH-04）。 */
        public double heightAgl;
        /**
         * 生成处的地表 y（AGL 基准）。
         * 旧实现在 {@code sample()} 里写死 {@code groundY = 60}：地表 70 的沙漠里
         * 采样点全都 {@code y < 60}，热泡永远采样不到；y<60 的低地则从空中就开始
         * （WX-TH-01 / SYS-03）。
         */
        public double groundY = 64.0;
        /**
         * 生成时的太阳因子。夜间已有热泡要跟着现在的太阳衰减，
         * 旧实现只在生成时看太阳、之后强度定死（WX-TH-05）。
         */
        public double spawnSolar = 1.0;
        /** 当前（最近一次 tick）的太阳因子，由 {@code tick()} 每步刷新。 */
        public double solarFactor = 1.0;

        /**
         * 光照衰减：{@code solarFactor / spawnSolar}，上限 1（白天不会因为
         * "生成时太阳弱"而变强）。{@code spawnSolar} 下限 0.1，避免日出瞬间生成
         * 的热泡因为分母太小而被放大。
         */
        double solarDecay() {
            return Math.max(0.0, Math.min(1.0, solarFactor / Math.max(0.1, spawnSolar)));
        }

        double envelope() {
            double t = ageSeconds / lifetimeSeconds;
            if (t < 0.15) return t / 0.15;
            if (t > 0.75) return Math.max(0, 1 - (t - 0.75) / 0.25);
            return 1.0;
        }

        /**
         * 垂直廓线 {@code sin(π·agl/H)^0.4}：地面为 0、柱中最大、柱顶回 0。
         * 旧实现整柱常数，柱顶直接 {@code continue} 成水平切面，柱底也从满强度开始。
         */
        double verticalProfile(double agl) {
            if (heightAgl <= 1e-6) return 0;
            double u = agl / heightAgl;
            if (u <= 0 || u >= 1) return 0;
            return Math.pow(Math.sin(Math.PI * u), 0.4);
        }

        /**
         * 外缘平滑权重 [0,1]：核心柱 {@code r < R} 为 1，在 {@code R..2.2R} 之间
         * 用 smoothstep 收敛到 0（WX-SQ/SYS-05 同类的硬截断：旧实现在
         * {@code r = radiusBlocks} 处把 4.5 m/s 上升气流直接归零）。
         */
        double outerTaper(double r) {
            double outer = radiusBlocks * SINK_RING_OUTER;
            if (r <= radiusBlocks) return 1.0;
            if (r >= outer) return 0.0;
            double t = (r - radiusBlocks) / (outer - radiusBlocks);
            return 1.0 - t * t * (3.0 - 2.0 * t);
        }

        /**
         * 下沉环形状：{@code g(t)=t(1-t)³/0.10547}，{@code t ∈ [0,1]} 映射到
         * {@code r ∈ [R, 2.2R]}。峰值归一化到 1，位置在 {@code t=0.25} 即
         * {@code r=1.3R}（Allen 2006 的简化热泡模型）。
         */
        static double sinkRingShape(double t) {
            if (t <= 0 || t >= 1) return 0;
            double u = 1 - t;
            return t * u * u * u / 0.10546875;
        }
    }

    private final List<Thermal> thermals = new ArrayList<>();
    private final ZenithRandom random;
    private double spawnAccumulator = 0;

    public ThermalSystem() {
        this(0L);
    }

    public ThermalSystem(long seed) {
        this.random = new ZenithRandom(seed);
    }

    @Override
    public void tick(ServerLevel level, double dtSeconds, WeatherContext ctx) {
        // 热泡只在白天长：正午最强、午夜归零（太阳时间统一走 FogModel.solarFactor）
        double solar = Math.max(0, FogModel.solarFactor(ctx.timeOfDay01()));
        solar = solar * (ctx.raining() ? 0.25 : 1.0)
                * ctx.seasonInfo().solarMultiplier();

        // 生成
        spawnAccumulator += dtSeconds * solar * 0.25;
        while (spawnAccumulator >= 1.0 && thermals.size() < MAX_THERMALS) {
            spawnAccumulator -= 1.0;
            ServerPlayer anchor = pickAnchor(level);
            if (anchor == null) break;
            double ax = anchor.getX() + (random.nextDouble() - 0.5) * 2 * SPAWN_RADIUS_BLOCKS;
            double az = anchor.getZ() + (random.nextDouble() - 0.5) * 2 * SPAWN_RADIUS_BLOCKS;
            ZenithTerrainSurfaceClass surface = ctx.terrain().surfaceAt(ax, az);
            double rate = heatingRate(surface);
            if (random.nextDouble() > rate * 1.4) continue; // 地太凉，热泡起不来
            Thermal t = new Thermal();
            t.x = ax;
            t.z = az;
            t.radiusBlocks = 8 + random.nextDouble() * 12;
            t.updraftMps = (2 + random.nextDouble() * 4) * (0.5 + rate * 0.7) * (0.4 + solar * 0.8);
            t.lifetimeSeconds = 300 + random.nextDouble() * 600;
            t.groundY = ctx.terrain().heightAt(ax, az);
            // 混合层顶：柱高跟随上升速度（≈ 25 格每 m/s），夹在 40–200 格，
            // 再与 L1 的不稳定层顶对齐（WX-TH-04）
            double ablHeight = ctx.l1().sample(ax, t.groundY + 2, az).ablStability() < -0.15
                    ? MesoscaleGrid.ABL_UNSTABLE_HEIGHT_BLOCKS
                    : MesoscaleGrid.ABL_STABLE_HEIGHT_BLOCKS;
            t.heightAgl = Math.min(Math.max(40, t.updraftMps * 25), Math.min(200, ablHeight));
            t.topY = t.groundY + t.heightAgl;
            t.spawnSolar = Math.max(0.1, solar);
            thermals.add(t);
        }

        // 推进与消亡。热泡自己不走，是被低层风吹着飘的
        Iterator<Thermal> it = thermals.iterator();
        while (it.hasNext()) {
            Thermal t = it.next();
            t.ageSeconds += dtSeconds;
            if (t.ageSeconds >= t.lifetimeSeconds) {
                it.remove();
                continue;
            }
            var s = ctx.l1().sample(t.x, t.groundY + 40, t.z);
            t.x += s.windX() * dtSeconds * 0.7;
            t.z += s.windZ() * dtSeconds * 0.7;
            // 地表跟着漂移而变（WX-TH-01 的 AGL 基准不能停在生成点）
            t.groundY = ctx.terrain().heightAt(t.x, t.z);
            t.topY = t.groundY + t.heightAgl;
            // 夜间/降雨时已有的热泡跟着减弱（WX-TH-05）
            t.solarFactor = Math.max(0, FogModel.solarFactor(ctx.timeOfDay01()))
                    * (ctx.raining() ? 0.25 : 1.0) * ctx.seasonInfo().solarMultiplier();
        }
    }

    private ServerPlayer pickAnchor(ServerLevel level) {
        var players = level.players();
        if (players.isEmpty()) return null;
        return players.get(random.nextInt(players.size()));
    }

    @Override
    public WindContribution sample(double x, double y, double z, double baseWindX, double baseWindZ) {
        WindContribution acc = WindContribution.NONE;
        for (Thermal t : thermals) {
            double dx = x - t.x, dz = z - t.z;
            double r = Math.sqrt(dx * dx + dz * dz);
            // AGL：用生成处记录的真实地表 y，不再写死 60（WX-TH-01）
            double agl = y - t.groundY;
            if (agl <= 0 || agl >= t.heightAgl) continue;
            // 垂直廓线：地面 0 → 柱中最大 → 柱顶 0，没有水平切面（WX-TH-02 后半）
            double env = t.envelope() * t.verticalProfile(agl) * t.solarDecay();
            if (env <= 0) continue;
            double taper = t.outerTaper(r);
            if (taper <= 0) continue;
            if (r < t.radiusBlocks) {
                double core = 1 - (r / t.radiusBlocks) * (r / t.radiusBlocks);
                double w = t.updraftMps * core * env;
                double conv = 0.4 * core * env; // 柱内轻微向里收
                double inv = r > 0.5 ? 1.0 / r : 0;
                acc = acc.add(new WindContribution(
                        -dx * inv * conv, w, -dz * inv * conv, 0.15 * core * env, 0));
            } else {
                // 下沉补偿环：按质量通量配平，不再是固定 −0.5 m/s（WX-TH-02）。
                // 形状 g(t)=t(1-t)³，峰在 r=1.3R；强度 = SINK_PEAK_PER_UPDRAFT · w₀
                double t01 = (r - t.radiusBlocks) / (t.radiusBlocks * SINK_RING_WIDTH);
                if (t01 >= 1) continue;
                double sink = -SINK_PEAK_PER_UPDRAFT * t.updraftMps
                        * Thermal.sinkRingShape(t01) * env * taper;
                acc = acc.add(new WindContribution(0, sink, 0, 0.05 * env * taper, 0));
            }
        }
        return acc;
    }

    @Override
    public boolean hasActive() { return !thermals.isEmpty(); }

    @Override
    public int activeCount() { return thermals.size(); }

    public List<Thermal> thermals() { return List.copyOf(thermals); }

    /** 供调试/指令：手动生成一个热泡。 */
    public void spawnDebug(double x, double z, double groundY) {
        Thermal t = new Thermal();
        t.x = x; t.z = z;
        t.radiusBlocks = 14;
        t.updraftMps = 5;
        t.lifetimeSeconds = 600;
        t.groundY = groundY;
        t.heightAgl = 180;
        t.topY = groundY + 180;
        t.spawnSolar = 1.0;
        t.solarFactor = 1.0;
        thermals.add(t);
    }

    // 存档（SYS-08）

    @Override
    public String saveKey() { return "thermals"; }

    @Override
    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putLong("rng", random.state());
        tag.putDouble("spawnAccumulator", spawnAccumulator);
        tag.put("list", WeatherNbt.writeList(thermals, t -> {
            CompoundTag c = new CompoundTag();
            c.putDouble("x", t.x);
            c.putDouble("z", t.z);
            c.putDouble("radius", t.radiusBlocks);
            c.putDouble("updraft", t.updraftMps);
            c.putDouble("age", t.ageSeconds);
            c.putDouble("lifetime", t.lifetimeSeconds);
            c.putDouble("heightAgl", t.heightAgl);
            c.putDouble("groundY", t.groundY);
            c.putDouble("spawnSolar", t.spawnSolar);
            c.putDouble("solar", t.solarFactor);
            return c;
        }));
        return tag;
    }

    @Override
    public void load(CompoundTag tag) {
        if (tag.contains("rng")) random.setState(tag.getLong("rng"));
        spawnAccumulator = tag.getDouble("spawnAccumulator");
        thermals.clear();
        thermals.addAll(WeatherNbt.readList(tag, "list", c -> {
            Thermal t = new Thermal();
            t.x = c.getDouble("x");
            t.z = c.getDouble("z");
            t.radiusBlocks = c.getDouble("radius");
            t.updraftMps = c.getDouble("updraft");
            t.ageSeconds = c.getDouble("age");
            t.lifetimeSeconds = Math.max(1.0, c.getDouble("lifetime"));
            t.heightAgl = c.getDouble("heightAgl");
            t.groundY = c.getDouble("groundY");
            t.spawnSolar = Math.max(0.1, c.getDouble("spawnSolar"));
            t.solarFactor = c.getDouble("solar");
            t.topY = t.groundY + t.heightAgl;
            return t;
        }));
        while (thermals.size() > MAX_THERMALS) thermals.remove(thermals.size() - 1);
    }
}
