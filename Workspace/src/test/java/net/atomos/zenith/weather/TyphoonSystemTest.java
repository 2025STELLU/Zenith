package net.atomos.zenith.weather;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * WX-TY-02 / WX-TY-03 / WX-TY-04：台风切向廓线。
 *
 * <p>旧实现是 Rankine 组合涡 + 两处硬截断：{@code r=eyeRadius} 处 {@code continue}
 * 砍出 13 m/s 的台阶，{@code r=influenceRadius} 处直接把 12 m/s 归零。
 * 现在换成 Holland (1980) 单峰光滑廓线 + smoothstep 外缘，全程 C∞。
 */
class TyphoonSystemTest {

    private static TyphoonSystem.Typhoon typhoon() {
        TyphoonSystem.Typhoon t = new TyphoonSystem.Typhoon();
        t.vmaxMps = 40;
        t.rmaxBlocks = 55;
        t.eyeRadiusBlocks = 18;
        t.influenceRadiusBlocks = 600;
        t.intensity01 = 1.0;
        return t;
    }

    @Test
    @DisplayName("Holland 峰值正好在 r = Rmax，且等于 Vmax")
    void peakIsAtRmax() {
        TyphoonSystem.Typhoon t = typhoon();
        assertEquals(t.vmaxMps, t.tangential(t.rmaxBlocks), 1e-9,
                "r=Rmax 处应严格等于 Vmax");

        // 两侧都更弱
        assertTrue(t.tangential(t.rmaxBlocks * 0.9) < t.vmaxMps);
        assertTrue(t.tangential(t.rmaxBlocks * 1.1) < t.vmaxMps);
    }

    @Test
    @DisplayName("r→0 平滑趋于 0（真静风眼），没有 1/r 奇点")
    void eyeIsCalm() {
        TyphoonSystem.Typhoon t = typhoon();
        assertEquals(0.0, t.tangential(0.0), 0.0, "r=0 必须精确为 0");
        assertTrue(t.tangential(1e-4) < 1e-3, "极小半径应接近 0");
        assertTrue(t.tangential(1.0) < 0.5, "眼内 1 格处应接近静风");
        // Holland 的"眼"比 eyeRadiusBlocks 小得多：风峰在 Rmax=55，眼内衰减是指数式的。
        // 这里只要求眼心附近（0.1·Rmax）弱于 Vmax 的 1/4。
        assertTrue(t.tangential(t.rmaxBlocks * 0.1) < t.vmaxMps * 0.25,
                "0.1·Rmax 处应显著弱于 Vmax，实测 " + t.tangential(t.rmaxBlocks * 0.1));
        assertTrue(t.tangential(t.eyeRadiusBlocks) < t.vmaxMps * 0.35,
                "眼半径处应仍明显弱于眼墙，实测 " + t.tangential(t.eyeRadiusBlocks));
    }

    @Test
    @DisplayName("廓线全程单调：眼内上升、眼墙后衰减")
    void profileIsUnimodal() {
        TyphoonSystem.Typhoon t = typhoon();
        double previous = -1;
        for (double r = 1; r <= t.rmaxBlocks; r += 0.5) {
            double v = t.tangential(r);
            assertTrue(v >= previous - 1e-9, "眼内应单调上升，r=" + r);
            previous = v;
        }
        previous = Double.MAX_VALUE;
        for (double r = t.rmaxBlocks; r <= 2000; r += 0.5) {
            double v = t.tangential(r);
            assertTrue(v <= previous + 1e-9, "眼墙外应单调衰减，r=" + r);
            previous = v;
        }
    }

    @Test
    @DisplayName("WX-TY-02 旧实现 r=eyeRadius 处的 13 m/s 台阶已消除")
    void noStepAtEyeRadius() {
        TyphoonSystem.Typhoon t = typhoon();
        double below = t.tangential(t.eyeRadiusBlocks - 0.01);
        double above = t.tangential(t.eyeRadiusBlocks + 0.01);
        assertTrue(Math.abs(above - below) < 0.05,
                "眼半径两侧跳变 " + Math.abs(above - below) + " m/s，应为连续");
    }

    @Test
    @DisplayName("WX-TY-02 外缘 smoothstep：0.75R 内为 1，R 处为 0，无硬截断")
    void outerTaperIsSmooth() {
        TyphoonSystem.Typhoon t = typhoon();
        double r = t.influenceRadiusBlocks;

        assertEquals(1.0, t.outerTaper(0), 0.0);
        assertEquals(1.0, t.outerTaper(r * 0.75), 0.0, "0.75R 以内不衰减");
        assertEquals(0.0, t.outerTaper(r), 1e-12, "R 处收敛到 0");
        assertEquals(0.0, t.outerTaper(r * 2), 1e-12, "R 外保持 0");

        // 单调下降，且没有跳变
        double previous = 1.0;
        for (double rr = r * 0.75; rr <= r; rr += 1.0) {
            double w = t.outerTaper(rr);
            assertTrue(w <= previous + 1e-12, "外缘权重非单调 @" + rr);
            assertTrue(previous - w < 0.02, "外缘出现跳变 @" + rr);
            previous = w;
        }
    }

    @Test
    @DisplayName("WX-TY-03 登陆衰减按 intensity01 线性缩放")
    void intensityScalesProfile() {
        TyphoonSystem.Typhoon t = typhoon();
        double full = t.tangential(t.rmaxBlocks);
        t.intensity01 = 0.5;
        assertEquals(full * 0.5, t.tangential(t.rmaxBlocks), 1e-9);
    }

    @Test
    @DisplayName("WX-TY-06 中心气压亏损 Δp = ρ·e·Vmax²/B，量级 3.3–6.2 kPa")
    void centralPressureDeficit() {
        TyphoonSystem.Typhoon t = typhoon();   // Vmax = 40
        double dp40 = t.centralPressureDeficitPa();
        assertEquals(3.3e3, dp40, 100, "40 m/s 应约 3.3 kPa");

        t.vmaxMps = 55;
        double dp55 = t.centralPressureDeficitPa();
        assertEquals(6.2e3, dp55, 150, "55 m/s 应约 6.2 kPa");

        // 与 Vmax 平方成正比
        t.vmaxMps = 80;
        assertEquals(dp40 * 4, t.centralPressureDeficitPa(), 1.0);

        // 衰减后按 intensity01² 缩小
        t.vmaxMps = 40;
        t.intensity01 = 0.5;
        assertEquals(dp40 * 0.25, t.centralPressureDeficitPa(), 1.0);
    }

    @Test
    @DisplayName("WX-TY-06 足迹参数有上限，避免 256 格网格算出假 18 m/s 背景风")
    void footprintIsCapped() {
        double cap = net.atomos.zenith.wind.WorldScaleDriver.TYPHOON_L0_MAX_PRESSURE_PA;
        assertTrue(cap > 0);
        assertTrue(cap <= 2000.0,
                "L0 气压封顶过高会让地转风超出 MAX_DYNAMIC_WIND_MPS");

        TyphoonSystem.Typhoon t = typhoon();
        t.vmaxMps = 200;   // 人为放大，检验封顶逻辑由调用方 clamp
        assertTrue(t.centralPressureDeficitPa() > cap,
                "该用例应当超过封顶，否则封顶值没有实际作用");
    }

    @Test
    @DisplayName("旋向由 HemisphereConvention 提供，不再写死 spin=1")
    void spinComesFromHemisphere() {
        TyphoonSystem.Typhoon t = typhoon();
        assertEquals(net.atomos.zenith.wind.HemisphereConvention.coriolisSign(),
                t.spin(), 0.0);
    }
}
