package net.atomos.zenith.api;

/**
 * 单点风场采样结果：平均风、阵风、有效风（平均+阵风+本地修正），外加一堆大气诊断量。
 *
 * 信任规则：只有 isTrustedForGameplay() 为 true 的采样才能用在服务端玩法
 * （实体受力、红石、伤害等）。客户端本地 L2 数据永远别拿去算玩法，防作弊。
 */
public final class ZenithWindSample {
    /**
     * 采样值的来源档位。
     *
     * <p>旧定义只有 {@code NONE/L0/L1/L2} 四档（API-11）：而服务端返回的实际上是
     * "L0 → L1 → 9 个天气现象 → L2"四层**叠加**的结果，标任何单档都是撒谎。
     * 新增 {@link #COMPOSITE}：{@link #layers()} 里置位多于一层时用它。
     * 想精确判断"含不含某一层"请用 {@link #hasLayer(int)}，别拿 {@code source()} 做位运算。
     */
    public enum Source { NONE, L0_BACKGROUND, L1_COARSE, PHENOMENA, L2_LOCAL, COMPOSITE }

    public enum Authority { UNKNOWN, SERVER_AUTHORITATIVE, CLIENT_LOCAL }

    /** {@link #layers()} 的位标记：L0 背景场。 */
    public static final int LAYER_L0 = 1;
    /** L1 中尺度场。 */
    public static final int LAYER_L1 = 2;
    /** 天气现象层（台风/飑线/尘卷风/热泡/背风波/海风）。 */
    public static final int LAYER_PHENOMENA = 4;
    /** L2 局地修正（绕流/尾流/热浮力/射流）。 */
    public static final int LAYER_L2 = 8;

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
    /** 实际参与合成的层位标记（API-11）：{@link #LAYER_L0} 等按位或。 */
    private final int layers;

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
        this.source = Integer.bitCount(b.layers) > 1 ? Source.COMPOSITE : b.source;
        this.authority = b.authority;
        this.freshnessEpoch = b.freshnessEpoch;
        this.confidence = b.confidence;
        this.layers = b.layers;
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

    /**
     * 管道/接力用的风速 = 平均 + 本地修正，**不含阵风**（BLK-07）。
     *
     * <p>风扇/风道要判断"上游有没有风在推我"：阵风是随机涨落，让风道按阵风开关会每秒
     * 抖动一次；而本地修正里含上游射流，才是真正该接力的东西。
     */
    public ZenithVec3 pipelineVelocityVector() {
        return meanVelocity.add(localModifier);
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

    /**
     * 是否真的"有风"。
     *
     * <p>旧实现是 {@code source != NONE}（API-11）：客户端没收到任何包时
     * {@code ClientWindState} 仍然标 {@code L1_COARSE}，于是 {@code hasFlow()==true}
     * 而风速严格为 0——风向标的 {@code if (!hasFlow()) return;} 永远不为真，
     * 箭头锁死在 {@code atan2(0,0)=0}（正南）。现在要求**矢量真的有模长**。
     */
    public boolean hasFlow() {
        return source != Source.NONE
                && (meanVelocity.lengthSqr() > 1e-12
                    || gustVelocity.lengthSqr() > 1e-12
                    || localModifier.lengthSqr() > 1e-12);
    }
    /** 是否处于建筑/地形遮蔽区（风速显著衰减）。 */
    public boolean isSheltered() { return sheltered; }
    public boolean hasLocalModifier() { return localModifier.length() > 1e-6; }

    /** 实际参与合成的层位标记（API-11）：{@link #LAYER_L0} 等按位或。 */
    public int layers() { return layers; }
    /** 结果里是否含某一层（{@link #LAYER_L0} / {@link #LAYER_L1} / …）。 */
    public boolean hasLayer(int layerFlag) { return (layers & layerFlag) != 0; }

    public Source source() { return source; }
    public Authority authority() { return authority; }
    public long freshnessEpoch() { return freshnessEpoch; }
    public boolean hasFreshnessEpoch() { return freshnessEpoch >= 0; }
    public float confidence() { return confidence; }

    public boolean isServerTrusted() { return authority == Authority.SERVER_AUTHORITATIVE; }
    public boolean isClientLocal() { return authority == Authority.CLIENT_LOCAL; }

    /**
     * 玩法可用性：服务端权威 + 有数据来源 + 置信度非零。
     *
     * <p>这里**不**用 {@link #hasFlow()}：真的无风（比如被地形完全遮蔽的洞穴）也是
     * 合法结果，用"矢量是否有模长"判可信会让风速计显示"无数据"、帆船在静风里仍吃
     * 一次无效采样。判断"UI 要不要画箭头"用 {@code hasFlow()}，判"敢不敢拿去算玩法"
     * 用这个方法。
     */
    public boolean isTrustedForGameplay() {
        return isServerTrusted() && source != Source.NONE && confidence > 0.0f;
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
        private int layers = 0;

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
        /**
         * 单个来源档位：同时把它对应的层位写进 {@link #layers(int)}。
         * 多层的合成结果请用 {@link #layers(int)} 直接给位标记，构造时会自动标成
         * {@link Source#COMPOSITE}。
         */
        public Builder source(Source s) {
            this.source = s;
            int flag = switch (s) {
                case L0_BACKGROUND -> LAYER_L0;
                case L1_COARSE -> LAYER_L1;
                case PHENOMENA -> LAYER_PHENOMENA;
                case L2_LOCAL -> LAYER_L2;
                case NONE, COMPOSITE -> 0;
            };
            if (flag != 0) this.layers |= flag;
            return this;
        }
        /** 实际参与合成的层位标记（{@link ZenithWindSample#LAYER_L0} 等按位或）。 */
        public Builder layers(int flags) { this.layers = flags; return this; }
        public Builder authority(Authority a) { this.authority = a; return this; }
        public Builder freshnessEpoch(long e) { this.freshnessEpoch = e; return this; }
        public Builder confidence(float c) { this.confidence = c; return this; }

        public ZenithWindSample build() { return new ZenithWindSample(this); }
    }
}
