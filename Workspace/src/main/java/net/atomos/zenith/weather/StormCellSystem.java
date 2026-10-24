package net.atomos.zenith.weather;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * 风暴单体：积雨云的一生——发展、成熟、消散。
 *
 * 发展期（~15 分钟）：核心上升气流 3–8 m/s，云越长越高；
 * 成熟期（~20 分钟）：降水核心开始下沉，阵风锋径向外流 8–15 m/s，还随机劈闪电；
 * 消散期（~15 分钟）：只剩弱下沉和层状降水。
 * 单体跟着中层风走；生成要同时满足：风暴活动度高、湿度够、层结不稳定。
 */
public class StormCellSystem implements WeatherPhenomenon {
    public static final int MAX_CELLS = 8;
    public static final double DEVELOP_SECONDS = 900;
    public static final double MATURE_SECONDS = 1200;
    public static final double DISSIPATE_SECONDS = 900;

    public enum Phase { DEVELOPING, MATURE, DISSIPATING }

    public static final class StormCell {
        public double x, z;
        public double radiusBlocks = 90;
        public double ageSeconds;
        public double moveX, moveZ; // m/s
        public double lightningTimer;
        public double hail01 = 0;   // 冰雹强度（成熟期计算）
        /** 单元中心处的地表 y（AGL 基准），由 tick 每步刷新（WX-SC-03）。 */
        public double groundY = 64.0;

        public Phase phase() {
            if (ageSeconds < DEVELOP_SECONDS) return Phase.DEVELOPING;
            if (ageSeconds < DEVELOP_SECONDS + MATURE_SECONDS) return Phase.MATURE;
            return Phase.DISSIPATING;
        }

        public double lifetimeSeconds() {
            return DEVELOP_SECONDS + MATURE_SECONDS + DISSIPATE_SECONDS;
        }

        public double envelope() {
            double t = ageSeconds / lifetimeSeconds();
            if (t < 0.1) return t / 0.1;
            if (t > 0.85) return Math.max(0, 1 - (t - 0.85) / 0.15);
            return 1.0;
        }
    }

    private final List<StormCell> cells = new ArrayList<>();
    private final ZenithRandom random;
    private double spawnAccumulator = 0;

    public StormCellSystem() {
        this(0L);
    }

    public StormCellSystem(long seed) {
        this.random = new ZenithRandom(seed);
    }

    @Override
    public void tick(ServerLevel level, double dtSeconds, WeatherContext ctx) {
        double stormAct = ctx.driver().stormActivity();

        // 生成
        spawnAccumulator += dtSeconds * Math.max(0, stormAct - 0.45) * 0.06;
        while (spawnAccumulator >= 1.0 && cells.size() < MAX_CELLS) {
            spawnAccumulator -= 1.0;
            ServerPlayer anchor = pickAnchor(level);
            if (anchor == null) break;
            double ax = anchor.getX() + (random.nextDouble() - 0.5) * 1200;
            double az = anchor.getZ() + (random.nextDouble() - 0.5) * 1200;
            double groundY = ctx.terrain().heightAt(ax, az);
            var l1s = ctx.l1().sample(ax, groundY + 150, az);
            // 需要湿 + 不稳定
            if (l1s.humidity01() < 0.55 && random.nextDouble() < 0.7) continue;
            StormCell c = new StormCell();
            c.x = ax;
            c.z = az;
            c.groundY = groundY;
            c.radiusBlocks = 60 + random.nextDouble() * 60;
            c.moveX = l1s.windX() * 0.6;
            c.moveZ = l1s.windZ() * 0.6;
            cells.add(c);
        }

        // 推进
        Iterator<StormCell> it = cells.iterator();
        while (it.hasNext()) {
            StormCell c = it.next();
            c.ageSeconds += dtSeconds;
            if (c.ageSeconds >= c.lifetimeSeconds()) {
                it.remove();
                continue;
            }
            c.x += c.moveX * dtSeconds;
            c.z += c.moveZ * dtSeconds;
            // 地表跟着走；引导气流持续缓松弛到当前 L1 风，不再"沿直线匀速平移的圆盘"（WX-SC-05）
            c.groundY = ctx.terrain().heightAt(c.x, c.z);
            var steer = ctx.l1().sample(c.x, c.groundY + 150, c.z);
            double k = Math.min(1.0, dtSeconds / 10.0);
            c.moveX += (steer.windX() * 0.6 - c.moveX) * k;
            c.moveZ += (steer.windZ() * 0.6 - c.moveZ) * k;

            // 成熟期闪电 + 冰雹
            if (c.phase() == Phase.MATURE) {
                c.lightningTimer -= dtSeconds;
                if (c.lightningTimer <= 0) {
                    c.lightningTimer = 4 + random.nextDouble() * 14;
                    strikeLightning(level, c);
                }
                // 冰雹：强上升 + 高湿度 → 雹强
                double target = clamp01((c.radiusBlocks - 70) / 50.0) * 0.8 + 0.2;
                c.hail01 += (target - c.hail01) * Math.min(1, dtSeconds / 60.0);
                // 冰雹伤害：核心区玩家每 2 秒 0.5 点，走 zenith:hail 伤害类型（WX-SC-01）
                if (((int) (c.ageSeconds / 2)) != ((int) ((c.ageSeconds - dtSeconds) / 2))) {
                    damagePlayersInHail(level, c);
                }
            } else {
                c.hail01 = Math.max(0, c.hail01 - dtSeconds / 120.0);
            }
        }
    }

