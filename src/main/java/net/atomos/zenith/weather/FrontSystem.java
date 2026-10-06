package net.atomos.zenith.weather;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Random;

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

    public enum FrontType { COLD, WARM }

    public static final class Front {
        public FrontType type;
        public double x1, z1, x2, z2; // 锋线两端点（格）
        public double ageSeconds;
        public double lifetimeSeconds = 7200 + Math.random() * 7200;
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

        /** 法向量。约定：冷锋指暖区（锋前），暖锋指冷区——统一成"锋前"方向。 */
        public double[] normal() {
            double dx = x2 - x1, dz = z2 - z1;
            double len = Math.hypot(dx, dz);
            if (len < 1) return new double[]{1, 0};
            return new double[]{-dz / len, dx / len};
        }
    }

    private final List<Front> fronts = new ArrayList<>();
    private final Random random = new Random();
    private double spawnAccumulator = 0;

    @Override
    public void tick(ServerLevel level, double dtSeconds, WeatherContext ctx) {
        double stormAct = ctx.driver().stormActivity();

        // 强气旋催生锋面：气旋越强越容易甩出锋
        spawnAccumulator += dtSeconds * Math.max(0, stormAct - 0.35) * 0.01;
        while (spawnAccumulator >= 1.0 && fronts.size() < MAX_FRONTS) {
            spawnAccumulator -= 1.0;
            ServerPlayer anchor = pickAnchor(level);
            if (anchor == null) break;
            double ang = random.nextDouble() * Math.PI * 2;
            double dist = 500 + random.nextDouble() * 800;
            double cx = anchor.getX() + Math.cos(ang) * dist;
            double cz = anchor.getZ() + Math.sin(ang) * dist;
            var l1s = ctx.l1().sample(cx, 120, cz);
            double windDir = Math.atan2(l1s.windX(), l1s.windZ());

            Front f = new Front();
            f.type = random.nextDouble() < 0.55 ? FrontType.COLD : FrontType.WARM;
            // 锋线走向：大致垂直于引导气流
            double lineAng = windDir + Math.PI / 2 + (random.nextDouble() - 0.5) * 0.6;
            double halfLen = 250 + random.nextDouble() * 350;
            f.x1 = cx - Math.cos(lineAng) * halfLen;
            f.z1 = cz - Math.sin(lineAng) * halfLen;
            f.x2 = cx + Math.cos(lineAng) * halfLen;
            f.z2 = cz + Math.sin(lineAng) * halfLen;
            double steer = Math.hypot(l1s.windX(), l1s.windZ());
            double[] n = f.normal();
            // 冷锋往暖区拱，暖锋往冷区拱——移动方向正好相反
            double dir = f.type == FrontType.COLD ? 1 : -1;
            f.moveX = n[0] * steer * 0.55 * dir;
            f.moveZ = n[1] * steer * 0.55 * dir;
            f.intensity01 = 0.5 + stormAct * 0.5;
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
            if (f.type == FrontType.COLD) {
                // 冷锋：窄降水带 + 阵风；锋后风向顺转用沿法线的推力近似一下
                double gust = 6.0 * e;
                acc = acc.add(new WindContribution(
                        n[0] * gust * 0.6, 0.8 * e, n[1] * gust * 0.6,
                        0.3 * e, 0.75 * e));
            } else {
                // 暖锋：宽层状降水带，风很弱
                acc = acc.add(new WindContribution(
                        -n[0] * 1.5 * e, 0.3 * e, -n[1] * 1.5 * e,
                        0.12 * e, 0.5 * e));
            }
        }
        return acc;
    }

    @Override
    public boolean hasActive() { return !fronts.isEmpty(); }

    @Override
    public int activeCount() { return fronts.size(); }

    public List<Front> fronts() { return List.copyOf(fronts); }

    /** 供调试/指令。 */
    public void spawnDebug(double x, double z, boolean cold) {
        Front f = new Front();
        f.type = cold ? FrontType.COLD : FrontType.WARM;
        f.x1 = x - 300; f.z1 = z;
        f.x2 = x + 300; f.z2 = z;
        f.moveX = 0; f.moveZ = 2;
        fronts.add(f);
    }
}
