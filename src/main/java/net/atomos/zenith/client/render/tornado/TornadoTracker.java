package net.atomos.zenith.client.render.tornado;

import net.atomos.zenith.client.ClientWindState;
import net.atomos.zenith.network.packet.WeatherSnapshotPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.ArrayList;
import java.util.List;

/**
 * 客户端龙卷跟踪：把每 5 秒一次的快照变成连续运动。
 *
 * <p>旧渲染器直接画快照里的位置：龙卷每 5 秒"瞬移"一次，强度包络也是阶梯状；
 * 服务端删掉龙卷时漏斗当帧消失。这里做三件事：
 * <ol>
 *   <li><b>配对</b>：新快照的每个龙卷按"预测位置"最近邻配对到已跟踪的龙卷（快照不带 id）；</li>
 *   <li><b>航位推算</b>：用相邻两次快照估速度，每 tick 外推 + 指数追随，渲染时再按 partialTick 插值；</li>
 *   <li><b>生死渐变</b>：新出现的淡入，快照里消失的淡出（≈3 秒），同时判断"形成中 / 消散中"，
 *       渲染器据此切换漏斗形态（消散期变成弯曲的绳状漏斗）。</li>
 * </ol>
 */
public final class TornadoTracker {
    private TornadoTracker() {}

    /** 配对半径：两次快照之间龙卷最多走这么远（55% 中层风 × 5 s ≈ 50–80 格）。 */
    private static final double MATCH_RADIUS = 128.0;

    public static final class Tracked {
        public final int seedInt;
        public final float seed;
        // 目标（最近一次快照）
        double tx, tz;
        double vx, vz;               // 格/秒
        float targetIntensity;
        float lastTargetIntensity;
        public float maxWind, coreRadius, spin;
        // 显示状态（每 tick 更新，渲染按 partialTick 插值）
        double x, z, px, pz;
        double groundY, pGroundY;
        float intensity, pIntensity;
        /** 消散程度 [0,1]：1 = 完全进入绳状期。 */
        float rope, pRope;
        boolean dying;
        boolean matched;
        // 闪电
        int flashTicks, pFlashTicks;
        int flashCooldown;

        Tracked(int seedInt) {
            this.seedInt = seedInt;
            this.seed = TornadoShape.hash01(seedInt, 77) * 6.2831f;
            this.flashCooldown = 60 + (seedInt & 63);
        }

        public double x(float pt) { return Mth.lerp(pt, px, x); }
        public double z(float pt) { return Mth.lerp(pt, pz, z); }
        public double groundY(float pt) { return Mth.lerp(pt, pGroundY, groundY); }
        public float intensity(float pt) { return Mth.lerp(pt, pIntensity, intensity); }
        public float rope(float pt) { return Mth.lerp(pt, pRope, rope); }
        public double vx() { return vx; }
        public double vz() { return vz; }
        /** 闪电亮度 [0,1]，双脉冲。 */
        public float flash(float pt) {
            float f = Mth.lerp(pt, pFlashTicks, flashTicks);
            if (f <= 0) return 0;
            // 6 tick：亮-暗-亮-渐灭
            float k = 6f - f;
            if (k < 1f) return k;
            if (k < 2f) return 0.35f;
            if (k < 3f) return 1f;
            return Math.max(0f, 1f - (k - 3f) / 3f);
        }
        void startFlash() { flashTicks = pFlashTicks = 6; }
        public boolean flashJustStarted() { return flashTicks == 6 && pFlashTicks == 6; }
    }

    private static final List<Tracked> TRACKED = new ArrayList<>();
    private static WeatherSnapshotPacket lastSnap;
    private static ClientLevel lastLevel;
    private static long ticks;
    private static long lastSnapTick = -1;
    private static int seedCounter = 1;

    /** 渲染线程读、客户端 tick 写，二者都在主线程，不用同步。 */
    public static List<Tracked> tornadoes() {
        return TRACKED;
    }

    public static void clear() {
        TRACKED.clear();
        lastSnap = null;
        lastSnapTick = -1;
    }

    public static void clientTick() {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null) {
            if (lastLevel != null) clear();
            lastLevel = null;
            return;
        }
        if (level != lastLevel) {
            clear();
            lastLevel = level;
        }
        if (mc.isPaused()) return;
        ticks++;

        WeatherSnapshotPacket snap = ClientWindState.get().snapshot();
        if (snap != lastSnap) {
            ingest(snap == null ? List.of() : snap.tornadoes());
            lastSnap = snap;
        }

