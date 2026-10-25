package net.atomos.zenith.weather;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * 锋面：冷锋和暖锋。
 *
 * 一条锋线就是一条线段，一个维度里最多 4 条。
 * 冷锋跑得快：锋后降温、气压上升、风向顺转，带着一条窄降水带和强阵风；
 * 暖锋是慢性子：锋前大片层状云降水，风弱，慢慢升温。
 * 锋线沿法线方向走（被引导气流推着），强气旋过境时会催生新的锋面。
 */
public class FrontSystem implements WeatherPhenomenon {
    public static final int MAX_FRONTS = 4;
    public static final double MIN_LIFETIME_SECONDS = 7200;
    public static final double MAX_EXTRA_LIFETIME_SECONDS = 7200;

    /**
     * 锋线相对槽线的倾斜角（WX-FRONT-01）。真实锋面很少严格垂直等压线：
     * 冷锋向后倾斜约 15°，暖锋向前倾斜同样的角度。
     */
    public static final double COLD_WARM_SKEW_RADIANS = Math.toRadians(15);

    /**
     * 锋线相对低压中心的法向偏移，单位是低压核心半径的比例（WX-FRONT-01）。
     * 冷锋在中心后侧（−n）、暖锋在前侧（+n），两者之间就是暖区。
     */
    public static final double FRONT_OFFSET_CORE_FRACTION = 0.45;

    /**
     * 冷锋的锋后降温（K，WX-FR-02）。真实冷锋过境 1 小时降 5–10 K；
     * 这里取 4 K 是"成熟段强度 1.0"时的峰值，随 {@code intensity01} 线性缩放。
     */
    public static final double COLD_FRONT_COOLING_K = 4.0;
    /** 冷锋的锋后加压（Pa，WX-FR-02）：典型 2–6 hPa，取 3 hPa。 */
    public static final double COLD_FRONT_PRESSURE_RISE_PA = 300.0;
    /** 暖锋的暖区升温（K，WX-FR-02）：暖锋平流本身比冷锋温和。 */
    public static final double WARM_FRONT_WARMING_K = 2.5;
    /** 暖锋的暖区降压（Pa，WX-FR-02）：暖平流 + 上升运动，取 −2 hPa。 */
    public static final double WARM_FRONT_PRESSURE_DROP_PA = -200.0;

    public enum FrontType { COLD, WARM }

    public static final class Front {
        public FrontType type;
        public double x1, z1, x2, z2; // 锋线两端点（格）
        public double ageSeconds;
        /**
         * 寿命。旧实现写在字段初始化里用 {@code Math.random()}（SYS-08）：每种一条锋面
         * 都从全局随机数取寿命，存档重启不可复现，也没法用一个种子重播。
         * 现在由 {@link #newFront()} 从可序列化的 {@link ZenithRandom} 里取。
         */
        public double lifetimeSeconds = MIN_LIFETIME_SECONDS + MAX_EXTRA_LIFETIME_SECONDS * 0.5;
        public double intensity01 = 0.8;
        public double moveX, moveZ; // m/s，沿锋线法向移动

        public double distanceTo(double x, double z) {
            double dx = x2 - x1, dz = z2 - z1;
            double len2 = dx * dx + dz * dz;
            if (len2 < 1) return Math.hypot(x - x1, z - z1);
            double t = ((x - x1) * dx + (z - z1) * dz) / len2;
            t = Math.max(0, Math.min(1, t));
            return Math.hypot(x - (x1 + dx * t), z - (z1 + dz * t));
        }

        /**
         * 有符号距离（格）：沿 {@link #normal()} 为正 = 锋前，为负 = 锋后（WX-FR-02）。
         *
         * <p>热力/气压扰动不是对称的：冷锋只在锋后降温加压，暖锋只在暖区升温降压。
         * 只靠 {@link #distanceTo} 的绝对值分不出前后，所以单独给一个有符号量。
         */
        public double signedDistanceTo(double x, double z) {
            double[] n = normal();
            return (x - x1) * n[0] + (z - z1) * n[1];
        }

        /** 法向量。约定：冷锋指暖区（锋前），暖锋指冷区——统一成"锋前"方向。 */
        public double[] normal() {
            double dx = x2 - x1, dz = z2 - z1;
            double len = Math.hypot(dx, dz);
            if (len < 1) return new double[]{1, 0};
            return new double[]{-dz / len, dx / len};
        }
    }

    private final List<Front> fronts = new ArrayList<>();
    private final ZenithRandom random;
    private double spawnAccumulator = 0;

    public FrontSystem() {
        this(0L);
    }

    public FrontSystem(long seed) {
        this.random = new ZenithRandom(seed);
    }

    private Front newFront() {
        Front f = new Front();
        f.lifetimeSeconds = MIN_LIFETIME_SECONDS
                + random.nextDouble() * MAX_EXTRA_LIFETIME_SECONDS;
        return f;
    }

