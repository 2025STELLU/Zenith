package net.atomos.zenith.client;

import net.atomos.zenith.particle.ZenithParticles;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BiomeTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import java.util.Random;

/**
 * 客户端环境粒子：每 tick 按本地风况撒粒子。
 * 风速超 3 m/s 飘风迹，森林里有风掉落叶，贴地大风或高湍流扬尘，
 * 风暴/台风天溅水沫，熔岩/火焰/点着的营火上冒热浪。
 */
@OnlyIn(Dist.CLIENT)
public final class ParticleWindController {
    private static final Random RANDOM = new Random();
    private static int tickCounter = 0;

    private ParticleWindController() {}

    public static void clientTick() {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null || mc.player == null || mc.isPaused()) return;
        // 客户端配置（ENG-03）：关掉风场粒子 / 降密度
        if (!net.atomos.zenith.ZenithConfig.showWindParticles()) return;
        double densityScale = net.atomos.zenith.ZenithConfig.particleDensity();
        if (densityScale <= 0) return;
        if (++tickCounter % 2 != 0) return;

        double px = mc.player.getX(), py = mc.player.getY(), pz = mc.player.getZ();
        var wind = ClientWindState.get().sampleCoarse(px, py + 1, pz);
        double speed = wind.length();
        boolean hasSnapshot = ClientWindState.get().snapshot() != null;
        double stormBoost = 0;
        if (hasSnapshot) {
            var snap = ClientWindState.get().snapshot();
            stormBoost = Math.max(snap.stormActivity(),
                    snap.typhoons().isEmpty() ? 0 : 0.8);
        }

        // 风迹
        if (speed > 3.0) {
            int count = Math.min(6, (int) (speed / 3 * densityScale));
            for (int i = 0; i < count; i++) {
                double x = px + (RANDOM.nextDouble() - 0.5) * 36;
                double y = py + RANDOM.nextDouble() * 10 - 2;
                double z = pz + (RANDOM.nextDouble() - 0.5) * 36;
                level.addParticle(ZenithParticles.WIND_STREAK.get(), x, y, z, 0, 0, 0);
            }
        }

        // 扬尘：高速或高湍流
        if (speed > 7.0 || stormBoost > 0.5) {
            for (int i = 0; i < 2; i++) {
                double x = px + (RANDOM.nextDouble() - 0.5) * 28;
                double z = pz + (RANDOM.nextDouble() - 0.5) * 28;
                BlockPos bp = new BlockPos((int) x, (int) py, (int) z);
                double gy = level.getHeightmapPos(
                        net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE, bp).getY();
                if (Math.abs(py - gy) < 6) {
                    level.addParticle(ZenithParticles.DUST.get(), x, gy + 0.3, z, 0, 0, 0);
                }
            }
        }

        // 落叶：森林附近（别用 biome.toString() 猜名字，永远匹配不上；直接问标签）
        if (tickCounter % 10 == 0 && speed > 1.5) {
            BlockPos bp = new BlockPos((int) px, (int) py, (int) pz);
            var biome = level.getBiome(bp);
            if (biome.is(BiomeTags.IS_FOREST) || biome.is(BiomeTags.IS_JUNGLE)
                    || biome.is(BiomeTags.IS_TAIGA)) {
                double x = px + (RANDOM.nextDouble() - 0.5) * 24;
                double z = pz + (RANDOM.nextDouble() - 0.5) * 24;
                level.addParticle(ZenithParticles.LEAF.get(), x, py + 4 + RANDOM.nextDouble() * 4, z, 0, 0, 0);
            }
        }

        // 水沫：风暴/台风天，只在水面附近溅起来；溅射初速 0.1~0.2 block/tick（2~4 m/s）
        if (stormBoost > 0.45 && tickCounter % 4 == 0) {
            for (int i = 0; i < 3; i++) {
                double x = px + (RANDOM.nextDouble() - 0.5) * 30;
                double z = pz + (RANDOM.nextDouble() - 0.5) * 30;
                // 从玩家脚下往下找水面：离水面超过 8 格就别硬溅了（岸上/悬崖上不溅）
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
                // 水面 +0.1 格出生，省得生在水里
                level.addParticle(ZenithParticles.SPRAY.get(), x, waterY + 1.1, z,
                        (RANDOM.nextDouble() - 0.5) * 2, 0.1 + RANDOM.nextDouble() * 0.1,
                        (RANDOM.nextDouble() - 0.5) * 2);
            }
        }

        // 热浪：玩家附近热源
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
