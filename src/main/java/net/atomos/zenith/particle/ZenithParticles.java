package net.atomos.zenith.particle;

import net.atomos.zenith.ZenithMod;
import net.minecraft.core.particles.ParticleType;
import net.minecraft.core.particles.SimpleParticleType;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.function.Supplier;

public final class ZenithParticles {
    public static final DeferredRegister<ParticleType<?>> PARTICLE_TYPES =
            DeferredRegister.create(net.minecraft.core.registries.Registries.PARTICLE_TYPE, ZenithMod.MOD_ID);

    /** 风迹：随风高速移动的细白 streak。 */
    public static final Supplier<SimpleParticleType> WIND_STREAK =
            PARTICLE_TYPES.register("wind_streak", () -> new SimpleParticleType(false));
    /** 落叶/碎屑：旋转飘落，被风吹走。 */
    public static final Supplier<SimpleParticleType> LEAF =
            PARTICLE_TYPES.register("leaf", () -> new SimpleParticleType(false));
    /** 扬尘：阵风时贴地卷起。 */
    public static final Supplier<SimpleParticleType> DUST =
            PARTICLE_TYPES.register("dust", () -> new SimpleParticleType(false));
    /** 水沫：海岸/风暴/台风。 */
    public static final Supplier<SimpleParticleType> SPRAY =
            PARTICLE_TYPES.register("spray", () -> new SimpleParticleType(false));
    /** 热浪：热源上方上升的近透明气流。 */
    public static final Supplier<SimpleParticleType> HEAT_SHIMMER =
            PARTICLE_TYPES.register("heat_shimmer", () -> new SimpleParticleType(false));
    /** 冰雹：风暴成熟期的小冰粒。 */
    public static final Supplier<SimpleParticleType> HAIL =
            PARTICLE_TYPES.register("hail", () -> new SimpleParticleType(false));

    private ZenithParticles() {}
}