    private void strikeLightning(ServerLevel level, StormCell c) {
        double ang = random.nextDouble() * Math.PI * 2;
        double r = random.nextDouble() * c.radiusBlocks * 0.8;
        int bx = (int) (c.x + Math.cos(ang) * r);
        int bz = (int) (c.z + Math.sin(ang) * r);
        // 统一走 LightningGate：维度/已加载/离玩家距离/伤害与点火配置四道闸（WX-SC-02）
        LightningGate.strike(level, bx, bz);
    }

    private ServerPlayer pickAnchor(ServerLevel level) {
        var players = level.players();
        return players.isEmpty() ? null : players.get(random.nextInt(players.size()));
    }

    private void damagePlayersInHail(ServerLevel level, StormCell c) {
        // 冰雹要在降雨里（WX-SC-01 的判定 1）：模组单体与 MC 天气是两套，只有下雨时才"砸"得下来
        if (!level.isRaining()) return;
        if (c.hail01 < 0.5) return;
        for (ServerPlayer p : level.players()) {
            double dx = p.getX() - c.x, dz = p.getZ() - c.z;
            if (dx * dx + dz * dz >= c.radiusBlocks * c.radiusBlocks * 0.64) continue;
            // 遮挡判定：用"头顶到云底之间有没有实心方块"，而不是 canSeeSky（树叶会挡天空光，
            // 但挡不住冰雹；2 格高的屋檐反而算见天）
            if (hasRoofBetween(level, p)) continue;
            p.hurt(net.atomos.zenith.ZenithDamageTypes.hail(level), 0.5f);
        }
    }

    /** 玩家头顶 2..40 格内是否有实心遮挡（有 = 冰雹落不到身上）。 */
    private static boolean hasRoofBetween(ServerLevel level, ServerPlayer p) {
        var base = p.blockPosition();
        for (int dy = 2; dy <= 40; dy++) {
            var pos = base.above(dy);
            if (pos.getY() > level.getMaxBuildHeight()) break;
            var st = level.getBlockState(pos);
            if (!st.isAir() && st.isSolidRender(level, pos)) return true;
        }
        return false;
    }

    /** 冰雹强度 [0,1]，供 API 和特效用。 */
    public double hailAt(double x, double z) {
        double h = 0;
        for (StormCell c : cells) {
            double dx = x - c.x, dz = z - c.z;
            double r = Math.sqrt(dx * dx + dz * dz);
            if (r < c.radiusBlocks) {
                h = Math.max(h, c.hail01 * (1 - r / c.radiusBlocks));
            }
        }
        return h;
    }

    @Override
    public WindContribution sample(double x, double y, double z, double baseWindX, double baseWindZ) {
        WindContribution acc = WindContribution.NONE;
        for (StormCell c : cells) {
            double dx = x - c.x, dz = z - c.z;
            double r = Math.sqrt(dx * dx + dz * dz);
            double norm = r / c.radiusBlocks;
            // 外缘 2.5R 处平滑收口，不再硬 continue（SYS-05）
            double radialTaper = smoothstepFalloff(norm, 2.5);
            if (radialTaper <= 0) continue;
            double env = c.envelope() * radialTaper;
            // 垂直结构（WX-SC-03）：低层入流、中层上升、高层砧状外流。
            // 旧实现所有分量都是整柱常数。
            double agl = Math.max(0.0, y - c.groundY);
            double lowInflow = Math.exp(-agl / 60.0);          // 0–60 格：低层辐合最强
            double midUpdraft = Math.exp(-Math.pow((agl - 130.0) / 90.0, 2)); // 100–180 格：主上升
            double anvilOutflow = smoothstepRamp(agl, 180.0, 280.0); // 180 格以上：砧状外流
            double inv = r > 0.5 ? 1.0 / r : 0;

            switch (c.phase()) {
                case DEVELOPING -> {
                    if (norm < 1.0) {
                        double core = 1 - norm * norm;
                        double w = 6.0 * core * env * (0.4 + 0.6 * midUpdraft);
                        acc = acc.add(new WindContribution(0, w, 0, 0.2 * core * env, 0.1 * env));
                    }
                }
                case MATURE -> {
                    if (norm < 1.0) {
                        double core = 1 - norm * norm;
                        // 降水拖着空气往下沉：只在低层（agl < 40）且内圈（norm < 0.85）生效
                        double downdraftDepth = Math.exp(-agl / 40.0);
                        double downdraftRadial = smoothstepFalloff(norm, 0.85);
                        double vy = -4.5 * core * env * downdraftDepth * downdraftRadial
                                // 中层是上升气流
                                + 6.0 * core * env * midUpdraft;
                        acc = acc.add(new WindContribution(
                                -dx * inv * 0.3 * core * env * lowInflow, vy,
                                -dz * inv * 0.3 * core * env * lowInflow,
                                0.45 * core * env, 0.9 * env));
                    } else {
                        // 阵风锋：径向向外，2.2R 内 sin 剖面，外缘由 radialTaper 收到 0
                        double ring = (norm - 1.0) / 1.2;
                        if (ring < 1.0) {
                            double gust = 12.0 * Math.sin(ring * Math.PI) * env;
                            // 阵风锋前缘：顺着移动方向那一侧更猛
                            double lead = (dx * c.moveX + dz * c.moveZ);
                            double leadBoost = 1.0 + 0.5 * clamp01(lead / (r * 8.0 + 1));
                            // 砧状外流在高空加强，低层靠冷池外流
                            double heightBoost = 1.0 + 0.5 * anvilOutflow;
                            acc = acc.add(new WindContribution(
                                    dx * inv * gust * leadBoost * heightBoost,
                                    0.5 * gust * 0.2 + 3.0 * env * anvilOutflow,
                                    dz * inv * gust * leadBoost * heightBoost,
                                    0.35 * env, 0.4 * env));
                        }
                    }
                }
                case DISSIPATING -> {
                    double dissipateTaper = smoothstepFalloff(norm, 1.5);
                    if (dissipateTaper > 0) {
                        acc = acc.add(new WindContribution(0, -1.2 * env, 0,
                                0.15 * env, 0.35 * env * dissipateTaper));
                    }
                }
            }
        }
        return acc;
    }

