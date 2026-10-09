package net.atomos.zenith.mixin;

import net.atomos.zenith.client.ZenithParticleWind;
import net.minecraft.client.particle.CampfireSmokeParticle;
import net.minecraft.world.phys.Vec3;
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
        Vec3 next = ZenithParticleWind.applyCampfireSmoke(
                self.zenith$getLevel(),
                self.zenith$getX(),
                self.zenith$getY(),
                self.zenith$getZ(),
                new Vec3(self.zenith$getXd(), self.zenith$getYd(), self.zenith$getZd()));
        self.zenith$setXd(next.x);
        self.zenith$setYd(next.y);
        self.zenith$setZd(next.z);
    }
}
