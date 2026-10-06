package net.atomos.zenith.client;

import net.atomos.zenith.sound.ZenithSounds;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;

/**
 * 客户端风声管理器：随玩家处风速实时调制环境风声循环的音量/音高。
 */
public final class WindSoundManager {
    private static WindLoopSound activeSound;

    private WindSoundManager() {}

    public static void clientTick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) {
            stop();
            return;
        }
        if (activeSound == null || !mc.getSoundManager().isActive(activeSound)) {
            activeSound = new WindLoopSound();
            mc.getSoundManager().play(activeSound);
        }
        // 音量/音高在 WindLoopSound.tick() 中每 tick 更新
    }

    public static void stop() {
        Minecraft mc = Minecraft.getInstance();
        if (activeSound != null) {
            mc.getSoundManager().stop(activeSound);
            activeSound = null;
        }
    }

    static class WindLoopSound extends AbstractTickableSoundInstance {
        WindLoopSound() {
            super(ZenithSounds.WIND_LOOP.get(), SoundSource.AMBIENT, RandomSource.create());
            this.looping = true;
            this.attenuation = SoundInstance.Attenuation.NONE;
            this.volume = 0;
            this.pitch = 0.9f;
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
            // 1.5 m/s 以下几乎无声，18 m/s 满音量
            double target = Math.max(0, Math.min(1, (speed - 1.5) / 16.0));
            // 平滑
            this.volume += (target * 0.55f - this.volume) * 0.08f;
            this.pitch = (float) (0.82 + speed * 0.014);
            if (this.pitch > 1.25f) this.pitch = 1.25f;
        }
    }
}
