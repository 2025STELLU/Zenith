package net.atomos.zenith.weather;

import net.atomos.zenith.wind.NoiseUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LightningBolt;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Random;

/**
 * 风暴单体系统：积雨云生命史（发展 → 成熟 → 消散）。
 *
 * <p>模型：</p>
 * <ul>
 *   <li>发展期（~15 min）：核心上升气流 3–8 m/s，云体增长</li>
 *   <li>成熟期（~20 min）：降水核心下沉气流，阵风锋径向外流 8–15 m/s，随机闪电</li>
 *   <li>消散期（~15 min）：弱下沉 + 层状降水</li>
 *   <li>单体随中层风移动；生成需要高风暴活动度 + 高湿度 + 不稳定层结</li>
 * </ul>
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
    private final Random random = new Random();
    private double spawnAccumulator = 0;

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
            var l1s = ctx.l1().sample(ax, 150, az);
            // 需要湿 + 不稳定
            if (l1s.humidity01() < 0.55 && random.nextDouble() < 0.7) continue;
            StormCell c = new StormCell();
            c.x = ax;
            c.z = az;
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
                // 冰雹伤害：核心区玩家每 2 秒 1 点
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
        BlockPos surface = level.getHeightmapPos(
                net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE,
                new BlockPos(bx, 0, bz));
        LightningBolt bolt = EntityType.LIGHTNING_BOLT.create(level);
        if (bolt == null) return;
        bolt.moveTo(surface.getX(), surface.getY(), surface.getZ());
        bolt.setVisualOnly(false);
        level.addFreshEntity(bolt);
    }

    private ServerPlayer pickAnchor(ServerLevel level) {
        var players = level.players();
        return players.isEmpty() ? null : players.get(random.nextInt(players.size()));
    }

    /** 冰雹伤害：核心区内玩家。 */
    private void damagePlayersInHail(ServerLevel level, StormCell c) {
        if (c.hail01 < 0.5) return;
        for (ServerPlayer p : level.players()) {
            double dx = p.getX() - c.x, dz = p.getZ() - c.z;
            if (dx * dx + dz * dz < c.radiusBlocks * c.radiusBlocks * 0.64) {
                // 露天才受伤（有方块遮挡不算）
                var headPos = p.blockPosition().above(2);
                if (level.canSeeSky(headPos)) {
                    p.hurt(level.damageSources().generic(), 1.0f);
                }
            }
        }
    }

    /** 指定点冰雹强度 [0,1]（供 API/特效）。 */
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
            if (r > c.radiusBlocks * 2.5) continue;
            double env = c.envelope();
            double norm = r / c.radiusBlocks;
            switch (c.phase()) {
                case DEVELOPING -> {
                    if (norm < 1.0) {
                        double core = 1 - norm * norm;
                        double w = 6.0 * core * env;
                        acc = acc.add(new WindContribution(0, w, 0, 0.2 * core * env, 0.1 * env));
                    }
                }
                case MATURE -> {
                    if (norm < 1.0) {
                        // 降水核心：下沉气流
                        double core = 1 - norm * norm;
                        acc = acc.add(new WindContribution(0, -4.5 * core * env, 0,
                                0.45 * core * env, 0.9 * env));
                    } else if (norm < 2.2) {
                        // 阵风锋：径向外流
                        double ring = (norm - 1.0) / 1.2;
                        double gust = 12.0 * Math.sin(ring * Math.PI) * env;
                        double inv = r > 0.5 ? 1.0 / r : 0;
                        // 叠加移动方向的前缘增强
                        double lead = (dx * c.moveX + dz * c.moveZ);
                        double leadBoost = 1.0 + 0.5 * clamp01(lead / (r * 8.0 + 1));
                        acc = acc.add(new WindContribution(
                                dx * inv * gust * leadBoost, 0.5 * gust * 0.2, dz * inv * gust * leadBoost,
                                0.35 * env, 0.4 * env));
                    }
                }
                case DISSIPATING -> {
                    if (norm < 1.5) {
                        acc = acc.add(new WindContribution(0, -1.2 * env, 0, 0.15 * env, 0.35 * env));
                    }
                }
            }
        }
        return acc;
    }

    @Override
    public boolean hasActive() { return !cells.isEmpty(); }

    @Override
    public int activeCount() { return cells.size(); }

    public List<StormCell> cells() { return List.copyOf(cells); }

    /** 供调试/指令：手动生成风暴单体。 */
    public void spawnDebug(double x, double z) {
        StormCell c = new StormCell();
        c.x = x; c.z = z;
        c.radiusBlocks = 100;
        c.ageSeconds = DEVELOP_SECONDS; // 直接进入成熟期
        cells.add(c);
    }

    private static double clamp01(double v) { return v < 0 ? 0 : Math.min(v, 1); }
}
