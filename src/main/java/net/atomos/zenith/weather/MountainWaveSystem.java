package net.atomos.zenith.weather;

import net.atomos.zenith.wind.SeedTerrainProvider;
import net.minecraft.server.level.ServerLevel;

/**
 * 地形背风波：风翻过山脊之后在山后激起的驻波，还有山脚下的转子湍流。
 *
 * <p>解析式诊断，没有离散对象：沿上风方向最多 {@value #UPWIND_MAX_BLOCKS} 格、等距取
 * {@value #UPWIND_SAMPLES} 个高度点，找最高点当山脊，再用三点抛物线插值把山脊位置细化到
 * 亚采样精度（WX-MW-02）。旧实现只探 5 个固定距离（80/160/320/560/800），相位因此只有
 * 6 个取值，背风波呈"条带"而不是连续波。
 *
 * <p>山够高（高差 &gt; 25 格）且风够快（&gt; 3 m/s）才起波。波长按内重力波公式
 * {@code λ = 2π·U/N}（{@code N = 0.012 s⁻¹}）算，但**乘一个显式的艺术压缩系数**
 * {@link #WAVE_SCALE_COMPRESSION}（WX-MW-01）：真实 λ 在 U = 5–15 m/s 时是 2.6–7.9 km，
 * MC 视距里连半个波都装不下。压缩后落在 40–400 格，约 1:12。这是刻意的尺度压缩，
 * 不是物理结论——相位/振幅的推导都以压缩后的 λ 为准。
 *
 * <p>垂直速度 {@code w = A·sin(2π·d/λ)·exp(−agl/350)}，振幅跟山高和风速成正比。
 * 山后近地面还有转子区——一坨强湍流，滑翔机飞进去会很难受，但波峰的上升气流倒是可以
 * 借来爬升，跟热对流互补。
 *
 * <p><b>地形来源（WX-MW-03）</b>：{@code ctx.terrain()} 注入的是
 * {@code ZenithServerRuntime.McTerrainAdapter}，区块已加载时读真实高度图、未加载才回退
 * 哈希估计；不再是旧的 {@code SeedTerrainProvider} 纯种子地形。
 */
public class MountainWaveSystem implements WeatherPhenomenon {
    public static final double BRUNT_VAISALA_N = 0.012; // s^-1

    /**
     * 波长艺术压缩比（WX-MW-01）。真实背风波波长 0.5–5 km，游戏里压到 1:12 左右才看得见。
     * 显式常量而不是写死在 clamp 里：任何"为什么波长这么短"的问题都能指到这里。
     */
    public static final double WAVE_SCALE_COMPRESSION = 0.08;
    public static final double MIN_WAVELENGTH_BLOCKS = 40.0;
    public static final double MAX_WAVELENGTH_BLOCKS = 400.0;

    /** 上风向探测的点数（WX-MW-02）：12–16 点 + 抛物线插值。 */
    public static final int UPWIND_SAMPLES = 16;
    /** 上风向探测的最远距离（格）。 */
    public static final double UPWIND_MAX_BLOCKS = 800.0;
    /** 起波的最小高差（格）。 */
    public static final double MIN_RIDGE_RISE_BLOCKS = 25.0;
    /** 起波的最小风速（m/s）。 */
    public static final double MIN_WAVE_WIND_MPS = 3.0;

    /** 高度采样函数：让山脊搜索可以脱离 {@link SeedTerrainProvider} 单测（WX-MW-02）。 */
    @FunctionalInterface
    public interface HeightFn {
        double heightAt(double x, double z);
    }

    private SeedTerrainProvider terrain;

    @Override
    public void tick(ServerLevel level, double dtSeconds, WeatherContext ctx) {
        this.terrain = ctx.terrain();
    }

