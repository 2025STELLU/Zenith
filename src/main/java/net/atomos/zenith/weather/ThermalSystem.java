package net.atomos.zenith.weather;

import net.atomos.zenith.api.ZenithTerrainSurfaceClass;
import net.atomos.zenith.wind.NoiseUtil;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Random;

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
        public double topY;

        double envelope() {
            double t = ageSeconds / lifetimeSeconds;
            if (t < 0.15) return t / 0.15;
            if (t > 0.75) return Math.max(0, 1 - (t - 0.75) / 0.25);
            return 1.0;
        }
    }

    private final List<Thermal> thermals = new ArrayList<>();
    private final Random random = new Random();
    private double spawnAccumulator = 0;

    @Override
    public void tick(ServerLevel level, double dtSeconds, WeatherContext ctx) {
        // timeOfDay01 里 0.25=日出、0.5=正午，正弦近似即可
        double solar = Math.sin(ctx.timeOfDay01() * Math.PI * 2.0 - Math.PI / 2.0);
        solar = Math.max(0, solar) * (ctx.raining() ? 0.25 : 1.0)
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
            t.topY = ctx.terrain().heightAt(ax, az) + 120 + random.nextDouble() * 120;
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
            var s = ctx.l1().sample(t.x, ctx.terrain().heightAt(t.x, t.z) + 40, t.z);
            t.x += s.windX() * dtSeconds * 0.7;
            t.z += s.windZ() * dtSeconds * 0.7;
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
            double groundY = 60; // 偷懒用 60 近似，运行时换真实地形
            double topY = t.topY;
            if (y < groundY || y > topY) continue;
            double env = t.envelope();
            if (r < t.radiusBlocks) {
                double core = 1 - (r / t.radiusBlocks) * (r / t.radiusBlocks);
                double w = t.updraftMps * core * env;
                double conv = 0.4 * core * env; // 柱内轻微向里收
                double inv = r > 0.5 ? 1.0 / r : 0;
                acc = acc.add(new WindContribution(
                        -dx * inv * conv, w, -dz * inv * conv, 0.15 * core * env, 0));
            } else if (r < t.radiusBlocks * 2.2) {
                // 下沉补偿环：柱子抽上去的空气，得在外圈沉回来
                double ring = (r - t.radiusBlocks) / (t.radiusBlocks * 1.2);
                double sink = -0.5 * Math.sin(ring * Math.PI) * env;
                acc = acc.add(new WindContribution(0, sink, 0, 0.05 * env, 0));
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
        t.topY = groundY + 180;
        thermals.add(t);
    }
}
