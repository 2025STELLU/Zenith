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
            // 1.5 m/s 以下几乎无声，18 m/s 满音量
            double target = Math.max(0, Math.min(1, (speed - 1.5) / 16.0));
            // 平滑
            this.volume += (target * 0.55f - this.volume) * 0.08f;
            this.pitch = (float) (0.82 + speed * 0.014);
            if (this.pitch > 1.25f) this.pitch = 1.25f;
        }
    }
}
