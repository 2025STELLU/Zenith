package net.atomos.zenith.client;

import net.atomos.zenith.particle.ZenithParticles;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import java.util.Random;

/**
 * 粒子风控（客户端）：每 tick 按本地风况生成环境粒子。
 * <ul>
 *   <li>风迹：风速 &gt; 3 m/s 时在玩家周围生成</li>
 *   <li>落叶：森林生物群系 + 有风时</li>
 *   <li>扬尘：阵风/高湍流贴地</li>
 *   <li>水沫：风暴/台风快照活跃时在玩家附近生成（模拟雨带水沫）</li>
 *   <li>热浪：熔岩/火焰/营火上方</li>
 * </ul>
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
        if (++tickCounter % 2 != 0) return; // 每 2 tick 一次

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
            int count = Math.min(6, (int) (speed / 3));
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

        // 落叶：森林附近
        if (tickCounter % 10 == 0 && speed > 1.5) {
            BlockPos bp = new BlockPos((int) px, (int) py, (int) pz);
            var biome = level.getBiome(bp).value();
            String bn = biome.toString().toLowerCase();
            if (bn.contains("forest") || bn.contains("jungle") || bn.contains("birch")) {
                double x = px + (RANDOM.nextDouble() - 0.5) * 24;
                double z = pz + (RANDOM.nextDouble() - 0.5) * 24;
                level.addParticle(ZenithParticles.LEAF.get(), x, py + 4 + RANDOM.nextDouble() * 4, z, 0, 0, 0);
            }
        }

        // 水沫：风暴/台风
        if (stormBoost > 0.45 && tickCounter % 4 == 0) {
            for (int i = 0; i < 3; i++) {
                double x = px + (RANDOM.nextDouble() - 0.5) * 30;
                double z = pz + (RANDOM.nextDouble() - 0.5) * 30;
                double y = py + RANDOM.nextDouble() * 6;
                level.addParticle(ZenithParticles.SPRAY.get(), x, y, z,
                        (RANDOM.nextDouble() - 0.5) * 2, 1 + RANDOM.nextDouble() * 2,
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
