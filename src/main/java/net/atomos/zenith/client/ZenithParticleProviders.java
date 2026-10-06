package net.atomos.zenith.client;

import net.atomos.zenith.particle.ZenithParticles;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleProvider;
import net.minecraft.client.particle.ParticleRenderType;
import net.minecraft.client.particle.SpriteSet;
import net.minecraft.client.particle.TextureSheetParticle;
import net.minecraft.core.particles.SimpleParticleType;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * Zenith 粒子提供者（客户端）。
 * 所有粒子都读取客户端风场（ClientWindState）实现随风漂移。
 */
@OnlyIn(Dist.CLIENT)
public final class ZenithParticleProviders {
    private ZenithParticleProviders() {}

    abstract static class WindDrivenParticle extends TextureSheetParticle {
        protected WindDrivenParticle(ClientLevel level, double x, double y, double z,
                                     SpriteSet sprites) {
            super(level, x, y, z);
            setSpriteFromAge(sprites);
        }

        /** 先设好基础速度再调这个叠风。 */
        protected void applyWind(double factor, double updraftBonus) {
            var v = ClientWindState.get().sampleCoarse(x, y, z);
            this.xd += v.x() * factor * 0.05;
            this.yd += (v.y() * factor + updraftBonus) * 0.05;
            this.zd += v.z() * factor * 0.05;
        }

        @Override
        public ParticleRenderType getRenderType() {
            return ParticleRenderType.PARTICLE_SHEET_OPAQUE;
        }
    }

    /** 风迹：细长白 streak，高速随风。 */
    public static class WindStreakProvider implements ParticleProvider<SimpleParticleType> {
        private final SpriteSet sprites;
        public WindStreakProvider(SpriteSet sprites) { this.sprites = sprites; }

        @Override
        public Particle createParticle(SimpleParticleType type, ClientLevel level,
                                       double x, double y, double z,
                                       double dx, double dy, double dz) {
            return new WindDrivenParticle(level, x, y, z, sprites) {
                {
                    lifetime = 25 + random.nextInt(20);
                    this.quadSize = 0.12f;
                    setColor(1f, 1f, 1f);
                    setAlpha(0.35f);
                    var v = ClientWindState.get().sampleCoarse(x, y, z);
                    this.xd = v.x() * 0.9;
                    this.yd = v.y() * 0.9;
                    this.zd = v.z() * 0.9;
                }

                @Override
                public void tick() {
                    super.tick();
                    applyWind(0.35, 0);
                    double sp = Math.sqrt(xd * xd + zd * zd);
                    if (sp > 3.0) {
                        xd *= 3.0 / sp;
                        zd *= 3.0 / sp;
                    }
                }

                @Override
                public ParticleRenderType getRenderType() {
                    return ParticleRenderType.PARTICLE_SHEET_TRANSLUCENT;
                }
            };
        }
    }

    /** 落叶：旋转飘落，被风吹。 */
    public static class LeafProvider implements ParticleProvider<SimpleParticleType> {
        private final SpriteSet sprites;
        public LeafProvider(SpriteSet sprites) { this.sprites = sprites; }

        @Override
        public Particle createParticle(SimpleParticleType type, ClientLevel level,
                                       double x, double y, double z,
                                       double dx, double dy, double dz) {
            return new WindDrivenParticle(level, x, y, z, sprites) {
                private float spin = random.nextFloat() * 360;

                {
                    lifetime = 120 + random.nextInt(120);
                    this.quadSize = 0.22f;
                    float g = 0.55f + random.nextFloat() * 0.25f;
                    setColor(0.35f * g, g, 0.2f * g); // 叶绿
                    if (random.nextFloat() < 0.3) setColor(0.6f, 0.42f, 0.2f); // 枯叶
                    this.yd = -0.25 - random.nextDouble() * 0.2;
                }

                @Override
                public void tick() {
                    super.tick();
                    applyWind(0.8, 0);
                    spin += 6;
                    oRoll = roll;
                    roll = (float) Math.toRadians(spin);
                    this.xd += Math.sin(age * 0.3) * 0.004;
                }
            };
        }
    }

    /** 扬尘：阵风贴地卷起。 */
    public static class DustProvider implements ParticleProvider<SimpleParticleType> {
        private final SpriteSet sprites;
        public DustProvider(SpriteSet sprites) { this.sprites = sprites; }

