package net.atomos.zenith.weather;

import net.atomos.zenith.api.ZenithTerrainSurfaceClass;
import net.atomos.zenith.wind.HemisphereConvention;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * 尘卷风：晴天热出来的地面小涡旋。
 *
 * 条件挺苛刻：沙漠/岩石/沙滩这类烫地表 + 大太阳 + 背景风弱。
 * 长出来之后半径 2–6 格，高 20–60 格，中心切向风 5–12 m/s，
 * 活个 1–5 分钟，满地乱晃。北半球统计上多为气旋式旋转，
 * 这里就随机了，别深究。
 */
public class DustDevilSystem implements WeatherPhenomenon {
    public static final int MAX_DEVILS = 12;
    /**
     * {@code (1 - e^{-x²}) / max(0.35, x)} 在 {@code x ≈ 1.121} 处的峰值。
     * 切向廓线除以它之后峰值正好是 1，{@code tangentialMps} 就真的是最大切向风速。
     */
    public static final double TANGENTIAL_PEAK = 0.6382;

    public static final class DustDevil {
        public double x, z;
        public double radiusBlocks = 4;
        public double heightBlocks = 40;
        public double tangentialMps = 8;
        public double ageSeconds;
        public double lifetimeSeconds = 120;
        public double spin = 1;
        public double wanderX, wanderZ;
        /** 生成处的地表 y（AGL 基准）。旧实现拿 {@code heightBlocks} 当绝对 y 用（WX-DD-02）。 */
        public double groundY = 64.0;

        double envelope() {
            double t = ageSeconds / lifetimeSeconds;
            if (t < 0.2) return t / 0.2;
            if (t > 0.7) return Math.max(0, 1 - (t - 0.7) / 0.3);
            return 1.0;
        }

        /**
         * 切向风廓线：Burgers–Rott 型 {@code v ∝ (1 - exp(-(r/rc)²))·rc/r}，
         * 在 {@code r → 0} 时线性趋于 0、在 {@code r ≈ 1.12·rc} 处达到峰值 1.0。
         *
         * <p>旧实现在 {@code r < radiusBlocks} 只给垂直上升、完全没有切向风，
         * "风眼"是个不转的柱子（WX-DD-01）。
         */
        double tangentialProfile(double r) {
            if (r < 1e-3) return 0.0;
            double x = r / radiusBlocks;
            double core = 1.0 - Math.exp(-x * x);
            // 除以峰值 0.6382（在 x≈1.12 处），使返回值最大为 1
            return core / Math.max(0.35, x) / TANGENTIAL_PEAK;
        }
    }

    private final List<DustDevil> devils = new ArrayList<>();
    private final ZenithRandom random;
    private double spawnAccumulator = 0;

    public DustDevilSystem() {
        this(0L);
    }

    public DustDevilSystem(long seed) {
        this.random = new ZenithRandom(seed);
    }

    @Override
    public void tick(ServerLevel level, double dtSeconds, WeatherContext ctx) {
        // 尘卷风是白天的事：正午最强、午夜归零（太阳时间统一走 FogModel.solarFactor）
        double solar = Math.max(0, FogModel.solarFactor(ctx.timeOfDay01())) * (ctx.raining() ? 0 : 1.0);

        spawnAccumulator += dtSeconds * solar * 0.05;
        while (spawnAccumulator >= 1.0 && devils.size() < MAX_DEVILS) {
            spawnAccumulator -= 1.0;
            ServerPlayer anchor = pickAnchor(level);
            if (anchor == null) break;
            double ax = anchor.getX() + (random.nextDouble() - 0.5) * 400;
            double az = anchor.getZ() + (random.nextDouble() - 0.5) * 400;
            var surface = ctx.terrain().surfaceAt(ax, az);
            boolean hotGround = surface == ZenithTerrainSurfaceClass.DESERT_SAND
                    || surface == ZenithTerrainSurfaceClass.ROCK
                    || surface == ZenithTerrainSurfaceClass.BEACH;
            if (!hotGround && random.nextDouble() < 0.8) continue;
            double groundY = ctx.terrain().heightAt(ax, az);
            // 尘卷风怕大风，背景风太强就长不出来（采样高度用当地地面 AGL，不是绝对 y=80）
            var l1s = ctx.l1().sample(ax, groundY + 10, az);
            if (Math.hypot(l1s.windX(), l1s.windZ()) > 6 && random.nextDouble() < 0.7) continue;

            DustDevil d = new DustDevil();
            d.x = ax; d.z = az;
            d.radiusBlocks = 2 + random.nextDouble() * 4;
            d.heightBlocks = 20 + random.nextDouble() * 40;
            d.tangentialMps = 5 + random.nextDouble() * 7;
            d.lifetimeSeconds = 60 + random.nextDouble() * 240;
            d.spin = random.nextBoolean() ? 1 : -1;
            d.groundY = groundY;
            d.wanderX = (random.nextDouble() - 0.5) * 2;
            d.wanderZ = (random.nextDouble() - 0.5) * 2;
            devils.add(d);
        }

        Iterator<DustDevil> it = devils.iterator();
        while (it.hasNext()) {
            DustDevil d = it.next();
            d.ageSeconds += dtSeconds;
            if (d.ageSeconds >= d.lifetimeSeconds) {
                it.remove();
                continue;
            }
            d.x += d.wanderX * dtSeconds;
            d.z += d.wanderZ * dtSeconds;
            // 地表跟着走：尘卷风会从沙丘晃到洼地，AGL 基准得跟着更新（WX-DD-02）
            d.groundY = ctx.terrain().heightAt(d.x, d.z);
            // 游荡方向缓慢变化
            if (random.nextDouble() < dtSeconds * 0.2) {
                double a = random.nextDouble() * Math.PI * 2;
                d.wanderX = Math.cos(a) * 1.5;
                d.wanderZ = Math.sin(a) * 1.5;
            }
        }
    }

