package net.atomos.zenith.client;

import net.atomos.zenith.api.SamplePolicy;
import net.atomos.zenith.api.ZenithVec3;
import net.atomos.zenith.api.ZenithWindSample;
import net.atomos.zenith.api.ZenithWorldRef;
import net.atomos.zenith.api.client.ZenithClientWindRuntimeProvider;
import net.atomos.zenith.network.packet.CoarseWindPacket;
import net.atomos.zenith.network.packet.WeatherSnapshotPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;

import java.util.List;

/**
 * 客户端风场状态：承接服务端粗风场包 + 天气快照包，
 * 为客户端采样 API 与可视化提供数据。
 *
 * 登出 / 换维度时清空：旧服务器的台风、龙卷不能画到新世界里。
 */
@EventBusSubscriber(value = Dist.CLIENT)
public final class ClientWindState implements ZenithClientWindRuntimeProvider {
    private static final ClientWindState INSTANCE = new ClientWindState();

    public static ClientWindState get() {
        return INSTANCE;
    }

    private volatile CoarseWindPacket coarseWind;
    private volatile WeatherSnapshotPacket snapshot;
    /** 上次看到的维度：只在维度真的变了时才清空，死亡同维度重生不清 */
    private volatile ResourceKey<Level> lastDimension;
    /** 当前粗风场帧是否带非零现象层（API-11 的 layer 标记用）。 */
    private volatile boolean hasPhenomena;

    private ClientWindState() {}

    public static void onCoarseWind(CoarseWindPacket pkt) {
        // 维度不匹配的包直接丢（NET-03）：旧实现靠 LoggingIn/Clone 猜维度，
        // 两个存档都有 minecraft:overworld 时会误用旧包。
        ResourceKey<Level> dim = Minecraft.getInstance().level == null
                ? null : Minecraft.getInstance().level.dimension();
        if (dim != null && !pkt.dimension().equals(dim.location().toString())) {
            return;
        }
        CoarseWindPacket prev = INSTANCE.coarseWind;
        int gridCells = pkt.gridSize() * pkt.gridSize();
        boolean prevUsable = prev != null
                && prev.cellSizeBlocks() == pkt.cellSizeBlocks()
                && prev.radiusCells() == pkt.radiusCells()
                && Math.abs(prev.centerX() - pkt.centerX()) < 1e-6
                && Math.abs(prev.centerZ() - pkt.centerZ()) < 1e-6
                && prev.vx().length == gridCells;
        if (pkt.isFullFrame() || !prevUsable) {
            // 全量帧，或上一帧的网格对不上（换维度/走远了/基准本身是增量帧）：
            // 增量帧没有基准就没法合并，直接丢弃等下一帧全量（RT-04）
            if (pkt.isFullFrame()) {
                INSTANCE.coarseWind = pkt;
                INSTANCE.hasPhenomena = hasNonZero(pkt.phx(), pkt.phy(), pkt.phz());
            }
            return;
        }
        // 增量帧（RT-04）：只覆盖 changed 里那几个索引，其余沿用上一帧
        CoarseWindPacket merged = merge(prev, pkt);
        INSTANCE.coarseWind = merged;
        INSTANCE.hasPhenomena = hasNonZero(merged.phx(), merged.phy(), merged.phz());
    }

    private static boolean hasNonZero(float[] a, float[] b, float[] c) {
        for (int i = 0; i < a.length; i++) {
            if (a[i] != 0 || b[i] != 0 || c[i] != 0) return true;
        }
        return false;
    }

    /** 把增量帧合并进上一帧的全量网格：位置/间距不变，值按索引逐格替换。 */
    private static CoarseWindPacket merge(CoarseWindPacket base, CoarseWindPacket delta) {
        int n = base.vx().length;
        float[] vx = base.vx().clone(), vy = base.vy().clone(), vz = base.vz().clone();
        float[] phx = base.phx().clone(), phy = base.phy().clone(), phz = base.phz().clone();
        int[] idx = delta.changed();
        for (int k = 0; k < idx.length; k++) {
            int at = idx[k];
            if (at < 0 || at >= n) continue; // 已由包的构造校验，这里再兜一层
            vx[at] = delta.vx()[k];
            vy[at] = delta.vy()[k];
            vz[at] = delta.vz()[k];
            phx[at] = delta.phx()[k];
            phy[at] = delta.phy()[k];
            phz[at] = delta.phz()[k];
        }
        return new CoarseWindPacket(delta.dimension(), delta.centerX(), delta.centerY(), delta.centerZ(),
                delta.cellSizeBlocks(), delta.radiusCells(), new int[0],
                vx, vy, vz, phx, phy, phz, delta.tick());
    }

