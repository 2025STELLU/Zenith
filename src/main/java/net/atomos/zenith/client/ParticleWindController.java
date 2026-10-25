package net.atomos.zenith.client;

import net.atomos.zenith.particle.ZenithParticles;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BiomeTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * 环境风粒子：每 2 tick 按风速生成风丝、扬尘、落叶、浪花、热浪。
 *
 * <p>门限 3 m/s：低于这个速度肉眼看不到风，生成粒子只会让屏幕变脏。
 * 水花/落叶/热浪各有自己的地形/biome 前置条件。
 *
 * <p><b>CLI-12</b>：静态 {@code Random} 与 {@code tickCounter} 改走
 * {@link ClientWindState} 的集中计数 + {@link RandomSource}；{@code (int)} 截断改
 * {@link BlockPos#containing}（内部 {@code Mth.floor}）。
 *
 * <p><b>CLI-13</b>：{@code stormBoost} 不再"有台风就 0.8"，改按距离衰减
 * （{@link ClientWindState#stormBoostAt}）。
 */
@OnlyIn(Dist.CLIENT)
public final class ParticleWindController {
    /** 低于此风速不生成风粒子（m/s）。与 {@code ZenithVisualizer} 共用同一门限。 */
    public static final double WIND_PARTICLE_THRESHOLD_MPS = 3.0;

    private ParticleWindController() {}

    public static void clientTick() {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null || mc.player == null || mc.isPaused()) return;
        // 表现开关（ENG-03）：粒子密度 / 总开关
        if (!net.atomos.zenith.ZenithConfig.showWindParticles()) return;
        double densityScale = net.atomos.zenith.ZenithConfig.particleDensity();
        if (densityScale <= 0) return;
        long tickCounter = ClientWindState.get().tickCounter();
        if ((tickCounter & 1L) != 0L) return;

        RandomSource random = level.random;
        double px = mc.player.getX(), py = mc.player.getY(), pz = mc.player.getZ();
        var wind = ClientWindState.get().sampleCoarse(px, py + 1, pz);
        double speed = wind.length();
        double stormBoost = ClientWindState.get().stormBoostAt(px, pz);

        // 风丝
        if (speed > WIND_PARTICLE_THRESHOLD_MPS) {
            int count = Math.min(6, (int) (speed / 3 * densityScale));
            for (int i = 0; i < count; i++) {
                double x = px + (random.nextDouble() - 0.5) * 36;
                double y = py + random.nextDouble() * 10 - 2;
                double z = pz + (random.nextDouble() - 0.5) * 36;
                level.addParticle(ZenithParticles.WIND_STREAK.get(), x, y, z, 0, 0, 0);
            }
        }

        // 地面扬尘
        if (speed > 7.0 || stormBoost > 0.5) {
            for (int i = 0; i < 2; i++) {
                double x = px + (random.nextDouble() - 0.5) * 28;
                double z = pz + (random.nextDouble() - 0.5) * 28;
                BlockPos bp = BlockPos.containing(x, py, z);
                double gy = level.getHeightmapPos(Heightmap.Types.WORLD_SURFACE, bp).getY();
                if (Math.abs(py - gy) < 6) {
                    level.addParticle(ZenithParticles.DUST.get(), x, gy + 0.3, z, 0, 0, 0);
                }
            }
        }

        // 森林/丛林/针叶林落叶
        if (tickCounter % 10 == 0 && speed > 1.5) {
            BlockPos bp = BlockPos.containing(px, py, pz);
            var biome = level.getBiome(bp);
            if (biome.is(BiomeTags.IS_FOREST) || biome.is(BiomeTags.IS_JUNGLE)
                    || biome.is(BiomeTags.IS_TAIGA)) {
                double x = px + (random.nextDouble() - 0.5) * 24;
                double z = pz + (random.nextDouble() - 0.5) * 24;
                level.addParticle(ZenithParticles.LEAF.get(), x, py + 4 + random.nextDouble() * 4, z,
                        0, 0, 0);
            }
        }

        // 风暴下的浪花：只在水面 0.1~0.2 block/tick（2~4 m/s）
        if (stormBoost > 0.45 && tickCounter % 4 == 0) {
            for (int i = 0; i < 3; i++) {
                double x = px + (random.nextDouble() - 0.5) * 30;
                double z = pz + (random.nextDouble() - 0.5) * 30;
                // 向下最多找 8 格，避免每次 8 格全查
                BlockPos bp = BlockPos.containing(x, py, z);
                int waterY = Integer.MIN_VALUE;
                for (int d = 0; d < 8; d++) {
                    BlockPos p = bp.below(d);
                    if (level.getFluidState(p).is(FluidTags.WATER)) {
                        waterY = p.getY();
                        break;
                    }
                }
                if (waterY == Integer.MIN_VALUE) continue;
                level.addParticle(ZenithParticles.SPRAY.get(), x, waterY + 1.1, z,
                        (random.nextDouble() - 0.5) * 2, 0.1 + random.nextDouble() * 0.1,
                        (random.nextDouble() - 0.5) * 2);
            }
        }

        // 热源上方的热浪
        if (tickCounter % 20 == 0) {
            BlockPos pp = mc.player.blockPosition();
            for (int dx = -8; dx <= 8; dx += 4)
                for (int dz = -8; dz <= 8; dz += 4)
                    for (int dy = -2; dy <= 4; dy += 3) {
                        BlockPos bp = pp.offset(dx, dy, dz);
                        BlockState st = level.getBlockState(bp);
                        boolean hot = st.getFluidState().is(Fluids.LAVA)
                                || st.is(Blocks.FIRE) || st.is(Blocks.MAGMA_BLOCK)
                                || (st.is(Blocks.CAMPFIRE) && st.getValue(CampfireBlock.LIT));
                        if (hot) {
                            level.addParticle(ZenithParticles.HEAT_SHIMMER.get(),
                                    bp.getX() + 0.5, bp.getY() + 1.1, bp.getZ() + 0.5, 0, 0, 0);
                        }
                    }
        }
    }
}
