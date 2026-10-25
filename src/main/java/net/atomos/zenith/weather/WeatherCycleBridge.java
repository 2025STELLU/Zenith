package net.atomos.zenith.weather;

import net.atomos.zenith.ZenithConfig;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;
import java.util.function.Predicate;

/**
 * 把模组降水写回原版天气状态机（WX-SC-04）。
 *
 * <p><b>问题</b>：雷暴单体在成熟期 {@code sample()} 返回 {@code precipIntensity01 = 0.9}、
 * 台风眼墙返回 0.95，但 {@code level.setRainLevel}/{@code setThunderLevel} 一次都没被调用过。
 * 于是：
 * <ul>
 *   <li>天上不会下雨、不会有雷声、天空不变暗；</li>
 *   <li>{@code level.isRaining()} 恒为原版值 → 客户端 {@code WeatherWindController}
 *       在 {@code !level.isRaining()} 处直接 return，雨丝粒子永不生成；</li>
 *   <li>驱动器 {@code advance(..., raining, thundering, ...)} 拿到的也是原版值，
 *       模组自己造的风暴反而不提升 {@code stormActivity}。</li>
 * </ul>
 *
 * <p><b>做法（与原版共存，只加强不抹除）</b>：每 tick 在玩家位置采样所有已启用的天气现象，
 * 取 {@code precipIntensity01} / {@code turbulenceAdd} 的最大值；超过阈值时调用
 * {@link ServerLevel#setWeatherParameters(int, int, boolean, boolean)} 让<b>原版状态机</b>
 * 把 {@code raining/thundering} 打开，由原版自己的 {@code advanceWeatherCycle} 以
 * 0.01/tick 的速率把 {@code rainLevel}/{@code thunderLevel} 平滑推到 1。
 *
 * <p><b>为什么不直接写 {@code setRainLevel}</b>：{@code Level.setRainLevel} 同时写
 * {@code oRainLevel} 与 {@code rainLevel}，而 {@code ServerLevel.advanceWeatherCycle}
 * 每 tick 会先 {@code oRainLevel = rainLevel}、再按 {@code levelData.isRaining()} 步进 ±0.01，
 * 然后比较 {@code o != rainLevel} 并 {@code broadcastAll(RAIN_LEVEL_CHANGE)}。只要稳态值
 * 不在 0 或 1，这个 0.01 的步进就<b>每 tick 都产生一次全维度广播</b>——20 tick/s × 2 个事件，
 * 相当于持续刷屏；而且我们写进去的值下一 tick 就被原版拖走 0.01，必须每 tick 重写。
 * 走状态机则只在真正的 0→1 / 1→0 过渡期发包（和原版下雨完全一致），且 0/1 稳态零发包。
 *
 * <p><b>交接</b>：一旦模组把雨打开，就每 {@link #RAIN_HOLD_TICKS} 续一次
 * {@code rainTime}；模组风暴离开玩家后不再续期，原版在 {@code rainTime} 归零时按自己的
 * 规则收雨（最多滞后一个 {@link #RAIN_HOLD_TICKS}）。原版本来就在下雨时我们<b>不碰</b>
 * {@code rainTime}，不会把原版的雨提前掐掉。
 */
public final class WeatherCycleBridge {
    /** 玩家处降水强度超过它就算"在下雨"。成熟雷暴核心约 0.9，消散期约 0.35。 */
    public static final double RAIN_PRECIP_THRESHOLD = 0.15;
    /** 触发打雷所需的最低降水强度。 */
    public static final double THUNDER_PRECIP_THRESHOLD = 0.45;
    /** 触发打雷所需的最低湍流强度：台风雨带（0.25+0.12）不够，眼墙/雷暴核心才够。 */
    public static final double THUNDER_TURBULENCE_THRESHOLD = 0.35;
    /** 每次强制打开天气时给原版的 {@code rainTime}（tick）。 */
    public static final int RAIN_HOLD_TICKS = 600;
    /**
     * 重算降水强度的周期（tick）。
     *
     * <p>原版把 {@code rainLevel} 从 0 推到 1 要 100 tick，天气本身的特征时间远大于 1 tick；
     * 每 tick 把所有现象 × 所有玩家采样一遍纯属浪费（成熟雷暴有 8 个单体）。
     * 20 tick 一次即 1 Hz，对"雨开始/停"完全够用。
     */
    public static final int REEVALUATE_TICKS = 20;
    /**
     * 模组风暴离开后，把雨收掉时给原版的 {@code rainTime}（tick）。
     *
     * <p>给 0 会让原版立刻切 {@code raining=false}，{@code rainLevel} 每 tick 掉 0.01，
     * 5 秒内天晴——正好是"风暴过境"的观感。给负数不行（{@code rainTime} 归零后
     * 原版会重新随机一个 12000–18000 的晴天时长，所以必须显式写 0 且状态置 false）。
     */
    public static final int STOP_LINGER_TICKS = 0;

