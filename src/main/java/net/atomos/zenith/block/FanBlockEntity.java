package net.atomos.zenith.block;

import net.atomos.zenith.wind.JetSourceRegistry;
import net.atomos.zenith.wind.LocalFlowSolver;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/** 风扇方块实体：每 20 tick 向射流注册表登记定向射流。 */
public class FanBlockEntity extends BlockEntity {
    private boolean redstoneOverride = false;
    private int tickCounter = 0;

    public FanBlockEntity(BlockPos pos, BlockState state) {
        super(ZenithBlocks.FAN_BE.get(), pos, state);
    }

    public void setRedstoneOverride(boolean powered) {
        this.redstoneOverride = powered;
    }

    public void tick() {
        if (level == null || level.isClientSide) return;
        if (++tickCounter % 20 != 0) return;
        BlockState state = getBlockState();
        int speedLevel = redstoneOverride ? 3 : state.getValue(FanBlock.SPEED);
        float speed = FanBlock.SPEED_MPS[speedLevel];
        Direction facing = state.getValue(FanBlock.FACING);
        BlockPos p = getBlockPos();
        if (speed <= 0) {
            JetSourceRegistry.unregisterAt(p.getX() + 0.5, p.getY() + 0.5, p.getZ() + 0.5);
            return;
        }
        double ox = p.getX() + 0.5 + facing.getStepX() * 0.6;
        double oy = p.getY() + 0.5 + facing.getStepY() * 0.6;
        double oz = p.getZ() + 0.5 + facing.getStepZ() * 0.6;
        float length = 10 + speedLevel * 8;
        JetSourceRegistry.register(new LocalFlowSolver.Jet(
                ox, oy, oz,
                facing.getStepX(), facing.getStepY(), facing.getStepZ(),
                speed, 2.0, length));
    }

    @Override
    public void setRemoved() {
        BlockPos p = getBlockPos();
        JetSourceRegistry.unregisterAt(p.getX() + 0.5, p.getY() + 0.5, p.getZ() + 0.5);
        super.setRemoved();
    }
}
