package net.atomos.zenith.mixin;

import net.atomos.zenith.client.ClientWindState;
import net.minecraft.client.particle.FlameParticle;
import net.minecraft.client.particle.Particle;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// 通用粒子钩：只管火把火焰（FlameParticle），风大时火苗会被吹歪。
@Mixin(Particle.class)
abstract class ParticleMixin {
    @Shadow
    protected double x;
    @Shadow
    protected double y;
    @Shadow
    protected double z;
    @Shadow
    protected double xd;
    @Shadow
    protected double zd;

    @Inject(method = "tick", at = @At("TAIL"))
    private void zenith$applyBaseParticleWind(CallbackInfo ci) {
        if (!(((Object) this) instanceof FlameParticle)) {
            return;
        }
        // 一阶松弛：火苗轻、响应快（τ≈2 tick）；只掰水平方向，垂直保持原版燃烧上升。
        // 风场是 m/s，xd 单位是 block/tick（1 tick = 1/20 s），所以除以 20。
        var wind = ClientWindState.get().sampleCoarse(this.x, this.y + 0.12, this.z);
        double k = 1.0 - Math.exp(-1.0 / 2.0);
        this.xd += (wind.x() / 20.0 - this.xd) * k;
        this.zd += (wind.z() / 20.0 - this.zd) * k;
    }
}
