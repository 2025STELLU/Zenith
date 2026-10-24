package net.atomos.zenith.api;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** API-09 / API-10：翼型 JSON 解析与几何口径。 */
class ZenithAirfoilTest {

    private static final ZenithId ID = ZenithId.of("test", "airfoil");

    @Test
    @DisplayName("API-09 截断 JSON 抛 IllegalArgumentException")
    void truncatedJsonIsWrapped() {
        assertThrows(IllegalArgumentException.class,
                () -> ZenithAirfoilJson.parse(ID, "{\"points\":[[0,0],[1,"));
    }

    @Test
    @DisplayName("API-09 非数值坐标抛 IllegalArgumentException（NaN 不能当点）")
    void nonNumericCoordinateIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> ZenithAirfoilJson.parse(ID, "{\"points\":[[0,0],[\"NaN\",1]]}"));
    }

    @Test
    @DisplayName("API-09 合法 JSON 正常解析")
    void validJsonParses() {
        ZenithAirfoilDefinition def = ZenithAirfoilJson.parse(ID,
                "{\"name\":\"x\",\"points\":[[0,0],[1,0]]}");
        assertEquals(ID, def.id());
        assertEquals(2, def.coordinates().size());
    }

    @Test
    @DisplayName("API-10 NACA 2412 点序：前缘 (0,0) 起、上表面在前、无重复端点")
    void naca2412PointOrder() {
        ZenithAirfoilDefinition naca = ZenithAirfoilPresets.NACA_2412;
        var pts = naca.coordinates();

        assertEquals(0.0, pts.get(0).x(), 1e-9, "首点应在弦向 x=0");
        assertEquals(0.0, pts.get(0).y(), 1e-9, "首点应在中线 y=0");

        // 前 N+1 个是上表面（前缘→后缘），后 N-1 个是下表面（后缘→前缘）
        int total = pts.size();
        assertEquals(0, total % 2, "上表面 N+1 点 + 下表面 N-1 点 = 偶数");
        int upperCount = total / 2 + 1;
        assertEquals(1.0, pts.get(upperCount - 1).x(), 1e-9, "上表面末点应在后缘 x=1");

        for (int i = 1; i < total; i++) {
            boolean duplicate = Math.abs(pts.get(i).x() - pts.get(i - 1).x()) < 1e-12
                    && Math.abs(pts.get(i).y() - pts.get(i - 1).y()) < 1e-12;
            assertTrue(!duplicate, "第 " + i + " 点与前一点重复，多边形会出现退化边");
        }
    }

    @Test
    @DisplayName("API-10 NACA 2412 上下表面在 x≈0.4 处分开，且上表面更厚")
    void naca2412CamberIsNotThickness() {
        ZenithAirfoilDefinition naca = ZenithAirfoilPresets.NACA_2412;
        var pts = naca.coordinates();
        int total = pts.size();
        int upperCount = total / 2 + 1;

        double yUpper = Double.NaN;
        double yLower = Double.NaN;
        for (int i = 0; i < upperCount; i++) {
            if (Math.abs(pts.get(i).x() - 0.4) < 0.02) yUpper = pts.get(i).y();
        }
        for (int i = upperCount; i < total; i++) {
            if (Math.abs(pts.get(i).x() - 0.4) < 0.02) yLower = pts.get(i).y();
        }

        assertTrue(yUpper > 0.02, "x≈0.4 处上表面 y 应显著为正，实测 " + yUpper);
        assertTrue(yLower < 0.0, "x≈0.4 处下表面 y 应为负，实测 " + yLower);
        assertTrue(yUpper > -yLower, "正弯度翼型的上表面应更厚：up=" + yUpper + " down=" + yLower);
    }

    @Test
    @DisplayName("API-10 maxThickness 按上下对应站位求差，NACA 2412 ≈ 0.12")
    void maxThicknessIsNotDoubleCamber() {
        // 旧实现 2·max|y| 把弯度算成厚度，NACA 2412 报 ~0.24
        assertEquals(0.12, ZenithAirfoilPresets.NACA_2412.maxThickness(), 0.005);
        assertEquals(0.12, ZenithAirfoilPresets.NACA_4412.maxThickness(), 0.005);
        assertEquals(0.12, ZenithAirfoilPresets.NACA_0012.maxThickness(), 0.005);
    }

    @Test
    @DisplayName("API-10 平板翼型退化为 0 厚度，不抛异常")
    void flatPlateThickness() {
        assertEquals(0.0, ZenithAirfoilPresets.FLAT_PLATE.maxThickness(), 1e-12);
    }

    @Test
    @DisplayName("API-10 点序不符约定时退回 2·max|y|（对称翼型仍正确）")
    void fallbackForNonStandardLayout() {
        // 只有两个点、且不是"上表面在前"的布局 → 走退回分支
        ZenithAirfoilDefinition symmetric = new ZenithAirfoilDefinition(ID, "symmetric",
                java.util.List.of(new ZenithAirfoilCoordinate(0.0, 0.05),
                        new ZenithAirfoilCoordinate(1.0, -0.05)));
        assertEquals(0.1, symmetric.maxThickness(), 1e-12);
    }
}
