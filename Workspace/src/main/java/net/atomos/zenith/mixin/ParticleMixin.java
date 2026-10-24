package net.atomos.zenith.mixin;

import net.atomos.zenith.client.ClientWindState;
import net.minecraft.client.particle.AshParticle;
import net.minecraft.client.particle.FlameParticle;
import net.minecraft.client.particle.LavaParticle;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.SmokeParticle;
import net.minecraft.client.particle.SnowflakeParticle;
import net.minecraft.client.particle.WhiteAshParticle;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 给"会被风明显吹动的"原版粒子叠加粗风场（CLI-16）。
 *
 * <p><b>旧实现</b>只有 {@code instanceof FlameParticle} 一个白名单——全 Minecraft 的烟、
 * 云、雨、雪、爆炸、岩浆、末地烛统统不受风影响。玩家在 20 m/s 的风里看篝火，
 * 烟被吹走；看普通火把的烟、熔炉的烟、营火的余烬，纹丝不动。视觉上"风只对两种
 * 粒子生效"，与风场可视化的目标不符。
 *
 * <p><b>现在</b>改成白名单 + 显式排除：
 * <ul>
 *   <li>白名单：火焰、烟、岩浆、灰烬、白色灰烬、雪花——都是"轻、悬浮、被风吹"的粒子；</li>
 *   <li>排除：{@code CampfireSmokeParticle} 已由 {@code CampfireSmokeParticleMixin}
 *       单独处理（它还要带上垂直速度，且用更长的响应时间 6 tick）。这里若不排除，
 *       同一个 tick 会被两个 mixin 各推一次，位移翻倍。</li>
 * </ul>
 *
 * <p>只做水平方向：这些粒子的 {@code yd} 由各自的原版逻辑管（火焰上浮、雪下落、
 * 岩浆弹跳），强加垂直风会把它们的观感彻底破坏。
 */
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
        if (!zenith$isWindAffected()) {
            return;
        }
        // 响应时间 2 tick 的一阶低通：m/s → block/tick 要除 20
        var wind = ClientWindState.get().sampleCoarse(this.x, this.y + 0.12, this.z);
        double k = 1.0 - Math.exp(-1.0 / 2.0);
        this.xd += (wind.x() / 20.0 - this.xd) * k;
        this.zd += (wind.z() / 20.0 - this.zd) * k;
    }

    /** 白名单判定（排除由专门 mixin 处理的类型）。 */
    private boolean zenith$isWindAffected() {
        Object self = this;
        // CampfireSmokeParticle 走自己的 mixin，避免双重施力
        if (self instanceof net.minecraft.client.particle.CampfireSmokeParticle) {
            return false;
        }
        return self instanceof FlameParticle
                || self instanceof SmokeParticle
                || self instanceof LavaParticle
                || self instanceof AshParticle
                || self instanceof WhiteAshParticle
                || self instanceof SnowflakeParticle;
    }
}
