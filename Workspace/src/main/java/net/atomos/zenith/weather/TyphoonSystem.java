package net.atomos.zenith.weather;

import net.atomos.zenith.wind.HemisphereConvention;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * 台风：暖心涡旋 + 眼墙 + 螺旋雨带。
 *
 * 切向风按 Holland (1980) 廓线，形状参数 {@code B = 1.5}：
 * {@code V(r) = Vmax·sqrt((Rmax/r)^B·e·exp(-(Rmax/r)^B))}。
 * 该式在 {@code r = Rmax} 处等于 Vmax，在 {@code r → 0} 时平滑趋于 0（真静风眼），
 * 无折角也无硬截断。影响半径外缘用 smoothstep 平滑收敛（WX-TY-02）。
 * 眼墙（r≈Rmax）强上升 8–15 m/s + 暴雨，外面 3 条对数螺旋雨带转着扫。
 * 边界层入流角随半径变化（眼墙 25°、远处 5°）。
 * 台风跟着引导气流走（×0.8），外加 β 漂移（往极地方向偏西 ~1.5 m/s）；
 * 一旦登陆就指数减弱——没了暖洋面供能，撑不了多久。
 */
public class TyphoonSystem implements WeatherPhenomenon {
    public static final int MAX_TYPHOONS = 2;
    public static final int RAINBAND_ARMS = 3;
    /** Holland 廓线形状参数：1.0 = 很尖的眼墙，2.5 = 很宽的环流；1.5 居中。 */
    public static final double HOLLAND_B = 1.5;

    public static final class Typhoon {
        public double x, z;
        public double vmaxMps = 40;       // 最大切向风速
        public double rmaxBlocks = 55;    // 最大风速半径
        public double eyeRadiusBlocks = 18;
        public double influenceRadiusBlocks = 600;
        /**
         * 旋向：台风永远是低压，所以是 {@code coriolisSign × 1}。
         * 旧实现写死 {@code spin = 1}，而 MC 的 {@code (-dz, +dx)} 在 (x,z) 上其实是
         * **顺时针**（东侧点 → +Z 南），北半球台风于是反着转（WX-TY-01 / SYS-01）。
         */
        public double spin() {
            return HemisphereConvention.coriolisSign();
        }
        public double ageSeconds;
        public double intensity01 = 1.0;  // 登陆后衰减用
        public String name = "unnamed";
        /**
         * 台风所在位置的地表 y（AGL 基准）。由 {@code tick()} 每步更新；
         * 旧实现的高度衰减直接用绝对 y，导致高原上的台风一出生就弱（WX-TY-05）。
         */
        public double groundY = 64.0;

        /**
         * Holland (1980) 切向风廓线，形状参数固定 {@code B = 1.5}。
         *
         * <p>由 {@code Vmax = sqrt(B/ρ·Δp/e)} 反解出 {@code Δp}，代回 Holland 公式后
         * 只剩一个形状参数：
         * <pre>V(r) = Vmax · sqrt( (Rmax/r)^B · e · exp(-(Rmax/r)^B) )</pre>
         * 该式在 {@code r = Rmax} 处正好等于 {@code Vmax}，在 {@code r → 0} 时按
         * {@code exp(-(Rmax/r)^B / 2)} 平滑趋于 0（真正的静风眼），全程 {@code C∞}。
         *
         * <p>旧实现是 Rankine 组合涡：内区线性、外区 {@code (Rmax/r)^0.5}，在
         * {@code r = Rmax} 处导数跳变（折角），且 {@code r = eyeRadius} 被
         * {@code sample()} 里的 {@code continue} 直接砍断，形成 13 m/s 的台阶（WX-TY-02/WX-TY-04）。
         */
        public double tangential(double r) {
            if (r < 1e-3) return 0.0;
            double ratio = Math.pow(rmaxBlocks / r, HOLLAND_B);
            double shape = ratio * Math.E * Math.exp(-ratio);
            return vmaxMps * Math.sqrt(Math.max(0.0, shape)) * intensity01;
        }

        /**
         * 平滑外缘权重 [0,1]：{@code r ≤ 0.75·R} 为 1，之后用 smoothstep 在
         * {@code R} 处收敛到 0，没有硬截断（WX-TY-02）。
         * 旧实现 {@code if (r > influenceRadiusBlocks) continue;} 在 r=R 处
         * 把 12.1 m/s 直接归零，玩家穿过时瞬间失速。
         */
        public double outerTaper(double r) {
            double inner = influenceRadiusBlocks * 0.75;
            if (r <= inner) return 1.0;
            double t = (r - inner) / (influenceRadiusBlocks - inner);
            if (t >= 1.0) return 0.0;
            return 1.0 - t * t * (3.0 - 2.0 * t);
        }
    }

    private final List<Typhoon> typhoons = new ArrayList<>();
    private final ZenithRandom random;
    private double spawnAccumulator = 0;
    private int nameCounter = 0;
    private static final String[] NAMES = {
            "天鹅", "海燕", "凤凰", "麒麟", "玄武", "朱雀", "白虎", "青龙",
            "鲲鹏", "烛龙", "应龙", "鸾鸟", "毕方", "穷奇", "饕餮", "梼杌"
    };

    public TyphoonSystem() {
        this(0L);
    }

    public TyphoonSystem(long seed) {
        this.random = new ZenithRandom(seed);
    }

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
            // 高度衰减的 AGL 基准：跟着台风中心的地表走（WX-TY-05）
            t.groundY = ctx.terrain().heightAt(t.x, t.z);

