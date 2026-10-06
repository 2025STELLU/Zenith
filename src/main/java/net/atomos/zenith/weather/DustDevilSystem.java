package net.atomos.zenith.weather;

import net.atomos.zenith.api.ZenithTerrainSurfaceClass;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Random;

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

    public static final class DustDevil {
        public double x, z;
        public double radiusBlocks = 4;
        public double heightBlocks = 40;
        public double tangentialMps = 8;
        public double ageSeconds;
        public double lifetimeSeconds = 120;
        public double spin = 1;
        public double wanderX, wanderZ;

        double envelope() {
            double t = ageSeconds / lifetimeSeconds;
            if (t < 0.2) return t / 0.2;
            if (t > 0.7) return Math.max(0, 1 - (t - 0.7) / 0.3);
            return 1.0;
        }
    }

    private final List<DustDevil> devils = new ArrayList<>();
    private final Random random = new Random();
    private double spawnAccumulator = 0;

    @Override
    public void tick(ServerLevel level, double dtSeconds, WeatherContext ctx) {
        double solar = Math.sin(ctx.timeOfDay01() * Math.PI * 2.0 - Math.PI / 2.0);
        solar = Math.max(0, solar) * (ctx.raining() ? 0 : 1.0);

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
            // 尘卷风怕大风，背景风太强就长不出来
            var l1s = ctx.l1().sample(ax, 80, az);
            if (Math.hypot(l1s.windX(), l1s.windZ()) > 6 && random.nextDouble() < 0.7) continue;

            DustDevil d = new DustDevil();
            d.x = ax; d.z = az;
            d.radiusBlocks = 2 + random.nextDouble() * 4;
            d.heightBlocks = 20 + random.nextDouble() * 40;
            d.tangentialMps = 5 + random.nextDouble() * 7;
            d.lifetimeSeconds = 60 + random.nextDouble() * 240;
            d.spin = random.nextBoolean() ? 1 : -1;
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
            if (r > d.radiusBlocks * 3 || y > d.heightBlocks + 60) continue;
            double env = d.envelope();
            double hDecay = y < d.heightBlocks ? 1.0 : Math.max(0, 1 - (y - d.heightBlocks) / 60.0);
            if (r < d.radiusBlocks) {
                // 核心区：上升气流最强
                double core = 1 - r / d.radiusBlocks;
                acc = acc.add(new WindContribution(0, 4.5 * core * env * hDecay, 0,
                        0.4 * core * env, 0));
            } else {
                double ring = Math.exp(-Math.pow((r - d.radiusBlocks) / (d.radiusBlocks * 1.5), 2));
                double inv = r > 0.5 ? 1.0 / r : 0;
                double vt = d.tangentialMps * ring * env * hDecay;
                acc = acc.add(new WindContribution(
                        -dz * inv * vt * d.spin, 0.5 * ring * env, dx * inv * vt * d.spin,
                        0.25 * ring * env, 0));
            }
        }
        return acc;
    }

    @Override
    public boolean hasActive() { return !devils.isEmpty(); }

    @Override
    public int activeCount() { return devils.size(); }

    public List<DustDevil> devils() { return List.copyOf(devils); }
}