    @Override
    public void tick(ServerLevel level, double dtSeconds, WeatherContext ctx) {
        double stormAct = ctx.driver().stormActivity();

        // 强气旋催生锋面：气旋越强越容易甩出锋
        spawnAccumulator += dtSeconds * Math.max(0, stormAct - 0.35) * 0.01;
        while (spawnAccumulator >= 1.0 && fronts.size() < MAX_FRONTS) {
            spawnAccumulator -= 1.0;
            ServerPlayer anchor = pickAnchor(level);
            if (anchor == null) break;

            // WX-FRONT-01：锋面必须挂在驱动器低压中心的槽线上。
            // 旧实现是"玩家周围 500–1300 格随机圆环上的随机方向"，与 driver.cyclones、
            // 与 L0/L1 的温度梯度毫无关系，于是同一时刻可以出现两条方向互斥的冷锋。
            var low = ctx.driver().nearestLowPressureCenter(anchor.getX(), anchor.getZ());
            if (low == null) break;   // 没有低压就没有锋面，宁可不生成

            // 同一个低压不重复挂锋：两条锋的线中点相距 < 400 格就跳过
            boolean taken = false;
            for (Front existing : fronts) {
                double mx = (existing.x1 + existing.x2) * 0.5;
                double mz = (existing.z1 + existing.z2) * 0.5;
                if (Math.hypot(mx - low.xBlocks(), mz - low.zBlocks()) < 400) { taken = true; break; }
            }
            if (taken) continue;

            // 引导气流在低压中心上空 120 格 AGL 处取（锋线走向 = 垂直于它）
            double guideY = ctx.terrain().heightAt(low.xBlocks(), low.zBlocks()) + 120.0;
            var l1s = ctx.l1().sample(low.xBlocks(), guideY, low.zBlocks());

            Front f = newFront();
            f.type = random.nextDouble() < 0.55 ? FrontType.COLD : FrontType.WARM;
            // 锋线走向：槽线 ≈ 垂直于引导气流（切变线）。旧公式把 atan2(x,z) 的
            // "从 +Z 量起"按"从 +X 量起"解释了，正东/正南风下线会平行于风。
            double wx = l1s.windX(), wz = l1s.windZ();
            double w = Math.hypot(wx, wz);
            if (w < 0.3) { wx = 1; wz = 0; w = 1; }  // 近静风：默认东风，方向才有定义
            double ldx = wz / w, ldz = -wx / w;      // 槽线方向 ⟂ 引导气流
            // 冷暖锋分别向暖区/冷区倾：冷锋在低压后方（−n）朝暖区斜切，
            // 暖锋在低压前方（+n）朝冷区斜切。南半球整体镜像（SYS-01 / DRV-08）。
            double hemi = net.atomos.zenith.wind.HemisphereConvention.coriolisSign();
            double skew = (f.type == FrontType.COLD ? +1 : -1) * COLD_WARM_SKEW_RADIANS * hemi;
            double cj = Math.cos(skew), sj = Math.sin(skew);
            double lineDirX = ldx * cj - ldz * sj;
            double lineDirZ = ldx * sj + ldz * cj;
            // 沿法线的偏移：冷锋退到低压后侧、暖锋推到前侧，两者夹出暖区
            double offset = (f.type == FrontType.COLD ? -1 : +1)
                    * low.coreRadiusBlocks() * FRONT_OFFSET_CORE_FRACTION;
            double nx = -lineDirZ, nz = lineDirX;  // 倾斜后锋线的法线（≈引导气流方向）
            double fx = low.xBlocks() + nx * offset;
            double fz = low.zBlocks() + nz * offset;

            double halfLen = Math.max(250, Math.min(600, low.outerRadiusBlocks() * 0.10));
            f.x1 = fx - lineDirX * halfLen;
            f.z1 = fz - lineDirZ * halfLen;
            f.x2 = fx + lineDirX * halfLen;
            f.z2 = fz + lineDirZ * halfLen;
            double steer = Math.hypot(l1s.windX(), l1s.windZ());
            double[] n = f.normal();
            // 冷暖锋都跟着引导气流顺风走，暖锋只是慢半拍
            double speedFactor = f.type == FrontType.COLD ? 0.55 : 0.35;
            f.moveX = n[0] * steer * speedFactor;
            f.moveZ = n[1] * steer * speedFactor;
            f.intensity01 = 0.5 + stormAct * 0.5;
            // 播种年龄到成熟段起点：tick() 的升段 t/0.2 会在下一个 tick 把强度刷成 ~2e-5，
            // 于是每条锋面前 20% 寿命（24–48 分钟）几乎无风无雨（WX-FR-01 / 旧 FR/SQ-01）。
            f.ageSeconds = 0.2 * f.lifetimeSeconds;
            fronts.add(f);
        }

        Iterator<Front> it = fronts.iterator();
        while (it.hasNext()) {
            Front f = it.next();
            f.ageSeconds += dtSeconds;
            if (f.ageSeconds >= f.lifetimeSeconds) {
                it.remove();
                continue;
            }
            f.x1 += f.moveX * dtSeconds; f.z1 += f.moveZ * dtSeconds;
            f.x2 += f.moveX * dtSeconds; f.z2 += f.moveZ * dtSeconds;
            double t = f.ageSeconds / f.lifetimeSeconds;
            f.intensity01 = t < 0.2 ? t / 0.2 : t > 0.8 ? Math.max(0.2, 1 - (t - 0.8) / 0.2) : 1.0;
        }
    }

