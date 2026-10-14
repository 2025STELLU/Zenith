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
            // 阻尼已由下面的松弛表达，friction 必须 1.0，别双重阻尼
            this.friction = 1.0f;
        }

        /**
         * 一阶松弛（水平）：速度指数趋近"耦合风速"。
         * 风场单位是 m/s，Particle 的 xd 却是 block/tick（1 tick = 1/20 s），所以除以 20。
         * k = 1 - exp(-1/τ)，τ 为空气动力学响应时间（tick），越小跟风越快。
         */
        protected void applyWind(double windCoupling, double tauTicks) {
            var v = ClientWindState.get().sampleCoarse(x, y, z);
            double k = 1.0 - Math.exp(-1.0 / tauTicks);
            this.xd += (v.x() * windCoupling / 20.0 - this.xd) * k;
            this.zd += (v.z() * windCoupling / 20.0 - this.zd) * k;
        }

        /**
         * 一阶松弛（垂直）：垂直速度趋近"风垂直分量×耦合 + 附加速度"。
         * 附加速度单位 m/s：热浪上浮取正、落叶下沉取负；
         * 雨/雹/水沫的垂直方向是重力弹道，别调这个，只掰水平。
         */
        protected void applyVertical(double windCoupling, double extraMps, double tauTicks) {
            var v = ClientWindState.get().sampleCoarse(x, y, z);
            double k = 1.0 - Math.exp(-1.0 / tauTicks);
            this.yd += ((v.y() * windCoupling + extraMps) / 20.0 - this.yd) * k;
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
                    // 初速直接取风速（block/tick）；以前 v.x()*0.9 用的是 m/s，初速是真风速的 18 倍
                    this.xd = v.x() / 20.0;
                    this.yd = v.y() / 20.0;
                    this.zd = v.z() / 20.0;
                }

                @Override
                public void tick() {
                    super.tick();
                    // τ=2 tick：风迹就是风的示踪，响应最快
                    applyWind(1.0, 2.0);
                    applyVertical(1.0, 0, 2.0);
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
                    this.yd = -0.06 - random.nextDouble() * 0.04; // 约 1.2~2 m/s 飘落初速
                }

                @Override
                public void tick() {
                    super.tick();
                    // τ=10 tick：落叶轻而兜风、打转卸力，跟风慢半拍；-1.2 m/s 下沉保证无风时也往下掉
                    applyWind(0.8, 10.0);
                    applyVertical(0.5, -1.2, 10.0);
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
                    // τ=4 tick：扬尘颗粒细小，几乎瞬间跟上阵风
                    applyWind(1.0, 4.0);
                    applyVertical(0.8, 0, 4.0);
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
                    // τ=2 tick：水滴小、跟风快；垂直走重力弹道（水花抛物线/雨滴终端速度），风只掰水平方向
                    applyWind(1.2, 2.0);
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
                    this.yd = 0.15 + random.nextDouble() * 0.1; // 3~5 m/s 上浮初速
                    this.xd = (random.nextDouble() - 0.5) * 0.2;
                    this.zd = (random.nextDouble() - 0.5) * 0.2;
                }

                @Override
                public void tick() {
                    super.tick();
                    // τ=6 tick：热浪是团暖空气，惯性大、跟风慢；+3 m/s 浮力保证一直往上飘
                    applyWind(0.5, 6.0);
                    applyVertical(0.5, 3.0, 6.0);
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
                    // 初速取 0.25 倍风速（block/tick），和下面的松弛目标一致，省得出生先被掰回来
                    this.xd = dx * 0.25;
                    this.zd = dz * 0.25;
                    gravity = 0.9f;
                }

                @Override
                public void tick() {
                    super.tick();
                    // τ=4 tick：冰雹比雨滴重、响应慢一拍；耦合只取 0.25，风再大也只是微偏
                    applyWind(0.25, 4.0);
                }
            };
        }
    }
}
