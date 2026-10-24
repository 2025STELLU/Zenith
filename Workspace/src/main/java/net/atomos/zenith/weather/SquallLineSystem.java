package net.atomos.zenith.weather;

import net.atomos.zenith.api.ZenithWeatherEvent;
import net.atomos.zenith.api.ZenithWeatherEvents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * 飑线：一条移动的线状强对流，附带下击暴流。
 *
 * <p>一个维度最多 2 条，长度 200–600 格。线上是强降水核心带 + 频繁闪电；
 * 线前顶着阵风锋，径向外流 15–20 m/s，比单体雷暴更强更宽；
 * 线上还随机嵌着下击暴流核（下沉 −10 m/s、半径 20–50 格），砸到地面再向外流。
 * 生成条件：风暴活动度高 + 低层风切变强。
 *
 * <p><b>几何</b>：所有径向/沿法向的贡献都乘一个 smoothstep 收口，不再用
 * {@code if (ahead < 160)} 这种硬边界（SYS-05）：玩家穿过边界时风速连续变化。
 */
public class SquallLineSystem implements WeatherPhenomenon {
    public static final int MAX_LINES = 2;
    /** 阵风锋影响的前向距离（格）。旧实现 160 处硬切。 */
    public static final double GUST_FRONT_RANGE = 200.0;
    /** 冷池出流（线后）的影响距离（格）。 */
    public static final double COLD_POOL_REAR_RANGE = 120.0;
    /** 阵风的高度衰减尺度（格 AGL）：地面最强、300 格 AGL 后基本消失（WX-SQ-01）。 */
    public static final double GUST_HEIGHT_SCALE = 300.0;
    /** 下击暴流上限/寿命范围（WX-SQ-03）。 */
    public static final int MAX_MICROBURSTS_PER_LINE = 6;
    public static final double MICROBURST_MIN_LIFETIME = 60;
    public static final double MICROBURST_MAX_LIFETIME = 180;
    public static final double MICROBURST_MIN_RADIUS = 20;
    public static final double MICROBURST_MAX_RADIUS = 50;

    public static final class Microburst {
        public double x, z;
        public double ageSeconds;
        public double lifetimeSeconds = 120;
        public double radiusBlocks = 30;
        /** 生成处的地表 y（AGL 基准）。 */
        public double groundY = 64;
        /** 由母体飑线的 stormActivity 派生，0.5–1.0。 */
        public double strength01 = 1.0;

        double envelope() {
            double t = ageSeconds / Math.max(1e-3, lifetimeSeconds);
            if (t < 0.25) return t / 0.25;           // 下沉核建立
            if (t > 0.6) return Math.max(0, 1 - (t - 0.6) / 0.4); // 触地后外流衰减
            return 1.0;
        }

        /** 外流（触地后）的径向收口：r 从 R 到 2.5R 平滑归零。 */
        double outflowTaper(double r) {
            double inner = radiusBlocks;
            double outer = radiusBlocks * 2.5;
            if (r <= inner) return 1.0;
            if (r >= outer) return 0.0;
            double t = (r - inner) / (outer - inner);
            return 1.0 - t * t * (3.0 - 2.0 * t);
        }
    }

    public static final class SquallLine {
        public double x1, z1, x2, z2;
        public double moveX, moveZ;
        public double ageSeconds;
        public double lifetimeSeconds = 10800;
        public double intensity01 = 1.0;
        public final List<Microburst> microbursts = new ArrayList<>();
        public double lightningTimer;
        /** 母体所在的地表 y 均值（AGL 基准）。 */
        public double groundY = 64;

        public double distanceTo(double x, double z) {
            double dx = x2 - x1, dz = z2 - z1;
            double len2 = dx * dx + dz * dz;
            if (len2 < 1) return Math.hypot(x - x1, z - z1);
            double t = ((x - x1) * dx + (z - z1) * dz) / len2;
            t = Math.max(0, Math.min(1, t));
            return Math.hypot(x - (x1 + dx * t), z - (z1 + dz * t));
        }

        /** 线法向量，保证指向移动/阵风锋那一侧。 */
        public double[] frontNormal() {
            double dx = x2 - x1, dz = z2 - z1;
            double len = Math.hypot(dx, dz);
            if (len < 1) return new double[]{1, 0};
            double nx = -dz / len, nz = dx / len;
            if (nx * moveX + nz * moveZ < 0) {
                nx = -nx;
                nz = -nz;
            }
            return new double[]{nx, nz};
        }
    }

    private final List<SquallLine> lines = new ArrayList<>();
    private final ZenithRandom random;
    private double spawnAccumulator = 0;

    public SquallLineSystem() {
        this(0L);
    }

    public SquallLineSystem(long seed) {
        this.random = new ZenithRandom(seed);
    }