        double sinceSnap = lastSnapTick < 0 ? 0 : (ticks - lastSnapTick) / 20.0;
        // 外推最多 2 个快照周期，再久说明快照断了，停在原地
        sinceSnap = Math.min(sinceSnap, 10.0);

        for (int i = TRACKED.size() - 1; i >= 0; i--) {
            Tracked t = TRACKED.get(i);
            t.px = t.x; t.pz = t.z; t.pGroundY = t.groundY;
            t.pIntensity = t.intensity; t.pRope = t.rope; t.pFlashTicks = t.flashTicks;

            double predX = t.tx + t.vx * sinceSnap;
            double predZ = t.tz + t.vz * sinceSnap;
            t.x += (predX - t.x) * 0.15;
            t.z += (predZ - t.z) * 0.15;

            double h = level.getHeight(Heightmap.Types.MOTION_BLOCKING, Mth.floor(t.x), Mth.floor(t.z));
            if (h <= level.getMinBuildHeight() + 1) h = t.groundY; // 区块没加载：保持
            t.groundY += (h - t.groundY) * 0.12;

            float target = t.dying ? 0f : t.targetIntensity;
            // 淡入快、淡出慢一点（τ ≈ 1 s / 1.5 s）
            float k = target > t.intensity ? 0.05f : 0.033f;
            t.intensity += (target - t.intensity) * k;

            boolean decaying = t.dying || t.targetIntensity < t.lastTargetIntensity - 0.005f;
            float ropeTarget = decaying ? TornadoShape.smoothstep(0.85f, 0.25f, t.intensity) : 0f;
            t.rope += (ropeTarget - t.rope) * 0.03f;

            if (t.flashTicks > 0) t.flashTicks--;
            if (--t.flashCooldown <= 0) {
                if (t.intensity > 0.35f) t.startFlash();
                // 4–12 秒一次，越强越频繁
                float r = TornadoShape.hash01(t.seedInt, (int) ticks);
                t.flashCooldown = (int) ((80 + 160 * r) / (0.6f + t.intensity));
            }

            if (t.dying && t.intensity < 0.01f) TRACKED.remove(i);
        }
    }

    private static void ingest(List<WeatherSnapshotPacket.TornadoInfo> infos) {
        double dt = lastSnapTick < 0 ? 0 : (ticks - lastSnapTick) / 20.0;
        lastSnapTick = ticks;

        for (Tracked t : TRACKED) t.matched = false;

        for (WeatherSnapshotPacket.TornadoInfo info : infos) {
            Tracked best = null;
            double bestD2 = MATCH_RADIUS * MATCH_RADIUS;
            for (Tracked t : TRACKED) {
                if (t.matched || t.dying) continue;
                double ex = t.tx + t.vx * dt, ez = t.tz + t.vz * dt;
                double d2 = (ex - info.x()) * (ex - info.x()) + (ez - info.z()) * (ez - info.z());
                if (d2 < bestD2) { bestD2 = d2; best = t; }
            }
            if (best == null) {
                best = new Tracked(seedCounter++);
                best.x = best.px = best.tx = info.x();
                best.z = best.pz = best.tz = info.z();
                best.groundY = best.pGroundY = 64;
                Minecraft mc = Minecraft.getInstance();
                if (mc.level != null) {
                    double h = mc.level.getHeight(Heightmap.Types.MOTION_BLOCKING,
                            Mth.floor(info.x()), Mth.floor(info.z()));
                    if (h > mc.level.getMinBuildHeight() + 1) best.groundY = best.pGroundY = h;
                }
                TRACKED.add(best);
            } else if (dt > 0.5) {
                double mvx = (info.x() - best.tx) / dt, mvz = (info.z() - best.tz) / dt;
                // 速度低通，免得一次异常快照把漏斗甩飞
                best.vx += (mvx - best.vx) * 0.6;
                best.vz += (mvz - best.vz) * 0.6;
                double sp = Math.hypot(best.vx, best.vz);
                if (sp > 40) { best.vx *= 40 / sp; best.vz *= 40 / sp; }
            }
            best.matched = true;
            best.tx = info.x();
            best.tz = info.z();
            best.lastTargetIntensity = best.targetIntensity;
            best.targetIntensity = Mth.clamp(info.intensity01(), 0f, 1f);
            best.maxWind = info.maxWindMps();
            best.coreRadius = Math.max(4f, info.coreRadiusBlocks());
            best.spin = info.spin() >= 0 ? 1f : -1f;
        }
        for (Tracked t : TRACKED) {
            if (!t.matched) t.dying = true;
        }
    }
}
