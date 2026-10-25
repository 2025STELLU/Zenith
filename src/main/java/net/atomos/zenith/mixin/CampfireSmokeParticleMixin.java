package net.atomos.zenith.mixin;

import net.atomos.zenith.client.ClientWindState;
import net.minecraft.client.particle.CampfireSmokeParticle;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// 营火烟：每 tick 结束后按本地风场掰一下方向，上升气流还能把它托高。
@Mixin(CampfireSmokeParticle.class)
abstract class CampfireSmokeParticleMixin {
    @Inject(method = "tick", at = @At("TAIL"))
    private void zenith$applyCampfireWind(CallbackInfo ci) {
        ParticleAccessor self = (ParticleAccessor) this;
        // 一阶松弛：烟团松散、惯性大，τ≈6 tick 才跟上风；垂直向"风垂直分量 + 烟自浮力"收敛。
        // 风场是 m/s，xd 单位是 block/tick（1 tick = 1/20 s），所以除以 20。
        var wind = ClientWindState.get().sampleCoarse(
                self.zenith$getX(), self.zenith$getY() + 0.6, self.zenith$getZ());
        double k = 1.0 - Math.exp(-1.0 / 6.0);
        double xd = self.zenith$getXd();
        double zd = self.zenith$getZd();
        self.zenith$setXd(xd + (wind.x() / 20.0 - xd) * k);
        self.zenith$setZd(zd + (wind.z() / 20.0 - zd) * k);
        double targetY = (wind.y() + 1.0) / 20.0; // 营火烟约 1 m/s 自浮力
        double yd = self.zenith$getYd();
        self.zenith$setYd(yd + (targetY - yd) * k);
    }
}
