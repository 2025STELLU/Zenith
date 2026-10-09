package net.atomos.zenith.mixin;

import net.atomos.zenith.client.ZenithParticleWind;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.FlameParticle;
import net.minecraft.client.particle.Particle;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// 通用粒子钩：只管火把火焰（FlameParticle），风大时火苗会被吹歪。
@Mixin(Particle.class)
abstract class ParticleMixin {
    @Shadow
    @Final
    protected ClientLevel level;

    @Shadow
    protected double x;
    @Shadow
    protected double y;
    @Shadow
    protected double z;
    @Shadow
    protected double xd;
    @Shadow
    protected double yd;
    @Shadow
    protected double zd;

    @Inject(method = "tick", at = @At("TAIL"))
    private void zenith$applyBaseParticleWind(CallbackInfo ci) {
        if (!(((Object) this) instanceof FlameParticle)) {
            return;
        }
        Vec3 next = ZenithParticleWind.applyTorchFlame(
                this.level, this.x, this.y, this.z, new Vec3(this.xd, this.yd, this.zd));
        this.xd = next.x;
        this.yd = next.y;
        this.zd = next.z;
    }
}
