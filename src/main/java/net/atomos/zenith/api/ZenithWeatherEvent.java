package net.atomos.zenith.api;

/**
 * 天气生命周期事件（API-05）。
 *
 * <p><b>为什么需要</b>：旧 API 只有 pull 式查询（{@code fronts()} / {@code typhoons()} /
 * {@code squallLines()}）。想知道"台风刚生成"或"风暴进入成熟期"只能每 tick 轮询，
 * 而 {@code fronts()} 每次调用都会 {@code stream().map().toList()} 新建一个 List——
 * 20 人服里每个模组每 tick 轮询一次就是几千次无谓分配。
 *
 * <p><b>为什么不挂 NeoForge EventBus</b>：{@code api/} 包的约定是**不依赖 MC/NeoForge**
 * （见 {@link ZenithId} / {@link ZenithVec3} 的注释），这样离线单测和第三方纯逻辑代码
 * 都能直接编译。所以这里用极简的监听器注册表，{@link ZenithWeatherEvents} 负责分发；
 * 服务端权威数据由 {@code ZenithServerRuntime} 在现象 tick 之后触发。
 *
 * <p><b>线程</b>：只在服务端逻辑线程分发。监听器里不要写盘、不要阻塞。
 */
public final class ZenithWeatherEvent {
    public enum Type {
        /** 台风生成（洋面、风暴活动度足够）。 */
        TYPHOON_SPAWNED,
        /** 台风消散（寿命耗尽或强度衰减到阈值以下）。 */
        TYPHOON_DISSIPATED,
        /** 风暴单体进入成熟期（开始降冰雹/强降水）。 */
        STORM_MATURED,
        /** 飑线生成。 */
        SQUALL_LINE_FORMED,
        /** 龙卷触地。 */
        TORNADO_FORMED,
        /** 尘卷风生成。 */
        DUST_DEVIL_FORMED
    }

    private final Type type;
    private final ZenithWorldRef world;
    private final double x;
    private final double z;
    /** 强度 0~1；龙卷/尘卷风是半径归一前的强度，台风是 vmax/70。 */
    private final double intensity01;
    /** 台风名字；其它事件为空串。 */
    private final String name;

    private ZenithWeatherEvent(Type type, ZenithWorldRef world, double x, double z,
                               double intensity01, String name) {
        this.type = type;
        this.world = world;
        this.x = x;
        this.z = z;
        this.intensity01 = intensity01;
        this.name = name == null ? "" : name;
    }

    public static ZenithWeatherEvent of(Type type, ZenithWorldRef world,
                                        double x, double z, double intensity01, String name) {
        return new ZenithWeatherEvent(type, world, x, z, intensity01, name);
    }

    public Type type() { return type; }
    public ZenithWorldRef world() { return world; }
    public double x() { return x; }
    public double z() { return z; }
    public double intensity01() { return intensity01; }
    public String name() { return name; }

    @Override public String toString() {
        return "ZenithWeatherEvent[" + type + " @" + (int) x + "," + (int) z
                + " i=" + String.format("%.2f", intensity01) + "]";
    }
}