    @Override
    public WindContribution sample(double x, double y, double z, double baseWindX, double baseWindZ) {
        if (terrain == null) return WindContribution.NONE;
        double speed = Math.hypot(baseWindX, baseWindZ);
        if (speed < MIN_WAVE_WIND_MPS) return WindContribution.NONE;

        double upX = -baseWindX / speed, upZ = -baseWindZ / speed;
        double hHere = terrain.heightAt(x, z);
        double[] crest = crestDistance(x, z, upX, upZ, terrain::heightAt);
        double crestDist = crest[0];
        double crestH = crest[1];

        double ridgeRise = crestH - hHere;
        if (ridgeRise < MIN_RIDGE_RISE_BLOCKS || crestDist < MIN_WAVELENGTH_BLOCKS) {
            return WindContribution.NONE;
        }

        double wavelength = wavelengthBlocks(speed);
        double agl = Math.max(0, y - hHere);
        double amplitude = Math.min(6.0, ridgeRise * 0.04 * speed * 0.15);
        double phase = 2 * Math.PI * crestDist / wavelength;
        double hDecay = Math.exp(-agl / 350.0);

        double w = amplitude * Math.sin(phase) * hDecay;
        double speedMod = 1.0 + 0.15 * Math.cos(phase) * hDecay;

        // 转子：山后低空的一坨强湍流，离山越近、越低越强
        double rotorTurb = 0;
        if (agl < 120 && crestDist < 600) {
            rotorTurb = 0.45 * Math.exp(-crestDist / 300.0) * (1 - agl / 120.0);
        }

        double vx = baseWindX * (speedMod - 1.0);
        double vz = baseWindZ * (speedMod - 1.0);
        return new WindContribution(vx, w, vz, Math.min(0.7, rotorTurb + 0.08), 0);
    }

    /** 压缩后的波长（格）：{@code clamp(2π·U/N · compression, 40, 400)}（WX-MW-01）。 */
    public static double wavelengthBlocks(double windSpeedMps) {
        double physical = 2.0 * Math.PI * windSpeedMps / BRUNT_VAISALA_N;
        return clamp(physical * WAVE_SCALE_COMPRESSION,
                MIN_WAVELENGTH_BLOCKS, MAX_WAVELENGTH_BLOCKS);
    }

    /**
     * 沿上风向找山脊，返回 {@code {crestDist, crestHeight}}（WX-MW-02）。
     *
     * <p>等距采样 {@link #UPWIND_SAMPLES} 点；最高点与左右邻点做三点抛物线插值，
     * 把山脊位置细化到亚采样精度。这样相邻格子的 {@code crestDist} 连续变化，
     * 相位不再被量化到 6 档。
     *
     * @return {@code crestDist = 0} 表示上风方向没有比本地更高的点
     */
    static double[] crestDistance(double x, double z, double upX, double upZ, HeightFn fn) {
        double step = UPWIND_MAX_BLOCKS / UPWIND_SAMPLES;
        double[] heights = new double[UPWIND_SAMPLES + 1];
        heights[0] = fn.heightAt(x, z);
        int best = 0;
        for (int i = 1; i <= UPWIND_SAMPLES; i++) {
            double d = i * step;
            heights[i] = fn.heightAt(x + upX * d, z + upZ * d);
            if (heights[i] > heights[best]) best = i;
        }
        if (best == 0) return new double[]{0, heights[0]};

        // 三点抛物线顶点：y(d) 在等距采样上拟合，顶点偏移 ∈ [-step, step]
        double offset = 0;
        if (best > 0 && best < UPWIND_SAMPLES) {
            double ym = heights[best - 1], y0 = heights[best], yp = heights[best + 1];
            double denom = ym - 2 * y0 + yp;
            if (Math.abs(denom) > 1e-9) {
                offset = clamp(0.5 * (ym - yp) / denom, -1.0, 1.0) * step;
            }
        }
        double crestDist = best * step + offset;
        double crestH = heights[best];
        if (offset != 0) {
            // 用顶点处的抛物线值当山脊高度：比"采样点高度"更贴近真实脊线
            double ym = best > 0 ? heights[best - 1] : heights[best];
            double yp = best < UPWIND_SAMPLES ? heights[best + 1] : heights[best];
            double denom = ym - 2 * heights[best] + yp;
            if (Math.abs(denom) > 1e-9) {
                crestH = heights[best] - 0.25 * (ym - yp) * offset / step;
            }
        }
        return new double[]{Math.max(0, crestDist), crestH};
    }

    @Override
    public boolean hasActive() {
        return terrain != null; // 只要有地形数据就可能有波
    }

    private static double clamp(double v, double lo, double hi) {
        return v < lo ? lo : Math.min(v, hi);
    }
}
