package net.atomos.zenith.weather;

/** 天气现象在采样点的风场贡献。 */
public record WindContribution(
        double vx, double vy, double vz,
        double turbulenceAdd,
        double precipIntensity01) {

    public static final WindContribution NONE =
            new WindContribution(0, 0, 0, 0, 0);

    public boolean isNone() {
        return vx == 0 && vy == 0 && vz == 0 && turbulenceAdd == 0 && precipIntensity01 == 0;
    }

    public WindContribution add(WindContribution o) {
        return new WindContribution(
                vx + o.vx, vy + o.vy, vz + o.vz,
                Math.min(1.0, turbulenceAdd + o.turbulenceAdd),
                Math.min(1.0, precipIntensity01 + o.precipIntensity01));
    }
}
