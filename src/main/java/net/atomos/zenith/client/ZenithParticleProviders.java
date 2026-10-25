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
         * 雨/雹/水沫别调这个，它们的垂直方向走下面的终端速度。
         */
        protected void applyVertical(double windCoupling, double extraMps, double tauTicks) {
            var v = ClientWindState.get().sampleCoarse(x, y, z);
            double k = 1.0 - Math.exp(-1.0 / tauTicks);
            this.yd += ((v.y() * windCoupling + extraMps) / 20.0 - this.yd) * k;
        }

        /**
         * 一阶松弛（终端速度）：垂直速度指数趋近固定的终端下落速度。
         * friction=1 之后降水粒子的垂直方向只剩重力、会无限加速，
         * 这里就是它们的"空气阻力"：雨滴约 9 m/s，2cm 冰雹约 20 m/s。
         */
        protected void applyTerminalVelocity(double vTermMps, double tauTicks) {
            double k = 1.0 - Math.exp(-1.0 / tauTicks);
            this.yd += (-vTermMps / 20.0 - this.yd) * k;
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

                @Override
                public ParticleRenderType getRenderType() {
                    // 落叶贴图边缘是半透明的，OPAQUE 下直接锯齿：走 TRANSLUCENT
                    return ParticleRenderType.PARTICLE_SHEET_TRANSLUCENT;
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
                    // 垂直方向不走重力了：终端速度松弛就是阻力，留着 gravity 会把平衡点拉到 20 m/s 去
                }

                @Override
                public void tick() {
                    super.tick();
                    // τ=2 tick：水滴小、跟风快；耦合钳到 1.0，粒子不能比风还快，
                    // "更斜"的观感以后靠拉伸渲染
                    applyWind(1.0, 2.0);
                    // 同一个 provider 冒充两种东西：雨丝和水沫，垂直速度正负一看便知
                    if (this.yd > 0) {
                        // 上升中 = 水沫溅射：终端速度 3.5 m/s、τ=7；
                        // 以前全按 9 m/s 压，第 1 tick 就把初速压成负的，溅不起来，看着就是雨
                        applyTerminalVelocity(3.5, 7.0);
                    } else {
                        // 下落中 = 雨丝：终端速度 9 m/s、τ=2
                        applyTerminalVelocity(9.0, 2.0);
                    }
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

    /** 雾气：大尺寸、低透明度、慢悠悠随风的软斑，贴地。 */
    public static class FogProvider implements ParticleProvider<SimpleParticleType> {
        private final SpriteSet sprites;
        public FogProvider(SpriteSet sprites) { this.sprites = sprites; }

        @Override
        public Particle createParticle(SimpleParticleType type, ClientLevel level,
                                       double x, double y, double z,
                                       double dx, double dy, double dz) {
            return new WindDrivenParticle(level, x, y, z, sprites) {
                private final float baseAlpha = 0.05f + random.nextFloat() * 0.05f;

                {
                    lifetime = 120 + random.nextInt(80);
                    // 2~6 格的大软斑：远看是雾，近看也不至于糊一脸
                    this.quadSize = 2.0f + random.nextFloat() * 4.0f;
                    setColor(0.92f, 0.95f, 0.97f); // 冷白
                    setAlpha(baseAlpha);
                }

                @Override
                public void tick() {
                    super.tick();
                    // τ=6 tick：雾团惯性大，懒洋洋地跟风；垂直不动，贴着地表走
                    applyWind(1.0, 6.0);
                    // 渐入渐出，别生硬地弹出/消失
                    float half = lifetime / 2.0f;
                    float f = Math.min(1.0f, Math.min(age, lifetime - age) / half);
                    setAlpha(baseAlpha * Math.max(0.0f, f));
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
        event.registerSpriteSet(ZenithParticles.FOG.get(), FogProvider::new);
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
                    // 生下来就是终端速度：-20/20 = -1.0 block/tick（2cm 冰雹约 20 m/s）；
                    // 以前 -2.5~-4.0 是 50~80 m/s 的炮弹，离谱
                    this.yd = -20.0 / 20.0;
                    // 初速取 0.25 倍风速（block/tick），和下面的松弛目标一致，省得出生先被掰回来
                    this.xd = dx * 0.25;
                    this.zd = dz * 0.25;
                    // 垂直不走重力，终端速度松弛就是阻力
                }

                @Override
                public void tick() {
                    super.tick();
                    // τ=4 tick：冰雹比雨滴重、响应慢一拍；耦合只取 0.25，风再大也只是微偏
                    applyWind(0.25, 4.0);
                    applyTerminalVelocity(20.0, 4.0);
                }

                @Override
                public ParticleRenderType getRenderType() {
                    // 冰粒边缘锯齿同落叶：走 TRANSLUCENT
                    return ParticleRenderType.PARTICLE_SHEET_TRANSLUCENT;
                }
            };
        }
    }
}