    /** 我们自己打开、还没被原版关掉的雨；{@code <= now} 表示不归我们管。 */
    private long holdUntilTick = 0;
    /** 上次重算的 tick（{@link #REEVALUATE_TICKS}）。 */
    private long lastEvaluateTick = Long.MIN_VALUE;
    /** 最近一次写回的目标，诊断用。 */
    private boolean lastWantedRain;
    private boolean lastWantedThunder;

    /**
     * 每 tick 调一次（仅天气维度、且 {@code weatherOverridesVanillaRain} 打开时）。
     *
     * @param enabled 现象开关（{@code ENG-03} 的逐系统配置）
     */
    public void tick(ServerLevel level, List<WeatherPhenomenon> phenomena,
                     Predicate<WeatherPhenomenon> enabled) {
        if (!ZenithConfig.weatherOverridesVanillaRain()) {
            holdUntilTick = 0;
            lastWantedRain = false;
            lastWantedThunder = false;
            return;
        }
        var players = level.players();
        if (players.isEmpty() || phenomena.isEmpty()) {
            lastWantedRain = false;
            lastWantedThunder = false;
            return;
        }

        long now = level.getGameTime();
        boolean due = lastEvaluateTick == Long.MIN_VALUE
                || now - lastEvaluateTick >= REEVALUATE_TICKS;
        // 已经开着雨时也要定期复查（台风可能在玩家头顶停住 20 分钟）
        if (!due) return;
        lastEvaluateTick = now;

        double precip = 0;
        double turbulence = 0;
        for (int i = 0, n = players.size(); i < n; i++) {
            ServerPlayer p = players.get(i);
            double px = p.getX(), py = p.getY(), pz = p.getZ();
            for (int j = 0, m = phenomena.size(); j < m; j++) {
                WeatherPhenomenon ph = phenomena.get(j);
                if (!enabled.test(ph)) continue;
                WindContribution c = ph.sample(px, py, pz, 0, 0);
                if (c.isNone()) continue;
                if (c.precipIntensity01() > precip) precip = c.precipIntensity01();
                if (c.turbulenceAdd() > turbulence) turbulence = c.turbulenceAdd();
            }
        }

        boolean wantRain = precip >= RAIN_PRECIP_THRESHOLD;
        boolean wantThunder = precip >= THUNDER_PRECIP_THRESHOLD
                && turbulence >= THUNDER_TURBULENCE_THRESHOLD;
        lastWantedRain = wantRain;
        lastWantedThunder = wantThunder;

        if (!wantRain) {
            // 风暴走了：把还在续期的雨尽快收掉（原版按 rainTime 归零后自己淡出）
            if (holdUntilTick > now) {
                level.setWeatherParameters(0, STOP_LINGER_TICKS, false, false);
                holdUntilTick = 0;
            }
            return;
        }
        boolean ownRain = holdUntilTick > now;
        // 原版本来就在下（且不是我们开的）→ 不碰它的 rainTime，只在需要时加雷
        if (level.isRaining() && !ownRain) {
            if (wantThunder && !level.isThundering()) {
                level.setWeatherParameters(0, RAIN_HOLD_TICKS, true, true);
                holdUntilTick = now + RAIN_HOLD_TICKS;
            }
            return;
        }
        // 开雨 / 续期：一旦雨是我们开的，thunder 就完全跟着 wantThunder 走
        // （不能 OR 上 level.isThundering()，否则我们打开的雷永远不会停）
        level.setWeatherParameters(0, RAIN_HOLD_TICKS, true, wantThunder);
        holdUntilTick = now + RAIN_HOLD_TICKS;
    }

    /** 上一次写回是否要求下雨（诊断/指令用）。 */
    public boolean lastWantedRain() { return lastWantedRain; }

    /** 上一次写回是否要求打雷（诊断/指令用）。 */
    public boolean lastWantedThunder() { return lastWantedThunder; }
}
