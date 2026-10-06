package net.atomos.zenith.weather;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Random;

/**
 * 飑线系统：线状强对流 + 下击暴流。
 *
 * <p>模型：</p>
 * <ul>
 *   <li>一条移动的对流线（线段），≤2 条/维度，长度 200–600 格</li>
 *   <li>线上有强降水核心带 + 频繁闪电</li>
 *   <li>线前阵风锋：径向外流 15–20 m/s（比单体更强更宽）</li>
 *   <li>下击暴流：线上随机嵌入强下沉核（−10 m/s，半径 30 格），触地外流</li>
 *   <li>生成需要高风暴活动度 + 强低层风切变</li>
 * </ul>
 */
public class SquallLineSystem implements WeatherPhenomenon {
    public static final int MAX_LINES = 2;

    public static final class Microburst {
        public double x, z;
        public double ageSeconds;
        public double lifetimeSeconds = 600;
        public double radiusBlocks = 30;

        double envelope() {
            double t = ageSeconds / lifetimeSeconds;
            if (t < 0.25) return t / 0.25;
            if (t > 0.6) return Math.max(0, 1 - (t - 0.6) / 0.4);
            return 1.0;
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

        public double distanceTo(double x, double z) {
            double dx = x2 - x1, dz = z2 - z1;
            double len2 = dx * dx + dz * dz;
            if (len2 < 1) return Math.hypot(x - x1, z - z1);
            double t = ((x - x1) * dx + (z - z1) * dz) / len2;
            t = Math.max(0, Math.min(1, t));
            return Math.hypot(x - (x1 + dx * t), z - (z1 + dz * t));
        }

        /** 线法向量（指向移动/阵风锋方向）。 */
        public double[] frontNormal() {
            double dx = x2 - x1, dz = z2 - z1;
            double len = Math.hypot(dx, dz);
            if (len < 1) return new double[]{1, 0};
            double nx = -dz / len, nz = dx / len;
            // 使法向量与移动方向一致
            if (nx * moveX + nz * moveZ < 0) {
                nx = -nx;
                nz = -nz;
            }
            return new double[]{nx, nz};
        }
    }

    private final List<SquallLine> lines = new ArrayList<>();
    private final Random random = new Random();
    private double spawnAccumulator = 0;

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
            var l1s = ctx.l1().sample(cx, 150, cz);
            if (stormAct < 0.6 && random.nextDouble() < 0.5) continue;

            SquallLine l = new SquallLine();
            double lineAng = Math.atan2(l1s.windX(), l1s.windZ()) + Math.PI / 2;
            double halfLen = 100 + random.nextDouble() * 200;
            l.x1 = cx - Math.cos(lineAng) * halfLen;
            l.z1 = cz - Math.sin(lineAng) * halfLen;
            l.x2 = cx + Math.cos(lineAng) * halfLen;
            l.z2 = cz + Math.sin(lineAng) * halfLen;
            l.moveX = l1s.windX() * 0.7;
            l.moveZ = l1s.windZ() * 0.7;
            l.intensity01 = 0.6 + stormAct * 0.4;
            lines.add(l);
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

            // 下击暴流生成/消亡
            if (random.nextDouble() < dtSeconds * 0.02 && l.microbursts.size() < 6) {
                double t = random.nextDouble();
                Microburst mb = new Microburst();
                mb.x = l.x1 + (l.x2 - l.x1) * t + (random.nextDouble() - 0.5) * 60;
                mb.z = l.z1 + (l.z2 - l.z1) * t + (random.nextDouble() - 0.5) * 60;
                mb.lifetimeSeconds = 300 + random.nextDouble() * 600;
                l.microbursts.add(mb);
            }
            l.microbursts.removeIf(mb -> {
                mb.ageSeconds += dtSeconds;
                return mb.ageSeconds >= mb.lifetimeSeconds;
            });

            // 闪电
            l.lightningTimer -= dtSeconds;
            if (l.lightningTimer <= 0) {
                l.lightningTimer = 2 + random.nextDouble() * 6;
                strikeLightning(level, l);
            }
        }
    }

    private void strikeLightning(ServerLevel level, SquallLine l) {
        double t = random.nextDouble();
        int bx = (int) (l.x1 + (l.x2 - l.x1) * t + (random.nextDouble() - 0.5) * 80);
        int bz = (int) (l.z1 + (l.z2 - l.z1) * t + (random.nextDouble() - 0.5) * 80);
        var surface = level.getHeightmapPos(
                net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE,
                new net.minecraft.core.BlockPos(bx, 0, bz));
        var bolt = net.minecraft.world.entity.EntityType.LIGHTNING_BOLT.create(level);
        if (bolt == null) return;
        bolt.moveTo(surface.getX(), surface.getY(), surface.getZ());
        level.addFreshEntity(bolt);
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
            // 锋前符号：点在线法向一侧为正
            double t = projectionT(l, x, z);
            double px = l.x1 + (l.x2 - l.x1) * t, pz = l.z1 + (l.z2 - l.z1) * t;
            double ahead = (x - px) * n[0] + (z - pz) * n[1];
            double e = l.intensity01;

            if (d < 90) {
                // 对流线核心：强降水 + 湍流
                double core = Math.exp(-(d * d) / (60 * 60));
                acc = acc.add(new WindContribution(0, -2.0 * core * e, 0,
                        0.5 * core * e, 0.95 * e));
            }
            if (ahead > 0 && ahead < 160) {
                // 阵风锋外流
                double gust = 18.0 * Math.exp(-ahead / 90.0) * e;
                acc = acc.add(new WindContribution(
                        n[0] * gust, 0.3 * gust * 0.3, n[1] * gust,
                        0.4 * Math.exp(-ahead / 90.0) * e, 0.3 * e));
            }
            // 下击暴流
            for (Microburst mb : l.microbursts) {
                double mdx = x - mb.x, mdz = z - mb.z;
                double mr = Math.sqrt(mdx * mdx + mdz * mdz);
                if (mr > mb.radiusBlocks * 2.5) continue;
                double menv = mb.envelope() * e;
                if (mr < mb.radiusBlocks) {
                    double core = 1 - mr / mb.radiusBlocks;
                    // 强下沉
                    acc = acc.add(new WindContribution(0, -10.0 * core * menv, 0,
                            0.5 * core * menv, 0.6 * menv));
                } else {
                    // 触地外流
                    double ring = (mr - mb.radiusBlocks) / (mb.radiusBlocks * 1.5);
                    double out = 14.0 * Math.sin(Math.min(1, ring) * Math.PI) * menv;
                    double inv = mr > 0.5 ? 1.0 / mr : 0;
                    acc = acc.add(new WindContribution(
                            mdx * inv * out, 0, mdz * inv * out,
                            0.35 * menv, 0.2 * menv));
                }
            }
        }
        return acc;
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
}
