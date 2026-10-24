package net.atomos.zenith;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * Zenith 的配置项。
 *
 * <p>此前所有可调参数都是 {@code public static final} 常量（ENG-03）：服务器管理员
 * 没法为低配机降刷新频率，整合包作者没法关掉台风，玩家没法调雾/粒子密度。
 * 这里按 NeoForge 的 {@code ModConfigSpec} 分成 SERVER（物理/性能/玩法开关）与
 * CLIENT（纯表现）两组，由 {@link ZenithMod} 注册。
 *
 * <p>所有读取都走本类的静态方法，内部对"配置尚未加载"做了兜底：返回代码里的默认值，
 * 不会在数据包/客户端初始化阶段抛 {@code IllegalStateException}。
 */
public final class ZenithConfig {
    public static final ModConfigSpec SERVER_SPEC;
    public static final Server SERVER;
    public static final ModConfigSpec CLIENT_SPEC;
    public static final Client CLIENT;

    static {
        ModConfigSpec.Builder sb = new ModConfigSpec.Builder();
        SERVER = new Server(sb);
        SERVER_SPEC = sb.build();

        ModConfigSpec.Builder cb = new ModConfigSpec.Builder();
        CLIENT = new Client(cb);
        CLIENT_SPEC = cb.build();
    }

    private ZenithConfig() {}

    /** 服务端配置：物理尺度、刷新频率、各天气系统开关、闪电行为。 */
    public static final class Server {
        public final ModConfigSpec.IntValue mesoscaleRefreshTicks;
        public final ModConfigSpec.IntValue backgroundRefreshTicks;
        public final ModConfigSpec.IntValue coarseSyncTicks;
        public final ModConfigSpec.IntValue weatherSnapshotTicks;

        public final ModConfigSpec.BooleanValue enableTyphoon;
        public final ModConfigSpec.BooleanValue enableStormCell;
        public final ModConfigSpec.BooleanValue enableSquallLine;
        public final ModConfigSpec.BooleanValue enableDustDevil;
        public final ModConfigSpec.BooleanValue enableMonsoon;
        public final ModConfigSpec.BooleanValue enableThermal;
        public final ModConfigSpec.BooleanValue enableSeaBreeze;
        public final ModConfigSpec.BooleanValue enableTornado;

        public final ModConfigSpec.BooleanValue weatherInNether;
        public final ModConfigSpec.BooleanValue weatherInEnd;

        public final ModConfigSpec.BooleanValue southernHemisphere;

        public final ModConfigSpec.BooleanValue lightningDamage;
        public final ModConfigSpec.BooleanValue lightningSetsFire;
        public final ModConfigSpec.IntValue lightningMaxDistanceFromPlayer;

        Server(ModConfigSpec.Builder b) {
            b.comment("物理与性能").push("physics");
            mesoscaleRefreshTicks = b
                    .comment("L1 中尺度网格刷新周期（tick）。默认 64，调大省 CPU、风场更\"阶梯\"。")
                    .defineInRange("mesoscaleRefreshTicks", 64, 4, 1200);
            backgroundRefreshTicks = b
                    .comment("L0 天气尺度网格刷新周期（tick）。默认 256。")
                    .defineInRange("backgroundRefreshTicks", 256, 20, 12000);
            b.pop();

            b.comment("网络").push("network");
            coarseSyncTicks = b
                    .comment("粗风场广播周期（tick）。默认 40。")
                    .defineInRange("coarseSyncTicks", 40, 5, 600);
            weatherSnapshotTicks = b
                    .comment("天气快照广播周期（tick）。默认 100。")
                    .defineInRange("weatherSnapshotTicks", 100, 5, 1200);
            b.pop();

            b.comment("天气系统开关").push("weather");
            enableTyphoon = b.comment("生成台风。").define("enableTyphoon", true);
            enableStormCell = b.comment("生成雷暴单体。").define("enableStormCell", true);
            enableSquallLine = b.comment("生成飑线。").define("enableSquallLine", true);
            enableDustDevil = b.comment("生成尘卷风。").define("enableDustDevil", true);
            enableMonsoon = b.comment("启用季风调制。").define("enableMonsoon", true);
            enableThermal = b.comment("生成热泡。").define("enableThermal", true);
            enableSeaBreeze = b.comment("启用海陆风。").define("enableSeaBreeze", true);
            enableTornado = b.comment("生成龙卷。").define("enableTornado", true);
            b.pop();

            b.comment("维度门控").push("dimensions");
            weatherInNether = b
                    .comment("在下界也跑天气系统。默认 false（下界没有天气）。")
                    .define("weatherInNether", false);
            weatherInEnd = b
                    .comment("在末地也跑天气系统。默认 false。")
                    .define("weatherInEnd", false);
            b.pop();

            b.comment("半球").push("hemisphere");
            southernHemisphere = b
                    .comment("南半球模式。默认 false（北半球：低压逆时针、地转风按右手法则）。",
                            "开启后科里奥利符号取反：低压顺时针、地转风反向。")
                    .define("southernHemisphere", false);
            b.pop();

            b.comment("闪电").push("lightning");
            lightningDamage = b
                    .comment("闪电造成真实伤害。关闭后只保留视觉效果。")
                    .define("lightningDamage", true);
            lightningSetsFire = b
                    .comment("闪电点燃方块。关闭后闪电不点火（避免刷怪塔/木屋被随机点燃）。")
                    .define("lightningSetsFire", true);
            lightningMaxDistanceFromPlayer = b
                    .comment("只在离玩家这个距离内落雷（格）。未加载区块一律跳过。默认 160。")
                    .defineInRange("lightningMaxDistanceFromPlayer", 160, 16, 512);
            b.pop();
        }
    }

