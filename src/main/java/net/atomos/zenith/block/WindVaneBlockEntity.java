package net.atomos.zenith.block;

import net.atomos.zenith.api.ZenithVec3;
import net.atomos.zenith.api.client.minecraft.ZenithMinecraftClientWindApi;
import net.atomos.zenith.api.minecraft.ZenithMinecraftWindApi;
import net.atomos.zenith.api.GameplayWindSample;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * 风向标方块实体：服务端每 20 tick 采样风向并同步角度（度），
 * 客户端每 tick 用本地风做平滑旋转。
 */
public class WindVaneBlockEntity extends BlockEntity {
    /** 箭头 yaw（度），0 = 朝南（+Z），与 atan2 约定一致。 */
    private float arrowYawDegrees = 0;
    private int tickCounter = 0;

    public WindVaneBlockEntity(BlockPos pos, BlockState state) {
        super(ZenithBlocks.WIND_VANE_BE.get(), pos, state);
    }

    public void tick() {
        if (level == null) return;
        if (++tickCounter % 20 != 0) return;
        double yaw;
        if (level instanceof ServerLevel serverLevel) {
            GameplayWindSample wind = ZenithMinecraftWindApi.sampleGameplay(
                    serverLevel, Vec3.atCenterOf(getBlockPos().above()));
            if (!wind.isTrustedForGameplay()) return;
            ZenithVec3 v = wind.meanVelocityVector();
            yaw = Math.toDegrees(Math.atan2(v.x(), v.z()));
        } else {
            var s = ZenithMinecraftClientWindApi.sample(Vec3.atCenterOf(getBlockPos().above()),
                    net.atomos.zenith.api.SamplePolicy.CLIENT_LOCAL_PREFERRED);
            if (!s.hasFlow()) return;
            ZenithVec3 v = s.meanVelocityVector();
            yaw = Math.toDegrees(Math.atan2(v.x(), v.z()));
        }
        // 角度平滑
        float target = (float) yaw;
        float delta = target - arrowYawDegrees;
        while (delta > 180) delta -= 360;
        while (delta < -180) delta += 360;
        arrowYawDegrees += delta * 0.25f;
        if (level.isClientSide) {
            // 客户端本地平滑即可；服务端角度通过方块实体同步包下发（简化：不同步，客户端自采样）
        }
    }

    public float getArrowYawDegrees() {
        return arrowYawDegrees;
    }
}
