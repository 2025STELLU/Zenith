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

    private ClientWindState() {}

    public static void onCoarseWind(CoarseWindPacket pkt) {
        INSTANCE.coarseWind = pkt;
    }

    public static void onWeatherSnapshot(WeatherSnapshotPacket pkt) {
        INSTANCE.snapshot = pkt;
    }

    /** 旧服数据清零：断线、换服、单人退存档都走这里，快照下个广播周期会重收。 */
    public static void clear() {
        INSTANCE.coarseWind = null;
        INSTANCE.snapshot = null;
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

    public ZenithVec3 sampleCoarse(double x, double y, double z) {
        CoarseWindPacket pkt = coarseWind;
        if (pkt == null) return ZenithVec3.ZERO;
        int n = pkt.gridSize();
        double fi = (x - pkt.centerX()) / pkt.cellSizeBlocks() + pkt.radiusCells();
        double fj = (z - pkt.centerZ()) / pkt.cellSizeBlocks() + pkt.radiusCells();
        int i0 = clamp((int) Math.floor(fi), 0, n - 2);
        int j0 = clamp((int) Math.floor(fj), 0, n - 2);
        double fx = clamp01(fi - i0), fz = clamp01(fj - j0);
        float[] vx = pkt.vx(), vy = pkt.vy(), vz = pkt.vz();
        double ax = bilerp(vx, n, i0, j0, fx, fz);
        double ay = bilerp(vy, n, i0, j0, fx, fz);
        double az = bilerp(vz, n, i0, j0, fx, fz);
        return ZenithVec3.of(ax, ay, az);
    }

    private static double bilerp(float[] g, int n, int i, int j, double fx, double fz) {
        double a = g[j * n + i], b = g[j * n + i + 1];
        double c = g[(j + 1) * n + i], d = g[(j + 1) * n + i + 1];
        double ab = a + (b - a) * fx, cd = c + (d - c) * fx;
        return ab + (cd - ab) * fz;
    }

    @Override
    public ZenithWindSample sample(ZenithWorldRef world, ZenithVec3 position, SamplePolicy policy) {
        ZenithVec3 v = sampleCoarse(position.x(), position.y(), position.z());
        // 客户端本地细节：叠加小尺度湍流抖动（纯视觉）
        long t = Minecraft.getInstance().level == null ? 0
                : Minecraft.getInstance().level.getGameTime();
        double jitter = 0;
        if (policy.allowClientLocal()) {
            double n = Math.sin(position.x() * 0.35 + t * 0.21)
                    + Math.sin(position.z() * 0.31 - t * 0.17);
            jitter = n * 0.25 * Math.min(3.0, v.length() * 0.3 + 0.4);
        }
        return ZenithWindSample.builder()
                .meanVelocity(v)
                .gustVelocity(ZenithVec3.of(jitter, jitter * 0.3, -jitter * 0.7))
                .turbulenceIntensity(0.25f)
                .source(ZenithWindSample.Source.L1_COARSE)
                .authority(ZenithWindSample.Authority.CLIENT_LOCAL)
                .confidence(0.6f)
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
    private static int clamp(int v, int lo, int hi) { return v < lo ? lo : Math.min(v, hi); }
}