            // 登陆减弱：没了洋面供能，指数衰减（时间常数 6 小时）。
            // 眼墙（r ≈ rmax 圈）仍在海上时只按半速衰减：真实的台风是"一半上岸"，
            // 核心仍能从海面取能（WX-TY-05）。
            boolean overLand = !ctx.terrain().isOceanAt(t.x, t.z);
            if (overLand) {
                boolean eyewallOverSea =
                        ctx.terrain().isOceanAt(t.x + t.rmaxBlocks, t.z)
                                || ctx.terrain().isOceanAt(t.x - t.rmaxBlocks, t.z)
                                || ctx.terrain().isOceanAt(t.x, t.z + t.rmaxBlocks)
                                || ctx.terrain().isOceanAt(t.x, t.z - t.rmaxBlocks);
                double timeConstant = eyewallOverSea ? 43200.0 : 21600.0;
                t.intensity01 *= Math.exp(-dtSeconds / timeConstant);
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
            // 不再硬截断影响半径、也不再挖掉眼区：
            // 外缘由 outerTaper 平滑收敛到 0，眼区由 Holland 廓线自然趋于 0（WX-TY-02）。
            double taper = t.outerTaper(r);
            if (taper <= 0) continue;

            double inv = r > 1e-3 ? 1.0 / r : 0;
            // 切向（气旋式）+ 径向内流（负号表示指向中心）
            double vt = t.tangential(r);
            // 入流角随半径变化：眼墙附近 25°、远处 5°（WX-TY-04）
            double inflowAngle = Math.toRadians(5.0 + 20.0 * Math.exp(-(r - t.rmaxBlocks) * (r - t.rmaxBlocks)
                    / (2.0 * t.rmaxBlocks * t.rmaxBlocks)));
            double vr = -vt * Math.tan(inflowAngle);
            // 切向单位向量统一走 HemisphereConvention：北半球低压逆时针，南半球顺时针。
            // 旧式 (-dz, +dx) 在 (x,z) 上是顺时针，等于把北半球台风转反了（WX-TY-01）。
            double[] tan = HemisphereConvention.tangential(dx, dz, true);
            double tx = tan[0] * inv, tz = tan[1] * inv;
            double rx = dx * inv, rz = dz * inv;
            double vx = tx * vt + rx * vr;
            double vz = tz * vt + rz * vr;

            // 台风是暖心结构：最强风在近地面（0–100 格 AGL），随离地高度衰减。
            // 旧实现用绝对 y（exp(-max(0, y-200)/400)），海平面 64 到 y=200 全满强度，
            // 而地表 200 的山地就从地面开始弱——与 SYS-03 同源（WX-TY-05）。
            double agl = y - t.groundY;
            double hDecay = Math.exp(-Math.max(0.0, agl - 100.0) / 300.0) * taper;

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
                    Math.min(0.8, turb * t.intensity01 * taper),
                    Math.min(1, precip * t.intensity01 * taper)));
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
     *
     * <p><b>只该作用于台风自身的贡献</b>，不能乘在总风上——真实台风眼里只是没有
     * 台风环流，背景风仍在。旧实现在 {@code ZenithServerRuntime} 里乘总风，
     * 把眼内的 L1 背景风也一起抹掉了（WX-TY-03）。
     *
     * <p>现在 Holland 廓线本身在 {@code r → 0} 时已经平滑趋于 0，这个函数只在
     * 需要"把残余环流再压一压"的地方使用，保留给调试与 API 查询。
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

    // 存档（SYS-08）

    @Override
    public String saveKey() { return "typhoons"; }

    @Override
    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putLong("rng", random.state());
        tag.putInt("nameCounter", nameCounter);
        tag.putDouble("spawnAccumulator", spawnAccumulator);
        tag.put("list", WeatherNbt.writeList(typhoons, t -> {
            CompoundTag c = new CompoundTag();
            c.putDouble("x", t.x);
            c.putDouble("z", t.z);
            c.putDouble("vmax", t.vmaxMps);
            c.putDouble("rmax", t.rmaxBlocks);
            c.putDouble("eyeRadius", t.eyeRadiusBlocks);
            c.putDouble("influenceRadius", t.influenceRadiusBlocks);
            c.putDouble("age", t.ageSeconds);
            c.putDouble("intensity", t.intensity01);
            c.putDouble("groundY", t.groundY);
            c.putString("name", t.name);
            return c;
        }));
        return tag;
    }

    @Override
    public void load(CompoundTag tag) {
        if (tag.contains("rng")) random.setState(tag.getLong("rng"));
        nameCounter = tag.getInt("nameCounter");
        spawnAccumulator = tag.getDouble("spawnAccumulator");
        typhoons.clear();
        typhoons.addAll(WeatherNbt.readList(tag, "list", c -> {
            Typhoon t = new Typhoon();
            t.x = c.getDouble("x");
            t.z = c.getDouble("z");
            t.vmaxMps = c.getDouble("vmax");
            t.rmaxBlocks = Math.max(1.0, c.getDouble("rmax"));
            t.eyeRadiusBlocks = c.getDouble("eyeRadius");
            t.influenceRadiusBlocks = Math.max(t.rmaxBlocks, c.getDouble("influenceRadius"));
            t.ageSeconds = c.getDouble("age");
            t.intensity01 = c.getDouble("intensity");
            t.groundY = c.getDouble("groundY");
            t.name = c.getString("name");
            return t;
        }));
        while (typhoons.size() > MAX_TYPHOONS) typhoons.remove(typhoons.size() - 1);
    }
}
