package net.atomos.zenith.block;

import net.atomos.zenith.api.GameplayWindSample;
import net.atomos.zenith.api.minecraft.ZenithMinecraftWindApi;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/** 涡轮探针方块实体：风速 → 红石 0–15（每 10 tick 更新）。 */
public class WindTurbineProbeBlockEntity extends BlockEntity {
    private int tickCounter = 0;

    public WindTurbineProbeBlockEntity(BlockPos pos, BlockState state) {
        super(ZenithBlocks.TURBINE_PROBE_BE.get(), pos, state);
    }

    public void tick() {
        if (!(level instanceof ServerLevel serverLevel)) return;
        if (++tickCounter % 10 != 0) return;
        GameplayWindSample wind = ZenithMinecraftWindApi.sampleGameplay(
                serverLevel, Vec3.atCenterOf(getBlockPos().above()));
        int power = 0;
        if (wind.isTrustedForGameplay()) {
            // 18 m/s → 满格
            power = (int) Math.min(15, Math.round(wind.effectiveSpeedMetersPerSecond() / 18.0 * 15));
        }
        BlockState state = getBlockState();
        if (state.getValue(WindTurbineProbeBlock.POWER) != power) {
            level.setBlock(getBlockPos(), state.setValue(WindTurbineProbeBlock.POWER, power), 3);
            level.updateNeighborsAt(getBlockPos(), state.getBlock());
        }
    }
}
