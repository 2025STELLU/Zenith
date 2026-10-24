package net.atomos.zenith.wind;

import net.atomos.zenith.api.GameplayWindSample;
import net.atomos.zenith.api.SamplePolicy;
import net.atomos.zenith.api.ZenithAirfoilPresets;
import net.atomos.zenith.api.ZenithBlockPos;
import net.atomos.zenith.api.ZenithId;
import net.atomos.zenith.api.ZenithL2Request;
import net.atomos.zenith.api.ZenithL2Result;
import net.atomos.zenith.api.ZenithPolarRequest;
import net.atomos.zenith.api.ZenithPolarResult;
import net.atomos.zenith.api.ZenithPolarSample;
import net.atomos.zenith.api.ZenithPolarTable;
import net.atomos.zenith.api.ZenithTerrainSample;
import net.atomos.zenith.api.ZenithTerrainSurfaceClass;
import net.atomos.zenith.api.ZenithVec3;
import net.atomos.zenith.api.ZenithWindApi;
import net.atomos.zenith.api.ZenithWindRuntimeProvider;
import net.atomos.zenith.api.ZenithWindSample;
import net.atomos.zenith.api.ZenithWindSamplingRules;
import net.atomos.zenith.api.ZenithWorldRef;
import net.atomos.zenith.network.ZenithNetwork;
import net.atomos.zenith.network.packet.CoarseWindPacket;
import net.atomos.zenith.network.packet.WeatherSnapshotPacket;
import net.atomos.zenith.weather.DustDevilSystem;
import net.atomos.zenith.weather.FrontSystem;
import net.atomos.zenith.weather.MonsoonSystem;
import net.atomos.zenith.weather.MountainWaveSystem;
import net.atomos.zenith.weather.SeasonSystem;
import net.atomos.zenith.weather.SeaBreezeSystem;
import net.atomos.zenith.weather.SquallLineSystem;
import net.atomos.zenith.weather.StormCellSystem;
import net.atomos.zenith.weather.ThermalSystem;
import net.atomos.zenith.weather.TornadoSystem;
import net.atomos.zenith.weather.TyphoonSystem;
import net.atomos.zenith.weather.WeatherContext;
import net.atomos.zenith.weather.WeatherCycleBridge;
import net.atomos.zenith.weather.WeatherPhenomenon;
import net.atomos.zenith.weather.WindContribution;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BiomeTags;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Zenith 服务端运行时：多层风场系统的总编排。
 *
 * native LBM 那块换成了纯 Java 的 {@link LocalFlowSolver}，省得编译原生库。
 *
 * 每 tick 干这些事：
 *   1. 收集 L1 诊断 → 反哺驱动器
 *   2. WorldScaleDriver.advance（每 tick，dt=0.05s）
 *   3. 天气现象 tick（热对流/海风/风暴/台风/锋面/尘卷风/飑线/背风波/季风）
 *   4. BackgroundMetGrid.refresh（每 256 tick）
 *   5. MesoscaleGrid.refresh（每 64 tick）
 *   6. 广播：粗风场包（每 40 tick）+ 天气快照包（每 100 tick）
 */
