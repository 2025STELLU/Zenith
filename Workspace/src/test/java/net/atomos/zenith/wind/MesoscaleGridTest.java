package net.atomos.zenith.wind;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * L1-01 / L1-04 / L1-06 / L0-07：中尺度网格的垂直廓线口径。
 *
 * <p>这几条是同一个 bug 的四个面：层定义从"绝对 y 等距 40 格"改成"AGL 非等距"
 * 之后，对数律、Ekman 偏转、气压透传才各自成立。所以放在一个测试类里，
 * 用同一套平地场景（地表顶面 y=64、草地粗糙度）跑一遍。
 */
class MesoscaleGridTest {

    /** 平地地表顶面 y（{@code getHeight(WORLD_SURFACE)} 的返回值）。 */
    private static final double GROUND_Y = 64.0;

    private static BackgroundMetGrid l0;
    private static MesoscaleGrid l1;
    private static double baseSpeed;

    @BeforeAll
    static void convergeFlatWorld() {
        l0 = new BackgroundMetGrid();
        l1 = new MesoscaleGrid(424242L);
        WorldScaleDriver driver = new WorldScaleDriver(424242L);

        BackgroundMetGrid.TerrainProbe l0Terrain = new BackgroundMetGrid.TerrainProbe() {
            @Override public double terrainHeightBlocks(double bx, double bz) { return GROUND_Y; }
            @Override public double roughness01(double bx, double bz) { return 0.30; }
            @Override public double biomeTemperatureKelvin(double bx, double bz) { return 284.0; }
        };
        MesoscaleGrid.TerrainProbe l1Terrain = new MesoscaleGrid.TerrainProbe() {
            @Override public double terrainHeightBlocks(double bx, double bz) { return GROUND_Y; }
            @Override public double roughness01(double bx, double bz) { return 0.30; }
            @Override public double[] slopeVector(double bx, double bz) { return new double[]{0, 0}; }
        };

        l0.setFocus(0, 0);
        // 先让 L0 收敛（10 分钟模拟时间），否则基流还在从初值爬升，比值没意义
        for (int i = 0; i < 60; i++) {
            driver.advance(12.8, 0.5, false, false, WorldScaleDriver.Feedback.none());
            l0.refresh(driver, l0Terrain, 12.8, 0.5, false, 0.0);
        }
        l1.setFocus(0, 0);
        l1.refresh(l0, l1Terrain, 0.5, false, 0L, GROUND_Y * 0.05);

        BackgroundMetGrid.Sample base = l0.sample(0, 0);
        baseSpeed = Math.hypot(base.windX(), base.windZ());
    }

    private static double speedRatioAt(double aglBlocks) {
        MesoscaleGrid.Sample s = l1.sample(0.5, GROUND_Y + aglBlocks, 0.5);
        return Math.hypot(s.windX(), s.windZ()) / baseSpeed;
    }

    @Test
    @DisplayName("层中心是 AGL 2/6/14/30/60/110/190/300，严格递增")
    void layerHeightsAreAglAndIncreasing() {
        double[] expected = {2, 6, 14, 30, 60, 110, 190, 300};
        assertEquals(expected.length, MesoscaleGrid.MAX_LAYERS, "层数");
        for (int i = 0; i < expected.length; i++) {
            assertEquals(expected[i], MesoscaleGrid.layerAglBlocks(i), 1e-9,
                    "第 " + i + " 层中心");
            if (i > 0) {
                assertTrue(MesoscaleGrid.layerAglBlocks(i) > MesoscaleGrid.layerAglBlocks(i - 1),
                        "层中心必须严格递增");
            }
        }
        // 越界钳制到首/末层，不能抛异常（sample() 会传入任意高度）
        assertEquals(2.0, MesoscaleGrid.layerAglBlocks(-5), 1e-9);
        assertEquals(300.0, MesoscaleGrid.layerAglBlocks(99), 1e-9);
    }

    @Test
    @DisplayName("近地层风速按对数律随 AGL 单调增加")
    void windSpeedGrowsWithHeight() {
        double previous = -1;
        for (double agl : new double[]{0.5, 1.62, 2, 6, 10, 14, 30, 60, 110, 190, 300}) {
            double ratio = speedRatioAt(agl);
            assertTrue(ratio > previous, "AGL " + agl + " 处比值 " + ratio
                    + " 未大于上一个高度 " + previous);
            previous = ratio;
        }
    }