    private ServerPlayer pickAnchor(ServerLevel level) {
        var players = level.players();
        return players.isEmpty() ? null : players.get(random.nextInt(players.size()));
    }

    @Override
    public WindContribution sample(double x, double y, double z, double baseWindX, double baseWindZ) {
        WindContribution acc = WindContribution.NONE;
        for (Front f : fronts) {
            double d = f.distanceTo(x, z);
            double width = f.type == FrontType.COLD ? 45 : 120;
            if (d > width * 2.5) continue;
            double band = Math.exp(-(d * d) / (width * width));
            double e = band * f.intensity01;
            double[] n = f.normal();
            // 有符号距离：正 = 锋前（暖区侧），负 = 锋后（冷区侧）
            double sd = f.signedDistanceTo(x, z);
            if (f.type == FrontType.COLD) {
                // 冷锋：窄降水带 + 阵风；锋后风向顺转用沿法线的推力近似一下
                double gust = 6.0 * e;
                // 锋后降温加压（WX-FR-02）：只用 sd < 0 的那一半，锋前不受影响。
                // 用 smoothstep 而不是阶跃，锋线两侧才没有 4 K 的突变。
                double behind = smoothstep(0.0, -width, sd);
                acc = acc.add(new WindContribution(
                        n[0] * gust * 0.6, 0.8 * e, n[1] * gust * 0.6,
                        0.3 * e, 0.75 * e,
                        -COLD_FRONT_COOLING_K * e * behind,
                        +COLD_FRONT_PRESSURE_RISE_PA * e * behind));
            } else {
                // 暖锋：宽层状降水带，风很弱
                double ahead = smoothstep(0.0, width, sd);
                acc = acc.add(new WindContribution(
                        -n[0] * 1.5 * e, 0.3 * e, -n[1] * 1.5 * e,
                        0.12 * e, 0.5 * e,
                        +WARM_FRONT_WARMING_K * e * ahead,
                        +WARM_FRONT_PRESSURE_DROP_PA * e * ahead));
            }
        }
        return acc;
    }

    /** {@code x} 从 {@code e0} 走到 {@code e1} 时 0→1 的平滑过渡；{@code e1 < e0} 时方向反转。 */
    private static double smoothstep(double e0, double e1, double x) {
        double t = (x - e0) / (e1 - e0);
        t = t < 0 ? 0 : Math.min(1, t);
        return t * t * (3 - 2 * t);
    }

    @Override
    public boolean hasActive() { return !fronts.isEmpty(); }

    @Override
    public int activeCount() { return fronts.size(); }

    public List<Front> fronts() { return List.copyOf(fronts); }

    /** 供调试/指令。 */
    public void spawnDebug(double x, double z, boolean cold) {
        Front f = newFront();
        f.type = cold ? FrontType.COLD : FrontType.WARM;
        f.x1 = x - 300; f.z1 = z;
        f.x2 = x + 300; f.z2 = z;
        f.moveX = 0; f.moveZ = 2;
        fronts.add(f);
    }

    // 存档（SYS-08）

    @Override
    public String saveKey() { return "fronts"; }

    @Override
    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putLong("rng", random.state());
        tag.putDouble("spawnAccumulator", spawnAccumulator);
        tag.put("list", WeatherNbt.writeList(fronts, f -> {
            CompoundTag t = new CompoundTag();
            t.putString("type", f.type.name());
            t.putDouble("x1", f.x1);
            t.putDouble("z1", f.z1);
            t.putDouble("x2", f.x2);
            t.putDouble("z2", f.z2);
            t.putDouble("age", f.ageSeconds);
            t.putDouble("lifetime", f.lifetimeSeconds);
            t.putDouble("intensity", f.intensity01);
            t.putDouble("moveX", f.moveX);
            t.putDouble("moveZ", f.moveZ);
            return t;
        }));
        return tag;
    }

    @Override
    public void load(CompoundTag tag) {
        if (tag.contains("rng")) random.setState(tag.getLong("rng"));
        spawnAccumulator = tag.getDouble("spawnAccumulator");
        fronts.clear();
        fronts.addAll(WeatherNbt.readList(tag, "list", t -> {
            Front f = new Front();
            try {
                f.type = FrontType.valueOf(t.getString("type"));
            } catch (IllegalArgumentException e) {
                f.type = FrontType.COLD;
            }
            f.x1 = t.getDouble("x1");
            f.z1 = t.getDouble("z1");
            f.x2 = t.getDouble("x2");
            f.z2 = t.getDouble("z2");
            f.ageSeconds = t.getDouble("age");
            f.lifetimeSeconds = Math.max(1.0, t.getDouble("lifetime"));
            f.intensity01 = t.getDouble("intensity");
            f.moveX = t.getDouble("moveX");
            f.moveZ = t.getDouble("moveZ");
            return f;
        }));
        while (fronts.size() > MAX_FRONTS) fronts.remove(fronts.size() - 1);
    }
}
