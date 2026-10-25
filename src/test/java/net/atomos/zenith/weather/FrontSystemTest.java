package net.atomos.zenith.weather;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * WX-FR-02：锋面热力/气压扰动的数据通路 + 锋线有符号距离的符号约定。
 *
 * <p>这些是纯几何/算术，不依赖 MC 运行时：{@link FrontSystem.Front} 只是一个
 * 坐标容器，{@link WindContribution} 是 record。
 */
class FrontSystemTest {

    /** 沿 +Z 走的一条线（x1=0,z1=0 → x2=0,z2=100），法线是 (−1, 0) 即指向 −X。 */
    private static FrontSystem.Front lineAlongZ(FrontSystem.FrontType type) {
        FrontSystem.Front f = new FrontSystem.Front();
        f.type = type;
        f.x1 = 0;
        f.z1 = 0;
        f.x2 = 0;
        f.z2 = 100;
        f.intensity01 = 1.0;
        return f;
    }

    @Test
    void signedDistanceIsZeroOnTheLine() {
        FrontSystem.Front f = lineAlongZ(FrontSystem.FrontType.COLD);
        assertEquals(0.0, f.signedDistanceTo(0, 50), 1e-9);
    }

    @Test
    void signedDistanceHasOppositeSignsOnBothSides() {
        FrontSystem.Front f = lineAlongZ(FrontSystem.FrontType.COLD);
        // 法线 = (-dz, dx)/len = (-1, 0)，所以 −X 侧为正、+X 侧为负
        double minusX = f.signedDistanceTo(-10, 50);
        double plusX = f.signedDistanceTo(10, 50);
        assertTrue(minusX > 0, "−X 侧应为正（锋前）");
        assertTrue(plusX < 0, "+X 侧应为负（锋后）");
        assertEquals(-minusX, plusX, 1e-9, "两侧绝对值应相等");
    }

    @Test
    void signedDistanceMagnitudeEqualsUnsignedDistance() {
        FrontSystem.Front f = lineAlongZ(FrontSystem.FrontType.WARM);
        double sd = f.signedDistanceTo(37, 20);
        double d = f.distanceTo(37, 20);
        assertEquals(d, Math.abs(sd), 1e-9);
    }

    @Test
    void windContributionAddClampsTurbulenceAndPrecip() {
        WindContribution a = new WindContribution(1, 0, 2, 0.7, 0.9);
        WindContribution b = new WindContribution(-0.5, 0.25, 0.5, 0.6, 0.8);
        WindContribution sum = a.add(b);
        assertEquals(0.5, sum.vx(), 1e-12);
        assertEquals(0.25, sum.vy(), 1e-12);
        assertEquals(2.5, sum.vz(), 1e-12);
        assertEquals(1.0, sum.turbulenceAdd(), 1e-12, "湍流叠加后必须钳到 1");
        assertEquals(1.0, sum.precipIntensity01(), 1e-12, "降水叠加后必须钳到 1");
    }

    @Test
    void windContributionCarriesTemperatureAndPressure() {
        // 冷锋后：−4 K / +300 Pa
        WindContribution cold = new WindContribution(0, 0, 0, 0.3, 0.75, -4.0, 300.0);
        // 暖锋前：+2.5 K / −200 Pa
        WindContribution warm = new WindContribution(0, 0, 0, 0.12, 0.5, 2.5, -200.0);
        WindContribution sum = cold.add(warm);
        assertEquals(-1.5, sum.tempDeltaK(), 1e-12);
        assertEquals(100.0, sum.pressureDeltaPa(), 1e-12);
        assertFalse(sum.isNone(), "只有热力/气压扰动时也不能算 none");
    }

    @Test
    void fiveArgContributionKeepsThermodynamicsAtZero() {
        // 老调用点（尘卷/背风波/热泡…）走 5 参构造，行为必须与 0.1.4 完全一致
        WindContribution c = new WindContribution(1, 2, 3, 0.4, 0.6);
        assertEquals(0.0, c.tempDeltaK(), 1e-12);
        assertEquals(0.0, c.pressureDeltaPa(), 1e-12);
    }

    @Test
    void noneIsAllZero() {
        assertTrue(WindContribution.NONE.isNone());
        assertEquals(0.0, WindContribution.NONE.tempDeltaK(), 1e-12);
        assertEquals(0.0, WindContribution.NONE.pressureDeltaPa(), 1e-12);
    }
}
