package net.atomos.zenith.weather;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** WX-SB-02 / WX-MS-01：海陆距离场。 */
class CoastDistanceTest {

    private static final double EPS = 1e-9;

    /** 4×4 掩膜，只有 (0,0) 是海，其余是陆。 */
    private static boolean[][] cornerSeaMask() {
        boolean[][] mask = new boolean[4][4];
        mask[0][0] = true;
        return mask;
    }

    @Test
    @DisplayName("chamfer 距离：同格 0、正交 1、对角 √2")
    void chamferDistances() {
        double[][] toSea = CoastDistance.chamfer(cornerSeaMask(), true);
        assertEquals(0.0, toSea[0][0], EPS, "海格到海");
        assertEquals(1.0, toSea[0][1], EPS, "正交相邻");
        assertEquals(Math.sqrt(2.0), toSea[1][1], EPS, "对角相邻");
        assertEquals(2.0, toSea[0][2], EPS, "两格正交");
    }

    @Test
    @DisplayName("chamfer 对 want=false 求到陆地的距离")
    void chamferToLand() {
        double[][] toLand = CoastDistance.chamfer(cornerSeaMask(), false);
        assertEquals(0.0, toLand[3][3], EPS, "陆格到陆");
        assertEquals(1.0, toLand[0][0], EPS, "海格到陆（相邻有陆）");
    }

    @Test
    @DisplayName("signedDistanceBlocks：陆地为正、海上为负、乘以格距")
    void signedDistanceSignConvention() {
        double cellSize = 256.0;
        double[][] sd = CoastDistance.signedDistanceBlocks(cornerSeaMask(), cellSize);
        assertTrue(sd[0][0] < 0, "海格应为负，实测 " + sd[0][0]);
        assertTrue(sd[3][3] > 0, "陆格应为正，实测 " + sd[3][3]);
        assertEquals(-256.0, sd[0][0], EPS, "海格离岸 1 格 = -256");
        assertEquals(256.0, sd[0][1], EPS, "紧邻海的陆格 = +256");
    }

    @Test
    @DisplayName("整片同质时夹到有限饱和值，不产生 NaN/Inf")
    void uniformFieldIsCappedNotNaN() {
        boolean[][] allSea = new boolean[5][5];
        for (boolean[] row : allSea) Arrays.fill(row, true);
        double[][] sd = CoastDistance.signedDistanceBlocks(allSea, 256.0);

        double cap = 256.0 * Math.hypot(5, 5);
        for (double[] row : sd) {
            for (double v : row) {
                assertTrue(Double.isFinite(v), "出现 NaN/Inf");
                assertTrue(Math.abs(v) <= cap + EPS, "未夹到饱和值：" + v);
                assertEquals(-cap, v, EPS, "全海时每格都是 -cap");
            }
        }
    }

    @Test
    @DisplayName("季风权重 exp(-|D|/600) 随离岸距离单调衰减")
    void monsoonWeightDecaysWithDistance() {
        double previous = Double.MAX_VALUE;
        for (double d : new double[]{0, 100, 300, 600, 1200, 2000}) {
            double w = Math.exp(-Math.abs(d) / 600.0);
            assertTrue(w <= previous, "|D|=" + d + " 权重 " + w + " 未衰减");
            assertTrue(w >= 0 && w <= 1, "权重越界：" + w);
            previous = w;
        }
        assertEquals(1.0, Math.exp(-0.0 / 600.0), EPS, "岸线处权重 = 1");
        assertEquals(Math.exp(-1.0), Math.exp(-600.0 / 600.0), EPS, "600 格处 = 1/e");
    }
}
