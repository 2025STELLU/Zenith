package net.atomos.zenith.block;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.phys.BlockHitResult;
import org.jetbrains.annotations.Nullable;

/**
 * 风扇：定向风源。右键切换档位（0–3 档 → 0/4/8/12 m/s），红石信号可强制满档。
 */
public class FanBlock extends Block implements EntityBlock {
    public static final DirectionProperty FACING = BlockStateProperties.FACING;
    public static final IntegerProperty SPEED = IntegerProperty.create("speed", 0, 3);
    public static final float[] SPEED_MPS = {0f, 4f, 8f, 12f};

    public FanBlock() {
        super(ZenithBlocks.fanProps());
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH).setValue(SPEED, 0));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, SPEED);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext ctx) {
        return defaultBlockState().setValue(FACING, ctx.getNearestLookingDirection().getOpposite());
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos,
                                               Player player, BlockHitResult hit) {
        if (!level.isClientSide) {
            int next = (state.getValue(SPEED) + 1) % 4;
            level.setBlock(pos, state.setValue(SPEED, next), 3);
            player.displayClientMessage(Component.translatable("message.zenith.fan_speed",
                    String.format("%.0f m/s", SPEED_MPS[next])), true);
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    @Override
    public void neighborChanged(BlockState state, Level level, BlockPos pos, Block block,
                                BlockPos fromPos, boolean isMoving) {
        boolean powered = level.hasNeighborSignal(pos);
        int want = powered ? 3 : state.getValue(SPEED);
        BlockEntity be = level.getBlockEntity(pos);
        if (be instanceof FanBlockEntity fan) fan.setRedstoneOverride(powered);
        if (want != state.getValue(SPEED)) {
            level.setBlock(pos, state.setValue(SPEED, want), 3);
        }
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new FanBlockEntity(pos, state);
    }

    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state,
                                                                   BlockEntityType<T> type) {
        return level.isClientSide ? null : (lvl, pos, st, be) -> {
            if (be instanceof FanBlockEntity fan) fan.tick();
        };
    }
}