    @Override
    public void tick(ServerLevel level, double dtSeconds, WeatherContext ctx) {
        double stormAct = ctx.driver().stormActivity();

        spawnAccumulator += dtSeconds * Math.max(0, stormAct - 0.55) * 0.006;
        while (spawnAccumulator >= 1.0 && lines.size() < MAX_LINES) {
            spawnAccumulator -= 1.0;
            ServerPlayer anchor = pickAnchor(level);
            if (anchor == null) break;
            double ang = random.nextDouble() * Math.PI * 2;
            double dist = 600 + random.nextDouble() * 600;
            double cx = anchor.getX() + Math.cos(ang) * dist;
            double cz = anchor.getZ() + Math.sin(ang) * dist;
            double groundY = ctx.terrain().heightAt(cx, cz);
            var l1s = ctx.l1().sample(cx, groundY + 80, cz);
            if (stormAct < 0.6 && random.nextDouble() < 0.5) continue;

            SquallLine l = new SquallLine();
            // 飑线走向 ⟂ 引导风（旧公式和 FrontSystem 同一个 atan2 轴混淆 bug）。
            // frontNormal() 会自己翻到移动/阵风锋那一侧，所以线取哪一侧无所谓。
            double wx = l1s.windX(), wz = l1s.windZ();
            double w = Math.hypot(wx, wz);
            if (w < 0.3) { wx = 1; wz = 0; w = 1; }
            double lineDirX = -wz / w, lineDirZ = wx / w;  // 线方向 ⟂ 风
            double halfLen = 100 + random.nextDouble() * 200;
            l.x1 = cx - lineDirX * halfLen;
            l.z1 = cz - lineDirZ * halfLen;
            l.x2 = cx + lineDirX * halfLen;
            l.z2 = cz + lineDirZ * halfLen;
            l.moveX = l1s.windX() * 0.7;
            l.moveZ = l1s.windZ() * 0.7;
            l.intensity01 = 0.6 + stormAct * 0.4;
            l.groundY = groundY;
            lines.add(l);
            // API-05：飑线生成事件（强度取刚算好的 intensity01）
            ZenithWeatherEvents.fire(ZenithWeatherEvent.of(
                    ZenithWeatherEvent.Type.SQUALL_LINE_FORMED, ctx.worldRef(),
                    (l.x1 + l.x2) * 0.5, (l.z1 + l.z2) * 0.5, l.intensity01, ""));
        }

        Iterator<SquallLine> it = lines.iterator();
        while (it.hasNext()) {
            SquallLine l = it.next();
            l.ageSeconds += dtSeconds;
            if (l.ageSeconds >= l.lifetimeSeconds) {
                it.remove();
                continue;
            }
            l.x1 += l.moveX * dtSeconds; l.z1 += l.moveZ * dtSeconds;
            l.x2 += l.moveX * dtSeconds; l.z2 += l.moveZ * dtSeconds;
            // 母体的 AGL 基准跟着走（山地上不能还用生成时的地面）
            l.groundY = ctx.terrain().heightAt((l.x1 + l.x2) * 0.5, (l.z1 + l.z2) * 0.5);

            // 微下击暴流：成熟期（活过 60 s）按 stormActivity 概率生成（WX-SQ-03）。
            // 期望约每 50 s 一个（stormActivity=1），同时最多 MAX_MICROBURSTS_PER_LINE 个。
            if (l.ageSeconds > 60
                    && l.microbursts.size() < MAX_MICROBURSTS_PER_LINE
                    && random.nextDouble() < dtSeconds * 0.02 * Math.max(0.3, stormAct)) {
                double t = random.nextDouble();
                double mbX = l.x1 + (l.x2 - l.x1) * t + (random.nextDouble() - 0.5) * 60;
                double mbZ = l.z1 + (l.z2 - l.z1) * t + (random.nextDouble() - 0.5) * 60;
                Microburst mb = new Microburst();
                mb.x = mbX;
                mb.z = mbZ;
                mb.radiusBlocks = MICROBURST_MIN_RADIUS
                        + random.nextDouble() * (MICROBURST_MAX_RADIUS - MICROBURST_MIN_RADIUS);
                mb.lifetimeSeconds = MICROBURST_MIN_LIFETIME
                        + random.nextDouble() * (MICROBURST_MAX_LIFETIME - MICROBURST_MIN_LIFETIME);
                mb.strength01 = 0.5 + 0.5 * Math.min(1.0, stormAct);
                mb.groundY = ctx.terrain().heightAt(mbX, mbZ);
                l.microbursts.add(mb);
            }
            l.microbursts.removeIf(mb -> {
                mb.ageSeconds += dtSeconds;
                return mb.ageSeconds >= mb.lifetimeSeconds;
            });

            l.lightningTimer -= dtSeconds;
            if (l.lightningTimer <= 0) {
                l.lightningTimer = 2 + random.nextDouble() * 6;
                strikeLightning(level, l);
            }
        }
    }

