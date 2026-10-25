package net.atomos.zenith.entity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SAIL-02..06：帆船物理的坐标约定与推进系数。
 *
 * <p>这些是"手感"的锚点：{@code driveCoefficient} 的曲线形状、逆风死区宽度、
 * 顺风保留比例，以及艏向与 MC {@code yaw} 的符号关系。改动任何一项都会让船
 * 忽然变得不能迎风调帆或者顺风跑不动，所以固定成数值断言。
 */
class SailingPhysicsTest {

    private static final double EPS = 1e-9;

    @Test
    @DisplayName("SAIL-05 逆风死区：≤30° 推进系数为 0")
    void noGoZoneIsDead() {
        for (double deg = 0; deg <= 30.0; deg += 5.0) {
            assertEquals(0.0,
                    SailingPhysics.driveCoefficient(Math.toRadians(deg), 10.0), EPS,
                    deg + "° 应完全无推进");
        }
    }

    @Test
    @DisplayName("SAIL-05 死区外沿平滑起步：35° 只有 3%，45° 约 35%")
    void fadeOutOfNoGoZone() {
        double at35 = SailingPhysics.driveCoefficient(Math.toRadians(35), 10.0);
        double at45 = SailingPhysics.driveCoefficient(Math.toRadians(45), 10.0);
        assertTrue(at35 > 0 && at35 < 0.06, "35° 应刚起步，实测 " + at35);
        assertEquals(0.3459, at45, 0.002, "45° 推进系数");
        // 旧实现是"偏 1° 就有 10% 推力"的阶跃，这里必须严格连续
        assertTrue(at45 > at35);
    }

    @Test
    @DisplayName("SAIL-05 横风主峰：90° 约 0.82，105° 达 0.85")
    void crosswindPeak() {
        assertEquals(0.8207, SailingPhysics.driveCoefficient(Math.toRadians(90), 10.0), 0.002);
        assertEquals(0.85, SailingPhysics.driveCoefficient(Math.toRadians(105), 10.0), 0.002);
        // 主峰确实在 105°（t=0.5）附近，不是 90°
        assertTrue(SailingPhysics.driveCoefficient(Math.toRadians(105), 10.0)
                > SailingPhysics.driveCoefficient(Math.toRadians(90), 10.0));
    }

    @Test
    @DisplayName("SAIL-05 正顺风保留 45%，不是 0")
    void downwindCeiling() {
        assertEquals(SailingPhysics.DOWNWIND_CEILING,
                SailingPhysics.driveCoefficient(Math.PI, 10.0), 0.002);
        assertTrue(SailingPhysics.driveCoefficient(Math.toRadians(170), 10.0) > 0.4,
                "170° 应接近顺风上限");
    }

    @Test
    @DisplayName("SAIL-05 视风过弱（<0.2 m/s）无推进")
    void weakApparentWindGivesNoDrive() {
        assertEquals(0.0, SailingPhysics.driveCoefficient(Math.toRadians(90), 0.1), EPS);
        assertEquals(0.0, SailingPhysics.driveCoefficient(Math.toRadians(90), 0.19), EPS);
        assertTrue(SailingPhysics.driveCoefficient(Math.toRadians(90), 0.21) > 0.5);
    }

    @Test
    @DisplayName("SAIL-05 推进系数恒在 [0, 0.86] 内")
    void driveCoefficientBounded() {
        for (double deg = 0; deg <= 180; deg += 0.5) {
            double c = SailingPhysics.driveCoefficient(Math.toRadians(deg), 12.0);
            assertTrue(c >= 0 && c <= 0.86, deg + "° 推进系数越界：" + c);
        }
    }

    @Test
    @DisplayName("SAIL-02 艏向约定：h=0 朝 +Z，fwd=(-sin h, cos h)")
    void headingConventionMatchesMinecraftYaw() {
        // 风 (0,+10) 从 -Z 吹向 +Z，艏向 h=0（朝 +Z）→ 正顺风
        double[] accel = SailingPhysics.step(0, 10, 0, 0, 0, 1, 0);
        assertTrue(accel[1] > 0, "正顺风应沿 +Z 加速，实测 az=" + accel[1]);
        assertEquals(0.0, accel[0], 1e-6, "正顺风不该有东向分量");
        assertEquals(SailingPhysics.DOWNWIND_CEILING, accel[2], 0.002, "正顺风推进系数");
    }

