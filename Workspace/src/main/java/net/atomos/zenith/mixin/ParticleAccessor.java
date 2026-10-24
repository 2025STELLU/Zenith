package net.atomos.zenith.mixin;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

// 拿到原版 Particle 里那些 protected 的位置/速度字段，mixin 才能读写。
@Mixin(Particle.class)
public interface ParticleAccessor {
    @Accessor("level")
    ClientLevel zenith$getLevel();

    @Accessor("x")
    double zenith$getX();

    @Accessor("y")
    double zenith$getY();

    @Accessor("z")
    double zenith$getZ();

    @Accessor("xd")
    double zenith$getXd();

    @Accessor("yd")
    double zenith$getYd();

    @Accessor("zd")
    double zenith$getZd();

    @Accessor("xd")
    void zenith$setXd(double value);

    @Accessor("yd")
    void zenith$setYd(double value);

    @Accessor("zd")
    void zenith$setZd(double value);
}