    /** 闪电沿线的方向分布（WX-SQ-02 确认这点本来就是对的），门控交给 {@link LightningGate}。 */
    private void strikeLightning(ServerLevel level, SquallLine l) {
        double t = random.nextDouble();
        int bx = (int) (l.x1 + (l.x2 - l.x1) * t + (random.nextDouble() - 0.5) * 80);
        int bz = (int) (l.z1 + (l.z2 - l.z1) * t + (random.nextDouble() - 0.5) * 80);
        LightningGate.strike(level, bx, bz);
    }

    private ServerPlayer pickAnchor(ServerLevel level) {
        var players = level.players();
        return players.isEmpty() ? null : players.get(random.nextInt(players.size()));
    }

    @Override
    public WindContribution sample(double x, double y, double z, double baseWindX, double baseWindZ) {
        WindContribution acc = WindContribution.NONE;
        for (SquallLine l : lines) {
            double d = l.distanceTo(x, z);
            double[] n = l.frontNormal();
            double t = projectionT(l, x, z);
            double px = l.x1 + (l.x2 - l.x1) * t, pz = l.z1 + (l.z2 - l.z1) * t;
            double ahead = (x - px) * n[0] + (z - pz) * n[1]; // >0 表示在线前侧
            double e = l.intensity01;

            // AGL：冷池是地面驱动现象，随高度衰减（WX-SQ-01 的核心）
            double agl = Math.max(0.0, y - l.groundY);
            double hDecay = Math.exp(-agl / GUST_HEIGHT_SCALE);

            // 对流线核心：强降水 + 大湍流。距离也用 smoothstep 收口，不在 90 处硬切。
            double coreTaper = smoothstepFalloff(d, 90.0);
            if (coreTaper > 0) {
                double core = Math.exp(-(d * d) / (60 * 60)) * coreTaper;
                acc = acc.add(new WindContribution(0, -2.0 * core * e * hDecay, 0,
                        0.5 * core * e, 0.95 * e * coreTaper));
            }

            if (ahead > 0) {
                // 阵风锋（线前）：smoothstep 在 GUST_FRONT_RANGE 处收敛到 0
                double frontTaper = smoothstepFalloff(ahead, GUST_FRONT_RANGE);
                if (frontTaper > 0) {
                    double gust = 18.0 * Math.exp(-ahead / 90.0) * e * frontTaper
                            * hDecay;
                    // 真实飑线前缘是强上升，w ≈ 0.3–0.5·U（旧实现 0.09·gust）
                    double wy = 0.35 * gust * Math.exp(-agl / 500.0);
                    acc = acc.add(new WindContribution(
                            n[0] * gust, wy, n[1] * gust,
                            0.4 * Math.exp(-ahead / 90.0) * e * frontTaper,
                            0.3 * e * frontTaper));
                }
            } else {
                // 冷池出流（线后）：旧实现这里是完全静风，缺了飑线最典型的"线后强风"
                double rear = -ahead; // >0
                double rearTaper = smoothstepFalloff(rear, COLD_POOL_REAR_RANGE);
                if (rearTaper > 0) {
                    double gust = 10.0 * Math.exp(-rear / 60.0) * e * rearTaper * hDecay;
                    acc = acc.add(new WindContribution(
                            n[0] * gust, -0.1 * gust, n[1] * gust,
                            0.25 * e * rearTaper, 0.45 * e * rearTaper));
                }
            }

            // 下击暴流：下沉核 + 触地外流，两者都按 AGL 与半径收口
            for (Microburst mb : l.microbursts) {
                double mdx = x - mb.x, mdz = z - mb.z;
                double mr = Math.sqrt(mdx * mdx + mdz * mdz);
                double menv = mb.envelope() * e * mb.strength01;
                if (menv <= 0) continue;
                double magl = Math.max(0.0, y - mb.groundY);
                if (mr < mb.radiusBlocks) {
                    // 下沉核：只在柱体下半段（触地后），上部是云底的下沉气流
                    double core = 1 - (mr / mb.radiusBlocks) * (mr / mb.radiusBlocks);
                    double vDecay = Math.exp(-magl / 400.0);
                    acc = acc.add(new WindContribution(0, -10.0 * core * menv * vDecay, 0,
                            0.5 * core * menv, 0.6 * menv * core));
                } else {
                    // 触地外流：径向向外，靠近地面最强
                    double outTaper = mb.outflowTaper(mr);
                    if (outTaper <= 0) continue;
                    double ring = (mr - mb.radiusBlocks) / (mb.radiusBlocks * 1.5);
                    double out = 14.0 * Math.sin(Math.min(1, ring) * Math.PI) * menv
                            * outTaper * Math.exp(-magl / 200.0);
                    double inv = mr > 0.5 ? 1.0 / mr : 0;
                    acc = acc.add(new WindContribution(
                            mdx * inv * out, 0, mdz * inv * out,
                            0.35 * menv * outTaper, 0.2 * menv * outTaper));
                }
            }
        }
        return acc;
    }

