package net.atomos.zenith.client.render.tornado;

import net.atomos.zenith.ZenithConfig;
import net.atomos.zenith.sound.ZenithSounds;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

/**
 * 龙卷的"在场感"：真实方块碎屑、低频轰鸣、云底闪电的雷声。全部只在客户端。
 */
public final class TornadoAmbience {
    private TornadoAmbience() {}

    private static final RandomSource RANDOM = RandomSource.create();
    private static RoarSound roar;

    public static void clientTick() {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null || mc.player == null || mc.isPaused()) return;
        if (ZenithConfig.tornadoQuality() == ZenithConfig.TornadoQuality.OFF) return;

        Vec3 cam = mc.gameRenderer.getMainCamera().getPosition();
        TornadoTracker.Tracked nearest = null;
        double nearestD = Double.MAX_VALUE;

        for (TornadoTracker.Tracked t : TornadoTracker.tornadoes()) {
            double dx = t.x - cam.x, dz = t.z - cam.z;
            double d = Math.sqrt(dx * dx + dz * dz);
            if (d < nearestD) { nearestD = d; nearest = t; }

            float strength = TornadoShape.strength(t.intensity, t.maxWind);
            float debris = TornadoShape.groundDebris(strength) * (1f - 0.6f * t.rope);

            if (ZenithConfig.tornadoDebrisParticles() && debris > 0.2f && d < 112) {
                spawnDebris(mc, level, t, debris);
            }
            // 云底闪电：雷声按距离自动延迟（原版 distanceDelay：每 40 格延迟 1 秒，近似声速）
            if (ZenithConfig.tornadoSounds() && t.flashJustStarted() && d < 600) {
                double y = t.groundY + 90;
                // 线性衰减的可闻半径 = max(vol,1)*16 格；按目标增益 g 反推 vol，远处的雷才听得到
                double g = Mth.clamp(1.0 - d / 700.0, 0.25, 1.0);
                float vol = (float) Math.max(1.0, d / (16.0 * (1.0 - g) + 1e-3));
                level.playLocalSound(t.x, y, t.z, SoundEvents.LIGHTNING_BOLT_THUNDER, SoundSource.WEATHER,
                        vol, 0.75f + RANDOM.nextFloat() * 0.25f, true);
            }
        }

        // 只在最近的龙卷确实有强度时才建轰鸣声，避免刚生成（强度≈0）时每 tick 新建又立刻停掉
        if (ZenithConfig.tornadoSounds() && nearest != null && nearestD < 320
                && TornadoShape.strength(nearest.intensity, nearest.maxWind) > 0.05f) {
            if (roar == null || roar.isStopped()) {
                roar = new RoarSound();
                mc.getSoundManager().play(roar);
            }
        }
    }

    /**
     * 卷起地表真实方块的碎屑粒子：绕轴高速切向 + 上抛，原版重力会把它们甩出去再落下。
     * 原版 {@code Particle} 构造时会把速度归一化成很小的值，所以创建后要用 setParticleSpeed 改回来。
     */
    private static void spawnDebris(Minecraft mc, ClientLevel level, TornadoTracker.Tracked t, float debris) {
        int n = Math.round((1f + 3f * debris) * (float) ZenithConfig.particleDensity());
        float coreR = Math.max(3.5f, t.coreRadius * 0.85f);
        for (int i = 0; i < n; i++) {
            double ang = RANDOM.nextDouble() * Math.PI * 2;
            double r = coreR * (0.4 + 1.8 * RANDOM.nextDouble());
            double x = t.x + Math.cos(ang) * r, z = t.z + Math.sin(ang) * r;
            int sy = level.getHeight(Heightmap.Types.MOTION_BLOCKING, Mth.floor(x), Mth.floor(z));
            BlockPos bp = BlockPos.containing(x, sy - 1, z);
            BlockState st = level.getBlockState(bp);
            if (st.isAir() || st.getRenderShape() == net.minecraft.world.level.block.RenderShape.INVISIBLE) continue;
            if (!st.getFluidState().isEmpty()) continue; // 水面上交给水沫
            // 切向：spin=+1 俯视逆时针，切向 = (sin, -cos)
            double tan = (0.45 + 0.65 * RANDOM.nextDouble()) * debris;
            double vx = Math.sin(ang) * tan * t.spin + Math.cos(ang) * 0.12;
            double vz = -Math.cos(ang) * tan * t.spin + Math.sin(ang) * 0.12;
            double vy = 0.35 + 0.5 * RANDOM.nextDouble() * debris;
            Particle p = mc.particleEngine.createParticle(new BlockParticleOption(ParticleTypes.BLOCK, st),
                    x, sy + 0.2, z, 0, 0, 0);
            if (p != null) {
                p.setParticleSpeed(vx, vy, vz);
                p.scale(1.2f + RANDOM.nextFloat() * 1.3f);
                p.setLifetime(30 + RANDOM.nextInt(40));
            }
        }
    }

    /** 龙卷轰鸣：复用风声循环，降调成低频隆隆声，随距离和强度变化。跟随最近的龙卷。 */
    private static final class RoarSound extends AbstractTickableSoundInstance {
        RoarSound() {
            super(ZenithSounds.WIND_LOOP.get(), SoundSource.WEATHER, SoundInstance.createUnseededRandom());
            this.looping = true;
            this.delay = 0;
            this.volume = 0.01f;
            this.pitch = 0.5f;
            this.relative = true;
            this.attenuation = SoundInstance.Attenuation.NONE;
        }

        @Override
        public boolean canStartSilent() {
            return true;
        }

        @Override
        public void tick() {
            Minecraft mc = Minecraft.getInstance();
            if (mc.level == null || !ZenithConfig.tornadoSounds()) {
                stop();
                return;
            }
            Vec3 cam = mc.gameRenderer.getMainCamera().getPosition();
            float best = 0f;
            float bestPitch = 0.5f;
            for (TornadoTracker.Tracked t : TornadoTracker.tornadoes()) {
                double d = Math.hypot(t.x - cam.x, t.z - cam.z);
                float strength = TornadoShape.strength(t.intensity, t.maxWind);
                // 320 格开外听不见；120 格内接近满音量；贴近核心再拉满
                float fall = (float) Mth.clamp(1.0 - (d - 30.0) / 290.0, 0.0, 1.0);
                float v = fall * fall * Math.min(1f, strength) * (1f - 0.5f * t.rope);
                if (v > best) {
                    best = v;
                    bestPitch = 0.42f + 0.12f * Math.min(1f, strength) + 0.1f * (1f - fall);
                }
            }
            float target = best * 1.4f * (float) ZenithConfig.windSoundVolume();
            this.volume += (target - this.volume) * 0.06f;
            this.pitch += (bestPitch - this.pitch) * 0.05f;
            if (best <= 0f && this.volume < 0.005f) stop();
        }
    }
}