    @Test
    @DisplayName("L1-01 验收：眼高 1.62 格 ≈ 0.41×L0，10 格 ≈ 0.61×L0")
    void nearSurfaceRatiosMatchAcceptanceWindow() {
        // 旧实现（层用绝对 y 等距 40 格 + 0.15 地板）在平地给出恒定 0.2775×L0，
        // 眼高和 10 格完全一样，玩家站在地上和站在脚手架上感到同样的风。
        double eye = speedRatioAt(1.62);
        double ten = speedRatioAt(10.0);
        assertTrue(eye >= 0.35 && eye <= 0.45, "1.62 AGL = " + eye + "，期望 [0.35, 0.45]");
        assertTrue(ten >= 0.50 && ten <= 0.65, "10 AGL = " + ten + "，期望 [0.50, 0.65]");
        assertTrue(ten > eye * 1.3, "10 格应明显强于眼高：" + ten + " vs " + eye);
    }

    @Test
    @DisplayName("层顶（300 AGL）收敛回 L0 基流")
    void topLayerMatchesBackground() {
        assertEquals(1.0, speedRatioAt(300.0), 0.02, "层顶应 ≈1.0×L0");
        // 层顶以上直接返回顶层值（自由大气），不能再往上外推
        assertEquals(speedRatioAt(300.0), speedRatioAt(900.0), 1e-9);
    }

    @Test
    @DisplayName("L1-04 Ekman 偏转：近地最大、层顶归零，北半球偏负")
    void ekmanTurnFadesToZeroAtTop() {
        BackgroundMetGrid.Sample base = l0.sample(0, 0);
        double dirBase = Math.atan2(base.windZ(), base.windX());

        MesoscaleGrid.Sample near = l1.sample(0.5, GROUND_Y + 2.0, 0.5);
        MesoscaleGrid.Sample top = l1.sample(0.5, GROUND_Y + 300.0, 0.5);
        double turnNear = WorldScaleDriver.wrapDelta(
                Math.toDegrees(Math.atan2(near.windZ(), near.windX()) - dirBase), 360);
        double turnTop = WorldScaleDriver.wrapDelta(
                Math.toDegrees(Math.atan2(top.windZ(), top.windX()) - dirBase), 360);

        assertTrue(Math.abs(turnTop) < 3.0, "层顶偏转 " + turnTop + "° 应归零");
        assertTrue(turnNear < -5.0, "北半球近地偏转应偏负，实测 " + turnNear + "°");
    }

    @Test
    @DisplayName("L1-06 上层存在垂直速度，不是恒 0")
    void verticalVelocityIsNonZeroAloft() {
        double wLow = Math.abs(l1.sample(0.5, GROUND_Y + 10.0, 0.5).windY());
        double wHigh = Math.abs(l1.sample(0.5, GROUND_Y + 300.0, 0.5).windY());
        assertTrue(wLow + wHigh > 1e-6, "上下层 |w| 之和 " + (wLow + wHigh) + " 应非 0");
        assertTrue(wHigh <= MesoscaleGridDiagnosticsProbe.maxContinuityW() + 1e-9,
                "|w| 必须受连续方程上限钳制");
    }

    @Test
    @DisplayName("L0-07 / API-07 气压异常从 L0 透传，不再恒 0")
    void pressureAnomalyIsForwarded() {
        MesoscaleGrid.Sample s = l1.sample(0.5, GROUND_Y + 10.0, 0.5);
        assertTrue(Math.abs(s.pressureAnomalyPa()) > 1.0,
                "气压异常 = " + s.pressureAnomalyPa() + " Pa，旧实现恒 0");
    }

    @Test
    @DisplayName("L1-02 域外采样平滑回退到 L0，不返回 NaN")
    void outsideDomainFallsBackToBackground() {
        // 网格半径 16 格 × 64 格 = 1024 格，再往外 512 格完全用 L0
        double far = 1024.0 + 600.0;
        MesoscaleGrid.Sample s = l1.sample(far, GROUND_Y + 10.0, far);
        assertTrue(Double.isFinite(s.windX()) && Double.isFinite(s.windZ()),
                "域外采样出现 NaN/Inf");
        double speed = Math.hypot(s.windX(), s.windZ());
        assertTrue(speed > 0, "域外应回退到 L0 基流，实测 " + speed);
    }

    /** 反射读取私有的连续方程上限常量，避免在测试里硬编码一份副本。 */
    private static final class MesoscaleGridDiagnosticsProbe {
        static double maxContinuityW() {
            try {
                var f = MesoscaleGrid.class.getDeclaredField("MAX_CONTINUITY_W_MPS");
                f.setAccessible(true);
                return f.getDouble(null);
            } catch (ReflectiveOperationException e) {
                return Double.MAX_VALUE;
            }
        }
    }
}