    private ServerPlayer pickAnchor(ServerLevel level) {
        var players = level.players();
        return players.isEmpty() ? null : players.get(random.nextInt(players.size()));
    }

    @Override
    public WindContribution sample(double x, double y, double z, double baseWindX, double baseWindZ) {
        WindContribution acc = WindContribution.NONE;
        for (DustDevil d : devils) {
            double dx = x - d.x, dz = z - d.z;
            double r = Math.sqrt(dx * dx + dz * dz);
            // 高度用 AGL：旧实现拿 heightBlocks(20–60) 当绝对 y，地表 200 的高山上
            // 采样点全都 y > heightBlocks+60 → 永远采样不到（WX-DD-02）
            double agl = y - d.groundY;
            if (r > d.radiusBlocks * 3 || agl < 0 || agl > d.heightBlocks + 60) continue;
            double env = d.envelope();
            // 柱顶平滑收口，不在 heightBlocks+60 处硬切（SYS-05）
            double hDecay = agl < d.heightBlocks ? 1.0
                    : Math.max(0, 1 - (agl - d.heightBlocks) / 60.0);
            hDecay = hDecay * hDecay * (3 - 2 * hDecay); // smoothstep
            double inv = r > 0.5 ? 1.0 / r : 0;
            // 切向风在核心与环区用同一条 Burgers–Rott 廓线，核心也转（WX-DD-01）
            double vt = d.tangentialMps * d.tangentialProfile(r) * env * hDecay;
            // 切向基向量走统一手性（SYS-01）：旧式 (-dz, +dx) 在 MC 坐标里是顺时针。
            // 尘卷风的旋向本身就两个方向都有（观测上没有明显偏向），所以再乘 d.spin。
            double[] tan = HemisphereConvention.tangential(dx, dz, true);
            double tx = tan[0] * inv * vt * d.spin;
            double tz = tan[1] * inv * vt * d.spin;
            // 核心上升：r → 0 处最强（1 - (r/R)²），不是旧实现的线性锥
            double coreN = Math.max(0.0, 1 - (r / d.radiusBlocks) * (r / d.radiusBlocks));
            double updraft = 4.5 * coreN * env * hDecay;
            // 外圈补一点下沉，质量守恒
            double sink = r > d.radiusBlocks
                    ? -0.6 * Math.exp(-Math.pow((r - d.radiusBlocks) / (d.radiusBlocks * 1.2), 2)) * env * hDecay
                    : 0.0;
            double turb = (0.4 * coreN + 0.25 * Math.min(1.0, d.tangentialProfile(r))) * env;
            acc = acc.add(new WindContribution(tx, updraft + sink, tz, turb, 0));
        }
        return acc;
    }

    @Override
    public boolean hasActive() { return !devils.isEmpty(); }

    @Override
    public int activeCount() { return devils.size(); }

    public List<DustDevil> devils() { return List.copyOf(devils); }

    // 存档（SYS-08）

    @Override
    public String saveKey() { return "dust_devils"; }

    @Override
    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putLong("rng", random.state());
        tag.putDouble("spawnAccumulator", spawnAccumulator);
        tag.put("list", WeatherNbt.writeList(devils, d -> {
            CompoundTag c = new CompoundTag();
            c.putDouble("x", d.x);
            c.putDouble("z", d.z);
            c.putDouble("radius", d.radiusBlocks);
            c.putDouble("height", d.heightBlocks);
            c.putDouble("tangential", d.tangentialMps);
            c.putDouble("age", d.ageSeconds);
            c.putDouble("lifetime", d.lifetimeSeconds);
            c.putDouble("spin", d.spin);
            c.putDouble("wanderX", d.wanderX);
            c.putDouble("wanderZ", d.wanderZ);
            c.putDouble("groundY", d.groundY);
            return c;
        }));
        return tag;
    }

    @Override
    public void load(CompoundTag tag) {
        if (tag.contains("rng")) random.setState(tag.getLong("rng"));
        spawnAccumulator = tag.getDouble("spawnAccumulator");
        devils.clear();
        devils.addAll(WeatherNbt.readList(tag, "list", c -> {
            DustDevil d = new DustDevil();
            d.x = c.getDouble("x");
            d.z = c.getDouble("z");
            d.radiusBlocks = Math.max(0.5, c.getDouble("radius"));
            d.heightBlocks = c.getDouble("height");
            d.tangentialMps = c.getDouble("tangential");
            d.ageSeconds = c.getDouble("age");
            d.lifetimeSeconds = Math.max(1.0, c.getDouble("lifetime"));
            d.spin = c.getDouble("spin") >= 0 ? 1 : -1;
            d.wanderX = c.getDouble("wanderX");
            d.wanderZ = c.getDouble("wanderZ");
            d.groundY = c.getDouble("groundY");
            return d;
        }));
        while (devils.size() > MAX_DEVILS) devils.remove(devils.size() - 1);
    }
}
