package net.atomos.zenith.block;

import net.atomos.zenith.api.GameplayWindSample;
import net.atomos.zenith.api.minecraft.ZenithMinecraftWindApi;
import net.atomos.zenith.api.ZenithVec3;
import net.atomos.zenith.wind.JetSourceRegistry;
import net.atomos.zenith.wind.LocalFlowSolver;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/** 风道方块实体：采样本地风，沿朝向接力。 */
public class DuctBlockEntity extends BlockEntity {
    private int tickCounter = 0;

    public DuctBlockEntity(BlockPos pos, BlockState state) {
        super(ZenithBlocks.DUCT_BE.get(), pos, state);
    }

    public void tick() {
        if (!(level instanceof ServerLevel serverLevel)) return;
        if (++tickCounter % 20 != 0) return;
        BlockPos p = getBlockPos();
        Direction facing = getBlockState().getValue(DuctBlock.FACING);
        GameplayWindSample wind = ZenithMinecraftWindApi.sampleGameplay(
                serverLevel, p.above());
        double ox = p.getX() + 0.5 + facing.getStepX() * 0.6;
        double oy = p.getY() + 0.5 + facing.getStepY() * 0.6;
        double oz = p.getZ() + 0.5 + facing.getStepZ() * 0.6;
        if (!wind.isTrustedForGameplay()) {
            JetSourceRegistry.unregisterAt(ox, oy, oz);
            return;
        }
        ZenithVec3 v = wind.meanVelocityVector();
        double speed = v.length();
        if (speed < 0.8) {
            JetSourceRegistry.unregisterAt(ox, oy, oz);
            return;
        }
        // 沿风道朝向接力（取风速大小，方向按风道）
        JetSourceRegistry.register(new LocalFlowSolver.Jet(
                ox, oy, oz,
                facing.getStepX(), facing.getStepY(), facing.getStepZ(),
                (float) Math.min(14, speed * 1.15), 1.6, 10));
    }

    @Override
    public void setRemoved() {
        BlockPos p = getBlockPos();
        Direction facing = getBlockState().getValue(DuctBlock.FACING);
        JetSourceRegistry.unregisterAt(
                p.getX() + 0.5 + facing.getStepX() * 0.6,
                p.getY() + 0.5 + facing.getStepY() * 0.6,
                p.getZ() + 0.5 + facing.getStepZ() * 0.6);
        super.setRemoved();
    }
}