    /** {@code r <= 0} 为 1，{@code r >= range} 为 0，中间平滑（替换硬边界）。 */
    private static double smoothstepFalloff(double r, double range) {
        if (r >= range) return 0.0;
        if (r <= 0) return 1.0;
        double t = r / range;
        return 1.0 - t * t * (3.0 - 2.0 * t);
    }

    /** 0 → 1 的平滑爬升：{@code x <= lo} 为 0，{@code x >= hi} 为 1。 */
    private static double smoothstepRamp(double x, double lo, double hi) {
        if (x <= lo) return 0.0;
        if (x >= hi) return 1.0;
        double t = (x - lo) / (hi - lo);
        return t * t * (3.0 - 2.0 * t);
    }

    @Override
    public boolean hasActive() { return !cells.isEmpty(); }

    @Override
    public int activeCount() { return cells.size(); }

    public List<StormCell> cells() { return List.copyOf(cells); }

    /** 供调试/指令：手动生成风暴单体。 */
    public void spawnDebug(double x, double z) {
        spawnDebug(x, z, 64.0);
    }

    /** 供调试/指令：手动生成风暴单体，指定地表 y（AGL 基准，WX-SC-03）。 */
    public void spawnDebug(double x, double z, double groundY) {
        StormCell c = new StormCell();
        c.x = x; c.z = z;
        c.groundY = groundY;
        c.radiusBlocks = 100;
        c.ageSeconds = DEVELOP_SECONDS; // 直接进入成熟期
        cells.add(c);
    }

    // 存档（SYS-08）

    @Override
    public String saveKey() { return "storm_cells"; }

    @Override
    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putLong("rng", random.state());
        tag.putDouble("spawnAccumulator", spawnAccumulator);
        tag.put("list", WeatherNbt.writeList(cells, c -> {
            CompoundTag t = new CompoundTag();
            t.putDouble("x", c.x);
            t.putDouble("z", c.z);
            t.putDouble("radius", c.radiusBlocks);
            t.putDouble("age", c.ageSeconds);
            t.putDouble("moveX", c.moveX);
            t.putDouble("moveZ", c.moveZ);
            t.putDouble("lightningTimer", c.lightningTimer);
            t.putDouble("hail", c.hail01);
            t.putDouble("groundY", c.groundY);
            return t;
        }));
        return tag;
    }

    @Override
    public void load(CompoundTag tag) {
        if (tag.contains("rng")) random.setState(tag.getLong("rng"));
        spawnAccumulator = tag.getDouble("spawnAccumulator");
        cells.clear();
        cells.addAll(WeatherNbt.readList(tag, "list", t -> {
            StormCell c = new StormCell();
            c.x = t.getDouble("x");
            c.z = t.getDouble("z");
            c.radiusBlocks = Math.max(1.0, t.getDouble("radius"));
            c.ageSeconds = t.getDouble("age");
            c.moveX = t.getDouble("moveX");
            c.moveZ = t.getDouble("moveZ");
            c.lightningTimer = t.getDouble("lightningTimer");
            c.hail01 = t.getDouble("hail");
            c.groundY = t.getDouble("groundY");
            return c;
        }));
        while (cells.size() > MAX_CELLS) cells.remove(cells.size() - 1);
    }

    private static double clamp01(double v) { return v < 0 ? 0 : Math.min(v, 1); }
}
