package net.atomos.zenith.block;

import net.atomos.zenith.api.ZenithVec3;
import net.atomos.zenith.api.client.minecraft.ZenithMinecraftClientWindApi;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * 风向标方块实体：客户端每 tick 采样本地风，箭头慢慢转过去。
 * 服务端不算——算出来也没人读，不跨端同步。
 * 注意：箭头指向风的去向（气象惯例是来向，这里保持去向）。
 */
public class WindVaneBlockEntity extends BlockEntity {
    /** 箭头 yaw（度），0 = 朝南（+Z），与 atan2 约定一致。 */
    private float arrowYawDegrees = 0;
    /** 上一 tick 的 yaw，给渲染插值用。 */
    private float prevYawDegrees = 0;

    public WindVaneBlockEntity(BlockPos pos, BlockState state) {
        super(ZenithBlocks.WIND_VANE_BE.get(), pos, state);
    }

    public void tick() {
        if (level == null || !level.isClientSide) return;
        prevYawDegrees = arrowYawDegrees;
        var s = ZenithMinecraftClientWindApi.sample(Vec3.atCenterOf(getBlockPos().above()),
                net.atomos.zenith.api.SamplePolicy.CLIENT_LOCAL_PREFERRED);
        if (!s.hasFlow()) return;
        ZenithVec3 v = s.meanVelocityVector();
        float target = (float) Math.toDegrees(Math.atan2(v.x(), v.z()));
        // 角度平滑：每 tick 靠过去一小步，转起来不抽
        float delta = target - arrowYawDegrees;
        while (delta > 180) delta -= 360;
        while (delta < -180) delta += 360;
        arrowYawDegrees += delta * 0.1f;
    }

    public float getArrowYawDegrees() {
        return arrowYawDegrees;
    }

    public float getPrevYawDegrees() {
        return prevYawDegrees;
    }
}
