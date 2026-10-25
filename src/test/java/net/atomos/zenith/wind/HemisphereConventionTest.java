package net.atomos.zenith.wind;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SYS-01 / DRV-08 / WX-TY-01：半球约定与坐标手性的唯一来源。
 *
 * <p>这里固定的是三件容易各自写死符号的事：切向绕向、地转风、径向辐合/辐散。
 * 0.1.4 里这三处各写一套，其中地转风整体转了 180°、辐合辐散写反。
 */
class HemisphereConventionTest {

    @AfterEach
    void restoreNorth() {
        // coriolisSign 是静态全局，测试不能污染后续用例
        HemisphereConvention.setHemisphereSign(HemisphereConvention.NORTH);
    }

    @Test
    @DisplayName("默认北半球，且非负值都归一为北半球")
    void defaultIsNorth() {
        assertEquals(HemisphereConvention.NORTH, HemisphereConvention.coriolisSign(), 0.0);
        assertEquals("north", HemisphereConvention.name());

        HemisphereConvention.setHemisphereSign(0.0);
        assertEquals(HemisphereConvention.NORTH, HemisphereConvention.coriolisSign(), 0.0);

        HemisphereConvention.setHemisphereSign(-1.0);
        assertEquals(HemisphereConvention.SOUTH, HemisphereConvention.coriolisSign(), 0.0);
        assertEquals("south", HemisphereConvention.name());

        HemisphereConvention.setHemisphereSign(-12345.0);
        assertEquals(HemisphereConvention.SOUTH, HemisphereConvention.coriolisSign(), 0.0);
    }

    @Test
    @DisplayName("北半球低压逆时针：正东侧（+X）的风朝 -Z（北）")
    void northLowPressureIsCounterClockwise() {
        // 地图视角（北在上、东在右、+Z 朝下）：逆时针在 3 点钟方向朝 12 点钟走，即 -Z。
        // 采样点在中心的正东侧：dx=1, dz=0
        double[] t = HemisphereConvention.tangential(1, 0, true);
        assertEquals(0.0, t[0], 1e-12, "正东侧不该有东向切向分量");
        assertTrue(t[1] < 0, "北半球低压在东侧应朝 -Z（北），实测 " + t[1]);
    }

    @Test
    @DisplayName("南半球低压顺时针：正东侧（+X）的风朝 +Z（南）")
    void southLowPressureIsClockwise() {
        HemisphereConvention.setHemisphereSign(HemisphereConvention.SOUTH);
        double[] t = HemisphereConvention.tangential(1, 0, true);
        assertTrue(t[1] > 0, "南半球低压在东侧应朝 +Z（南），实测 " + t[1]);
    }

    @Test
    @DisplayName("高压的切向与低压反向")
    void highPressureReversesTangential() {
        double[] low = HemisphereConvention.tangential(1, 0, true);
        double[] high = HemisphereConvention.tangential(1, 0, false);
        assertEquals(-low[0], high[0], 1e-12);
        assertEquals(-low[1], high[1], 1e-12);
    }

    @Test
    @DisplayName("切向模长等于径向距离（只旋转不缩放）")
    void tangentialPreservesMagnitude() {
        for (double[] d : new double[][]{{1, 0}, {0, 1}, {3, 4}, {-2, 5}}) {
            double[] t = HemisphereConvention.tangential(d[0], d[1], true);
            assertEquals(Math.hypot(d[0], d[1]), Math.hypot(t[0], t[1]), 1e-12);
        }
    }

    @Test
    @DisplayName("地转风：北半球 u=+∂p/∂z·K、w=−∂p/∂x·K")
    void geostrophicSigns() {
        double scale = 2.0;
        // 只沿 +X 有气压梯度：低压在东 → 风应朝 +Z（南）
        double[] g = HemisphereConvention.geostrophic(1.0, 0.0, scale);
        assertEquals(0.0, g[0], 1e-12);
        assertEquals(-2.0, g[1], 1e-12, "旧实现把两个分量都写反（整体转 180°）");

        // 只沿 +Z 有梯度
        double[] g2 = HemisphereConvention.geostrophic(0.0, 1.0, scale);
        assertEquals(2.0, g2[0], 1e-12);
        assertEquals(0.0, g2[1], 1e-12);
    }

    @Test
    @DisplayName("南半球地转风整体反向")
    void geostrophicFlipsInSouth() {
        HemisphereConvention.setHemisphereSign(HemisphereConvention.SOUTH);
        double[] g = HemisphereConvention.geostrophic(1.0, 0.0, 2.0);
        assertEquals(2.0, g[1], 1e-12);
    }

    @Test
    @DisplayName("径向符号：低压辐合为 -1，高压辐散为 +1")
    void radialSignConvention() {
        assertEquals(-1.0, HemisphereConvention.radialSign(true), 0.0,
                "调用方乘在**外**向上，低压必须取负才是辐合");
        assertEquals(1.0, HemisphereConvention.radialSign(false), 0.0);
    }
}
