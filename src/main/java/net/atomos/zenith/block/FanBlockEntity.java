package net.atomos.zenith.block;

import net.atomos.zenith.wind.JetSourceRegistry;
import net.atomos.zenith.wind.LocalFlowSolver;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 风扇方块实体：每 20 tick 向射流注册表登记定向射流。
 *
 * 档位规则：blockstate 的 SPEED 永远是用户档位；红石通电只覆盖"有效输出"为满档，
 * 断电后自动回到用户档位，不用记两份。
 */
public class FanBlockEntity extends BlockEntity {
    private boolean redstoneOverride = false;
    private int tickCounter = 0;

    public FanBlockEntity(BlockPos pos, BlockState state) {
        super(ZenithBlocks.FAN_BE.get(), pos, state);
    }

    public void setRedstoneOverride(boolean powered) {
        if (this.redstoneOverride != powered) {
            this.redstoneOverride = powered;
            this.tickCounter = 19; // 下一 tick 立刻按新档位登记，不用干等 20 tick
        }
    }

    @Override
    public void onLoad() {
        super.onLoad();
        // 区块重载不会重放 neighborChanged，通电的风扇重进会掉回用户档位，这里补一次
        if (level != null) {
            this.redstoneOverride = level.hasNeighborSignal(getBlockPos());
        }
    }

    /** 出风口坐标：注册和注销都走这里。写岔一次，就漏一个关不掉的永久射流。 */
    private double[] outletPos() {
        BlockPos p = getBlockPos();
        Direction facing = getBlockState().getValue(FanBlock.FACING);
        return new double[]{
                p.getX() + 0.5 + facing.getStepX() * 0.6,
                p.getY() + 0.5 + facing.getStepY() * 0.6,
                p.getZ() + 0.5 + facing.getStepZ() * 0.6};
    }

    public void tick() {
        if (level == null || level.isClientSide) return;
        if (++tickCounter % 20 != 0) return;
        BlockState state = getBlockState();
        int speedLevel = redstoneOverride ? 3 : state.getValue(FanBlock.SPEED);
        float speed = FanBlock.SPEED_MPS[speedLevel];
        Direction facing = state.getValue(FanBlock.FACING);
        ResourceKey<Level> dim = level.dimension();
        double[] o = outletPos();
        if (speed <= 0) {
            JetSourceRegistry.unregisterAt(dim, o[0], o[1], o[2]);
            return;
        }
        float length = 10 + speedLevel * 8;
        JetSourceRegistry.register(dim, new LocalFlowSolver.Jet(
                o[0], o[1], o[2],
                facing.getStepX(), facing.getStepY(), facing.getStepZ(),
                speed, 2.0, length));
    }

    @Override
    public void setRemoved() {
        // 拆除也要清射流，坐标必须跟注册时完全一致（出风口），不然清不掉
        if (level != null) {
            double[] o = outletPos();
            JetSourceRegistry.unregisterAt(level.dimension(), o[0], o[1], o[2]);
        }
        super.setRemoved();
    }
}
