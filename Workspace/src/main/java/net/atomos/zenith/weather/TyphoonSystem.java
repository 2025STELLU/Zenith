package net.atomos.zenith.weather;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Random;

/**
 * 台风：暖心涡旋 + 眼墙 + 螺旋雨带。
 *
 * 切向风按 Rankine 组合涡旋：眼墙内（r&lt;Rmax）Vmax·r/Rmax 往上爬，
 * 眼墙外按 Vmax·(Rmax/r)^0.5 往下掉。眼区静风（走 {@link #eyeDampening}，
 * 对总风做乘法衰减），眼墙（r≈Rmax）强上升 8–15 m/s + 暴雨，
 * 外面 3 条对数螺旋雨带转着扫。边界层径向内流按切向风的 15% 算。
 * 台风跟着引导气流走（×0.8），外加 β 漂移（往极地方向偏西 ~1.5 m/s）；
 * 一旦登陆就指数减弱——没了暖洋面供能，撑不了多久。
 */
public class TyphoonSystem implements WeatherPhenomenon {
    public static final int MAX_TYPHOONS = 2;
    public static final int RAINBAND_ARMS = 3;

    public static final class Typhoon {
        public double x, z;
        public double vmaxMps = 40;       // 最大切向风速
        public double rmaxBlocks = 55;    // 最大风速半径
        public double eyeRadiusBlocks = 18;
        public double influenceRadiusBlocks = 600;
        public double spin = 1;           // 北半球气旋式，逆时针
        public double ageSeconds;
        public double intensity01 = 1.0;  // 登陆后衰减用
        public String name = "unnamed";

        /** Rankine 组合涡旋的切向风速廓线（m/s）。 */
        public double tangential(double r) {
            double v;
            if (r < rmaxBlocks) v = vmaxMps * r / rmaxBlocks;
            else v = vmaxMps * Math.pow(rmaxBlocks / r, 0.5);
            return v * intensity01;
        }
    }

    private final List<Typhoon> typhoons = new ArrayList<>();
    private final Random random = new Random();
    private double spawnAccumulator = 0;
    private int nameCounter = 0;
    private static final String[] NAMES = {
            "天鹅", "海燕", "凤凰", "麒麟", "玄武", "朱雀", "白虎", "青龙",
            "鲲鹏", "烛龙", "应龙", "鸾鸟", "毕方", "穷奇", "饕餮", "梼杌"
    };

    @Override
    public void tick(ServerLevel level, double dtSeconds, WeatherContext ctx) {
        double stormAct = ctx.driver().stormActivity();

        // 生成：洋面 + 高风暴活动度
        spawnAccumulator += dtSeconds * Math.max(0, stormAct - 0.6) * 0.008;
        while (spawnAccumulator >= 1.0 && typhoons.size() < MAX_TYPHOONS) {
            spawnAccumulator -= 1.0;
            ServerPlayer anchor = pickAnchor(level);
            if (anchor == null) break;
            // 在玩家 800–1500 格外的洋面生成
            boolean spawned = false;
            for (int attempt = 0; attempt < 12 && !spawned; attempt++) {
                double ang = random.nextDouble() * Math.PI * 2;
                double dist = 800 + random.nextDouble() * 700;
                double cx = anchor.getX() + Math.cos(ang) * dist;
                double cz = anchor.getZ() + Math.sin(ang) * dist;
                if (!ctx.terrain().isOceanAt(cx, cz)) continue;
                Typhoon t = new Typhoon();
                t.x = cx; t.z = cz;
                t.vmaxMps = 25 + random.nextDouble() * 35;
                t.rmaxBlocks = 30 + random.nextDouble() * 50;
                t.eyeRadiusBlocks = 12 + random.nextDouble() * 13;
                t.influenceRadiusBlocks = 400 + random.nextDouble() * 400;
                t.name = NAMES[nameCounter++ % NAMES.length];
                typhoons.add(t);
                spawned = true;
            }
        }

        // 推进
        Iterator<Typhoon> it = typhoons.iterator();
        while (it.hasNext()) {
            Typhoon t = it.next();
            t.ageSeconds += dtSeconds;

            // 引导气流 + β 漂移
            var l0s = ctx.l0().sample(t.x, t.z);
            double steerX = l0s.windX() * 0.8;
            double steerZ = l0s.windZ() * 0.8;
            // β 漂移：往极地方向偏西（约定 -z 为北）
            double betaX = -1.0, betaZ = -1.1;
            t.x += (steerX + betaX) * dtSeconds;
            t.z += (steerZ + betaZ) * dtSeconds;

            // 登陆减弱：没了洋面供能，指数衰减（时间常数 6 小时）
            boolean overLand = !ctx.terrain().isOceanAt(t.x, t.z);
            if (overLand) {
                t.intensity01 *= Math.exp(-dtSeconds / 21600.0);
            } else if (t.intensity01 < 1.0) {
                // 回到洋面慢慢回血，上限 0.85
                t.intensity01 = Math.min(0.85, t.intensity01 + dtSeconds / 43200.0);
            }

            // 30 天后强制消散，别让它赖着不走
            if (t.ageSeconds > 2_592_000 || t.intensity01 < 0.12) it.remove();
        }
    }

