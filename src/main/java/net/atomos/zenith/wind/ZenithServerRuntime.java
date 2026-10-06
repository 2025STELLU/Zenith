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
import net.atomos.zenith.weather.TyphoonSystem;
import net.atomos.zenith.weather.WeatherContext;
import net.atomos.zenith.weather.WeatherPhenomenon;
import net.atomos.zenith.weather.WindContribution;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.NeoForge;
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
 * <p>移植自 Aerodynamics4MC-Core 的 {@code AeroServerRuntime}（MIT），
 * native LBM 部分替换为纯 Java {@link LocalFlowSolver}。</p>
 *
 * <p>每 tick 编排：</p>
 * <ol>
 *   <li>收集 L1 诊断 → 反馈给驱动器</li>
 *   <li>{@code WorldScaleDriver.advance}（每 tick，dt=0.05s）</li>
 *   <li>天气现象 tick（热对流/海风/风暴/台风）</li>
 *   <li>{@code BackgroundMetGrid.refresh}（每 256 tick）</li>
 *   <li>{@code MesoscaleGrid.refresh}（每 64 tick）</li>
 *   <li>广播粗风场包（每 40 tick）与天气快照包（每 100 tick）</li>
 * </ol>
 */
public class ZenithServerRuntime implements ZenithWindRuntimeProvider,
        net.atomos.zenith.api.ZenithTerrainProvider,
        net.atomos.zenith.api.ZenithWeatherRuntimeProvider {
    private static final Logger LOGGER = LoggerFactory.getLogger("zenith");

    public static final double SOLVER_STEP_SECONDS = 0.05;
    public static final int BACKGROUND_MET_REFRESH_TICKS = 256;
    public static final int MESOSCALE_REFRESH_TICKS = 64;
    public static final int COARSE_WIND_SYNC_TICKS = 40;
    public static final int WEATHER_SNAPSHOT_TICKS = 100;
    public static final int COARSE_WIND_SYNC_CELL_SIZE_BLOCKS = 32;
    public static final int COARSE_WIND_SYNC_RADIUS_CELLS = 4; // 9×9

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

    /** 单维度状态。 */
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
        final MountainWaveSystem mountainWaves;
        final MonsoonSystem monsoon;
        final HashedSeedTerrainProvider hashedTerrain;
        McTerrainAdapter terrainAdapter;
        LocalFlowSolver l2;
        MesoscaleGrid.DiagnosticsSummary lastDiagnostics =
                new MesoscaleGrid.DiagnosticsSummary(0, 0, 0, 0.5, 0);
        final List<LocalFlowSolver.HeatSource> heatCache = new ArrayList<>();
        long heatCacheTick = -1;
        double focusX, focusZ;

        DimensionState(long seed) {
            this.driver = new WorldScaleDriver(seed);
            this.l0 = new BackgroundMetGrid();
            this.l1 = new MesoscaleGrid(seed ^ 0x51ab);
            this.thermals = new ThermalSystem();
            this.seaBreeze = new SeaBreezeSystem();
            this.storms = new StormCellSystem();
            this.typhoons = new TyphoonSystem();
            this.fronts = new FrontSystem();
            this.dustDevils = new DustDevilSystem();
            this.squallLines = new SquallLineSystem();
            this.mountainWaves = new MountainWaveSystem();
            this.monsoon = new MonsoonSystem();
            this.hashedTerrain = new HashedSeedTerrainProvider(seed);
        }

        List<WeatherPhenomenon> phenomena() {
            return List.of(thermals, seaBreeze, storms, typhoons, fronts, dustDevils, squallLines,
                    mountainWaves, monsoon);
        }
    }

    private final Map<ResourceKey<Level>, DimensionState> dimensions = new HashMap<>();

    private DimensionState stateFor(ServerLevel level) {
        return dimensions.computeIfAbsent(level.dimension(), k -> {
            DimensionState s = new DimensionState(level.getSeed());
            s.terrainAdapter = new McTerrainAdapter(level, s.hashedTerrain);
            s.l2 = new LocalFlowSolver(
                    (x, y, z) -> {
                        if (y < level.getMinBuildHeight() || y > level.getMaxBuildHeight()) return false;
                        BlockState st = level.getBlockState(new BlockPos(x, y, z));
                        return st.isSolid();
                    },
                    (x, y, z, r) -> heatNear(s, x, y, z, r),
                    (x, y, z, r) -> JetSourceRegistry.jetsNear(x, y, z, r));
            return s;
        });
    }

    // ---------------- tick 编排 ----------------

    @SubscribeEvent
    public void onServerTick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        for (ServerLevel level : server.getAllLevels()) {
            tickDimension(level);
        }
    }

    private void tickDimension(ServerLevel level) {
        DimensionState s = stateFor(level);
        long tick = level.getGameTime();
        double dt = SOLVER_STEP_SECONDS;

        // 焦点：玩家平均位置（无玩家则用出生点）
        updateFocus(level, s);

        double timeOfDay = (level.getDayTime() % 24000L) / 24000.0;
        boolean raining = level.isRaining();
        boolean thundering = level.isThundering();

        // 1) 驱动器推进（含上 tick 的 L1 反馈）
        var d = s.lastDiagnostics;
        var feedback = new WorldScaleDriver.Feedback(
                d.maxInstabilityProxy(), d.meanLowLevelShear(), d.maxPositiveMoistureConvergence());
        s.driver.advance(dt, timeOfDay, raining, thundering, feedback);

        // 2) 天气现象
        WeatherContext ctx = new WeatherContext(level, s.driver, s.l0, s.l1,
                s.terrainAdapter, tick, timeOfDay, raining, thundering,
                SeasonSystem.compute(level.getDayTime()));
        for (WeatherPhenomenon p : s.phenomena()) {
            p.tick(level, dt, ctx);
        }

        // 3) L0 刷新
        if (tick % BACKGROUND_MET_REFRESH_TICKS == 0) {
            s.l0.setFocus(s.focusX, s.focusZ);
            s.l0.refresh(s.driver, s.terrainAdapter, BACKGROUND_MET_REFRESH_TICKS * dt,
                    timeOfDay, raining, SeasonSystem.compute(level.getDayTime()).tempBiasKelvin());
        }
        // 4) L1 刷新
        if (tick % MESOSCALE_REFRESH_TICKS == 0) {
            s.l1.setFocus(s.focusX, s.focusZ);
            s.lastDiagnostics = s.l1.refresh(s.l0, s.terrainAdapter, timeOfDay, raining, tick);
        }

        // 5) 热源缓存刷新
        if (tick - s.heatCacheTick > 200) {
            refreshHeatCache(level, s, tick);
        }

        // 6) 网络广播
        if (tick % COARSE_WIND_SYNC_TICKS == 0) {
            broadcastCoarseWind(level, s);
        }
        if (tick % WEATHER_SNAPSHOT_TICKS == 0) {
            broadcastWeatherSnapshot(level, s);
        }
    }

    private void updateFocus(ServerLevel level, DimensionState s) {
        var players = level.players();
        if (players.isEmpty()) {
            BlockPos spawn = level.getSharedSpawnPos();
            s.focusX = spawn.getX();
            s.focusZ = spawn.getZ();
        } else {
            double sx = 0, sz = 0;
            for (ServerPlayer p : players) {
                sx += p.getX();
                sz += p.getZ();
            }
            s.focusX = sx / players.size();
            s.focusZ = sz / players.size();
        }
    }

    // ---------------- 采样实现 ----------------

    @Override
    public ZenithWindSample sample(ZenithWorldRef world, ZenithVec3 position, SamplePolicy policy) {
        return sampleInternal(world, position.x(), position.y(), position.z(), policy, false);
    }

    @Override
    public ZenithWindSample sample(ZenithWorldRef world, ZenithBlockPos position, SamplePolicy policy) {
        ZenithVec3 c = position.center();
        return sampleInternal(world, c.x(), c.y(), c.z(), policy, false);
    }

    @Override
    public net.atomos.zenith.api.ZenithTerrainSample sample(ZenithWorldRef world, ZenithBlockPos pos) {
        // 地形采样：暂返回未知，待接入实际地形高度查询
        return net.atomos.zenith.api.ZenithTerrainSample.unknown();
    }

    private ZenithWindSample sampleInternal(ZenithWorldRef worldRef, double x, double y, double z,
                                            SamplePolicy policy, boolean fromClient) {
        MinecraftServer server = net.neoforged.neoforge.server.ServerLifecycleHooks.getCurrentServer();
        ZenithWindSample.Builder b = ZenithWindSample.builder()
                .source(ZenithWindSample.Source.NONE)
                .authority(fromClient ? ZenithWindSample.Authority.CLIENT_LOCAL
                        : ZenithWindSample.Authority.SERVER_AUTHORITATIVE);
        if (server == null) return b.build();

        ServerLevel level = null;
        for (ServerLevel l : server.getAllLevels()) {
            if (l.dimension().location().toString().equals(worldRef.dimension().toString())) {
                level = l;
                break;
            }
        }
        if (level == null) return b.build();
        DimensionState s = stateFor(level);

        // L1 基流
        MesoscaleGrid.Sample l1 = s.l1.sample(x, y, z);
        double vx = l1.windX(), vy = l1.windY(), vz = l1.windZ();

        // 天气现象贡献
        double turbAdd = 0, precip = 0;
        for (WeatherPhenomenon p : s.phenomena()) {
            WindContribution c = p.sample(x, y, z, vx, vz);
            if (c.isNone()) continue;
            vx += c.vx();
            vy += c.vy();
            vz += c.vz();
            turbAdd += c.turbulenceAdd();
            precip = Math.max(precip, c.precipIntensity01());
        }

        // 台风眼静风衰减
        double eyeDamp = s.typhoons.eyeDampening(x, z);
        if (eyeDamp > 0) {
            vx *= (1 - eyeDamp);
            vz *= (1 - eyeDamp);
        }

        // L2 本地修正（服务端也计算，用于 gameplay 遮蔽/绕流）
        LocalFlowSolver.LocalFlow l2 = s.l2.solve(x, y, z, vx, vy, vz, x, y, z);

        double turb = l1.turbulenceIntensity() >= 0
                ? Math.min(1.0, l1.turbulenceIntensity() + turbAdd)
                : Math.min(1.0, 0.1 + turbAdd);

        return b.meanVelocity(ZenithVec3.of(vx, vy, vz))
                .gustVelocity(ZenithVec3.of(l1.gustX(), l1.gustY(), l1.gustZ()))
                .localModifier(ZenithVec3.of(l2.modX(), l2.modY(), l2.modZ()))
                .turbulenceIntensity((float) turb)
                .windShearPerBlock((float) l1.shearPerBlock())
                .airTemperatureKelvin((float) l1.airTempK())
                .humidity01((float) l1.humidity01())
                .sheltered(l2.sheltered() || l1.sheltered())
                .source(ZenithWindSample.Source.L1_COARSE)
                .confidence(0.9f)
                .freshnessEpoch(level.getGameTime())
                .build();
    }

    @Override
    public ZenithL2Result runL2(ZenithL2Request request) {
        // L2-lite 诊断：返回零力矩占位（诊断用）
        return ZenithL2Result.of(net.atomos.zenith.api.ZenithL2ForceMoment.zero());
    }

    /**
     * 翼型极线生成器（薄翼型理论 + 失速模型，原版用 LBM 数值计算）。
     * cl = 2π(α−α0)·AR修正；失速后按平板模型衰减；cd = cd0 + k·cl²。
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
                    // 失速：按 sin(2α) 平板模型混合衰减
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

    @Override
    public ZenithTerrainSample sampleTerrain(ZenithWorldRef world, ZenithBlockPos pos) {
        MinecraftServer server = net.neoforged.neoforge.server.ServerLifecycleHooks.getCurrentServer();
        if (server == null) return ZenithTerrainSample.unknown();
        for (ServerLevel l : server.getAllLevels()) {
            if (l.dimension().location().toString().equals(world.dimension().toString())) {
                DimensionState s = stateFor(l);
                double h = s.terrainAdapter.terrainHeightBlocks(pos.x(), pos.z());
                return ZenithTerrainSample.of((int) h,
                        (float) s.terrainAdapter.roughness01(pos.x(), pos.z()),
                        s.terrainAdapter.surfaceAt(pos.x(), pos.z()));
            }
        }
        return ZenithTerrainSample.unknown();
    }

    // ---------------- 热源缓存 ----------------

    private List<LocalFlowSolver.HeatSource> heatNear(DimensionState s, double x, double y, double z,
                                                      double radius) {
        List<LocalFlowSolver.HeatSource> out = new ArrayList<>();
        double r2 = radius * radius;
        for (LocalFlowSolver.HeatSource h : s.heatCache) {
            double dx = h.x() - x, dy = h.y() - y, dz = h.z() - z;
            if (dx * dx + dy * dy + dz * dz <= r2) out.add(h);
        }
        return out;
    }

    private void refreshHeatCache(ServerLevel level, DimensionState s, long tick) {
        s.heatCache.clear();
        s.heatCacheTick = tick;
        int cx = (int) s.focusX, cz = (int) s.focusZ;
        int cy = level.getSeaLevel();
        int R = 48;
        for (int dx = -R; dx <= R; dx += 2)
            for (int dz = -R; dz <= R; dz += 2)
                for (int dy = -16; dy <= 24; dy += 2) {
                    BlockPos p = new BlockPos(cx + dx, cy + dy, cz + dz);
                    BlockState st = level.getBlockState(p);
                    double power = heatPowerOf(st);
                    if (power > 0) {
                        s.heatCache.add(new LocalFlowSolver.HeatSource(
                                p.getX() + 0.5, p.getY() + 0.5, p.getZ() + 0.5, power, 6));
                    }
                    if (s.heatCache.size() > 256) return;
                }
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

    // ---------------- 网络广播 ----------------

    private void broadcastCoarseWind(ServerLevel level, DimensionState s) {
        for (ServerPlayer player : level.players()) {
            double px = player.getX(), py = player.getY(), pz = player.getZ();
            int n = COARSE_WIND_SYNC_RADIUS_CELLS * 2 + 1;
            float[] vx = new float[n * n];
            float[] vy = new float[n * n];
            float[] vz = new float[n * n];
            for (int i = 0; i < n; i++)
                for (int j = 0; j < n; j++) {
                    double bx = px + (i - COARSE_WIND_SYNC_RADIUS_CELLS) * COARSE_WIND_SYNC_CELL_SIZE_BLOCKS;
                    double bz = pz + (j - COARSE_WIND_SYNC_RADIUS_CELLS) * COARSE_WIND_SYNC_CELL_SIZE_BLOCKS;
                    MesoscaleGrid.Sample smp = s.l1.sample(bx, py, bz);
                    int k = j * n + i;
                    vx[k] = (float) smp.windX();
                    vy[k] = (float) smp.windY();
                    vz[k] = (float) smp.windZ();
                }
            PacketDistributor.sendToPlayer(player,
                    new CoarseWindPacket(px, py, pz, COARSE_WIND_SYNC_CELL_SIZE_BLOCKS,
                            COARSE_WIND_SYNC_RADIUS_CELLS, vx, vy, vz, level.getGameTime()));
        }
    }

    private void broadcastWeatherSnapshot(ServerLevel level, DimensionState s) {
        var typhoons = s.typhoons.typhoons();
        var storms = s.storms.cells();
        // 湿度：取焦点处 L1 采样
        var l1sample = s.l1.sample(s.focusX, 80, s.focusZ);
        WeatherSnapshotPacket pkt = WeatherSnapshotPacket.build(
                s.driver.stormActivity(), s.driver.activeTornadoCount(),
                s.thermals.activeCount(), s.seaBreeze.frontInlandDistance(),
                l1sample.humidity01(),
                typhoons, storms,
                s.fronts.fronts(), s.dustDevils.devils(), s.squallLines.lines());
        for (ServerPlayer player : level.players()) {
            PacketDistributor.sendToPlayer(player, pkt);
        }
    }

    // ---------------- ZenithWeatherRuntimeProvider ----------------

    private DimensionState stateFor(net.atomos.zenith.api.ZenithWorldRef worldRef) {
        MinecraftServer server = net.neoforged.neoforge.server.ServerLifecycleHooks.getCurrentServer();
        if (server == null) return null;
        for (ServerLevel l : server.getAllLevels()) {
            if (l.dimension().location().toString().equals(worldRef.dimension().toString())) {
                return stateFor(l);
            }
        }
        return null;
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

    // ---------------- 地形适配器 ----------------

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
            Biome biome = c.getNoiseBiome(x >> 2, 64 >> 2, z >> 2).value();
            float r = 0.3f;
            String name = biome.toString();
            if (name.contains("forest") || name.contains("jungle")) r = 0.75f;
            else if (name.contains("desert") || name.contains("beach")) r = 0.15f;
            else if (name.contains("ocean") || name.contains("river")) r = 0.03f;
            else if (name.contains("mountain") || name.contains("peak")) r = 0.85f;
            return r;
        }

        @Override
        public double biomeTemperatureKelvin(double bx, double bz) {
            ChunkAccess c = chunkIfLoaded(bx, bz);
            float t;
            if (c != null) {
                int x = (int) Math.floor(bx), z = (int) Math.floor(bz);
                t = c.getNoiseBiome(x >> 2, 64 >> 2, z >> 2).value().getBaseTemperature();
            } else {
                t = 0.7f;
            }
            return 273.15 + t * 30.0; // 粗略映射到开尔文
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
                int h = c.getHeight(Heightmap.Types.WORLD_SURFACE, x & 15, z & 15);
                BlockPos p = new BlockPos(x, h, z);
                BlockState st = c.getBlockState(p);
                return st.getFluidState().is(Fluids.WATER);
            }
            return fallback.isOceanAt(bx, bz);
        }

        @Override
        public ZenithTerrainSurfaceClass surfaceAt(double bx, double bz) {
            ChunkAccess c = chunkIfLoaded(bx, bz);
            if (c == null) return fallback.surfaceAt(bx, bz);
            int x = (int) Math.floor(bx), z = (int) Math.floor(bz);
            int h = c.getHeight(Heightmap.Types.WORLD_SURFACE, x & 15, z & 15);
            BlockState st = c.getBlockState(new BlockPos(x, h, z));
            if (st.getFluidState().is(Fluids.WATER)) return ZenithTerrainSurfaceClass.OCEAN;
            String name = st.getBlock().toString().toLowerCase();
            if (name.contains("sand")) return ZenithTerrainSurfaceClass.DESERT_SAND;
            if (name.contains("snow") || name.contains("ice")) return ZenithTerrainSurfaceClass.SNOW;
            if (name.contains("stone") || name.contains("rock") || name.contains("deepslate"))
                return ZenithTerrainSurfaceClass.ROCK;
            if (name.contains("leaves") || name.contains("log"))
                return ZenithTerrainSurfaceClass.FOREST;
            return ZenithTerrainSurfaceClass.GRASS;
        }
    }

    // ---------------- 诊断 ----------------

    public DimensionState debugState(ResourceKey<Level> dim) {
        return dimensions.get(dim);
    }
}
