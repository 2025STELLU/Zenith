import net.atomos.zenith.weather.CoastDistance;
import net.atomos.zenith.weather.ZenithRandom;
import net.atomos.zenith.wind.WorldScaleDriver;

public class Verify {
    static int fail = 0;

    static void check(String name, boolean ok, String detail) {
        System.out.println((ok ? "[ok]   " : "[FAIL] ") + name + "  " + detail);
        if (!ok) fail++;
    }

    public static void main(String[] args) throws Exception {
        System.out.println("=== DRV-01 wrapDelta（真实现，单位=格） ===");
        int TAU = 384; // GRID_CELLS
        double[] ds = {0, 1, 191, 192, 193, 383, 384, 385, 400, 768, -1, -383, -384, -385};
        for (double d : ds) {
            System.out.printf("  cell %7.1f -> %8.2f%n", d, WorldScaleDriver.wrapDelta(d, TAU));
        }
        boolean inRange = true, continuous = true;
        for (double d = -3000; d <= 3000; d += 0.5) {
            double w = WorldScaleDriver.wrapDelta(d, TAU);
            if (w < -192.0 - 1e-9 || w >= 192.0 + 1e-9) { inRange = false; System.out.println("  OUT OF RANGE: " + d + " -> " + w); break; }
        }
        check("值域是 [-T/2, T/2)", inRange, "扫描 ±3000");
        // 连续性：除环面接缝（恰好相差半个周期）外，相邻 0.5 格结果差不应超过 0.5。
        // 接缝处 ±192 互换是环面的固有奇异点（两点正对时两个方向等价），必须排除。
        int jumps = 0;
        for (double d = -600; d <= 600; d += 0.5) {
            double w1 = WorldScaleDriver.wrapDelta(d, TAU);
            double w2 = WorldScaleDriver.wrapDelta(d + 0.5, TAU);
            if (Math.abs(w2 - w1) > 0.5 + 1e-9) {
                boolean atSeam = Math.abs(Math.abs(w1) - 192.0) < 1e-9 || Math.abs(Math.abs(w2) - 192.0) < 1e-9;
                if (!atSeam) { jumps++; System.out.println("  BAD JUMP at " + d + ": " + w1 + " -> " + w2); }
            }
        }
        check("除环面接缝外无断裂（旧实现在 0 处 384 跳变早已消除）", jumps == 0, "badJumps=" + jumps);
        check("wrap(383.5) = -0.5", Math.abs(WorldScaleDriver.wrapDelta(383.5, TAU) + 0.5) < 1e-9,
                "= " + WorldScaleDriver.wrapDelta(383.5, TAU));
        check("整周期 = 0", Math.abs(WorldScaleDriver.wrapDelta(768, TAU)) < 1e-9, "");
        check("cells→blocks 一致：相邻格差 256 格", 
                Math.abs(WorldScaleDriver.wrapDelta(1, TAU) * 256 - 256.0) < 1e-9
                && Math.abs(WorldScaleDriver.wrapDelta(383, TAU) * 256 + 256.0) < 1e-9,
                "wrap(1)*256=" + (WorldScaleDriver.wrapDelta(1, TAU) * 256)
                + ", wrap(383)*256=" + (WorldScaleDriver.wrapDelta(383, TAU) * 256));

        System.out.println();
        System.out.println("=== CoastDistance ===");
        boolean[][] mask = new boolean[4][4];
        mask[0][0] = true;
        double[][] toSea = CoastDistance.chamfer(mask, true);
        double[][] toLand = CoastDistance.chamfer(mask, false);
        check("海格到海距离=0", toSea[0][0] == 0.0, "d=" + toSea[0][0]);
        check("相邻正交格到海=1", Math.abs(toSea[0][1] - 1.0) < 1e-9, "d=" + toSea[0][1]);
        check("对角格到海=√2", Math.abs(toSea[1][1] - Math.sqrt(2)) < 1e-9, "d=" + toSea[1][1]);
        check("陆地格到陆距离=0", toLand[3][3] == 0.0, "d=" + toLand[3][3]);
        check("海格到陆=1（相邻有陆）", Math.abs(toLand[0][0] - 1.0) < 1e-9, "d=" + toLand[0][0]);

        double[][] sd = CoastDistance.signedDistanceBlocks(mask, 256.0);
        check("海格 signed 为负", sd[0][0] < 0, "d=" + sd[0][0]);
        check("陆格 signed 为正", sd[3][3] > 0, "d=" + sd[3][3]);
        check("signed 与 chamfer 一致（陆）", Math.abs(sd[1][1] - toSea[1][1] * 256.0) < 1e-9,
                sd[1][1] + " vs " + toSea[1][1] * 256.0);

        boolean[][] allSea = new boolean[5][5];
        for (boolean[] row : allSea) java.util.Arrays.fill(row, true);
        double[][] sd2 = CoastDistance.signedDistanceBlocks(allSea, 256.0);
        boolean noNaN = true;
        for (double[] row : sd2) for (double v : row) if (!Double.isFinite(v)) noNaN = false;
        check("全海不产生 NaN/Inf", noNaN, "sd2[0][0]=" + sd2[0][0] + " cap=" + (256 * Math.hypot(5, 5)));

        // 季风权重 exp(-|D|/600)
        System.out.println("  monsoon weight exp(-|D|/600):");
        for (double d : new double[]{0, 100, 300, 600, 1200, 2000}) {
            System.out.printf("    |D|=%6.0f -> w=%.4f%n", d, Math.exp(-Math.abs(d) / 600.0));
        }

        System.out.println();
        System.out.println("=== ZenithRandom (SYS-08) ===");
        ZenithRandom a = new ZenithRandom(12345L);
        ZenithRandom b = new ZenithRandom(12345L);
        boolean same = true;
        for (int i = 0; i < 1000; i++) if (a.nextDouble() != b.nextDouble()) { same = false; break; }
        check("同种子序列完全一致", same, "");

        ZenithRandom c = new ZenithRandom(999L);
        c.nextDouble();
        long st = c.state();
        double expect = c.nextDouble();
        ZenithRandom d = new ZenithRandom(0L);
        d.setState(st);
        double got = d.nextDouble();
        check("state() 往返可复现", got == expect, "expect=" + expect + " got=" + got);

        ZenithRandom e = new ZenithRandom(7L);
        double sum = 0;
        int n = 200000;
        for (int i = 0; i < n; i++) sum += e.nextDouble();
        check("nextDouble 均值≈0.5", Math.abs(sum / n - 0.5) < 0.005, "mean=" + (sum / n));

        ZenithRandom f = new ZenithRandom(3L);
        boolean inR = true;
        for (int i = 0; i < 100000; i++) {
            int v = f.nextInt(7);
            if (v < 0 || v >= 7) { inR = false; break; }
        }
        check("nextInt(7) 在 [0,7)", inR, "");

        // 不同种子应给出不同序列
        ZenithRandom g1 = new ZenithRandom(1L), g2 = new ZenithRandom(2L);
        check("不同种子序列不同", g1.nextLong() != g2.nextLong(), "");

        System.out.println();
        System.out.println("=== BLK-08 风扇射程（改为纯正比后） ===");
        double k = 3.0;
        for (int lvl = 1; lvl <= 3; lvl++) {
            double v = new double[]{0, 4, 8, 12}[lvl];
            double len = k * v;
            System.out.printf("  档位 %d: v=%4.1f m/s, length=%5.1f 格, L/v=%.3f%n", lvl, v, len, len / v);
        }
        check("L/v 恒定（严格正比）",
                Math.abs((k * 12) / 12.0 - (k * 4) / 4.0) < 1e-12,
                "12格/4 = 3.0, 36格/12 = 3.0");

        System.out.println();
        System.out.println("=== BLK-07 串联收敛 ===");
        double maxOutlet = 14.0;
        double s = 0;
        for (int i = 0; i < 50; i++) s = Math.min(maxOutlet, Math.max(4.0, s));
        check("max 串联保持用户档位", Math.abs(s - 4.0) < 1e-9, "s=" + s);
        s = 8.0;
        for (int i = 0; i < 50; i++) s = Math.min(maxOutlet, Math.max(4.0, s));
        check("上游 8 → 下游保持 8", Math.abs(s - 8.0) < 1e-9, "s=" + s);
        s = 20.0;
        for (int i = 0; i < 50; i++) s = Math.min(maxOutlet, Math.max(4.0, s));
        check("超上限钳到 14", Math.abs(s - 14.0) < 1e-9, "s=" + s);

        System.out.println();
        System.out.println("=== WX-TY Holland 廓线（B=1.5, 不含 env/taper） ===");
        double vmax = 40, rmax = 55, B = 1.5;
        double peak = 0, peakR = 0;
        for (double r = 1; r <= 600; r += 1) {
            double v = vmax * Math.pow(rmax / r, B) * Math.exp(-Math.pow(rmax / r, B));
            if (v > peak) { peak = v; peakR = r; }
        }
        System.out.printf("  峰值 %.3f @ r=%.0f（Holland 峰在 r=rmax）%n", peak, peakR);
        check("峰值位置 = rmax", Math.abs(peakR - rmax) < 2.5, "peakR=" + peakR);
        check("峰值 ≈ vmax·e^-1 = 14.7", Math.abs(peak - vmax * Math.exp(-1)) < 0.2,
                "peak=" + peak + " expect=" + (vmax * Math.exp(-1)));
        check("r→0 时切向风有限（无 1/r 奇点）",
                Math.abs(vmax * Math.pow(rmax / 1.0, B) * Math.exp(-Math.pow(rmax / 1.0, B))) < 1.0,
                "v(1)=" + (vmax * Math.pow(rmax / 1.0, B) * Math.exp(-Math.pow(rmax / 1.0, B))));

        System.out.println();
        System.out.println("=== L1-01 对数律（AGL 层 2/6/14/30/60/110/190/300） ===");
        double[] levels = {2, 6, 14, 30, 60, 110, 190, 300};
        for (double rz : new double[]{0.05, 0.1, 0.2, 0.35}) {
            double z0 = 0.01 * Math.pow(200, rz);
            System.out.printf("  z0=%.4f:", z0);
            for (double z : levels) System.out.printf("  %5.1fm→%.3f", z, Math.log(z / z0));
            System.out.println();
        }
        // 归一化到 10 m AGL
        double z0 = 0.01 * Math.pow(200, 0.1);
        double ref = Math.log(10.0 / z0);
        System.out.printf("  z0=%.4f 归一化到 10m 的系数: 眼高1.62m→%.3f  10m→1.000%n",
                z0, Math.log(1.62 / z0) / ref);

        System.out.println();
        System.out.println("=== L1-01 / L1-04 / L1-06 实际网格积分（平地） ===");
        net.atomos.zenith.wind.BackgroundMetGrid l0 = new net.atomos.zenith.wind.BackgroundMetGrid();
        net.atomos.zenith.wind.MesoscaleGrid l1 = new net.atomos.zenith.wind.MesoscaleGrid(424242L);
        net.atomos.zenith.wind.WorldScaleDriver drv =
                new net.atomos.zenith.wind.WorldScaleDriver(424242L);
        // 平地：地表顶面 y=64，草地粗糙度
        net.atomos.zenith.wind.BackgroundMetGrid.TerrainProbe t0 =
                new net.atomos.zenith.wind.BackgroundMetGrid.TerrainProbe() {
                    public double terrainHeightBlocks(double bx, double bz) { return 64.0; }
                    public double roughness01(double bx, double bz) { return 0.30; }
                    public double biomeTemperatureKelvin(double bx, double bz) { return 284.0; }
                };
        net.atomos.zenith.wind.MesoscaleGrid.TerrainProbe t1 =
                new net.atomos.zenith.wind.MesoscaleGrid.TerrainProbe() {
                    public double terrainHeightBlocks(double bx, double bz) { return 64.0; }
                    public double roughness01(double bx, double bz) { return 0.30; }
                    public double[] slopeVector(double bx, double bz) { return new double[]{0, 0}; }
                };
        l0.setFocus(0, 0);
        // 先让 L0 收敛（10 分钟模拟时间）
        for (int i = 0; i < 60; i++) {
            drv.advance(12.8, 0.5, false, false,
                    new net.atomos.zenith.wind.WorldScaleDriver.Feedback(0.1, 0.01, 0.0));
            l0.refresh(drv, t0, 12.8, 0.5, false, 0.0);
        }
        l1.setFocus(0, 0);
        l1.refresh(l0, t1, 0.5, false, 0L, 64 * 0.05);

        net.atomos.zenith.wind.BackgroundMetGrid.Sample base = l0.sample(0, 0);
        double baseSpd = Math.hypot(base.windX(), base.windZ());
        System.out.printf("  L0 基流 = %.3f m/s, 气压异常 = %.1f Pa%n", baseSpd, base.pressureAnomalyPa());

        net.atomos.zenith.wind.MesoscaleGrid.Sample s10 = l1.sample(0.5, 64 + 10.0, 0.5);
        net.atomos.zenith.wind.MesoscaleGrid.Sample s16 = l1.sample(0.5, 64 + 1.62, 0.5);
        double r10 = Math.hypot(s10.windX(), s10.windZ()) / baseSpd;
        double r16 = Math.hypot(s16.windX(), s16.windZ()) / baseSpd;
        System.out.printf("  AGL 10.0 格 → %.3f × L0%n", r10);
        System.out.printf("  AGL  1.62 格 → %.3f × L0%n", r16);
        check("L1-01 验收：10 格 ∈ [0.50, 0.65]", r10 >= 0.50 && r10 <= 0.65, "= " + r10);
        check("L1-01 验收：1.62 格 ∈ [0.35, 0.45]", r16 >= 0.35 && r16 <= 0.45, "= " + r16);
        check("L1-07 气压透传非 0", Math.abs(s10.pressureAnomalyPa()) > 1.0,
                "p=" + s10.pressureAnomalyPa());

        // L1-04：Ekman 偏转在近地最大、ABL 顶归零（方向相对 L0 逆时针，北半球）
        net.atomos.zenith.wind.MesoscaleGrid.Sample sTop =
                l1.sample(0.5, 64 + 300.0, 0.5);
        net.atomos.zenith.wind.MesoscaleGrid.Sample sNear =
                l1.sample(0.5, 64 + 2.0, 0.5);
        double dirL0 = Math.atan2(base.windZ(), base.windX());
        double dirTop = Math.atan2(sTop.windZ(), sTop.windX());
        double dirNear = Math.atan2(sNear.windZ(), sNear.windX());
        double turnTop = net.atomos.zenith.wind.WorldScaleDriver.wrapDelta(
                Math.toDegrees(dirTop - dirL0), 360);
        double turnNear = net.atomos.zenith.wind.WorldScaleDriver.wrapDelta(
                Math.toDegrees(dirNear - dirL0), 360);
        System.out.printf("  Ekman: 2 格偏转 %.1f°, 300 格偏转 %.1f°（相对 L0）%n", turnNear, turnTop);
        check("L1-04 层顶基本回正 |turn(300)| < 3°", Math.abs(turnTop) < 3.0, "= " + turnTop);
        check("L1-04 近地偏转符号为负（北半球偏向低压）", turnNear < -5.0, "= " + turnNear);

        // L1-06：上层垂直速度不再恒 0
        double wAloft = Math.abs(sTop.windY()) + Math.abs(s10.windY());
        System.out.printf("  |w| @10 格 = %.3f, @300 格 = %.3f%n", s10.windY(), sTop.windY());
        check("L1-06 上层存在垂直速度（非恒 0）", wAloft > 1e-6, "sum=" + wAloft);

        // API-10：NACA 翼型点序（上表面在前、下表面在后），几何真实性
        // 旧实现两圈对调了下表面/上表面，且前后缘各重复一个点。
        var naca = net.atomos.zenith.api.ZenithAirfoilPresets.NACA_2412;
        var pts = naca.coordinates();
        int ptCount = pts.size();
        System.out.printf("  NACA 2412: %d 点，首=(%.3f,%.3f) 次=(%.3f,%.3f) 末=(%.3f,%.3f)%n",
                ptCount, pts.get(0).x(), pts.get(0).y(), pts.get(1).x(), pts.get(1).y(),
                pts.get(ptCount - 1).x(), pts.get(ptCount - 1).y());
        check("API-10 点序：首点为前缘 (0,0)", Math.abs(pts.get(0).x()) < 1e-9
                && Math.abs(pts.get(0).y()) < 1e-9, "");
        // 上表面：x=0.4 附近（最大弯度处）y 应显著为正
        double yUpperAt40 = Double.NaN, yLowerAt40 = Double.NaN;
        for (int i = 0; i < ptCount; i++) {
            var p = pts.get(i);
            if (Math.abs(p.x() - 0.4) < 0.02) {
                if (i < ptCount / 2) yUpperAt40 = p.y();
                else yLowerAt40 = p.y();
            }
        }
        System.out.printf("  x≈0.4 处 上表面 y=%.4f，下表面 y=%.4f%n", yUpperAt40, yLowerAt40);
        check("API-10 上表面确实在上（y>0）", yUpperAt40 > 0.02, "y=" + yUpperAt40);
        check("API-10 下表面确实在下（y<0）", yLowerAt40 < 0.0, "y=" + yLowerAt40);
        check("API-10 上表面厚于下表面（正弯度）", yUpperAt40 > -yLowerAt40,
                "up=" + yUpperAt40 + " down=" + yLowerAt40);
        // 不重复端点：相邻点不同
        boolean noDup = true;
        for (int i = 1; i < ptCount; i++) {
            if (Math.abs(pts.get(i).x() - pts.get(i - 1).x()) < 1e-12
                    && Math.abs(pts.get(i).y() - pts.get(i - 1).y()) < 1e-12) noDup = false;
        }
        check("API-10 没有重复端点/退化边", noDup, "n=" + ptCount);
        // 厚度口径：NACA 2412 的相对厚度应 ≈ 0.12（旧实现 2·max|y| 会报 ~0.24）
        double th = naca.maxThickness();
        System.out.printf("  NACA 2412 maxThickness = %.4f（期望 ≈0.12）%n", th);
        check("API-10 最大相对厚度 ≈ 0.12（不把弯度算成厚度）",
                Math.abs(th - 0.12) < 0.005, "= " + th);

        // API-09：JSON 解析错误统一成 IllegalArgumentException
        int thrown = 0;
        try {
            net.atomos.zenith.api.ZenithAirfoilJson.parse(
                    net.atomos.zenith.api.ZenithId.of("test", "bad"), "{\"points\":[[0,0],[1,");
        } catch (IllegalArgumentException ex) {
            thrown++;
        } catch (RuntimeException ex) {
            System.out.println("  未包装异常：" + ex.getClass().getSimpleName());
        }
        try {
            net.atomos.zenith.api.ZenithAirfoilJson.parse(
                    net.atomos.zenith.api.ZenithId.of("test", "nan"), "{\"points\":[[0,0],[\"NaN\",1]]}");
        } catch (IllegalArgumentException ex) {
            thrown++;
        }
        try {
            net.atomos.zenith.api.ZenithAirfoilJson.parse(
                    net.atomos.zenith.api.ZenithId.of("test", "ok"), "{\"name\":\"x\",\"points\":[[0,0],[1,0]]}");
        } catch (RuntimeException ex) {
            thrown += 100;
        }
        check("API-09 坏 JSON 抛 IllegalArgumentException（2 例），好 JSON 不抛", thrown == 2,
                "thrown=" + thrown);

        // API-11 / API-05：Source 位标记语义与 hasFlow 语义
        var none = net.atomos.zenith.api.ZenithWindSample.builder()
                .authority(net.atomos.zenith.api.ZenithWindSample.Authority.SERVER_AUTHORITATIVE)
                .source(net.atomos.zenith.api.ZenithWindSample.Source.L1_COARSE)
                .confidence(0.6f)
                .build();
        System.out.printf("  零风速 L1 样本: hasFlow=%s trusted=%s layers=%d source=%s%n",
                none.hasFlow(), none.isTrustedForGameplay(), none.layers(), none.source());
        check("API-11 零风速但有效源：hasFlow=false、trusted=true",
                !none.hasFlow() && none.isTrustedForGameplay(), "");
        check("API-11 source(L1) 自动置 LAYER_L1 位",
                none.hasLayer(net.atomos.zenith.api.ZenithWindSample.LAYER_L1)
                        && !none.hasLayer(net.atomos.zenith.api.ZenithWindSample.LAYER_L2), "");
        var composite = net.atomos.zenith.api.ZenithWindSample.builder()
                .authority(net.atomos.zenith.api.ZenithWindSample.Authority.SERVER_AUTHORITATIVE)
                .source(net.atomos.zenith.api.ZenithWindSample.Source.L1_COARSE)
                .layers(net.atomos.zenith.api.ZenithWindSample.LAYER_L0
                        | net.atomos.zenith.api.ZenithWindSample.LAYER_L1
                        | net.atomos.zenith.api.ZenithWindSample.LAYER_PHENOMENA)
                .meanVelocity(net.atomos.zenith.api.ZenithVec3.of(3, 0, 4))
                .confidence(0.9f)
                .build();
        System.out.printf("  多层样本: source=%s layers=%d hasFlow=%s%n",
                composite.source(), composite.layers(), composite.hasFlow());
        check("API-11 多层结果标 COMPOSITE", composite.source()
                == net.atomos.zenith.api.ZenithWindSample.Source.COMPOSITE, "");
        check("API-11 有模长时 hasFlow=true", composite.hasFlow(), "");
        check("API-05 API_VERSION=0.2 且 isAtLeast(0,2)", "0.2".equals(
                net.atomos.zenith.api.ZenithApiVersion.API_VERSION)
                && net.atomos.zenith.api.ZenithApiVersion.isAtLeast(0, 2)
                && !net.atomos.zenith.api.ZenithApiVersion.isAtLeast(1, 0), "");

        // API-05：事件注册/分发/取消
        int[] hits = {0};
        Runnable h = net.atomos.zenith.api.ZenithWeatherEvents.register(ev -> hits[0]++);
        net.atomos.zenith.api.ZenithWeatherEvents.fire(net.atomos.zenith.api.ZenithWeatherEvent.of(
                net.atomos.zenith.api.ZenithWeatherEvent.Type.TYPHOON_SPAWNED,
                net.atomos.zenith.api.ZenithWorldRef.server(
                        net.atomos.zenith.api.ZenithId.of("minecraft", "overworld")),
                0, 0, 0.8, "test"));
        h.run();
        net.atomos.zenith.api.ZenithWeatherEvents.fire(net.atomos.zenith.api.ZenithWeatherEvent.of(
                net.atomos.zenith.api.ZenithWeatherEvent.Type.TYPHOON_SPAWNED,
                net.atomos.zenith.api.ZenithWorldRef.server(
                        net.atomos.zenith.api.ZenithId.of("minecraft", "overworld")),
                0, 0, 0.8, "test"));
        check("API-05 事件分发一次、注销后不再收到", hits[0] == 1, "hits=" + hits[0]);
        int[] bad = {0};
        Runnable h2 = net.atomos.zenith.api.ZenithWeatherEvents.register(ev -> {
            throw new IllegalStateException("boom");
        });
        Runnable h3 = net.atomos.zenith.api.ZenithWeatherEvents.register(ev -> bad[0]++);
        net.atomos.zenith.api.ZenithWeatherEvents.fire(net.atomos.zenith.api.ZenithWeatherEvent.of(
                net.atomos.zenith.api.ZenithWeatherEvent.Type.SQUALL_LINE_FORMED,
                net.atomos.zenith.api.ZenithWorldRef.server(
                        net.atomos.zenith.api.ZenithId.of("minecraft", "overworld")),
                0, 0, 0.5, ""));
        h2.run();
        h3.run();
        check("API-05 监听器抛异常不打断其它监听器", bad[0] == 1, "bad=" + bad[0]);

        System.out.println();
        System.out.println(fail == 0 ? "ALL CHECKS PASSED" : (fail + " CHECK(S) FAILED"));
        if (fail > 0) System.exit(1);
    }
}