    public static void onWeatherSnapshot(WeatherSnapshotPacket pkt) {
        // 维度过滤（NET-03）：气象图/特效只认当前维度的快照。
        // 旧包不带维度，换维度后 <2 s 内客户端会拿着上一维度的台风位置画图。
        ResourceKey<Level> dim = Minecraft.getInstance().level == null
                ? null : Minecraft.getInstance().level.dimension();
        if (dim != null && !pkt.dimension().equals(dim.location().toString())) {
            return;
        }
        INSTANCE.snapshot = pkt;
    }

    /** 旧服数据清零：断线、换服、单人退存档都走这里，快照下个广播周期会重收。 */
    public static void clear() {
        INSTANCE.coarseWind = null;
        INSTANCE.snapshot = null;
        INSTANCE.hasPhenomena = false;
    }

    @SubscribeEvent
    public static void onLoggingIn(ClientPlayerNetworkEvent.LoggingIn event) {
        // 进服先记下维度：不然第一次死亡重生时 lastDimension 还是 null，
        // 必判"维度变化"误清一次风场
        INSTANCE.lastDimension = event.getPlayer().level().dimension();
    }

    @SubscribeEvent
    public static void onLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        clear();
        INSTANCE.lastDimension = null; // 下次进服当新维度处理
    }

    @SubscribeEvent
    public static void onPlayerClone(ClientPlayerNetworkEvent.Clone event) {
        // 玩家重建（死亡重生 / 换维度都走这里）：维度没变就不清，
        // 免得死一次风场数据断 2 秒
        ResourceKey<Level> dim = event.getNewPlayer().level().dimension();
        if (!dim.equals(INSTANCE.lastDimension)) {
            clear();
        }
        INSTANCE.lastDimension = dim;
    }

    public CoarseWindPacket coarseWind() {
        return coarseWind;
    }

    public WeatherSnapshotPacket snapshot() {
        return snapshot;
    }

    /**
     * 粗网格双线性采样（L1 基流）。
     *
     * <p>域外不再把边缘格的值硬夹出去（NET-01 / SYS-05）：旧实现让气象图 ±1200 格
     * 全用"最近边缘格"的外推值，画出一片假的均匀场。现在出界 0..256 格内线性淡到 0，
     * 边界外就是"没有数据"，而不是"边缘那一格的风复制到天边"。
     */
    public ZenithVec3 sampleCoarse(double x, double y, double z) {
        CoarseWindPacket pkt = coarseWind;
        if (pkt == null) return ZenithVec3.ZERO;
        return sampleGrid(pkt.vx(), pkt.vy(), pkt.vz(), x, z, pkt);
    }

    /**
     * 现象层合成矢量（台风/飑线/尘卷风/热泡，NET-01）。
     *
     * <p>与 {@link #sampleCoarse} 同一张网格、同一套插值，所以两者相加后
     * 与服务端 {@code sampleInternal} 的空间分布一致（区别只在于服务端还有 L2）。
     */
    public ZenithVec3 samplePhenomena(double x, double y, double z) {
        CoarseWindPacket pkt = coarseWind;
        if (pkt == null) return ZenithVec3.ZERO;
        return sampleGrid(pkt.phx(), pkt.phy(), pkt.phz(), x, z, pkt);
    }

    private static ZenithVec3 sampleGrid(float[] gx, float[] gy, float[] gz,
                                         double x, double z, CoarseWindPacket pkt) {
        int n = pkt.gridSize();
        // 非法包（radiusCells 越界会让 n<2）：直接返回零，别在客户端 tick 线程抛 AIOOBE（NET-07）
        if (n < 2 || gx.length < n * n) return ZenithVec3.ZERO;
        int cell = Math.max(1, pkt.cellSizeBlocks());
        double fiRaw = (x - pkt.centerX()) / cell + pkt.radiusCells();
        double fjRaw = (z - pkt.centerZ()) / cell + pkt.radiusCells();
        double fi = clamp(fiRaw, 0.0, (double) (n - 1));
        double fj = clamp(fjRaw, 0.0, (double) (n - 1));
        int i0 = clamp((int) Math.floor(fi), 0, n - 2);
        int j0 = clamp((int) Math.floor(fj), 0, n - 2);
        double fx = clamp01(fi - i0), fz = clamp01(fj - j0);
        double ax = bilerp(gx, n, i0, j0, fx, fz);
        double ay = bilerp(gy, n, i0, j0, fx, fz);
        double az = bilerp(gz, n, i0, j0, fx, fz);

        // 域外淡出：出界 0..OUTSIDE_FADE_CELLS 个格距内线性收敛到 0
        double outsideCells = Math.max(Math.max(0, -fiRaw), Math.max(0, fiRaw - (n - 1)));
        double outsideZCells = Math.max(Math.max(0, -fjRaw), Math.max(0, fjRaw - (n - 1)));
        double outside = Math.max(outsideCells, outsideZCells);
        if (outside > 0) {
            double keep = clamp01(1.0 - outside / OUTSIDE_FADE_CELLS);
            ax *= keep;
            ay *= keep;
            az *= keep;
        }
        return ZenithVec3.of(ax, ay, az);
    }

    /** 域外淡出的过渡宽度（单位：格距）。256 格 ≈ 4 个 L1 网格。 */
    private static final double OUTSIDE_FADE_CELLS = 4.0;

    private static double bilerp(float[] g, int n, int i, int j, double fx, double fz) {
        double a = g[j * n + i], b = g[j * n + i + 1];
        double c = g[(j + 1) * n + i], d = g[(j + 1) * n + i + 1];
        double ab = a + (b - a) * fx, cd = c + (d - c) * fx;
        return ab + (cd - ab) * fz;
    }

    @Override
    public ZenithWindSample sample(ZenithWorldRef world, ZenithVec3 position, SamplePolicy policy) {
        ZenithVec3 base = sampleCoarse(position.x(), position.y(), position.z());
        ZenithVec3 phenomena = samplePhenomena(position.x(), position.y(), position.z());
        ZenithVec3 v = base.add(phenomena);
        // 客户端本地细节：叠加小尺度湍流抖动（纯视觉）
        long t = Minecraft.getInstance().level == null ? 0
                : Minecraft.getInstance().level.getGameTime();
        double jitter = 0;
        if (policy.allowClientLocal()) {
            double n = Math.sin(position.x() * 0.35 + t * 0.21)
                    + Math.sin(position.z() * 0.31 - t * 0.17);
            jitter = n * 0.25 * Math.min(3.0, v.length() * 0.3 + 0.4);
        }
        // 没有数据时**不能**谎报"有风、只是 0 m/s"（NET-03 / API-11）：
        // 旧实现永远 source(L1_COARSE) + confidence(0.6)，跨维度传送后最多 2 s
        // 客户端拿到的是"L1 的零风"而不是"没数据"，UI/风声无法区分。
        CoarseWindPacket cw = coarseWind;
        boolean hasData = cw != null;
        // 层位标记（API-11）：客户端只有背景/中尺度网格 + 现象层，没有 L2。
        int layers = hasData
                ? (ZenithWindSample.LAYER_L1
                   | (INSTANCE.hasPhenomena ? ZenithWindSample.LAYER_PHENOMENA : 0))
                : 0;
        return ZenithWindSample.builder()
                .meanVelocity(v)
                .gustVelocity(ZenithVec3.of(jitter, jitter * 0.3, -jitter * 0.7))
                .turbulenceIntensity(hasData ? 0.25f : -1f)
                .source(hasData ? ZenithWindSample.Source.L1_COARSE : ZenithWindSample.Source.NONE)
                .layers(layers)
                .authority(ZenithWindSample.Authority.CLIENT_LOCAL)
                // 现象层是解析式重建、不是逐点采样，置信度比纯 L1 略低
                .confidence(hasData ? 0.6f : 0f)
                .freshnessEpoch(t)
                .build();
    }

    public List<WeatherSnapshotPacket.TyphoonInfo> typhoons() {
        WeatherSnapshotPacket s = snapshot;
        return s == null ? List.of() : s.typhoons();
    }

    public List<WeatherSnapshotPacket.StormInfo> storms() {
        WeatherSnapshotPacket s = snapshot;
        return s == null ? List.of() : s.storms();
    }

    public List<WeatherSnapshotPacket.TornadoInfo> tornadoes() {
        WeatherSnapshotPacket s = snapshot;
        return s == null ? List.of() : s.tornadoes();
    }

    private static double clamp01(double v) { return v < 0 ? 0 : Math.min(v, 1); }
    private static double clamp(double v, double lo, double hi) { return v < lo ? lo : Math.min(v, hi); }
    private static int clamp(int v, int lo, int hi) { return v < lo ? lo : Math.min(v, hi); }
}