        @Override
        public Particle createParticle(SimpleParticleType type, ClientLevel level,
                                       double x, double y, double z,
                                       double dx, double dy, double dz) {
            return new WindDrivenParticle(level, x, y, z, sprites) {
                {
                    lifetime = 40 + random.nextInt(40);
                    this.quadSize = 0.5f;
                    setColor(0.72f, 0.62f, 0.48f);
                    setAlpha(0.4f);
                    this.yd = 0.35 + random.nextDouble() * 0.4;
                }

                @Override
                public void tick() {
                    super.tick();
                    applyWind(1.0, 0);
                    this.quadSize += 0.02f;
                    setAlpha(Math.max(0, alpha - 0.008f));
                }

                @Override
                public ParticleRenderType getRenderType() {
                    return ParticleRenderType.PARTICLE_SHEET_TRANSLUCENT;
                }
            };
        }
    }

    /** 水沫：海岸/风暴。 */
    public static class SprayProvider implements ParticleProvider<SimpleParticleType> {
        private final SpriteSet sprites;
        public SprayProvider(SpriteSet sprites) { this.sprites = sprites; }

        @Override
        public Particle createParticle(SimpleParticleType type, ClientLevel level,
                                       double x, double y, double z,
                                       double dx, double dy, double dz) {
            return new WindDrivenParticle(level, x, y, z, sprites) {
                {
                    lifetime = 30 + random.nextInt(25);
                    this.quadSize = 0.16f;
                    setColor(0.85f, 0.93f, 1f);
                    setAlpha(0.7f);
                    this.xd = dx;
                    this.yd = dy;
                    this.zd = dz;
                    gravity = 0.35f;
                }

                @Override
                public void tick() {
                    super.tick();
                    applyWind(1.2, 0);
                }

                @Override
                public ParticleRenderType getRenderType() {
                    return ParticleRenderType.PARTICLE_SHEET_TRANSLUCENT;
                }
            };
        }
    }

    /** 热浪：热源上方上升的近透明气流。 */
    public static class HeatShimmerProvider implements ParticleProvider<SimpleParticleType> {
        private final SpriteSet sprites;
        public HeatShimmerProvider(SpriteSet sprites) { this.sprites = sprites; }

        @Override
        public Particle createParticle(SimpleParticleType type, ClientLevel level,
                                       double x, double y, double z,
                                       double dx, double dy, double dz) {
            return new WindDrivenParticle(level, x, y, z, sprites) {
                {
                    lifetime = 35 + random.nextInt(20);
                    this.quadSize = 0.6f;
                    setColor(1f, 1f, 1f);
                    setAlpha(0.10f);
                    this.yd = 0.9 + random.nextDouble() * 0.5;
                    this.xd = (random.nextDouble() - 0.5) * 0.2;
                    this.zd = (random.nextDouble() - 0.5) * 0.2;
                }

                @Override
                public void tick() {
                    super.tick();
                    applyWind(0.5, 0.15);
                    this.quadSize += 0.03f;
                    setAlpha(Math.max(0, alpha - 0.003f));
                }

                @Override
                public ParticleRenderType getRenderType() {
                    return ParticleRenderType.PARTICLE_SHEET_TRANSLUCENT;
                }
            };
        }
    }

    public static void registerAll(
            net.neoforged.neoforge.client.event.RegisterParticleProvidersEvent event) {
        event.registerSpriteSet(ZenithParticles.WIND_STREAK.get(), WindStreakProvider::new);
        event.registerSpriteSet(ZenithParticles.LEAF.get(), LeafProvider::new);
        event.registerSpriteSet(ZenithParticles.DUST.get(), DustProvider::new);
        event.registerSpriteSet(ZenithParticles.SPRAY.get(), SprayProvider::new);
        event.registerSpriteSet(ZenithParticles.HEAT_SHIMMER.get(), HeatShimmerProvider::new);
        event.registerSpriteSet(ZenithParticles.HAIL.get(), HailProvider::new);
    }

    /** 冰雹：高速下落的冰粒，随风微偏。 */
    public static class HailProvider implements ParticleProvider<SimpleParticleType> {
        private final SpriteSet sprites;
        public HailProvider(SpriteSet sprites) { this.sprites = sprites; }

        @Override
        public Particle createParticle(SimpleParticleType type, ClientLevel level,
                                       double x, double y, double z,
                                       double dx, double dy, double dz) {
            return new WindDrivenParticle(level, x, y, z, sprites) {
                {
                    lifetime = 60;
                    this.quadSize = 0.14f;
                    setColor(0.9f, 0.95f, 1f);
                    this.yd = -2.5 - random.nextDouble() * 1.5;
                    this.xd = dx;
                    this.zd = dz;
                    gravity = 0.9f;
                }

                @Override
                public void tick() {
                    super.tick();
                    applyWind(0.25, 0);
                }
            };
        }
    }
}
