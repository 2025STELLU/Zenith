package net.atomos.zenith.weather;

import net.atomos.zenith.wind.HemisphereConvention;
import net.atomos.zenith.wind.WorldScaleDriver;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * 龙卷：从行星尺度驱动器的对流团里长出来的小尺度涡旋。
 *
 * <p><b>为什么从 L0 搬出来</b>：龙卷核心 10–24 格、影响半径 48–128 格，而 L0 网格是 256 格
 * 一格。旧实现让 L0 在格中心采龙卷，{@code if (r > influenceRadius) continue} 意味着只有
 * 涡心恰好落进 ±128 格才有值，而 {@code exp(-(r/rc)²)} 在 r≈100 时已经≈0——玩家几乎永远
 * 感受不到龙卷的风（DRV-03）。现在 L0 只收它的**大尺度足迹**（{@link
 * WorldScaleDriver#reportTornadoFootprint}），切向风在这里逐点算。
 *
 * <p><b>廓线</b>：Burgers–Rott 涡
 * {@code v(r) = (Γ/2πr)(1 − e^{−r²/rc²})}，峰值在 {@code r ≈ 1.12·rc}，
 * 按峰值归一化（{@link #TANGENTIAL_PEAK}）后 {@code maxWind} 就是真正的最大切向风速。
 * 与旧实现的 {@code exp(-(r/rc)²)} 相比：r→0 时线性趋于 0（真·涡核），外面按 1/r 缓慢衰减
 * （兰金涡外区），两头都连续。
 */
public class TornadoSystem implements WeatherPhenomenon {
    public static final int MAX_ACTIVE_TORNADOES = 2;
    public static final double MIN_STORM_ACTIVITY = 0.35;
    public static final double MIN_SUPPORT = 0.75;
    public static final double MIN_LIFETIME_SECONDS = 45.0;
    public static final double MAX_LIFETIME_SECONDS = 140.0;

    /**
     * {@code (1 − e^{−x²}) / max(0.35, x)} 在 {@code x ≈ 1.121} 处的峰值。除以它之后
     * 廓线峰值正好是 1，{@code maxWind} 才名副其实。
     */
    public static final double TANGENTIAL_PEAK = 0.6382;

    /** 漏斗的垂直尺度：近地最强，100 格 AGL 以上按 exp 衰减。 */
    public static final double HEIGHT_SCALE_BLOCKS = 300.0;
    /** 漏斗顶部切线风消失的高度（AGL），再往上只剩云。 */
    public static final double FUNNEL_TOP_AGL = 1200.0;
    /** 核心上升气流 = 切向峰值的这个比例（旋涡越强抽吸越猛）。 */
    public static final double UPDRAFT_PER_TANGENTIAL = 0.35;

    public static final class Tornado {
        /** 世界方块坐标（不是驱动格！）。旧实现发给客户端前才 ×256，物理侧却用格坐标。 */
        public double x, z;
        public double groundY = 64.0;
        public double maxWind = 30.0;
        public double coreRadius = 16.0;
        public double influenceRadius = 80.0;
        public double ageSeconds;
        public double lifetimeSeconds = 90.0;
        public String name = "unnamed";

        /** 寿命包络 [0,1]：0.2 起旋、0.7 之后衰减。 */
        public double intensityEnvelope() {
            double t = ageSeconds / Math.max(1e-3, lifetimeSeconds);
            if (t < 0.2) return t / 0.2;
            if (t > 0.7) return Math.max(0.0, 1.0 - (t - 0.7) / 0.3);
            return 1.0;
        }

        /** 切向廓线，峰值归一化为 1（Burgers–Rott）。 */
        public double tangentialProfile(double r) {
            if (r < 1e-3) return 0.0;
            double n = r / Math.max(1e-6, coreRadius);
            double core = 1.0 - Math.exp(-n * n);
            return core / Math.max(0.35, n) / TANGENTIAL_PEAK;
        }

        /**
         * 外缘平滑收口：{@code r ≤ 0.7·R} 为 1，到 {@code R} 处 smoothstep 收到 0。
         * 取代旧 L0 的 {@code continue}（SYS-05）。
         */
        public double outerTaper(double r) {
            double inner = influenceRadius * 0.7;
            if (r <= inner) return 1.0;
            if (r >= influenceRadius) return 0.0;
            double t = (r - inner) / Math.max(1e-6, influenceRadius - inner);
            return 1.0 - t * t * (3.0 - 2.0 * t);
        }

        /** 旋向：北半球气旋式（逆时针），南半球顺时针。 */
        public double spin() {
            return HemisphereConvention.coriolisSign();
        }
    }

    private final List<Tornado> tornadoes = new ArrayList<>();
    private final ZenithRandom random;
    private int nameCounter = 0;
    private static final String[] NAMES = {
            "小锥", "铁砧", "灰蛇", "旋齿", "断木", "尘镰", "雷镰", "裂谷"
    };

    public TornadoSystem() {
        this(0L);
    }

    public TornadoSystem(long seed) {
        this.random = new ZenithRandom(seed);
    }

    @Override
    public void tick(ServerLevel level, double dtSeconds, WeatherContext ctx) {
        WorldScaleDriver driver = ctx.driver();
        double stormAct = driver.stormActivity();

        // 生成：在对流团里挑宿主，条件够才生（原逻辑在 WorldScaleDriver.advance 里）
        if (tornadoes.size() < MAX_ACTIVE_TORNADOES
                && stormAct > MIN_STORM_ACTIVITY
                && random.nextDouble() < dtSeconds * 0.02 * stormAct) {
            WorldScaleDriver.ConvectiveHost host = driver.pickConvectiveHost(random);
            if (host != null) {
                double support = 0.5 * stormAct + 0.5 * Math.min(1.0, host.intensity01());
                if (support >= MIN_SUPPORT) {
                    tornadoes.add(spawn(host));
                }
            }
        }

        Iterator<Tornado> it = tornadoes.iterator();
        while (it.hasNext()) {
            Tornado t = it.next();
            t.ageSeconds += dtSeconds;
            if (t.ageSeconds >= t.lifetimeSeconds) {
                it.remove();
                continue;
            }
            // 沿宿主对流团的引导气流漂移（用 L1 中层风，别用格坐标 +0.01 那种"魔法平移"）
            var steer = ctx.l1().sample(t.x, t.groundY + 400, t.z);
            t.x += steer.windX() * 0.55 * dtSeconds;
            t.z += steer.windZ() * 0.55 * dtSeconds;
            t.groundY = ctx.terrain().heightAt(t.x, t.z);
        }

        // 把活跃龙卷的**大尺度**足迹报给 L0：气压亏损 + 一点加热，切向风一概不给
        for (Tornado t : tornadoes) {
            double env = t.intensityEnvelope();
            if (env <= 0) continue;
            driver.reportTornadoFootprint(t.x, t.z, env * Math.min(1.0, t.maxWind / 45.0));
        }
    }

    private Tornado spawn(WorldScaleDriver.ConvectiveHost host) {
        Tornado t = new Tornado();
        // 宿主位置是方块坐标；龙卷落在对流团中心附近（±4 格，跟旧实现一致）
        t.x = host.x() + (random.nextDouble() - 0.5) * 8;
        t.z = host.z() + (random.nextDouble() - 0.5) * 8;
        t.maxWind = 18 + random.nextDouble() * 24;
        t.coreRadius = 10 + random.nextDouble() * 14;
        t.influenceRadius = 48 + random.nextDouble() * 80;
        t.lifetimeSeconds = MIN_LIFETIME_SECONDS
                + random.nextDouble() * (MAX_LIFETIME_SECONDS - MIN_LIFETIME_SECONDS);
        t.ageSeconds = 0;
        t.name = NAMES[nameCounter++ % NAMES.length];
        return t;
    }

    @Override
    public WindContribution sample(double x, double y, double z, double baseWindX, double baseWindZ) {
        if (tornadoes.isEmpty()) return WindContribution.NONE;
        WindContribution acc = WindContribution.NONE;
        for (Tornado t : tornadoes) {
            double dx = x - t.x, dz = z - t.z;
            double r = Math.sqrt(dx * dx + dz * dz);
            double taper = t.outerTaper(r);
            if (taper <= 0) continue;

            double agl = y - t.groundY;
            if (agl < -2 || agl > FUNNEL_TOP_AGL) continue;
            // 近地最强，往上按 exp 衰减；地面以下 2 格内线性收口，不留硬边
            double hDecay = Math.exp(-Math.max(0.0, agl) / HEIGHT_SCALE_BLOCKS);
            if (agl < 0) hDecay *= Math.max(0.0, 1.0 + agl / 2.0);

            double env = t.intensityEnvelope();
            double vt = t.maxWind * t.tangentialProfile(r) * env * taper * hDecay;
            double inv = r > 1e-3 ? 1.0 / r : 0;
            double[] tan = HemisphereConvention.tangential(dx, dz, true);

            // 核心抽吸：r→0 最强，到 rc 处大致减半
            double coreN = Math.exp(-(r * r) / (2.0 * t.coreRadius * t.coreRadius));
            double updraft = vt * UPDRAFT_PER_TANGENTIAL * coreN;

            // 折角处微弱的径向入流（真实龙卷的角动量向内输运）
            double inflow = -0.18 * vt * (1.0 - coreN);

            acc = acc.add(new WindContribution(
                    tan[0] * inv * vt + dx * inv * inflow,
                    updraft,
                    tan[1] * inv * vt + dz * inv * inflow,
                    Math.min(0.9, 0.35 + 0.5 * env) * taper,
                    Math.min(0.9, 0.25 * env * taper)));
        }
        return acc;
    }

    @Override
    public boolean hasActive() { return !tornadoes.isEmpty(); }

    @Override
    public int activeCount() { return tornadoes.size(); }

    public List<Tornado> tornadoes() { return List.copyOf(tornadoes); }

    /** 供调试指令：在指定位置手动生成一个龙卷。 */
    public void spawnDebug(double x, double z, double groundY) {
        Tornado t = new Tornado();
        t.x = x;
        t.z = z;
        t.groundY = groundY;
        t.maxWind = 42;
        t.name = NAMES[nameCounter++ % NAMES.length];
        tornadoes.add(t);
    }

    // 存档（SYS-08）

    @Override
    public String saveKey() { return "tornadoes"; }

    @Override
    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putLong("rng", random.state());
        tag.putInt("nameCounter", nameCounter);
        tag.put("list", WeatherNbt.writeList(tornadoes, t -> {
            CompoundTag c = new CompoundTag();
            c.putDouble("x", t.x);
            c.putDouble("z", t.z);
            c.putDouble("groundY", t.groundY);
            c.putDouble("maxWind", t.maxWind);
            c.putDouble("coreRadius", t.coreRadius);
            c.putDouble("influenceRadius", t.influenceRadius);
            c.putDouble("age", t.ageSeconds);
            c.putDouble("lifetime", t.lifetimeSeconds);
            c.putString("name", t.name);
            return c;
        }));
        return tag;
    }

    @Override
    public void load(CompoundTag tag) {
        if (tag.contains("rng")) random.setState(tag.getLong("rng"));
        nameCounter = tag.getInt("nameCounter");
        tornadoes.clear();
        tornadoes.addAll(WeatherNbt.readList(tag, "list", c -> {
            Tornado t = new Tornado();
            t.x = c.getDouble("x");
            t.z = c.getDouble("z");
            t.groundY = c.getDouble("groundY");
            t.maxWind = c.getDouble("maxWind");
            t.coreRadius = Math.max(1.0, c.getDouble("coreRadius"));
            t.influenceRadius = Math.max(t.coreRadius, c.getDouble("influenceRadius"));
            t.ageSeconds = c.getDouble("age");
            t.lifetimeSeconds = Math.max(1.0, c.getDouble("lifetime"));
            t.name = c.getString("name");
            return t;
        }));
        while (tornadoes.size() > MAX_ACTIVE_TORNADOES) tornadoes.remove(tornadoes.size() - 1);
    }
}
