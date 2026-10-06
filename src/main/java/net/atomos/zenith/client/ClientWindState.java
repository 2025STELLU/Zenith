package net.atomos.zenith.client;

import net.atomos.zenith.api.SamplePolicy;
import net.atomos.zenith.api.ZenithVec3;
import net.atomos.zenith.api.ZenithWindSample;
import net.atomos.zenith.api.ZenithWorldRef;
import net.atomos.zenith.api.client.ZenithClientWindRuntimeProvider;
import net.atomos.zenith.network.packet.CoarseWindPacket;
import net.atomos.zenith.network.packet.WeatherSnapshotPacket;
import net.minecraft.client.Minecraft;

import java.util.List;

/**
 * 客户端风场状态：承接服务端粗风场包 + 天气快照包，
 * 为客户端采样 API 与可视化提供数据。
 */
public final class ClientWindState implements ZenithClientWindRuntimeProvider {
    private static final ClientWindState INSTANCE = new ClientWindState();

    public static ClientWindState get() {
        return INSTANCE;
    }

    private volatile CoarseWindPacket coarseWind;
    private volatile WeatherSnapshotPacket snapshot;

    private ClientWindState() {}

    public static void onCoarseWind(CoarseWindPacket pkt) {
        INSTANCE.coarseWind = pkt;
    }

    public static void onWeatherSnapshot(WeatherSnapshotPacket pkt) {
        INSTANCE.snapshot = pkt;
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

    private static double clamp01(double v) { return v < 0 ? 0 : Math.min(v, 1); }
    private static int clamp(int v, int lo, int hi) { return v < lo ? lo : Math.min(v, hi); }
}
