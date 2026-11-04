package net.atomos.zenith.client.render.tornado;

/**
 * 龙卷外形的纯数学部分：凝结漏斗廓线、轴线弯曲、旋转速率。
 * 网格渲染和体积着色器（{@code tornado_volume.fsh}）用的是同一套公式，改一边要同步改另一边。
 *
 * <h2>凝结漏斗为什么是这个形状</h2>
 * 漏斗云并不是风的边界，而是一条<b>等压面</b>：气块被吸进低压核心、绝热膨胀降温，
 * 降到露点就凝结成可见的云。所以在高度 {@code z}，凝结发生的条件是
 * <pre>  Δp(r) ≥ Δp_c(z) ≈ ρ g (H − z)</pre>
 * 其中 {@code H} 是云底（抬升凝结高度）。离云底越近，需要的气压亏损越小，漏斗就越粗；
 * 越往地面需要的亏损越大，漏斗就越细，太弱的龙卷根本"够不着地"，只剩悬在半空的漏斗云
 * 加上地面一团看不见风、只看得见尘的碎屑旋涡。现实中龙卷正是这个样子。
 *
 * <p>径向气压廓线取兰金涡：核心内 {@code Δp/Δp₀ = 1 − n²/2}，核心外 {@code 1/(2n²)}，
 * {@code n = r / r_c}。反解就得到每个高度的漏斗半径，不需要任何手调曲线：
 * 形成期漏斗从云底往下"长"，消散期往上收、变细变弯（绳状期），都是这条式子自然给出的。
 *
 * <p>为了让游戏里 80–110 格的云底也有合理的比例，用无量纲强度
 * {@code A = 包络 × (V_max / V_ref)²} 代替真实的 {@code ρV²/(ρgH)}，
 * 并把触地门槛定为 {@link #TOUCH_THRESHOLD}。这是美术上的唯一自由度：
 * 门槛越低，越容易出现"楔形"大漏斗。
 */
public final class TornadoShape {
    private TornadoShape() {}

    /**
     * 参考风速。服务端龙卷最大风速在 18–42 m/s：
     * 18 m/s 只有悬空漏斗 + 地面尘旋，24 m/s 漏斗下到半空，30 m/s 正好触地，42 m/s 是粗壮的楔形。
     */
    public static final float V_REF = 30.0f;
    /** 地面处所需的无量纲气压亏损。A ≥ 它才触地。 */
    public static final float TOUCH_THRESHOLD = 0.85f;
    /** 门槛随高度衰减的指数：>1 让漏斗下半段更直、上半段更快张开成喇叭口。 */
    public static final float THRESHOLD_EXP = 1.5f;
    /** 漏斗最粗不超过核心半径的这么多倍，再往外就交给墙云。 */
    public static final float MAX_RADIUS_FACTOR = 4.2f;

    /** 无量纲强度：寿命包络 × 风速平方比，钳在 [0, 1.6]。 */
    public static float strength(float intensity01, float maxWindMps) {
        float w = maxWindMps / V_REF;
        float s = intensity01 * Math.max(0.3f, w * w);
        return Math.max(0f, Math.min(1.6f, s));
    }

    /** 高度 zeta（0 = 地面，1 = 云底）处的凝结门槛。 */
    public static float threshold(float zeta) {
        float k = 1f - Math.max(0f, Math.min(1f, zeta));
        return TOUCH_THRESHOLD * (float) Math.pow(k, THRESHOLD_EXP);
    }

    /**
     * 漏斗半径（以核心半径为单位），在该高度不凝结时返回 0。
     * 反解兰金涡气压廓线 {@code A·g(n) = threshold}。
     */
    public static float radiusFactor(float zeta, float strength) {
        if (strength <= 1e-4f) return 0f;
        float thr = threshold(zeta);
        if (thr >= strength) return 0f;
        float q = thr / strength;               // ∈ (0, 1)
        float n;
        if (q >= 0.5f) {
            n = (float) Math.sqrt(2f * (1f - q)); // 核心内
        } else {
            n = (float) Math.sqrt(1f / (2f * Math.max(q, 1e-4f))); // 核心外
        }
        return Math.min(n, MAX_RADIUS_FACTOR);
    }

    /** 凝结漏斗下沿的 zeta：0 = 已触地；>0 = 漏斗还悬在半空。 */
    public static float funnelBottom(float strength) {
        if (strength >= TOUCH_THRESHOLD) return 0f;
        if (strength <= 1e-4f) return 1f;
        return 1f - (float) Math.pow(strength / TOUCH_THRESHOLD, 1f / THRESHOLD_EXP);
    }

    /**
     * 地面碎屑云强度 [0, 1]：近地风速够大就卷起碎屑，不管漏斗有没有触地。
     * 现实中"先见尘、后见漏斗"是龙卷触地最典型的观感。
     */
    public static float groundDebris(float strength) {
        return smoothstep(0.25f, 0.75f, strength);
    }

    /**
     * 轴线水平偏移（相对地面接触点）。写进 {@code out[0..1]}。
     *
     * @param zeta     高度 0..1
     * @param heightBl 云底离地高度（格）
     * @param tiltX,tiltZ 移动方向上的倾斜量（单位向量 × 倾斜系数），地面受摩擦拖后，顶部领先
     * @param wobble   摆动幅度（格），绳状期会大很多
     * @param seed     每个龙卷不同的相位
     * @param t        秒
     */
    public static void axisOffset(float zeta, float heightBl, float tiltX, float tiltZ,
                                  float wobble, float seed, float t, float[] out) {
        float lean = heightBl * (float) Math.pow(zeta, 1.6f);
        float env = (float) Math.sin(Math.PI * Math.min(1f, zeta * 1.15f));
        float wx = (float) Math.sin(2 * Math.PI * (1.3f * zeta - 0.15f * t) + seed);
        float wz = (float) Math.cos(2 * Math.PI * (0.9f * zeta - 0.11f * t) + seed * 1.7f);
        // 第二个更高频的摆动模态：绳状期的"S"形
        float wx2 = (float) Math.sin(2 * Math.PI * (2.7f * zeta - 0.31f * t) + seed * 2.3f);
        out[0] = tiltX * lean + wobble * env * (wx + 0.35f * wx2);
        out[1] = tiltZ * lean + wobble * env * (wz + 0.35f * wx2);
    }

    /**
     * 视觉旋转速率（圈/秒），带符号（spin=+1 北半球逆时针）。
     * 用 v/r 的量级但压缩过：真实 45 m/s、10 m 半径是 0.7 圈/秒，太快会"转成糊"。
     */
    public static float rotationRate(float strength, float spin, float radiusFactor) {
        float base = 0.22f + 0.38f * Math.min(1.2f, strength);
        // 越往上越粗，角速度越慢（角动量守恒的意味）
        return spin * base / Math.max(0.6f, (float) Math.sqrt(Math.max(radiusFactor, 0.01f)));
    }

    public static float smoothstep(float e0, float e1, float x) {
        float t = Math.max(0f, Math.min(1f, (x - e0) / (e1 - e0)));
        return t * t * (3f - 2f * t);
    }

    /** 稳定的 [0,1) 伪随机，渲染时每帧可重复（不要用 Random，会闪）。 */
    public static float hash01(int a, int b) {
        int h = a * 0x27d4eb2d ^ (b + 0x165667b1) * 0x9E3779B1;
        h ^= h >>> 15;
        h *= 0x85ebca6b;
        h ^= h >>> 13;
        return (h & 0xFFFFFF) / (float) 0x1000000;
    }
}