    private ServerPlayer pickAnchor(ServerLevel level) {
        var players = level.players();
        return players.isEmpty() ? null : players.get(random.nextInt(players.size()));
    }

    @Override
    public WindContribution sample(double x, double y, double z, double baseWindX, double baseWindZ) {
        WindContribution acc = WindContribution.NONE;
        for (Typhoon t : typhoons) {
            double dx = x - t.x, dz = z - t.z;
            double r = Math.sqrt(dx * dx + dz * dz);
            if (r > t.influenceRadiusBlocks) continue;
            if (r < t.eyeRadiusBlocks) continue; // 眼区由 eyeDampening 处理

            double inv = r > 1e-3 ? 1.0 / r : 0;
            // 切向（气旋式）+ 径向内流（负号表示指向中心）
            double vt = t.tangential(r);
            double vr = -vt * 0.15;
            double tx = -dz * inv * t.spin, tz = dx * inv * t.spin;
            double rx = dx * inv, rz = dz * inv;
            double vx = tx * vt + rx * vr;
            double vz = tz * vt + rz * vr;

            // 台风是深厚系统，300 格以上才明显衰减
            double hDecay = Math.exp(-Math.max(0, y - 200) / 400.0);

            double vy = 0, turb = 0.12, precip = 0.25;
            // 眼墙：r≈Rmax 强上升 + 暴雨
            double wallDist = Math.abs(r - t.rmaxBlocks) / (t.rmaxBlocks * 0.35);
            if (wallDist < 2.0) {
                double wall = Math.exp(-wallDist * wallDist);
                vy += 12.0 * wall * t.intensity01;
                turb += 0.4 * wall;
                precip = Math.max(precip, 0.95);
            }
            // 螺旋雨带：3 条对数螺旋臂
            double band = rainbandFactor(t, dx, dz, r);
            if (band > 0) {
                vy += 3.5 * band * t.intensity01;
                turb += 0.25 * band;
                precip = Math.max(precip, 0.55 * band + 0.2);
            }

            acc = acc.add(new WindContribution(
                    vx * hDecay, vy * hDecay, vz * hDecay,
                    Math.min(0.8, turb * t.intensity01), Math.min(1, precip * t.intensity01)));
        }
        return acc;
    }

    /** 对数螺旋雨带因子 [0,1]。 */
    private double rainbandFactor(Typhoon t, double dx, double dz, double r) {
        if (r < t.rmaxBlocks * 1.2) return 0;
        double theta = Math.atan2(dz, dx);
        // 螺旋臂相位随半径外旋，随时间缓慢旋转
        double spiral = theta - Math.log(r / t.rmaxBlocks) * 1.8 + t.ageSeconds * 0.004;
        double best = 0;
        for (int arm = 0; arm < RAINBAND_ARMS; arm++) {
            double phase = spiral - arm * 2 * Math.PI / RAINBAND_ARMS;
            phase = Math.atan2(Math.sin(phase), Math.cos(phase));
            double g = Math.exp(-phase * phase / 0.18);
            best = Math.max(best, g);
        }
        // 雨带只在眼墙外一圈环带里有效
        double ring = Math.exp(-Math.pow((r - t.rmaxBlocks * 3.2) / (t.rmaxBlocks * 2.6), 2));
        return best * ring;
    }

    /**
     * 眼区静风衰减系数 [0,1]：1 表示完全静风。
     * 调用方拿这个对总风做乘法——眼区里风得掐掉。
     */
    public double eyeDampening(double x, double z) {
        double damp = 0;
        for (Typhoon t : typhoons) {
            double r = Math.hypot(x - t.x, z - t.z);
            if (r < t.eyeRadiusBlocks) {
                double f = 1 - r / t.eyeRadiusBlocks;
                damp = Math.max(damp, f * f * 0.92 * t.intensity01);
            }
        }
        return damp;
    }

    @Override
    public boolean hasActive() { return !typhoons.isEmpty(); }

    @Override
    public int activeCount() { return typhoons.size(); }

    public List<Typhoon> typhoons() { return List.copyOf(typhoons); }

    /** 供调试/指令：手动生成台风。 */
    public void spawnDebug(double x, double z) {
        Typhoon t = new Typhoon();
        t.x = x; t.z = z;
        t.vmaxMps = 45;
        t.name = NAMES[nameCounter++ % NAMES.length];
        typhoons.add(t);
    }
}