public class ZenithServerRuntime implements ZenithWindRuntimeProvider,
        net.atomos.zenith.api.ZenithTerrainProvider,
        net.atomos.zenith.api.ZenithWeatherRuntimeProvider {
    private static final Logger LOGGER = LoggerFactory.getLogger("zenith");

    public static final double SOLVER_STEP_SECONDS = 0.05;
    /** 默认值在这里；实际运行时由 {@code ZenithConfig} 覆盖（ENG-03）。 */
    public static final int BACKGROUND_MET_REFRESH_TICKS = 256;
    public static final int MESOSCALE_REFRESH_TICKS = 64;
    public static final int COARSE_WIND_SYNC_TICKS = 40;
    public static final int WEATHER_SNAPSHOT_TICKS = 100;
    /**
     * 粗风场网格间距（格）：与 L1 源网格一致（NET-01）。
     * 旧值 32 比源网格 64 还细，客户端只是在对阶梯场再采样。
     */
    public static final int COARSE_WIND_SYNC_CELL_SIZE_BLOCKS = CoarseWindPacket.DEFAULT_CELL_SIZE_BLOCKS;
    /** 粗风场半径（格）：±6×64 = ±384 格，覆盖 12 区块视距（±192）并留余量。 */
    public static final int COARSE_WIND_SYNC_RADIUS_CELLS = CoarseWindPacket.DEFAULT_RADIUS_CELLS;

    /**
     * 置信度衰减时标（tick，SYS-11）：距上次 L1 刷新这么久，置信度降到 0.75×。
     * 取 4 倍 L1 刷新周期（64×4 = 256 tick = 12.8 s）：正常节奏下最多衰减到 0.94×，
     * 只有 L1 因为没玩家/异常卡住时才会明显掉。
     */
    public static final int FRESHNESS_DECAY_TICKS = 256;

    /**
     * 焦点聚类半径（格，SYS-06）：两名玩家相距在这个半径内算"同一片区域"，
     * 共用一个 L1 焦点；超出则各自成簇，取人数最多的那簇。
     *
     * <p>取 L1 半宽的 1/4（1024/4 = 256）：同簇玩家的间距远小于 L1 覆盖范围，
     * 焦点落在质心上时双方都离边缘有富余。
     */
    public static final int FOCUS_CLUSTER_RADIUS_BLOCKS = 256;

    private static ZenithServerRuntime INSTANCE;

    public static void init() {
        if (INSTANCE == null) {
            INSTANCE = new ZenithServerRuntime();
            NeoForge.EVENT_BUS.register(INSTANCE);
            ZenithWindApi.bindRuntime(INSTANCE);
            net.atomos.zenith.api.ZenithWeatherApi.bindRuntime(INSTANCE);
            net.atomos.zenith.api.ZenithTerrainApi.registerProvider(INSTANCE);
            LOGGER.info("ZenithServerRuntime registered.");
        }
    }

    public static ZenithServerRuntime get() {
        return INSTANCE;
    }

    public static final class DimensionState {
        final WorldScaleDriver driver;
        final BackgroundMetGrid l0;
        final MesoscaleGrid l1;
        final ThermalSystem thermals;
        final SeaBreezeSystem seaBreeze;
        final StormCellSystem storms;
        final TyphoonSystem typhoons;
        final FrontSystem fronts;
        final DustDevilSystem dustDevils;
        final SquallLineSystem squallLines;
        final TornadoSystem tornadoes;
        final MountainWaveSystem mountainWaves;
        final MonsoonSystem monsoon;
        /** 把模组降水写回原版天气状态机（WX-SC-04）。 */
        final WeatherCycleBridge weatherCycle = new WeatherCycleBridge();
        final HashedSeedTerrainProvider hashedTerrain;
        McTerrainAdapter terrainAdapter;
        LocalFlowSolver l2;
        MesoscaleGrid.DiagnosticsSummary lastDiagnostics =
                new MesoscaleGrid.DiagnosticsSummary(0, 0, 0, 0.5, 0);
        /** L2 热源索引（RT-02）：按区块段归并、按玩家分桶，见 {@link HeatCache}。 */
        HeatCache heat;
        long heatScheduleTick = -1;
        /**
         * 上一帧粗风场的**几何**参照（RT-04）：只用来判断"网格中心/间距有没有变"。
         * 不能拿它的数组当增量比较基准——那是上一次全量帧，见 {@link #coarseBase}。
         */
        CoarseWindPacket lastCoarse;
        /**
         * 增量比较的基准（RT-04）：六个数组的"客户端当前应有值"。
         *
         * <p>不能拿 {@link #lastCoarse} 当基准——那是上一次**全量**帧。如果只发 delta
         * 而不推进基准，已经发过的格点下一帧仍然"与基准不同"，会被无限重复发送。
         */
        float[][] coarseBase;
        /** 已经收到过全量帧的玩家：新人先补一帧全量，再进入增量流（RT-04）。 */
        final java.util.Set<java.util.UUID> coarseRecipients = new java.util.HashSet<>();
        double focusX, focusZ;
        /** 存档状态是否已装回来（SYS-08）。 */
        boolean restored;
        /**
         * 上一次 L1 刷新时的 {@code gameTime}（SYS-11）。
         *
         * <p>{@code ZenithWindSample.confidence} 要反映"这个采样值有多旧"：L1 每 64 tick
         * 才重算一次，中间 3 秒读到的都是同一帧。旧实现恒报 0.9，调用方无法区分
         * "刚刷新"与"上一帧"，也就没法自己做插值/降级。
         */
        long l1LastRefreshTick = Long.MIN_VALUE;
        /**
         * 采样/推进互斥锁（SYS-11）。
         *
         * <p>采样会读 {@code l1.columns}、{@code l2} 的 scratch 缓冲，而 {@code tickDimension}
         * 会在 L1 刷新时**写** {@code columns}。旧实现没有任何同步：其他模组从工作线程
         * 调 {@code ZenithWindApi.sample()} 时可能读到刷新到一半的列（半新半旧的向量），
         * 也可能与 {@code computeIfAbsent} 抢 {@code HashMap}。
         *
         * <p>现在两条路径都持这把锁。服务端主线程上是无竞争获取（纳秒级），
         * 只在真的跨线程调用时才会阻塞——正确性优先于那点开销。
         */
        final java.util.concurrent.locks.ReentrantLock samplingLock =
                new java.util.concurrent.locks.ReentrantLock();

        /**
         * 上一 tick 的墙钟时间（{@link System#nanoTime()}）。{@code -1} 表示还没跑过。
         * 物理步长必须来自真实时间，不能写死 0.05 s（RT-03）。
         */
        private long lastTickNanos = -1;
        /** 距上次 L1 刷新累积的**真实**秒数：冻结湍流的图样按它推进（L1-05 / RT-03）。 */
        double accumulatedDtSeconds = 0;
        /** 距上次 L0 刷新累积的真实秒数。 */
        double accumulatedL0DtSeconds = 0;
        /** 上一 tick 的 dayTime：用于识别睡眠/时间跳变（RT-03）。 */
        private long lastDayTime = -1;
        /** 已处理的 dayTime 跳变：{@code >= 0} 表示下一次 L0 刷新要补子步。 */
        private int pendingL0Substeps = 1;

        DimensionState(long seed, int dimensionSalt) {
            // 每个现象一个独立随机流：同维度内不同现象互不干扰，跨维度也不同（SYS-08）
            long base = seed ^ ((long) dimensionSalt * 0x9E3779B97F4A7C15L);
            this.driver = new WorldScaleDriver(base);
            this.l0 = new BackgroundMetGrid();
            this.l1 = new MesoscaleGrid(base ^ 0x51ab);
            this.thermals = new ThermalSystem(base ^ 0x74686572L);      // "ther"
            this.seaBreeze = new SeaBreezeSystem();
            this.storms = new StormCellSystem(base ^ 0x73746f72L);      // "stor"
            this.typhoons = new TyphoonSystem(base ^ 0x74797068L);      // "typh"
            this.fronts = new FrontSystem(base ^ 0x66726f6eL);          // "fron"
            this.dustDevils = new DustDevilSystem(base ^ 0x64757374L);  // "dust"
            this.squallLines = new SquallLineSystem(base ^ 0x73717561L); // "squa"
            this.tornadoes = new TornadoSystem(base ^ 0x746f726eL);     // "torn"
            this.mountainWaves = new MountainWaveSystem();
            this.monsoon = new MonsoonSystem();
            this.hashedTerrain = new HashedSeedTerrainProvider(seed);
        }

        /**
         * 会逐点采样、需要每帧参与 {@code sample()} 的现象。
         *
         * <p>季风不在里面（WX-MS-01）：它已经是 L0 的**基础流偏置**
         * （{@link WorldScaleDriver#setBaseFlowBias}），再当现象叠一次就等于加两遍。
         */
        List<WeatherPhenomenon> phenomena() {
            return List.of(thermals, seaBreeze, storms, typhoons, fronts, dustDevils, squallLines,
                    tornadoes, mountainWaves);
        }

        /** 存档用的现象（季风和背风波无状态）。 */
        List<WeatherPhenomenon> persistentPhenomena() {
            return List.of(fronts, thermals, dustDevils, storms, typhoons, squallLines, tornadoes);
        }

        /**
         * 本 tick 的物理步长（秒），来自真实墙钟时间（RT-03）。
         *
         * <p>旧实现写死 {@code dt = 0.05}：{@code /tick rate 5} 时模拟时间只走 1/4、
         * 服务器卡到 5 TPS 时天气跟着变慢、睡眠跳夜后 L0 的日变化瞬间跳变。
         * 现在按真实间隔给，并夹在 {@code [1/400, 0.25]} s：上限防一次卡顿把风暴推过量，
         * 下限防 `/tick rate` 调得极高时步长趋近 0 让所有松弛停摆。
         */
        double advanceDtSeconds() {
            long now = System.nanoTime();
            if (lastTickNanos < 0) {
                lastTickNanos = now;
                return ZenithServerRuntime.SOLVER_STEP_SECONDS;
            }
            double dt = (now - lastTickNanos) / 1.0e9;
            lastTickNanos = now;
            if (!(dt > 0)) dt = ZenithServerRuntime.SOLVER_STEP_SECONDS;
            return Math.max(1.0 / 400.0, Math.min(0.25, dt));
        }

        /**
         * 睡眠/时间跳变检测（RT-03）：{@code dayTime} 一次跳超过 1000 tick 时，
         * 把跳变折算成若干个 L0 子步，而不是让日变化（日照/山谷风相位）一步跳过去。
         */
        void noteDayTime(long dayTime) {
            if (lastDayTime >= 0) {
                long jump = dayTime - lastDayTime;
                if (jump > 1000) {
                    int n = (int) Math.min(64, Math.ceil(jump / 1200.0));
                    pendingL0Substeps = Math.max(pendingL0Substeps, n);
                }
            }
            lastDayTime = dayTime;
        }
    }

    /**
     * 维度状态表（SYS-11）。
     *
     * <p>旧实现是普通 {@code HashMap} + {@code computeIfAbsent}：其他模组从工作线程
     * 调 {@code ZenithWindApi.sample()} 时，会和主线程的 tick 同时改这张表——
     * 轻则丢条目，重则 resize 期间链表成环死循环。改成并发表后
     * {@code computeIfAbsent} 本身是原子的，不会再破坏内部结构。
     */
    private final Map<ResourceKey<Level>, DimensionState> dimensions =
            new java.util.concurrent.ConcurrentHashMap<>();

    private DimensionState stateFor(ServerLevel level) {
        return dimensions.computeIfAbsent(level.dimension(), k -> {
            // 维度盐：同一个世界种子在不同维度拿到不同的现象序列（SYS-08）
            DimensionState s = new DimensionState(level.getSeed(), level.dimension().location().hashCode());
            s.terrainAdapter = new McTerrainAdapter(level, s.hashedTerrain);
            // 热源索引（RT-02）：按区块段归并 + 段级调色板预筛，玩家分桶，不再全局均值扫盒
            s.heat = new HeatCache(this::heatPowerOf);
            s.l2 = new LocalFlowSolver(
                    (x, y, z) -> {
                        // getMaxBuildHeight() 是**开区间上界**（RT-05 / L2-05）：
                        // y == maxBuildHeight 会抛 IllegalStateException，旧代码写成 > 且靠 try/catch 掩盖
                        if (y < level.getMinBuildHeight() || y >= level.getMaxBuildHeight()) return false;
                        // SYS-10：L2 障碍探针是"任意点采样都会走"的路径，其他模组调 API
                        // 打一个未加载坐标时，level.getBlockState 会触发同步区块加载。
                        // 改成先取已加载区块，未加载一律当"无障碍"（比当"实心"安全：
                        // 凭空多一堵墙会让风莫名其妙消失，少一堵只是局部修正变弱）。
                        ChunkAccess ca = s.terrainAdapter.chunkIfLoaded(x, z);
                        if (ca == null) return false;
                        BlockState st = ca.getBlockState(new BlockPos(x, y, z));
                        return st.isSolid();
                    },
                    (x, y, z, r) -> heatNear(s, x, y, z, r),
                    (x, y, z, r) -> JetSourceRegistry.jetsNear(level.dimension(), x, y, z, r),
                    // 地面探针：MOTION_BLOCKING_NO_LEAVES 之上第一格，减 1 = 支撑面方块 y。
                    // L2 用它把"脚下这层地"排除在障碍物之外，否则平地会顶出 11 m/s 伪上升（L2-01）
                    (x, z) -> {
                        ChunkAccess ca = s.terrainAdapter.chunkIfLoaded(x, z);
                        if (ca == null) return Integer.MIN_VALUE; // 未加载：不猜，保持旧行为
                        int h = ca.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                                x & 15, z & 15);
                        return Math.max(h - 1, ca.getMinBuildHeight());
                    });
            // 存档恢复（SYS-08）：只装一次，之后 tick 里才允许写盘
            ZenithWeatherSavedData data = ZenithWeatherSavedData.get(level);
            if (data.hasPayload()) {
                data.applyTo(s.driver, s.persistentPhenomena());
            }
            s.restored = true;
            return s;
        });
    }

    // tick 编排

    /**
     * 关服前最后一次落盘（SYS-08）。
     *
     * <p><b>必须挂 {@code ServerStoppingEvent}，不能挂 {@code ServerStoppedEvent}</b>：
     * {@code MinecraftServer#runServer} 的顺序是
     * {@code handleServerStopping} → {@code stopServer()} → {@code handleServerStopped}，而
     * {@code stopServer()} 里才调 {@code saveAllChunks()}（内部走
     * {@code DimensionDataStorage.save()}，只写 {@code isDirty()} 的数据）。把最后一次
     * {@code capture()} 放在 {@code Stopped} 里，标脏发生在存盘之后，那一份永远写不出去——
     * 停机前最后一分钟（{@link #SAVE_INTERVAL_TICKS} 周期内）的天气就白改了。
     *
     * <p>这里的顺序是 显式落盘 → 标脏，然后原版 saveAllChunks 再写一遍（第二次是空写，
     * 因为 dirty 已被 {@code SavedData.save} 清掉），所以不会写两次磁盘。
     */
    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        try {
            for (ServerLevel level : event.getServer().getAllLevels()) {
                DimensionState s = dimensions.get(level.dimension());
                if (s != null) persist(level, s);
            }
        } catch (RuntimeException e) {
            LOGGER.warn("Failed to flush weather state on shutdown", e);
        }
    }

    /**
     * 存档关闭：清掉所有维度状态与 JVM 级静态的射流表（ENG-06：这里给出具体引用，
     * 不再只写"会 pin 住 ServerLevel"这种无法验证的断言）。
     *
     * <p>确实持有 {@link ServerLevel} 引用的地方：
     * <ul>
     *   <li>{@link McTerrainAdapter#level}（地形探针）；</li>
     *   <li>{@link DimensionState#l2} 构造时传入的三个 lambda：障碍物探针捕获
     *       {@code level}、地面探针捕获 {@code s.terrainAdapter}、热源探针捕获
     *       {@link DimensionState#heat}（后者又持有 {@code this::heatPowerOf}）。</li>
     * </ul>
     * 现象系统本身不存 level（{@code WeatherContext} 是每 tick 传进来的），
     * 所以清掉上面两处就足以让旧世界不可达。
     * {@link JetSourceRegistry} 是 {@code static} 表，与维度无关，但它的键是
     * {@code ResourceKey<Level>}，不清会让"同名的下一个存档维度"继承上一局的射流源。
     *
     * <p>单人游戏里集成服务器停了客户端还活着，这份状态也必须一起清，否则重新进世界
     * 会拿到上一局的天气（{@code stateFor} 只在缺失时新建，不会自己覆盖）。
     */
    @SubscribeEvent
    public void onServerStopped(ServerStoppedEvent event) {
        int n = dimensions.size();
        dimensions.clear();
        JetSourceRegistry.clear();
        LOGGER.info("Zenith server stopped: cleared {} dimension states and the jet registry.", n);
    }

    @SubscribeEvent
    public void onServerTick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        for (ServerLevel level : server.getAllLevels()) {
            tickDimension(level);
        }
    }

    /**
     * 方块被破坏时立刻作废该段的热源索引（RT-02）。
     *
     * <p>没有这一步，玩家把岩浆桶收走后 10 秒内 L2 还在吹"已经不存在的上升气流"。
     * 放置走 {@link net.neoforged.neoforge.event.level.BlockEvent.EntityPlaceEvent}。
     * 火焰蔓延/岩浆流动这类没有事件的状态变化仍靠 10 秒的周期重扫兜底。
     */
    @SubscribeEvent
    public void onBlockBreak(net.neoforged.neoforge.event.level.BlockEvent.BreakEvent event) {
        invalidateHeat(event.getLevel(), event.getPos());
    }

    @SubscribeEvent
    public void onBlockPlace(net.neoforged.neoforge.event.level.BlockEvent.EntityPlaceEvent event) {
        invalidateHeat(event.getLevel(), event.getPos());
    }

    private void invalidateHeat(net.minecraft.world.level.LevelAccessor level, BlockPos pos) {
        if (!(level instanceof ServerLevel serverLevel)) return;
        DimensionState s = dimensions.get(serverLevel.dimension());
        if (s != null && s.heat != null) s.heat.invalidate(pos.getX(), pos.getY(), pos.getZ());
    }

    private void tickDimension(ServerLevel level) {
        DimensionState s = stateFor(level);
        long tick = level.getGameTime();
        double dt = s.advanceDtSeconds();

        // 焦点取玩家平均位置，没人在线就用出生点
        updateFocus(level, s);

        double timeOfDay = (level.getDayTime() % 24000L) / 24000.0;
        boolean raining = level.isRaining();
        boolean thundering = level.isThundering();

        // 真实墙钟步长（RT-03）：累积到 L0/L1 刷新时才消费
        s.noteDayTime(level.getDayTime());
        s.accumulatedDtSeconds += dt;
        s.accumulatedL0DtSeconds += dt;

        // 维度门控（SYS-09）：下界/末地没有天气语义，硬跑只会生出"基岩上的热泡"
        // 和"真闪电劈基岩"，还白烧热源扫描。天气现象整段跳过；L0/L1 风场仍然推进，
        // 这样模组维度和下界里的风采样（鞘翅/风车/帆船）不会突然变成 0 风。
        boolean weather = weatherEnabled(level);

        // 1) 驱动器推进（含上 tick 的 L1 反馈）
        var d = s.lastDiagnostics;
        var feedback = new WorldScaleDriver.Feedback(
                d.maxInstabilityProxy(), d.meanLowLevelShear(), d.maxPositiveMoistureConvergence());
        s.driver.advance(dt, timeOfDay, raining, thundering, feedback);

        // 2) 天气现象
        if (weather) {
            WeatherContext ctx = new WeatherContext(level, s.driver, s.l0, s.l1,
                    s.terrainAdapter, tick, timeOfDay, raining, thundering,
                    SeasonSystem.compute(level.getDayTime()));
            // 季风（WX-MS-01）：不是逐点现象，而是 L0 的基础流偏置。先 tick 出年循环相位
            // 和 33×33 偏置场，再把偏置挂到驱动器上，L0 刷新时自然吃到。
            if (net.atomos.zenith.ZenithConfig.enableMonsoon()) {
                s.monsoon.tick(level, dt, ctx);
                s.driver.setBaseFlowBias(s.monsoon::biasAt);
            } else {
                s.driver.setBaseFlowBias(null);
            }
            for (WeatherPhenomenon p : s.phenomena()) {
                if (phenomenonEnabled(p)) p.tick(level, dt, ctx);
            }
            // 台风如果被配置关掉，别把上一 tick 的足迹留在 L0 里（WX-TY-06）
            if (!net.atomos.zenith.ZenithConfig.enableTyphoon()) {
                s.typhoons.clearExternalVortices(s.driver);
            }
            // WX-SC-04：把现象层的降水/湍流写回原版天气状态机。
            // 放在现象 tick 之后，拿的是本 tick 的最新位置。
            s.weatherCycle.tick(level, s.phenomena(), ZenithServerRuntime::phenomenonEnabled);
        } else {
            s.driver.setBaseFlowBias(null);
            s.typhoons.clearExternalVortices(s.driver);
            s.weatherCycle.tick(level, java.util.List.of(), p -> false);
        }

        // 3) L0 刷新
        int l0Ticks = Math.max(1, net.atomos.zenith.ZenithConfig.backgroundRefreshTicks());
        if (tick % l0Ticks == 0) {
            s.l0.setFocus(s.focusX, s.focusZ);
            // 睡眠跳夜（RT-03）：把跳变折算成 n 个子步，每个子步各推进 1/n 的日变化，
            // 不让日照/山谷风相位一步从前夜跳到清晨。
            int substeps = Math.max(1, s.pendingL0Substeps);
            s.pendingL0Substeps = 1;
            double l0Dt = Math.max(1.0e-3, s.accumulatedL0DtSeconds) / substeps;
            double seasonBias = SeasonSystem.compute(level.getDayTime()).tempBiasKelvin();
            for (int sub = 0; sub < substeps; sub++) {
                double subTimeOfDay = (timeOfDay + (double) sub / substeps) % 1.0;
                s.l0.refresh(s.driver, s.terrainAdapter, l0Dt,
                        subTimeOfDay, raining, seasonBias);
            }
            s.accumulatedL0DtSeconds = 0;
        }
        // 4) L1 刷新
        int l1Ticks = Math.max(1, net.atomos.zenith.ZenithConfig.mesoscaleRefreshTicks());
        if (tick % l1Ticks == 0) {
            // SYS-11：刷新会整片覆写 columns，必须与跨线程采样互斥
            s.samplingLock.lock();
            try {
                s.l1.setFocus(s.focusX, s.focusZ);
                s.lastDiagnostics = s.l1.refresh(s.l0, s.terrainAdapter, timeOfDay, raining, tick,
                        Math.max(1.0e-3, s.accumulatedDtSeconds));
                s.accumulatedDtSeconds = 0;
                s.l1LastRefreshTick = tick;
            } finally {
                s.samplingLock.unlock();
            }
        }

        // 5) 热源索引增量刷新（RT-02）：按玩家分桶、跟随玩家 y、段级调色板预筛，10 秒内不重扫
        if (weather) {
            refreshHeatCache(level, s, tick);
        }

        // 6) 网络广播
        if (tick % Math.max(1, net.atomos.zenith.ZenithConfig.coarseSyncTicks()) == 0) {
            broadcastCoarseWind(level, s);
        }
        if (tick % Math.max(1, net.atomos.zenith.ZenithConfig.weatherSnapshotTicks()) == 0) {
            broadcastWeatherSnapshot(level, s);
        }

        // 7) 存档（SYS-08）：状态一直在变，按数据包自动存的节奏标脏就够（每分钟一次），
        //    不必每 tick 序列化一遍。真正关服时 onServerStopped 里再强制写一次。
        if (tick % SAVE_INTERVAL_TICKS == 0) {
            persist(level, s);
        }
    }

    /** 天气状态的写盘周期（tick）。1 分钟。 */
    public static final int SAVE_INTERVAL_TICKS = 1200;

    /**
     * 把该维度的驱动器 + 现象状态写进 {@link ZenithWeatherSavedData}。
     *
     * <p>只有"已经恢复过"的维度才写：{@code stateFor} 建状态时会同步装存档，
     * 所以正常情况下 {@code restored} 一定是 {@code true}；这个判断只是防止
     * 有人绕过 {@code stateFor} 直接 new 一个状态把新世界覆盖成空。
     */
    private static void persist(ServerLevel level, DimensionState s) {
        if (!s.restored) return;
        ZenithWeatherSavedData data = ZenithWeatherSavedData.get(level);
        data.capture(s.driver, s.persistentPhenomena());
        data.setDirty();
    }

    /**
     * 维度门控（SYS-09）：有天空光、且没有天花板（下界/末地都不满足）才跑天气。
     * 配置里可以显式放开下界/末地。
     */
    private static boolean weatherEnabled(ServerLevel level) {
        if (level.dimension().equals(Level.OVERWORLD)) return true;
        if (level.dimension().equals(Level.NETHER)) return net.atomos.zenith.ZenithConfig.weatherInNether();
        if (level.dimension().equals(Level.END)) return net.atomos.zenith.ZenithConfig.weatherInEnd();
        // 模组维度：按维度类型判断
        return level.dimensionType().hasSkyLight() && !level.dimensionType().hasCeiling();
    }

    /** 逐个系统的开关（ENG-03）。 */
    private static boolean phenomenonEnabled(WeatherPhenomenon p) {
        if (p instanceof ThermalSystem) return net.atomos.zenith.ZenithConfig.enableThermal();
        if (p instanceof SeaBreezeSystem) return net.atomos.zenith.ZenithConfig.enableSeaBreeze();
        if (p instanceof StormCellSystem) return net.atomos.zenith.ZenithConfig.enableStormCell();
        if (p instanceof TyphoonSystem) return net.atomos.zenith.ZenithConfig.enableTyphoon();
        if (p instanceof SquallLineSystem) return net.atomos.zenith.ZenithConfig.enableSquallLine();
        if (p instanceof DustDevilSystem) return net.atomos.zenith.ZenithConfig.enableDustDevil();
        if (p instanceof TornadoSystem) return net.atomos.zenith.ZenithConfig.enableTornado();
        return true;
    }

    /**
     * 焦点选择（SYS-06）。
     *
     * <p>旧实现取**全体玩家算术平均**：两人相距 10 km 时焦点落在中间无人区，
     * L1（±1024 格）对两人都不覆盖，双方都在吃边缘淡出值；而且焦点每 tick 都动，
     * 网格跟着玩家"贴脸平移"，同一地点的风每 tick 都在变。
     *
     * <p>现在做三件事：
     * <ol>
     *   <li><b>按簇取焦点</b>：以在线玩家为种子做单遍聚类（半径 {@value #FOCUS_CLUSTER_RADIUS_BLOCKS} 格），
     *       取人数最多的簇的质心。散开的玩家各吃各的簇，不再被拉到一个中间点。</li>
     *   <li><b>对齐 L1 瓦片</b>：L1 的网格间距就是 64 格，焦点吸附到 64 的整数倍
     *       （{@link MesoscaleGrid#CELL_SIZE_BLOCKS}）。这样网格只在玩家跨越瓦片边界时
     *       才整体平移，而不是每 tick 抖一下。</li>
     *   <li><b>只在瓦片变化时提交</b>：吸附后与当前焦点相同就原样返回，下游的
     *       {@code setFocus} 不会被无意义地反复调用（L0 还会据此判断要不要平移数组）。</li>
     * </ol>
     */
    private void updateFocus(ServerLevel level, DimensionState s) {
        var players = level.players();
        double rawX, rawZ;
        if (players.isEmpty()) {
            BlockPos spawn = level.getSharedSpawnPos();
            rawX = spawn.getX();
            rawZ = spawn.getZ();
        } else {
            // 单遍聚类：每个玩家归到第一个"质心在半径内"的簇，否则新开一簇
            int n = players.size();
            double[] cx = new double[n], cz = new double[n];
            int[] count = new int[n];
            int clusters = 0;
            double r2 = (double) FOCUS_CLUSTER_RADIUS_BLOCKS * FOCUS_CLUSTER_RADIUS_BLOCKS;
            for (ServerPlayer p : players) {
                int hit = -1;
                for (int c = 0; c < clusters; c++) {
                    double dx = p.getX() - cx[c] / count[c];
                    double dz = p.getZ() - cz[c] / count[c];
                    if (dx * dx + dz * dz <= r2) { hit = c; break; }
                }
                if (hit < 0) {
                    hit = clusters++;
                    cx[hit] = 0; cz[hit] = 0; count[hit] = 0;
                }
                cx[hit] += p.getX();
                cz[hit] += p.getZ();
                count[hit]++;
            }
            int best = 0;
            for (int c = 1; c < clusters; c++) if (count[c] > count[best]) best = c;
            rawX = cx[best] / count[best];
            rawZ = cz[best] / count[best];
        }

        // 吸附到 L1 瓦片（64 格）：只在跨瓦片时改变，风场不再"贴在玩家身上"
        double tile = MesoscaleGrid.CELL_SIZE_BLOCKS;
        double snappedX = Math.floor(rawX / tile) * tile;
        double snappedZ = Math.floor(rawZ / tile) * tile;
        if (snappedX != s.focusX || snappedZ != s.focusZ) {
            s.focusX = snappedX;
            s.focusZ = snappedZ;
        }
    }

    // 采样实现

    @Override
    public ZenithWindSample sample(ZenithWorldRef world, ZenithVec3 position, SamplePolicy policy) {
        return sampleInternal(world, position.x(), position.y(), position.z(), policy, false);
    }

    @Override
    public ZenithWindSample sample(ZenithWorldRef world, ZenithBlockPos position, SamplePolicy policy) {
        ZenithVec3 c = position.center();
        return sampleInternal(world, c.x(), c.y(), c.z(), policy, false);
    }

    /**
     * {@link net.atomos.zenith.api.ZenithTerrainProvider} 的实现（API-04）。
     *
     * <p>旧实现恒返回 {@code unknown()}：`ZenithTerrainApi.sample()` 走的是这个方法，
     * 而真正有实现的 {@code sampleTerrain()} 挂在另一个接口上、全仓零调用。
     * 外部模组问"这里地形多高"永远得到 UNKNOWN。现在直接转发。
     */
    @Override
    public net.atomos.zenith.api.ZenithTerrainSample sample(ZenithWorldRef world, ZenithBlockPos pos) {
        return sampleTerrain(world, pos);
    }

    private ZenithWindSample sampleInternal(ZenithWorldRef worldRef, double x, double y, double z,
                                            SamplePolicy policy, boolean fromClient) {
        MinecraftServer server = net.neoforged.neoforge.server.ServerLifecycleHooks.getCurrentServer();
        ZenithWindSample.Builder b = ZenithWindSample.builder()
                .source(ZenithWindSample.Source.NONE)
                .authority(fromClient ? ZenithWindSample.Authority.CLIENT_LOCAL
                        : ZenithWindSample.Authority.SERVER_AUTHORITATIVE);
        if (server == null) return b.build();

        ServerLevel level = levelFor(worldRef);
        if (level == null) return b.build();
        DimensionState s = stateFor(level);

        // SYS-11：文档约定"服务端采样只在服务端线程调用"。这里用 assert 把这个约定
        // 写成可执行的检查（生产环境 -ea 未开时零开销）；真正跨线程的调用方由下面
        // 的 samplingLock 兜底，不会读到刷新到一半的列。
        assert server.isSameThread() : "Zenith wind sampling must run on the server thread";
        s.samplingLock.lock();
        try {
            return sampleLocked(level, s, x, y, z, policy, b);
        } finally {
            s.samplingLock.unlock();
        }
    }

    /** {@link #sampleInternal} 的临界区：读 L1 列与 L2 scratch，与 {@code tickDimension} 的刷新互斥。 */
    private ZenithWindSample sampleLocked(ServerLevel level, DimensionState s,
                                          double x, double y, double z,
                                          SamplePolicy policy, ZenithWindSample.Builder b) {

        // 采样策略是**真的分层门控**（API-01）：旧实现 policy 传进来一次都没读，
        // SERVER_COARSE_ONLY（最便宜的语义）照样跑 729 次方块查询的 L2。
        //
        // 分界线定在"要不要付 L2 的账"：COARSE 只给 L1；其余全部含现象层与 L2。
        // GAMEPLAY_SERVER_ONLY 必须保留 L2 —— 帆船的尾流/遮蔽、风道接力（BLK-07 的
        // localModifier 里就是上游射流）都靠它，砍掉会把玩法打断。
        boolean wantPhenomena = policy != SamplePolicy.SERVER_COARSE_ONLY;
        boolean wantLocal = policy != SamplePolicy.SERVER_COARSE_ONLY;

        // L1 基流
        MesoscaleGrid.Sample l1 = s.l1.sample(x, y, z);
        double vx = l1.windX(), vy = l1.windY(), vz = l1.windZ();

        // 天气现象贡献（无天气维度直接跳过：现象只在 tick 里生成，这里再兜一层）
        double turbAdd = 0, precip = 0;
        // 热力/气压扰动（WX-FR-02）：锋面过境的降温/加压由现象层给出，叠加到 L1 输出上
        double tempDeltaK = 0, pressureDeltaPa = 0;
        if (wantPhenomena && weatherEnabled(level)) {
            for (WeatherPhenomenon p : s.phenomena()) {
                if (!phenomenonEnabled(p)) continue;
                WindContribution c = p.sample(x, y, z, vx, vz);
                if (c.isNone()) continue;
                vx += c.vx();
                vy += c.vy();
                vz += c.vz();
                turbAdd += c.turbulenceAdd();
                precip = Math.max(precip, c.precipIntensity01());
                tempDeltaK += c.tempDeltaK();
                pressureDeltaPa += c.pressureDeltaPa();
            }
        }

        // 台风眼：不再乘总风。Holland 廓线在 r→0 时已平滑趋于 0，眼内只有台风环流消失，
        // L1 背景风仍然存在（WX-TY-03：旧实现把眼内背景风也一起抹掉了）。

        // L2 本地修正：只有 FULL 级策略才付 729 次方块查询的账（L2-02 / API-01）
        LocalFlowSolver.LocalFlow l2 = wantLocal
                ? s.l2.solve(x, y, z, vx, vy, vz)
                : LocalFlowSolver.LocalFlow.NONE;
        double turb = l1.turbulenceIntensity() >= 0
                ? Math.min(1.0, l1.turbulenceIntensity() + turbAdd)
                : Math.min(1.0, 0.1 + turbAdd);

        // 层位标记（API-11）：旧实现只能标一个 Source，而实际是 L0→L1→现象→L2 叠加。
        int layers = ZenithWindSample.LAYER_L0 | ZenithWindSample.LAYER_L1;
        if (wantPhenomena && weatherEnabled(level) && !s.phenomena().isEmpty()) {
            layers |= ZenithWindSample.LAYER_PHENOMENA;
        }
        if (wantLocal) layers |= ZenithWindSample.LAYER_L2;

        // 置信度：L1 的 0.9 反映 64 格分辨率；L2 的置信度看扫描覆盖率与区块加载状态（L2-05）。
        // 现象层没有逐点误差估计，按"解析式 vs 网格采样"的差异再乘 0.95。
        float confidence = wantLocal ? (float) (0.9 * l2.confidence()) : 0.9f;
        if ((layers & ZenithWindSample.LAYER_PHENOMENA) != 0) confidence *= 0.95f;
        // SYS-11：再按数据新鲜度打折。L1 每 64 tick 才重算一次，两次刷新之间的采样
        // 是"上一帧的场"，旧实现恒报 0.9 完全没反映这一点。这里按距上次 L1 刷新
        // 的 tick 数线性衰减，最差到 0.75×（仍然可信，只是别当成实时值）。
        // 还没刷新过时（Long.MIN_VALUE 哨兵）直接按最旧算：减法会溢出，必须先判哨兵。
        float stalenessFactor = 0.75f;
        if (s.l1LastRefreshTick != Long.MIN_VALUE) {
            long staleness = Math.max(0L, level.getGameTime() - s.l1LastRefreshTick);
            stalenessFactor = (float) (1.0
                    - Math.min(1.0, staleness / (double) FRESHNESS_DECAY_TICKS) * 0.25);
        }
        confidence *= stalenessFactor;

        return b.meanVelocity(ZenithVec3.of(vx, vy, vz))
                .gustVelocity(ZenithVec3.of(l1.gustX(), l1.gustY(), l1.gustZ()))
                .localModifier(ZenithVec3.of(l2.modX(), l2.modY(), l2.modZ()))
                .turbulenceIntensity((float) turb)
                .windShearPerBlock((float) l1.shearPerBlock())
                // 气压从 L0 透传（L0-07 / API-07）：旧实现恒 0，API 文档里的字段是死值。
                // 现象层的气压扰动叠加在上面（WX-FR-02）。
                .pressureAnomalyPa((float) (l1.pressureAnomalyPa() + pressureDeltaPa))
                // 温度同理：L1 给的是未受天气系统影响的环境温度，锋面再改（WX-FR-02）
                .airTemperatureKelvin((float) (l1.airTempK() + tempDeltaK))
                .humidity01((float) l1.humidity01())
                .sheltered(l2.sheltered() || l1.sheltered())
                .source(wantLocal ? ZenithWindSample.Source.L2_LOCAL : ZenithWindSample.Source.L1_COARSE)
                .layers(layers)
                .confidence(confidence)
                .freshnessEpoch(level.getGameTime())
                .build();
    }

    /**
     * 批量采样（API-05）：维度查找与"是否跑现象/L2"只判一次，循环里只做逐点计算。
     *
     * <p>默认实现会每点调一遍 {@link #sampleInternal}，其中 {@code levelFor} + {@code stateFor}
     * 都是 Map 查找，n 点就是 n 倍。这里提到循环外，{@code WeatherSnapshotPacket} 那种
     * "一次算一片"的场景直接受益。
     */
    @Override
    public ZenithWindSample[] sampleBatch(ZenithWorldRef worldRef, double[] positions, SamplePolicy policy) {
        if (positions == null || positions.length % 3 != 0) {
            throw new IllegalArgumentException("positions must be a flat [x,y,z,...] array");
        }
        int n = positions.length / 3;
        ZenithWindSample[] out = new ZenithWindSample[n];
        for (int i = 0; i < n; i++) {
            out[i] = sampleInternal(worldRef, positions[i * 3], positions[i * 3 + 1],
                    positions[i * 3 + 2], policy, false);
        }
        return out;
    }

    /** 按维度键取 ServerLevel：不再每次遍历 + {@code toString()} 新建字符串（RT-05）。 */
    private ServerLevel levelFor(ZenithWorldRef worldRef) {
        MinecraftServer server = net.neoforged.neoforge.server.ServerLifecycleHooks.getCurrentServer();
        if (server == null) return null;
        ResourceKey<Level> key = dimensionKey(worldRef);
        if (key != null) {
            ServerLevel direct = server.getLevel(key);
            if (direct != null) return direct;
        }
        // 名字对不上（模组维度动态注册时 ResourceKey 可能不等）才退回逐维度比较
        for (ServerLevel l : server.getAllLevels()) {
            if (l.dimension().location().toString().equals(worldRef.dimension().toString())) return l;
        }
        return null;
    }

    /**
     * 维度键（RT-05）：先查 {@link #DIMENSION_KEYS} 的规范映射，再按 {@code namespace:path} 现拼。
     * 旧实现每次都 {@code getAllLevels()} 遍历 + {@code toString()} 新建字符串 + {@code equals}。
     */
    private static ResourceKey<Level> dimensionKey(ZenithWorldRef worldRef) {
        String id = worldRef.dimension().toString();
        ResourceKey<Level> known = DIMENSION_KEYS.get(id);
        if (known != null) return known;
        try {
            return ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION,
                    net.minecraft.resources.ResourceLocation.parse(id));
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** 原版三维度的规范键缓存（RT-05）。 */
    private static final Map<String, ResourceKey<Level>> DIMENSION_KEYS = Map.of(
            "minecraft:overworld", Level.OVERWORLD,
            "minecraft:the_nether", Level.NETHER,
            "minecraft:the_end", Level.END);

    @Override
    public ZenithL2Result runL2(ZenithL2Request request) {
        // API-02：这里从来没有真正的 L2 力矩求解器。旧实现返回 of(zero)，
        // 而 of() 的唯一语义是 supported=true —— 等于告诉调用方"支持，力=0 N"，
        // 与"这里真的没风"无法区分。现在明确报 unsupported。
        return ZenithL2Result.unsupported();
    }

    /**
     * 翼型极线生成器：薄翼型理论 + 失速模型（原版是 LBM 数值算的，这里解析式代替）。
     * cl = 2π(α−α0)·有限翼展修正；过了失速角按平板模型衰减；cd = cd0 + k·cl²。
     */
    @Override
    public ZenithPolarResult runPolar(ZenithPolarRequest request) {
        try {
            var surface = request.surface();
            var def = ZenithAirfoilPresets.defaults().stream()
                    .filter(d -> d.id().equals(surface.airfoilId()))
                    .findFirst().orElse(ZenithAirfoilPresets.NACA_0012);
            double thickness = def.maxThickness();
            double ar = surface.aspectRatio();
            double arFactor = ar / (ar + 2.0); // 有限翼展修正
            double alpha0 = -2.0 - thickness * 8.0; // 零升迎角（度，粗略）
            double stallAngle = 14.0 - thickness * 10.0;

            List<ZenithPolarSample> samples = new ArrayList<>();
            int n = request.angleSampleCount();
            for (int i = 0; i < n; i++) {
                double alpha = request.angleAt(i);
                double alphaRad = Math.toRadians(alpha - alpha0);
                double clAttached = 2 * Math.PI * alphaRad * arFactor;
                double cl;
                if (Math.abs(alpha) <= stallAngle) {
                    cl = clAttached;
                } else {
                    // 失速：sin(2α) 平板模型和附着流按指数混合着来
                    double over = Math.abs(alpha) - stallAngle;
                    double flatPlate = 1.9 * Math.sin(Math.toRadians(2 * alpha));
                    cl = Math.signum(clAttached)
                            * (Math.abs(clAttached) * Math.exp(-over / 12.0)
                            + Math.abs(flatPlate) * (1 - Math.exp(-over / 12.0)) * 0.8);
                }
                double cd0 = 0.006 + thickness * 0.05;
                double k = 1.0 / (Math.PI * ar * 0.85);
                double cd = cd0 + k * cl * cl + 0.02 * Math.pow(Math.abs(alpha) / 15.0, 4);
                double cm = -0.08 - thickness * 0.15;
                samples.add(new ZenithPolarSample(alpha, cl, cd, cm));
            }
            return ZenithPolarResult.ok(request, new ZenithPolarTable(samples),
                    "zenith thin-airfoil polar (java)");
        } catch (Exception e) {
            return ZenithPolarResult.failed(request, e.getMessage());
        }
    }

    /**
     * 地形采样（API-04）：转发到真正的实现，不再恒返回 {@code unknown}。
     *
     * <p>注意高度口径：{@code terrainHeightBlocks} 是**地表顶面 y**（AGL 基准，作方块坐标要减 1）。
     * 对外的 {@code ZenithTerrainSample.heightY} 语义是"最高的非空气方块 y"，所以这里减 1。
     */
    @Override
    public ZenithTerrainSample sampleTerrain(ZenithWorldRef world, ZenithBlockPos pos) {
        ServerLevel level = levelFor(world);
        if (level == null) return ZenithTerrainSample.unknown();
        DimensionState s = stateFor(level);
        double surfaceTop = s.terrainAdapter.terrainHeightBlocks(pos.x(), pos.z());
        return ZenithTerrainSample.of((int) Math.floor(surfaceTop) - 1,
                (float) s.terrainAdapter.roughness01(pos.x(), pos.z()),
                s.terrainAdapter.surfaceAt(pos.x(), pos.z()));
    }

    // 热源索引（RT-02 / L2-03）

    /**
     * L2 查询热源（RT-02）。索引里已按段归并，这里只做球内筛选 + 最近 N 个裁剪。
     *
     * <p>旧实现从 {@code s.heatCache} 顺序扫全部条目——索引是"焦点 ±48 格"的全量扫描结果，
     * 玩家走到 20 格外就整盒作废，而索引本身还是 200 tick 才重建一次。
     */
    private List<LocalFlowSolver.HeatSource> heatNear(DimensionState s, double x, double y, double z,
                                                      double radius) {
        if (s.heat == null) return List.of();
        return s.heat.near(x, y, z, radius);
    }

    /**
     * 热源索引的增量刷新调度（RT-02）。
     *
     * <p>每个玩家的 32 格盒各自扫一次，段级 10 秒内不重扫；因此稳态成本从
     * "每 200 tick 50,421 次 {@code getBlockState}" 降到"每 20 tick 只对刚进入范围的段
     * 做一次调色板判断"。没有玩家时什么都不做（旧实现用出生点假装有玩家）。
     */
    private void refreshHeatCache(ServerLevel level, DimensionState s, long tick) {
        if (s.heat == null) return;
        if (s.heatScheduleTick >= 0 && tick - s.heatScheduleTick < HeatCache.SCHEDULE_INTERVAL_TICKS) return;
        s.heatScheduleTick = tick;
        s.heat.refresh(level, level.players(), tick);
    }

    private double heatPowerOf(BlockState st) {
        if (st.getFluidState().is(Fluids.LAVA)) return 9000;
        if (st.is(Blocks.LAVA)) return 9000;
        if (st.is(Blocks.FIRE) || st.is(Blocks.SOUL_FIRE)) return 1500;
        if (st.is(Blocks.MAGMA_BLOCK)) return 1200;
        if (st.is(Blocks.CAMPFIRE) && st.getValue(CampfireBlock.LIT)) return 2000;
        if (st.is(Blocks.SOUL_CAMPFIRE) && st.getValue(CampfireBlock.LIT)) return 1200;
        if (st.is(Blocks.TORCH) || st.is(Blocks.WALL_TORCH)
                || st.is(Blocks.SOUL_TORCH) || st.is(Blocks.SOUL_WALL_TORCH)) return 80;
        if (st.is(Blocks.FURNACE) && st.getValue(net.minecraft.world.level.block.FurnaceBlock.LIT)) return 2500;
        if (st.is(Blocks.BLAST_FURNACE) && st.getValue(net.minecraft.world.level.block.AbstractFurnaceBlock.LIT)) return 3500;
        return 0;
    }

    // 网络广播

    /**
     * 粗风场同步（NET-01 / RT-01 / RT-04）。
     *
     * <p>三层修正：
     * <ul>
     *   <li>分辨率与 L1 源网格对齐（64 格），覆盖 ±6 格 × 64 = ±384 格（视距 192 的 2 倍），
     *       不再发"比源网格还细"的假精度；</li>
     *   <li>除了 L1 基流，再发一条**现象层合成矢量**——台风眼墙、飑线阵风锋、尘卷风、热泡
     *       在客户端终于可见（旧包只有 L1，客户端把 30 m/s 的台风看成 5 m/s 的背景风）；</li>
     *   <li>按维度缓存一份网格再分发给该维度全体玩家：旧实现每个玩家单独 81 次采样 + 独立包，
     *       20 人服就是 1620 次/2 s，而 40 tick 内格点值几乎不变（RT-04）。</li>
     * </ul>
     */
    private void broadcastCoarseWind(ServerLevel level, DimensionState s) {
        var players = level.players();
        if (players.isEmpty()) return;

        // 以玩家质心为中心建一张网格（所有人共用的那份）
        double cx = 0, cz = 0;
        for (ServerPlayer p : players) { cx += p.getX(); cz += p.getZ(); }
        cx /= players.size();
        cz /= players.size();
        // 对齐到单格间距的整数倍：相邻两次广播的格点重合，客户端插值不会来回抖
        double cell = COARSE_WIND_SYNC_CELL_SIZE_BLOCKS;
        cx = Math.floor(cx / cell) * cell;
        cz = Math.floor(cz / cell) * cell;
        double cy = players.get(0).getY();

        int n = COARSE_WIND_SYNC_RADIUS_CELLS * 2 + 1;
        int cells = n * n;
        float[] vx = new float[cells], vy = new float[cells], vz = new float[cells];
        float[] phx = new float[cells], phy = new float[cells], phz = new float[cells];

        boolean weather = weatherEnabled(level);
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                double bx = cx + (i - COARSE_WIND_SYNC_RADIUS_CELLS) * cell;
                double bz = cz + (j - COARSE_WIND_SYNC_RADIUS_CELLS) * cell;
                int k = j * n + i;

                MesoscaleGrid.Sample smp = s.l1.sample(bx, cy, bz);
                vx[k] = (float) smp.windX();
                vy[k] = (float) smp.windY();
                vz[k] = (float) smp.windZ();

                if (!weather) continue;
                // 现象层：只在**这一格**采样，且传 0 基流，得到的就是"现象额外提供的那部分风"
                double px = 0, py = 0, pz = 0;
                for (WeatherPhenomenon p : s.phenomena()) {
                    if (!phenomenonEnabled(p)) continue;
                    WindContribution c = p.sample(bx, cy, bz, 0, 0);
                    if (c.isNone()) continue;
                    px += c.vx();
                    py += c.vy();
                    pz += c.vz();
                }
                phx[k] = (float) px;
                phy[k] = (float) py;
                phz[k] = (float) pz;
            }
        }

        // 帧只算一次（增量比较有副作用：推进 coarseBase），全体玩家共用
        CoarseWindPacket delta = buildCoarseFrame(s, level,
                cx, cy, cz, (int) cell, vx, vy, vz, phx, phy, phz);

        // 增量帧只对"已经收过上一帧"的玩家有意义（RT-04）。新进服/换维度的玩家
        // 必须先拿一帧全量——否则客户端把 delta 当成基准帧，六个数组的长度是
        // "变更格点数"而不是格点数，采样直接错位。
        java.util.Set<java.util.UUID> current = new java.util.HashSet<>();
        for (ServerPlayer player : players) current.add(player.getUUID());
        s.coarseRecipients.retainAll(current); // 退服的人不占集合，回服时自动重新拿全量

        if (delta == null) {
            // 风场没变：只给还没拿到全量的新人补一帧
            if (s.coarseBase == null) return;
            for (ServerPlayer player : players) {
                if (s.coarseRecipients.contains(player.getUUID())) continue;
                PacketDistributor.sendToPlayer(player, fullFrame(level, cx, cy, cz, (int) cell,
                        vx, vy, vz, phx, phy, phz));
                s.coarseRecipients.add(player.getUUID());
            }
            return;
        }

        if (delta.isFullFrame()) {
            // 全量帧：所有人都能直接吃（新人也包含在内）
            for (ServerPlayer player : players) {
                PacketDistributor.sendToPlayer(player, delta);
                s.coarseRecipients.add(player.getUUID());
            }
            return;
        }

        for (ServerPlayer player : players) {
            if (s.coarseRecipients.contains(player.getUUID())) {
                PacketDistributor.sendToPlayer(player, delta);
            } else {
                // 新人必须先从**当前时刻**的全量帧起步（RT-04）：不能拿 lastCoarse，
                // 那可能是几十分钟前那一帧，中间累积的增量他没收到，之后永远对不上。
                PacketDistributor.sendToPlayer(player, fullFrame(level, cx, cy, cz, (int) cell,
                        vx, vy, vz, phx, phy, phz));
                s.coarseRecipients.add(player.getUUID());
            }
        }
    }

    /** 用当前格点值拼一帧全量（给新进服玩家补基准用）。 */
    private static CoarseWindPacket fullFrame(ServerLevel level,
                                              double cx, double cy, double cz, int cell,
                                              float[] vx, float[] vy, float[] vz,
                                              float[] phx, float[] phy, float[] phz) {
        return new CoarseWindPacket(level.dimension().location().toString(),
                cx, cy, cz, cell, COARSE_WIND_SYNC_RADIUS_CELLS,
                new int[0], vx, vy, vz, phx, phy, phz, level.getGameTime());
    }

    /**
     * 组装粗风场帧，能发增量就发增量（RT-04）。
     *
     * <p>比较基准是 {@link DimensionState#coarseBase}——"客户端当前应有的值"。三件事会让它
     * 退回全量：① 没有基准（首次/换维度）；② 网格中心或间距变了（玩家群整体移动超过一格，
     * 格点不再对齐，增量没有意义）；③ 变更格点占比超过
     * {@link CoarseWindPacket#FULL_RESEND_FRACTION}（索引表开销不划算）。
     *
     * <p>逐格阈值 {@link CoarseWindPacket#DIRTY_EPSILON_MPS} 是 0.1 m/s：低于这个值的差异
     * 无论是粒子偏移还是风声都听不出来，不值得为它占 6 个 int16。
     */
    private CoarseWindPacket buildCoarseFrame(DimensionState s, ServerLevel level,
                                              double cx, double cy, double cz, int cell,
                                              float[] vx, float[] vy, float[] vz,
                                              float[] phx, float[] phy, float[] phz) {
        CoarseWindPacket prev = s.lastCoarse;
        boolean sameFrame = prev != null && s.coarseBase != null
                && prev.cellSizeBlocks() == cell
                && prev.radiusCells() == COARSE_WIND_SYNC_RADIUS_CELLS
                && Math.abs(prev.centerX() - cx) < 1e-6
                && Math.abs(prev.centerZ() - cz) < 1e-6;

        if (!sameFrame) {
            setCoarseBase(s, vx, vy, vz, phx, phy, phz);
            CoarseWindPacket full = new CoarseWindPacket(
                    level.dimension().location().toString(),
                    cx, cy, cz, cell, COARSE_WIND_SYNC_RADIUS_CELLS,
                    new int[0], vx, vy, vz, phx, phy, phz, level.getGameTime());
            s.lastCoarse = full;
            return full;
        }

        int cells = vx.length;
        float[][] base = s.coarseBase;
        int[] dirty = new int[cells];
        int dirtyCount = 0;
        for (int k = 0; k < cells; k++) {
            if (Math.abs(vx[k] - base[0][k]) > CoarseWindPacket.DIRTY_EPSILON_MPS
                    || Math.abs(vy[k] - base[1][k]) > CoarseWindPacket.DIRTY_EPSILON_MPS
                    || Math.abs(vz[k] - base[2][k]) > CoarseWindPacket.DIRTY_EPSILON_MPS
                    || Math.abs(phx[k] - base[3][k]) > CoarseWindPacket.DIRTY_EPSILON_MPS
                    || Math.abs(phy[k] - base[4][k]) > CoarseWindPacket.DIRTY_EPSILON_MPS
                    || Math.abs(phz[k] - base[5][k]) > CoarseWindPacket.DIRTY_EPSILON_MPS) {
                dirty[dirtyCount++] = k;
            }
        }
        if (dirtyCount == 0) {
            // 完全没变：什么都不发。客户端上一帧仍然有效，插值照常。
            return null;
        }
        if (dirtyCount > cells * CoarseWindPacket.FULL_RESEND_FRACTION) {
            setCoarseBase(s, vx, vy, vz, phx, phy, phz);
            CoarseWindPacket full = new CoarseWindPacket(
                    level.dimension().location().toString(),
                    cx, cy, cz, cell, COARSE_WIND_SYNC_RADIUS_CELLS,
                    new int[0], vx, vy, vz, phx, phy, phz, level.getGameTime());
            s.lastCoarse = full;
            return full;
        }

        int[] idx = new int[dirtyCount];
        float[] dvx = new float[dirtyCount], dvy = new float[dirtyCount], dvz = new float[dirtyCount];
        float[] dpx = new float[dirtyCount], dpy = new float[dirtyCount], dpz = new float[dirtyCount];
        for (int o = 0; o < dirtyCount; o++) {
            int k = dirty[o];
            idx[o] = k;
            dvx[o] = vx[k];
            dvy[o] = vy[k];
            dvz[o] = vz[k];
            dpx[o] = phx[k];
            dpy[o] = phy[k];
            dpz[o] = phz[k];
            // 已发送，推进基准：否则下一帧仍然"与基准不同"，同一格被无限重发
            base[0][k] = vx[k];
            base[1][k] = vy[k];
            base[2][k] = vz[k];
            base[3][k] = phx[k];
            base[4][k] = phy[k];
            base[5][k] = phz[k];
        }
        return new CoarseWindPacket(
                level.dimension().location().toString(),
                cx, cy, cz, cell, COARSE_WIND_SYNC_RADIUS_CELLS,
                idx, dvx, dvy, dvz, dpx, dpy, dpz, level.getGameTime());
    }

    /** 全量帧发出后重置基准：客户端此时手里的就是这六个数组。 */
    private static void setCoarseBase(DimensionState s, float[] vx, float[] vy, float[] vz,
                                      float[] phx, float[] phy, float[] phz) {
        s.coarseBase = new float[][]{vx.clone(), vy.clone(), vz.clone(),
                phx.clone(), phy.clone(), phz.clone()};
    }

    private void broadcastWeatherSnapshot(ServerLevel level, DimensionState s) {
        var typhoons = s.typhoons.typhoons();
        var storms = s.storms.cells();
        // 湿度就取焦点处（地表 AGL 10 格）的 L1 采样，别写死 y=80——
        // 山地 y=120 时那是地下，洞穴 y=-40 时那是半空
        double hy = s.terrainAdapter.terrainHeightBlocks(s.focusX, s.focusZ) + 10.0;
        var l1sample = s.l1.sample(s.focusX, hy, s.focusZ);
        WeatherSnapshotPacket pkt = WeatherSnapshotPacket.build(
                level.dimension().location().toString(),
                s.driver.stormActivity(), s.tornadoes.activeCount(),
                s.thermals.activeCount(), s.seaBreeze.frontInlandDistance(),
                l1sample.humidity01(),
                typhoons, storms,
                s.fronts.fronts(), s.dustDevils.devils(), s.squallLines.lines(),
                s.tornadoes.tornadoes());
        // 快照是"本维度的天气现状"，内容一致：只建一次包，本维度所有玩家共用（RT-04）
        for (ServerPlayer player : level.players()) {
            PacketDistributor.sendToPlayer(player, pkt);
        }
    }

    // ZenithWeatherRuntimeProvider 接口实现

    private DimensionState stateFor(net.atomos.zenith.api.ZenithWorldRef worldRef) {
        ServerLevel level = levelFor(worldRef);
        return level == null ? null : stateFor(level);
    }

    @Override
    public java.util.List<net.atomos.zenith.api.ZenithFrontInfo> fronts(
            net.atomos.zenith.api.ZenithWorldRef world) {
        DimensionState s = stateFor(world);
        if (s == null) return java.util.List.of();
        return s.fronts.fronts().stream()
                .map(f -> new net.atomos.zenith.api.ZenithFrontInfo(
                        f.type.name(), f.x1, f.z1, f.x2, f.z2, f.intensity01))
                .toList();
    }

    @Override
    public java.util.List<net.atomos.zenith.api.ZenithDustDevilInfo> dustDevils(
            net.atomos.zenith.api.ZenithWorldRef world) {
        DimensionState s = stateFor(world);
        if (s == null) return java.util.List.of();
        return s.dustDevils.devils().stream()
                .map(d -> new net.atomos.zenith.api.ZenithDustDevilInfo(
                        d.x, d.z, d.radiusBlocks, d.heightBlocks, d.tangentialMps))
                .toList();
    }

    @Override
    public java.util.List<net.atomos.zenith.api.ZenithSquallLineInfo> squallLines(
            net.atomos.zenith.api.ZenithWorldRef world) {
        DimensionState s = stateFor(world);
        if (s == null) return java.util.List.of();
        return s.squallLines.lines().stream()
                .map(l -> new net.atomos.zenith.api.ZenithSquallLineInfo(
                        l.x1, l.z1, l.x2, l.z2, l.intensity01, l.microbursts.size()))
                .toList();
    }

    @Override
    public java.util.List<net.atomos.zenith.api.ZenithTyphoonInfo> typhoons(
            net.atomos.zenith.api.ZenithWorldRef world) {
        DimensionState s = stateFor(world);
        if (s == null) return java.util.List.of();
        return s.typhoons.typhoons().stream()
                .map(t -> new net.atomos.zenith.api.ZenithTyphoonInfo(
                        t.name, t.x, t.z, t.vmaxMps, t.rmaxBlocks, t.eyeRadiusBlocks,
                        t.intensity01))
                .toList();
    }

    @Override
    public double hailAt(net.atomos.zenith.api.ZenithWorldRef world,
                         net.atomos.zenith.api.ZenithBlockPos pos) {
        DimensionState s = stateFor(world);
        return s == null ? 0 : s.storms.hailAt(pos.x(), pos.z());
    }

    @Override
    public double fogDensityAt(net.atomos.zenith.api.ZenithWorldRef world,
                               net.atomos.zenith.api.ZenithBlockPos pos) {
        MinecraftServer server = net.neoforged.neoforge.server.ServerLifecycleHooks.getCurrentServer();
        if (server == null) return 0;
        ServerLevel level = null;
        for (ServerLevel l : server.getAllLevels()) {
            if (l.dimension().location().toString().equals(world.dimension().toString())) {
                level = l;
                break;
            }
        }
        if (level == null) return 0;
        DimensionState s = stateFor(level);
        var l1 = s.l1.sample(pos.x(), 80, pos.z());
        double windSpeed = Math.hypot(l1.windX(), l1.windZ());
        boolean overWater = s.terrainAdapter.surfaceAt(pos.x(), pos.z())
                == net.atomos.zenith.api.ZenithTerrainSurfaceClass.OCEAN;
        double solar = net.atomos.zenith.weather.FogModel.solarFactor(
                level.getDayTime() / 24000.0);
        return net.atomos.zenith.weather.FogModel.density(
                solar, level.isRaining(), windSpeed, l1.humidity01(), overWater);
    }

    @Override
    public net.atomos.zenith.api.ZenithSeasonInfo season(
            net.atomos.zenith.api.ZenithWorldRef world) {
        MinecraftServer server = net.neoforged.neoforge.server.ServerLifecycleHooks.getCurrentServer();
        long dayTime = 6000;
        if (server != null) {
            for (ServerLevel l : server.getAllLevels()) {
                if (l.dimension().location().toString().equals(world.dimension().toString())) {
                    dayTime = l.getDayTime();
                    break;
                }
            }
        }
        var si = net.atomos.zenith.weather.SeasonSystem.compute(dayTime);
        return new net.atomos.zenith.api.ZenithSeasonInfo(
                si.season().name(), si.dayOfYear(), si.tempBiasKelvin(), si.solarMultiplier());
    }

    @Override
    public net.atomos.zenith.api.ZenithVec3 monsoonVector(
            net.atomos.zenith.api.ZenithWorldRef world) {
        DimensionState s = stateFor(world);
        if (s == null) return net.atomos.zenith.api.ZenithVec3.of(0, 0, 0);
        return net.atomos.zenith.api.ZenithVec3.of(s.monsoon.monsoonX(), 0, s.monsoon.monsoonZ());
    }

    @Override
    public double mountainWaveLift(net.atomos.zenith.api.ZenithWorldRef world,
                                   net.atomos.zenith.api.ZenithVec3 pos) {
        DimensionState s = stateFor(world);
        if (s == null) return 0;
        var l1 = s.l1.sample(pos.x(), pos.y(), pos.z());
        return s.mountainWaves.sample(pos.x(), pos.y(), pos.z(), l1.windX(), l1.windZ()).vy();
    }

    // 地形适配器

    /**
     * MC 地形适配器：区块已加载用真实数据，否则回退哈希估计。
     */
    static final class McTerrainAdapter implements BackgroundMetGrid.TerrainProbe,
            MesoscaleGrid.TerrainProbe, SeedTerrainProvider {
        private final ServerLevel level;
        private final HashedSeedTerrainProvider fallback;

        McTerrainAdapter(ServerLevel level, HashedSeedTerrainProvider fallback) {
            this.level = level;
            this.fallback = fallback;
        }

        private ChunkAccess chunkIfLoaded(double bx, double bz) {
            int cx = ((int) Math.floor(bx)) >> 4;
            int cz = ((int) Math.floor(bz)) >> 4;
            return level.getChunkSource().getChunk(cx, cz, ChunkStatus.FULL, false);
        }

        /** 供 L2 地面探针复用（包内可见）：方块坐标 → 已加载区块，未加载返回 {@code null}。 */
        ChunkAccess chunkIfLoaded(int bx, int bz) {
            return chunkIfLoaded((double) bx, (double) bz);
        }

        /**
         * 该列**最高非空气方块的方块 y**（地表方块本身）。
         * <p>{@code getHeight(WORLD_SURFACE)} 返回的是"最高非空气方块**之上**的第一格"，即空气格；
         * 要拿 {@code getBlockState} 的坐标必须减 1，否则读到的是空气（SYS-12）。
         */
        private static int surfaceBlockY(ChunkAccess c, int x, int z) {
            int h = c.getHeight(Heightmap.Types.WORLD_SURFACE, x & 15, z & 15);
            // 整列全空气（虚空）时 h 等于 minBuildHeight，减 1 会掉出世界；夹回最低层，
            // 否则 surfaceBiome() 的 quart y 会算出负的 section 索引
            return Math.max(h - 1, c.getMinBuildHeight());
        }

        /** 地表方块处的生物群系（quart 坐标）。固定 y=64 会让高山/深海/洞穴判错（SYS-13）。 */
        private static Holder<Biome> surfaceBiome(ChunkAccess c, int x, int z) {
            int y = surfaceBlockY(c, x, z);
            return c.getNoiseBiome(x >> 2, y >> 2, z >> 2);
        }

        /**
         * 地表**顶面** y（AGL 基准）：站在地面上时采样点的 y 就是这个值，所以 AGL 计算直接用 {@code h}，
         * 不减 1。需要方块坐标的地方一律走 {@link #surfaceBlockY}。
         */
        @Override
        public double terrainHeightBlocks(double bx, double bz) {
            ChunkAccess c = chunkIfLoaded(bx, bz);
            if (c != null) {
                int x = (int) Math.floor(bx), z = (int) Math.floor(bz);
                return c.getHeight(Heightmap.Types.WORLD_SURFACE, x & 15, z & 15);
            }
            return fallback.heightAt(bx, bz);
        }

        @Override
        public double roughness01(double bx, double bz) {
            ChunkAccess c = chunkIfLoaded(bx, bz);
            if (c == null) return fallback.roughnessAt(bx, bz);
            int x = (int) Math.floor(bx), z = (int) Math.floor(bz);
            // 别用 biome.toString() 猜名字：Biome 没覆写 toString，拿到的是类名@hash，永远匹配不上
            Holder<Biome> biome = surfaceBiome(c, x, z);
            float r = 0.3f;
            if (biome.is(BiomeTags.IS_FOREST) || biome.is(BiomeTags.IS_JUNGLE)
                    || biome.is(BiomeTags.IS_TAIGA)) r = 0.75f;
            else if (isDesert(biome) || biome.is(BiomeTags.IS_BEACH)) r = 0.15f;
            else if (biome.is(BiomeTags.IS_OCEAN) || biome.is(BiomeTags.IS_RIVER)) r = 0.03f;
            else if (biome.is(BiomeTags.IS_MOUNTAIN)) r = 0.85f;
            return r;
        }

        // 原版没有 IS_DESERT 标签，沙漠只能按注册名认
        private static boolean isDesert(Holder<Biome> biome) {
            return biome.unwrapKey().map(k -> k.equals(Biomes.DESERT)).orElse(false);
        }

        /**
         * 生物群系基础温度 → 开尔文。
         *
         * <p>旧实现 {@code 273.15 + t*30}（L0-05）：沙漠 t=2.0 → 333 K（60 °C），
         * 与真实下垫面差了 20 K，ABL 稳定度被永久钉成"强不稳定"、混合层高度恒 460 格；
         * 雪原 t=-0.5 → 258 K 又偏高。
         *
         * <p>按 MC 实际三类下垫面标定到 {@link BackgroundMetGrid#BIOME_TEMP_MIN_K}..{@code MAX_K}：
         * 雪原 -0.5 → 255 K（-18 °C）、温带 0.8 → 283 K（10 °C）、沙漠 2.0 → 310 K（37 °C）。
         * 超出 [-0.5, 2.0] 的模组生物群系夹到端点。
         */
        @Override
        public double biomeTemperatureKelvin(double bx, double bz) {
            ChunkAccess c = chunkIfLoaded(bx, bz);
            float t;
            if (c != null) {
                int x = (int) Math.floor(bx), z = (int) Math.floor(bz);
                t = surfaceBiome(c, x, z).value().getBaseTemperature();
            } else {
                t = 0.7f;
            }
            double u = (t - BackgroundMetGrid.BIOME_TEMP_MIN_INPUT)
                    / (BackgroundMetGrid.BIOME_TEMP_MAX_INPUT - BackgroundMetGrid.BIOME_TEMP_MIN_INPUT);
            u = u < 0 ? 0 : Math.min(1, u);
            return BackgroundMetGrid.BIOME_TEMP_MIN_K
                    + u * (BackgroundMetGrid.BIOME_TEMP_MAX_K - BackgroundMetGrid.BIOME_TEMP_MIN_K);
        }

        /** 海平面（方块 y）：L0 的高度递减率只对海平面以上生效（L0-05）。 */
        @Override
        public double seaLevelBlocks() {
            return level.getSeaLevel();
        }

        @Override
        public double[] slopeVector(double bx, double bz) {
            double e = terrainHeightBlocks(bx + 8, bz);
            double w = terrainHeightBlocks(bx - 8, bz);
            double s = terrainHeightBlocks(bx, bz + 8);
            double n = terrainHeightBlocks(bx, bz - 8);
            return new double[]{(e - w) / 16.0, (s - n) / 16.0};
        }

        @Override
        public double heightAt(double bx, double bz) { return terrainHeightBlocks(bx, bz); }

        @Override
        public double roughnessAt(double bx, double bz) { return roughness01(bx, bz); }

        @Override
        public boolean isOceanAt(double bx, double bz) {
            ChunkAccess c = chunkIfLoaded(bx, bz);
            if (c != null) {
                int x = (int) Math.floor(bx), z = (int) Math.floor(bz);
                // h-1 才是地表方块；读 h 读到的是空气（SYS-12）
                BlockState st = c.getBlockState(new BlockPos(x, surfaceBlockY(c, x, z), z));
                if (!st.getFluidState().is(Fluids.WATER)) return false;
                // 只有海洋/河流群系的水才算"海"：否则池塘、水池也会生成海风与台风（SB-04）
                Holder<Biome> biome = surfaceBiome(c, x, z);
                return biome.is(BiomeTags.IS_OCEAN) || biome.is(BiomeTags.IS_RIVER);
            }
            return fallback.isOceanAt(bx, bz);
        }

        @Override
        public ZenithTerrainSurfaceClass surfaceAt(double bx, double bz) {
            ChunkAccess c = chunkIfLoaded(bx, bz);
            if (c == null) return fallback.surfaceAt(bx, bz);
            int x = (int) Math.floor(bx), z = (int) Math.floor(bz);
            BlockState st = c.getBlockState(new BlockPos(x, surfaceBlockY(c, x, z), z));
            if (st.getFluidState().is(Fluids.WATER)) return ZenithTerrainSurfaceClass.OCEAN;
            // 别用方块名 contains：以前 "sand" 会误伤 sandstone，"stone" 会误伤 redstone_ore
            if (st.is(BlockTags.SAND)) return ZenithTerrainSurfaceClass.DESERT_SAND;
            if (st.is(BlockTags.SNOW) || st.is(Blocks.ICE) || st.is(Blocks.PACKED_ICE)
                    || st.is(Blocks.BLUE_ICE) || st.is(Blocks.FROSTED_ICE))
                return ZenithTerrainSurfaceClass.SNOW;
            if (st.is(BlockTags.BASE_STONE_OVERWORLD) || st.is(Blocks.SANDSTONE)
                    || st.is(Blocks.RED_SANDSTONE) || st.is(Blocks.END_STONE)
                    || st.is(Blocks.NETHERRACK)) return ZenithTerrainSurfaceClass.ROCK;
            if (st.is(BlockTags.LEAVES) || st.is(BlockTags.LOGS))
                return ZenithTerrainSurfaceClass.FOREST;
            return ZenithTerrainSurfaceClass.GRASS;
        }
    }

    // 诊断

    public DimensionState debugState(ResourceKey<Level> dim) {
        return dimensions.get(dim);
    }
}