    @Test
    @DisplayName("SAIL-02 横风时推力沿艏向，不沿风向")
    void crosswindThrustFollowsHeading() {
        // 风从东吹向西（windX=-10），艏向 h=+90° 朝 -X（西）→ 正顺风
        double[] downwind = SailingPhysics.step(-10, 0, 0, 0, Math.toRadians(90), 1, 0);
        assertTrue(downwind[0] < 0, "应沿 -X 加速，实测 ax=" + downwind[0]);

        // 同样风、艏向朝南（h=0，+Z）→ 横风，推力应主要落在 +Z
        double[] crosswind = SailingPhysics.step(-10, 0, 0, 0, 0, 1, 0);
        assertTrue(crosswind[1] > 0, "横风应沿艏向 +Z 推进，实测 az=" + crosswind[1]);
        assertTrue(crosswind[2] > 0.5, "横风推进系数应高，实测 " + crosswind[2]);
    }

    @Test
    @DisplayName("SAIL-02 trim=0 时帆不产生推力（推进系数本身仍按受风角给出）")
    void zeroTrimMeansNoDrive() {
        double[] accel = SailingPhysics.step(0, 10, 0, 0, 0, 0, 0);
        assertEquals(0.0, accel[0], 1e-9, "trim=0 不该有推力");
        assertEquals(0.0, accel[1], 1e-9, "trim=0 不该有推力");
        // driveCoefficient 是几何量（受风角 + 视风强度），不含 trim；
        // trim 作为乘数在 step 里生效。所以这里第 3 位仍应是顺风上限。
        assertEquals(SailingPhysics.DOWNWIND_CEILING, accel[2], 0.002);

        // 同一角度、trim=1 时才有加速度
        double[] powered = SailingPhysics.step(0, 10, 0, 0, 0, 1, 0);
        assertTrue(Math.abs(powered[1]) > 1e-3, "trim=1 应产生加速度");
    }

    @Test
    @DisplayName("SAIL-04 横向水阻力：纯横漂时衰减力与速度反向")
    void lateralResistanceOpposesDrift() {
        // 艏向 +Z，速度纯 +X（横漂），应有 -X 方向的力
        double[] accel = SailingPhysics.step(0, 0, 5, 0, 0, 0, 0);
        assertTrue(accel[0] < 0, "横漂应被反向力抑制，实测 ax=" + accel[0]);
        // τ = m/c = 800/600 ≈ 1.33 s，落在真实小帆船 1–2 s 区间
        double tau = SailingPhysics.HULL_MASS_KG / SailingPhysics.LATERAL_RESISTANCE_K;
        assertTrue(tau > 1.0 && tau < 2.0, "横向衰减时间常数 " + tau + " s");
    }

    @Test
    @DisplayName("SAIL-06 舵效随船速增加并封顶，松舵不改变艏向")
    void rudderEffectScalesWithSpeed() {
        assertEquals(0.5, SailingPhysics.updateHeading(0.5, 0, 10, 0.05), 1e-12,
                "松舵不该改艏向");

        double slow = SailingPhysics.updateHeading(0, 1, 0.5, 0.05);
        double fast = SailingPhysics.updateHeading(0, 1, 5, 0.05);
        assertTrue(fast > slow, "船越快舵效越强：" + fast + " vs " + slow);
        assertTrue(slow > 0, "低速仍应有少量舵效，实测 " + slow);

        // 封顶：速度再高，转向率也不超过 1.2·0.9
        double capped = SailingPhysics.updateHeading(0, 1, 100, 0.05);
        assertEquals(1.2 * 0.9 * 0.05, capped, 1e-9);

        // 反向舵给出对称的反向转向
        assertEquals(-capped, SailingPhysics.updateHeading(0, -1, 100, 0.05), 1e-9);
    }

    @Test
    @DisplayName("smoothstep 输入夹到 [0,1]")
    void smoothstepClamps() {
        assertEquals(0.0, SailingPhysics.smoothstep(-1.0), EPS);
        assertEquals(0.0, SailingPhysics.smoothstep(0.0), EPS);
        assertEquals(0.5, SailingPhysics.smoothstep(0.5), EPS);
        assertEquals(1.0, SailingPhysics.smoothstep(1.0), EPS);
        assertEquals(1.0, SailingPhysics.smoothstep(2.0), EPS);
    }
}