    /** 客户端配置：纯表现，不影响服务端物理。 */
    public static final class Client {
        public final ModConfigSpec.DoubleValue particleDensity;
        public final ModConfigSpec.DoubleValue fogMultiplier;
        public final ModConfigSpec.DoubleValue windSoundVolume;
        public final ModConfigSpec.BooleanValue showWindParticles;

        Client(ModConfigSpec.Builder b) {
            b.comment("表现").push("visuals");
            particleDensity = b
                    .comment("天气粒子密度倍率。0 = 关闭粒子，1 = 默认。")
                    .defineInRange("particleDensity", 1.0, 0.0, 4.0);
            fogMultiplier = b
                    .comment("雾浓度倍率。0 = 关闭模组雾。")
                    .defineInRange("fogMultiplier", 1.0, 0.0, 4.0);
            windSoundVolume = b
                    .comment("风声音量倍率。")
                    .defineInRange("windSoundVolume", 1.0, 0.0, 2.0);
            showWindParticles = b
                    .comment("显示风场粒子。")
                    .define("showWindParticles", true);
            b.pop();
        }
    }

    // ---- 带兜底的读取接口 ----

    private static <T> T safe(java.util.function.Supplier<T> getter, T fallback) {
        try {
            T v = getter.get();
            return v == null ? fallback : v;
        } catch (IllegalStateException | NullPointerException e) {
            // 配置还没加载（数据包注册早期、单元测试等）：用默认值
            return fallback;
        }
    }

    public static int mesoscaleRefreshTicks() { return safe(() -> SERVER.mesoscaleRefreshTicks.get(), 64); }
    public static int backgroundRefreshTicks() { return safe(() -> SERVER.backgroundRefreshTicks.get(), 256); }
    public static int coarseSyncTicks() { return safe(() -> SERVER.coarseSyncTicks.get(), 40); }
    public static int weatherSnapshotTicks() { return safe(() -> SERVER.weatherSnapshotTicks.get(), 100); }

    public static boolean enableTyphoon() { return safe(() -> SERVER.enableTyphoon.get(), true); }
    public static boolean enableStormCell() { return safe(() -> SERVER.enableStormCell.get(), true); }
    public static boolean enableSquallLine() { return safe(() -> SERVER.enableSquallLine.get(), true); }
    public static boolean enableDustDevil() { return safe(() -> SERVER.enableDustDevil.get(), true); }
    public static boolean enableMonsoon() { return safe(() -> SERVER.enableMonsoon.get(), true); }
    public static boolean enableThermal() { return safe(() -> SERVER.enableThermal.get(), true); }
    public static boolean enableSeaBreeze() { return safe(() -> SERVER.enableSeaBreeze.get(), true); }
    public static boolean enableTornado() { return safe(() -> SERVER.enableTornado.get(), true); }

    public static boolean weatherInNether() { return safe(() -> SERVER.weatherInNether.get(), false); }
    public static boolean weatherInEnd() { return safe(() -> SERVER.weatherInEnd.get(), false); }

    public static boolean southernHemisphere() {
        return safe(() -> SERVER.southernHemisphere.get(), false);
    }

    /**
     * 把配置里的半球写进 {@code HemisphereConvention}。配置加载/重载时调用。
     * 没调用也能跑（默认北半球），但服务端启动后应当调用一次。
     */
    public static void applyHemisphere() {
        net.atomos.zenith.wind.HemisphereConvention.setHemisphereSign(southernHemisphere() ? -1.0 : 1.0);
    }

    public static boolean lightningDamage() { return safe(() -> SERVER.lightningDamage.get(), true); }
    public static boolean lightningSetsFire() { return safe(() -> SERVER.lightningSetsFire.get(), true); }
    public static int lightningMaxDistanceFromPlayer() {
        return safe(() -> SERVER.lightningMaxDistanceFromPlayer.get(), 160);
    }

    public static double particleDensity() { return safe(() -> CLIENT.particleDensity.get(), 1.0); }
    public static double fogMultiplier() { return safe(() -> CLIENT.fogMultiplier.get(), 1.0); }
    public static double windSoundVolume() { return safe(() -> CLIENT.windSoundVolume.get(), 1.0); }
    public static boolean showWindParticles() { return safe(() -> CLIENT.showWindParticles.get(), true); }
}
