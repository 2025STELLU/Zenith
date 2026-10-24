package net.atomos.zenith.weather;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * WX-FOG-01：三种雾都必须是连续函数。
 *
 * <p>旧实现的平流雾是 {@code if (rh > 0.7 && 1.5 < wind < 9) d = max(d, 0.6)}：
 * 湿度跨过 0.70 的一瞬间雾浓度从 0 跳到 0.6，风速到 9 m/s 又瞬间归零，
 * 玩家走两步雾就闪一下。这些测试逐点扫过两个阈值来固定"不许跳变"。
 */
class FogModelTest {

    private static final double EPS = 1e-9;

    @Test
    @DisplayName("saturationDeficit 是 (rh−lo)/(1−lo)，端点 0 与 1")
    void saturationDeficitIsNormalised() {
        assertEquals(0.0, FogModel.saturationDeficit(0.70, 0.70), EPS);
        assertEquals(1.0, FogModel.saturationDeficit(1.00, 0.70), EPS);
        assertEquals(0.5, FogModel.saturationDeficit(0.85, 0.70), EPS);
        // 低于阈值夹到 0，不得出现负浓度
        assertEquals(0.0, FogModel.saturationDeficit(0.10, 0.70), EPS);
        // 阈值 1.0 是退化情形，必须返回 0 而不是 NaN
        assertEquals(0.0, FogModel.saturationDeficit(1.0, 1.0), EPS);
    }

    @Test
    @DisplayName("smoothstep 端点与单调性")
    void smoothstepEndpoints() {
        assertEquals(0.0, FogModel.smoothstep(0.0, 0.0, 1.0), EPS);
        assertEquals(1.0, FogModel.smoothstep(1.0, 0.0, 1.0), EPS);
        assertEquals(0.5, FogModel.smoothstep(0.5, 0.0, 1.0), EPS);
        double previous = -1;
        for (double x = 0; x <= 1.0001; x += 0.01) {
            double y = FogModel.smoothstep(x, 0.0, 1.0);
            assertTrue(y >= previous - EPS, "smoothstep 非单调 @" + x);
            previous = y;
        }
        // 退化区间（hi <= lo）当阶跃处理，不能除零
        assertEquals(0.0, FogModel.smoothstep(0.4, 1.0, 1.0), EPS);
        assertEquals(1.0, FogModel.smoothstep(1.0, 1.0, 1.0), EPS);
    }

    @Test
    @DisplayName("solarFactor：正午 +1、午夜 -1、日出日落 0")
    void solarFactorPhases() {
        // timeOfDay01: 0=日出、0.25=正午、0.5=日落、0.75=午夜
        assertEquals(0.0, FogModel.solarFactor(0.0), 1e-9);
        assertEquals(1.0, FogModel.solarFactor(0.25), 1e-9);
        assertEquals(0.0, FogModel.solarFactor(0.5), 1e-9);
        assertEquals(-1.0, FogModel.solarFactor(0.75), 1e-9);
        // 旧实现用 sin(2πt−π/2)，峰值落在日落，整整错 6 小时
        assertTrue(FogModel.solarFactor(0.25) > FogModel.solarFactor(0.20));
        assertTrue(FogModel.solarFactor(0.25) > FogModel.solarFactor(0.30));
    }

    @Test
    @DisplayName("平流雾在湿度 0.70 与风速 9 m/s 两个阈值上无跳变")
    void advectionFogHasNoStepAtThresholds() {
        double windAtPeak = 4.0;
        // solar01 = 1.0（白天）：把辐射雾关掉，只观察平流雾这一项
        double solar = 1.0;

        // 扫湿度：0.70 是旧实现的硬边
        double previous = 0;
        for (double rh = 0.60; rh <= 1.0001; rh += 0.002) {
            double d = FogModel.density(solar, false, windAtPeak, rh, true);
            assertTrue(Math.abs(d - previous) < 0.02,
                    "湿度 " + rh + " 处雾浓度从 " + previous + " 跳到 " + d);
            previous = d;
        }

        // 扫风速：1.5 与 9 都是旧实现的硬边
        previous = 0;
        for (double wind = 0.0; wind <= 12.0001; wind += 0.02) {
            double d = FogModel.density(solar, false, wind, 1.0, true);
            assertTrue(Math.abs(d - previous) < 0.02,
                    "风速 " + wind + " 处雾浓度从 " + previous + " 跳到 " + d);
            previous = d;
        }
    }

    @Test
    @DisplayName("平流雾只在海上出现，峰值受 ADVECTION_FOG_PEAK 约束")
    void advectionFogRequiresWater() {
        double onLand = FogModel.density(0.0, false, 4.0, 1.0, false);
        double onSea = FogModel.density(0.0, false, 4.0, 1.0, true);
        assertEquals(0.0, onLand, EPS, "陆地上不应有平流雾");
        assertTrue(onSea > 0.4, "海上满湿 4 m/s 应有明显雾，实测 " + onSea);
        assertTrue(onSea <= FogModel.ADVECTION_FOG_PEAK + EPS,
                "超过 ADVECTION_FOG_PEAK：" + onSea);
    }

    @Test
    @DisplayName("辐射雾：晴朗夜间弱风高湿最浓，白天或大风消散")
    void radiationFogConditions() {
        double night = FogModel.density(-1.0, false, 0.5, 1.0, false);
        assertTrue(night > 0.5, "晴朗夜间高湿弱风应起雾，实测 " + night);

        assertEquals(0.0, FogModel.density(1.0, false, 0.5, 1.0, false), EPS, "正午不该有辐射雾");
        assertEquals(0.0, FogModel.density(-1.0, false, 10.0, 1.0, false), EPS, "大风该吹散");

        // 下雨时辐射雾项关闭；剩下的 0.35 是**雨雾**，不是辐射雾
        assertEquals(FogModel.RAIN_FOG_PEAK,
                FogModel.density(-1.0, true, 0.5, 1.0, false), 1e-9);
    }

    @Test
    @DisplayName("雨雾：阈值 0.85 以上才有，且比辐射雾淡")
    void rainFogIsFaint() {
        assertEquals(0.0, FogModel.density(-1.0, true, 0.0, 0.80, false), EPS);
        double d = FogModel.density(-1.0, true, 0.0, 1.0, false);
        assertEquals(FogModel.RAIN_FOG_PEAK, d, 1e-9);
        assertTrue(d < 0.5, "雨雾应当很淡：" + d);
    }

    @Test
    @DisplayName("输出恒在 [0,1]")
    void densityAlwaysNormalised() {
        for (double solar = -1; solar <= 1; solar += 0.25) {
            for (boolean rain : new boolean[]{false, true}) {
                for (double wind = 0; wind <= 15; wind += 1.5) {
                    for (double rh = 0; rh <= 1; rh += 0.1) {
                        for (boolean water : new boolean[]{false, true}) {
                            double d = FogModel.density(solar, rain, wind, rh, water);
                            assertTrue(d >= 0 && d <= 1, "浓度越界：" + d);
                        }
                    }
                }
            }
        }
    }
}
