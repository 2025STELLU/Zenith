package net.atomos.zenith.api;

/**
 * 服务端玩法用的采样，保证来自服务端权威数据源。
 * 用之前先查 {@link #isTrustedForGameplay()}，没信任的别直接拿去用。
 */
public final class GameplayWindSample {
    private final ZenithWindSample inner;

    private GameplayWindSample(ZenithWindSample inner) {
        this.inner = inner;
    }

    public static GameplayWindSample of(ZenithWindSample sample) {
        return new GameplayWindSample(sample);
    }

    /** 不受信任时的空采样：全是 0，就当没风。 */
    public static GameplayWindSample empty() {
        return new GameplayWindSample(ZenithWindSample.builder()
                .authority(ZenithWindSample.Authority.UNKNOWN)
                .source(ZenithWindSample.Source.NONE)
                .build());
    }

    public boolean isTrustedForGameplay() { return inner.isTrustedForGameplay(); }
    public ZenithVec3 meanVelocityVector() { return inner.meanVelocityVector(); }
    public ZenithVec3 gustVelocityVector() { return inner.gustVelocityVector(); }
    public ZenithVec3 effectiveVelocityVector() { return inner.effectiveVelocityVector(); }
    public float meanSpeedMetersPerSecond() { return inner.speedMetersPerSecond(); }
    public float horizontalMeanSpeedMetersPerSecond() { return inner.horizontalSpeedMetersPerSecond(); }
    public float effectiveSpeedMetersPerSecond() { return inner.effectiveSpeedMetersPerSecond(); }
    public float turbulenceIntensity() { return inner.turbulenceIntensity(); }
    public boolean hasFlow() { return inner.hasFlow(); }
    public boolean hasTemperature() { return inner.hasTemperature(); }
    public boolean hasHumidity() { return inner.hasHumidity(); }
    public boolean hasLocalModifier() { return inner.hasLocalModifier(); }
    public boolean isSheltered() { return inner.isSheltered(); }
    public float pressureAnomalyPa() { return inner.pressureAnomalyPa(); }
    public float airTemperatureKelvin() { return inner.airTemperatureKelvin(); }

    public ZenithWindSample unwrap() { return inner; }
}
