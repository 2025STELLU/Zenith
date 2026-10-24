package net.atomos.zenith.client;

import net.atomos.zenith.sound.ZenithSounds;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * 客户端风声管理器：随玩家处风速实时调制环境风声循环的音量/音高。
 */
public final class WindSoundManager {
    private static WindLoopSound activeSound;
    private static int retryCooldown = 0;

    private WindSoundManager() {}

    public static void clientTick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) {
            stop();
            return;
        }
        if (activeSound == null) {
            activeSound = new WindLoopSound();
            mc.getSoundManager().play(activeSound);
            retryCooldown = 0;
        } else if (!mc.getSoundManager().isActive(activeSound)) {
            // 声音系统重载偶尔会丢实例；节流重试，别每 tick 狂建
            if (++retryCooldown >= 20) {
                retryCooldown = 0;
                activeSound = new WindLoopSound();
                mc.getSoundManager().play(activeSound);
            }
        } else {
            retryCooldown = 0;
        }
        // 音量/音高在 WindLoopSound.tick() 中每 tick 更新，不重建实例
    }

    public static void stop() {
        Minecraft mc = Minecraft.getInstance();
        if (activeSound != null) {
            mc.getSoundManager().stop(activeSound);
            activeSound = null;
        }
    }

    static class WindLoopSound extends AbstractTickableSoundInstance {
        private int skySampleTick = 0;
        /** 天空可见度 0~1：野外=1、室内≈0。默认先当野外，采到样再说。 */
        private float skyExposure = 1.0f;

        WindLoopSound() {
            super(ZenithSounds.WIND_LOOP.get(), SoundSource.AMBIENT, RandomSource.create());
            this.looping = true;
            this.attenuation = SoundInstance.Attenuation.NONE;
            this.volume = 0;
            this.pitch = 0.9f;
        }

        // 零音量起播：不覆写的话 SoundEngine.play 直接把实例跳过，永远注册不上
        @Override
        public boolean canStartSilent() {
            return true;
        }

        @Override
        public void tick() {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player == null || mc.level == null) {
                this.volume = 0;
                return;
            }
            var p = mc.player;
            var wind = ClientWindState.get().sampleCoarse(p.getX(), p.getY(), p.getZ());
            double speed = Math.hypot(wind.x(), wind.z());
            // 每 10 tick 看一眼头顶：室内风声就小下去，野外不受影响
            if (++skySampleTick >= 10) {
                skySampleTick = 0;
                var base = p.blockPosition();
                int open = 0;
                if (mc.level.canSeeSky(base)) open++;
                if (skyOpenAt(mc.level, base, 8, 0)) open++;
                if (skyOpenAt(mc.level, base, -8, 0)) open++;
                if (skyOpenAt(mc.level, base, 0, 8)) open++;
                if (skyOpenAt(mc.level, base, 0, -8)) open++;
                skyExposure += (open / 5.0f - skyExposure) * 0.1f;
            }
            // 1.5 m/s 以下几乎无声，18 m/s 满音量；室内再打折；客户端配置再乘一次
            double target = Math.max(0, Math.min(1, (speed - 1.5) / 16.0));
            double audible = target * 0.55 * (0.25 + 0.75 * skyExposure)
                    * net.atomos.zenith.ZenithConfig.windSoundVolume();
            // 平滑
            this.volume += (audible - this.volume) * 0.08f;
            this.pitch = (float) (0.82 + speed * 0.014);
            if (this.pitch > 1.25f) this.pitch = 1.25f;
        }
        /**
         * 偏移点的天空采样：只有该点**真的埋进方块**才往上抬（最多 4 格），否则直接用玩家脚高。
         * <p>旧实现取 {@code max(脚高, 地表+1)}，而 {@code WORLD_SURFACE} 的返回值本身就已经在地表**之上**，
         * 于是 4 个偏移点在已加载区恒为露天——屋里/洞里风声跟野外几乎一样大（N-20）。
         * 抬升上限 4 格：山坡上的点能露出地表，而屋顶/洞顶远高于 4 格时仍判为遮蔽。
         */
        private static boolean skyOpenAt(ClientLevel level, BlockPos base, int dx, int dz) {
            int x = base.getX() + dx, z = base.getZ() + dz;
            BlockPos p = new BlockPos(x, base.getY(), z);
            if (!level.getBlockState(p).isAir()) {
                int surface = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
                p = new BlockPos(x, Math.min(surface, base.getY() + 4), z);
            }
            return level.canSeeSky(p);
        }
    }
}
