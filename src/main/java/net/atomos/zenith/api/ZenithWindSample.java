package net.atomos.zenith.api;

/**
 * 单点风场采样结果：平均风、阵风、有效风（平均+阵风+本地修正），外加一堆大气诊断量。
 *
 * 信任规则：只有 isTrustedForGameplay() 为 true 的采样才能用在服务端玩法
 * （实体受力、红石、伤害等）。客户端本地 L2 数据永远别拿去算玩法，防作弊。
 */
public final class ZenithWindSample {
    public enum Source { NONE, L0_BACKGROUND, L1_COARSE, L2_LOCAL }

    public enum Authority { UNKNOWN, SERVER_AUTHORITATIVE, CLIENT_LOCAL }

    private final ZenithVec3 meanVelocity;
    private final ZenithVec3 gustVelocity;
    private final ZenithVec3 localModifier;
    private final float turbulenceIntensity;
    private final float windShearPerBlock;
    private final float pressureAnomalyPa;
    private final float airTemperatureKelvin;
    private final float humidity;
    private final boolean hasTemperature;
    private final boolean hasHumidity;
    private final boolean sheltered;
    private final Source source;
    private final Authority authority;
    private final long freshnessEpoch;
    private final float confidence;

    private ZenithWindSample(Builder b) {
        this.meanVelocity = b.meanVelocity;
        this.gustVelocity = b.gustVelocity;
        this.localModifier = b.localModifier;
        this.turbulenceIntensity = b.turbulenceIntensity;
        this.windShearPerBlock = b.windShearPerBlock;
        this.pressureAnomalyPa = b.pressureAnomalyPa;
        this.airTemperatureKelvin = b.airTemperatureKelvin;
        this.humidity = b.humidity;
        this.hasTemperature = b.hasTemperature;
        this.hasHumidity = b.hasHumidity;
        this.sheltered = b.sheltered;
        this.source = b.source;
        this.authority = b.authority;
        this.freshnessEpoch = b.freshnessEpoch;
        this.confidence = b.confidence;
    }

    public static Builder builder() { return new Builder(); }

    /** 平均风速矢量 (m/s)。 */
    public ZenithVec3 meanVelocityVector() { return meanVelocity; }
    /** 阵风分量 (m/s)。 */
    public ZenithVec3 gustVelocityVector() { return gustVelocity; }
    /** 本地修正（障碍物绕流/热浮力，L2 层）。 */
    public ZenithVec3 localModifierVector() { return localModifier; }
    /** 有效风速 = 平均 + 阵风 + 本地修正。 */
    public ZenithVec3 effectiveVelocityVector() {
        return meanVelocity.add(gustVelocity).add(localModifier);
    }
    /** 阵风叠加后的风速（不含本地修正）。 */
    public ZenithVec3 velocityWithGustVector() { return meanVelocity.add(gustVelocity); }

    public float speedMetersPerSecond() { return (float) meanVelocity.length(); }
    public float horizontalSpeedMetersPerSecond() { return (float) meanVelocity.horizontalLength(); }
    public float effectiveSpeedMetersPerSecond() { return (float) effectiveVelocityVector().length(); }
    public float turbulenceIntensity() { return turbulenceIntensity; }
    public float windShearMagnitudePerBlock() { return windShearPerBlock; }
    public float pressureAnomalyPa() { return pressureAnomalyPa; }
    public float airTemperatureKelvin() { return airTemperatureKelvin; }
    public float humidity01() { return humidity; }
    public boolean hasTemperature() { return hasTemperature; }
    public boolean hasHumidity() { return hasHumidity; }
    public boolean hasTurbulence() { return turbulenceIntensity >= 0; }
    public boolean hasGust() { return gustVelocity.length() > 1e-6; }
    public boolean hasWindShear() { return windShearPerBlock >= 0; }
    public boolean hasFlow() { return source != Source.NONE; }
    /** 是否处于建筑/地形遮蔽区（风速显著衰减）。 */
    public boolean isSheltered() { return sheltered; }
    public boolean hasLocalModifier() { return localModifier.length() > 1e-6; }

    public Source source() { return source; }
    public Authority authority() { return authority; }
    public long freshnessEpoch() { return freshnessEpoch; }
    public boolean hasFreshnessEpoch() { return freshnessEpoch >= 0; }
    public float confidence() { return confidence; }

    public boolean isServerTrusted() { return authority == Authority.SERVER_AUTHORITATIVE; }
    public boolean isClientLocal() { return authority == Authority.CLIENT_LOCAL; }
    public boolean isTrustedForGameplay() {
        return isServerTrusted() && hasFlow() && confidence > 0.0f;
    }

    public static final class Builder {
        private ZenithVec3 meanVelocity = ZenithVec3.ZERO;
        private ZenithVec3 gustVelocity = ZenithVec3.ZERO;
        private ZenithVec3 localModifier = ZenithVec3.ZERO;
        private float turbulenceIntensity = -1;
        private float windShearPerBlock = -1;
        private float pressureAnomalyPa = 0;
        private float airTemperatureKelvin = Float.NaN;
        private float humidity = -1;
        private boolean hasTemperature = false;
        private boolean hasHumidity = false;
        private boolean sheltered = false;
        private Source source = Source.NONE;
        private Authority authority = Authority.UNKNOWN;
        private long freshnessEpoch = -1;
        private float confidence = 0;

        public Builder meanVelocity(ZenithVec3 v) { this.meanVelocity = v; return this; }
        public Builder gustVelocity(ZenithVec3 v) { this.gustVelocity = v; return this; }
        public Builder localModifier(ZenithVec3 v) { this.localModifier = v; return this; }
        public Builder turbulenceIntensity(float f) { this.turbulenceIntensity = f; return this; }
        public Builder windShearPerBlock(float f) { this.windShearPerBlock = f; return this; }
        public Builder pressureAnomalyPa(float p) { this.pressureAnomalyPa = p; return this; }
        public Builder airTemperatureKelvin(float t) {
            this.airTemperatureKelvin = t; this.hasTemperature = true; return this;
        }
        public Builder humidity01(float h) {
            this.humidity = h; this.hasHumidity = true; return this;
        }
        public Builder sheltered(boolean s) { this.sheltered = s; return this; }
        public Builder source(Source s) { this.source = s; return this; }
        public Builder authority(Authority a) { this.authority = a; return this; }
        public Builder freshnessEpoch(long e) { this.freshnessEpoch = e; return this; }
        public Builder confidence(float c) { this.confidence = c; return this; }

        public ZenithWindSample build() { return new ZenithWindSample(this); }
    }
}