    /**
     * 线性收口：{@code r=0} 为 1，{@code r=range} 处为 0，中间平滑。
     * 用于取代 {@code if (d < 90)} / {@code if (ahead < 160)} 这类硬边界（SYS-05）。
     */
    private static double smoothstepFalloff(double r, double range) {
        if (r >= range) return 0.0;
        if (r <= 0) return 1.0;
        double t = r / range;
        return 1.0 - t * t * (3.0 - 2.0 * t);
    }

    private static double projectionT(SquallLine l, double x, double z) {
        double dx = l.x2 - l.x1, dz = l.z2 - l.z1;
        double len2 = dx * dx + dz * dz;
        if (len2 < 1) return 0;
        return Math.max(0, Math.min(1, ((x - l.x1) * dx + (z - l.z1) * dz) / len2));
    }

    @Override
    public boolean hasActive() { return !lines.isEmpty(); }

    @Override
    public int activeCount() { return lines.size(); }

    public List<SquallLine> lines() { return List.copyOf(lines); }

    // 存档（SYS-08）

    @Override
    public String saveKey() { return "squall_lines"; }

    @Override
    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putLong("rng", random.state());
        tag.putDouble("spawnAccumulator", spawnAccumulator);
        tag.put("list", WeatherNbt.writeList(lines, l -> {
            CompoundTag c = new CompoundTag();
            c.putDouble("x1", l.x1);
            c.putDouble("z1", l.z1);
            c.putDouble("x2", l.x2);
            c.putDouble("z2", l.z2);
            c.putDouble("moveX", l.moveX);
            c.putDouble("moveZ", l.moveZ);
            c.putDouble("age", l.ageSeconds);
            c.putDouble("lifetime", l.lifetimeSeconds);
            c.putDouble("intensity", l.intensity01);
            c.putDouble("lightningTimer", l.lightningTimer);
            c.putDouble("groundY", l.groundY);
            c.put("microbursts", WeatherNbt.writeList(l.microbursts, mb -> {
                CompoundTag m = new CompoundTag();
                m.putDouble("x", mb.x);
                m.putDouble("z", mb.z);
                m.putDouble("age", mb.ageSeconds);
                m.putDouble("lifetime", mb.lifetimeSeconds);
                m.putDouble("radius", mb.radiusBlocks);
                m.putDouble("groundY", mb.groundY);
                m.putDouble("strength", mb.strength01);
                return m;
            }));
            return c;
        }));
        return tag;
    }

    @Override
    public void load(CompoundTag tag) {
        if (tag.contains("rng")) random.setState(tag.getLong("rng"));
        spawnAccumulator = tag.getDouble("spawnAccumulator");
        lines.clear();
        lines.addAll(WeatherNbt.readList(tag, "list", c -> {
            SquallLine l = new SquallLine();
            l.x1 = c.getDouble("x1");
            l.z1 = c.getDouble("z1");
            l.x2 = c.getDouble("x2");
            l.z2 = c.getDouble("z2");
            l.moveX = c.getDouble("moveX");
            l.moveZ = c.getDouble("moveZ");
            l.ageSeconds = c.getDouble("age");
            l.lifetimeSeconds = Math.max(1.0, c.getDouble("lifetime"));
            l.intensity01 = c.getDouble("intensity");
            l.lightningTimer = c.getDouble("lightningTimer");
            l.groundY = c.getDouble("groundY");
            l.microbursts.addAll(WeatherNbt.readList(c, "microbursts", m -> {
                Microburst mb = new Microburst();
                mb.x = m.getDouble("x");
                mb.z = m.getDouble("z");
                mb.ageSeconds = m.getDouble("age");
                mb.lifetimeSeconds = Math.max(1.0, m.getDouble("lifetime"));
                mb.radiusBlocks = Math.max(1.0, m.getDouble("radius"));
                mb.groundY = m.getDouble("groundY");
                mb.strength01 = m.getDouble("strength");
                return mb;
            }));
            while (l.microbursts.size() > MAX_MICROBURSTS_PER_LINE) {
                l.microbursts.remove(l.microbursts.size() - 1);
            }
            return l;
        }));
        while (lines.size() > MAX_LINES) lines.remove(lines.size() - 1);
    }
}
