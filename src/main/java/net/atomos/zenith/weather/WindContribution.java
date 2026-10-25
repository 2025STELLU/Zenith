package net.atomos.zenith.weather;

/**
 * 天气现象在采样点的贡献。
 *
 * <p><b>WX-FR-02</b>：旧结构只有风与降水，锋面过境只剩"突然刮一阵"，
 * 温度骤降与气压跳升这两个最直观的锋面特征完全缺失。现在多了
 * {@link #tempDeltaK} 与 {@link #pressureDeltaPa}：现象层可以表达
 * "冷锋后 −4 K / +3 hPa"这类热力-气压扰动，由
 * {@code ZenithServerRuntime.sampleInternal} 叠加到 L1 输出的
 * {@code airTemperatureKelvin} / {@code pressureAnomalyPa} 上。
 *
 * @param vx              东向风增量（m/s）
 * @param vy              垂直风增量（m/s）
 * @param vz              南向风增量（m/s）
 * @param turbulenceAdd   湍流强度增量（0..1，叠加后钳到 1）
 * @param precipIntensity01 降水强度（0..1，取各现象最大值）
 * @param tempDeltaK      温度增量（K，正 = 升温）
 * @param pressureDeltaPa 气压异常增量（Pa，正 = 加压）
 */
public record WindContribution(
        double vx, double vy, double vz,
        double turbulenceAdd,
        double precipIntensity01,
        double tempDeltaK,
        double pressureDeltaPa) {

    public static final WindContribution NONE =
            new WindContribution(0, 0, 0, 0, 0, 0, 0);

    /**
     * 只带风/湍流/降水的五参构造（WX-FR-02 之前的调用点全部走这里）。
     * 热力与气压扰动默认 0，行为与旧版完全一致。
     */
    public WindContribution(double vx, double vy, double vz,
                            double turbulenceAdd, double precipIntensity01) {
        this(vx, vy, vz, turbulenceAdd, precipIntensity01, 0, 0);
    }

    public boolean isNone() {
        return vx == 0 && vy == 0 && vz == 0
                && turbulenceAdd == 0 && precipIntensity01 == 0
                && tempDeltaK == 0 && pressureDeltaPa == 0;
    }

    public WindContribution add(WindContribution o) {
        return new WindContribution(
                vx + o.vx, vy + o.vy, vz + o.vz,
                Math.min(1.0, turbulenceAdd + o.turbulenceAdd),
                Math.min(1.0, precipIntensity01 + o.precipIntensity01),
                tempDeltaK + o.tempDeltaK,
                pressureDeltaPa + o.pressureDeltaPa);
    }
}
