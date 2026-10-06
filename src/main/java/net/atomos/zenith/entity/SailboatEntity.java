package net.atomos.zenith.entity;

import net.atomos.zenith.api.GameplayWindSample;
import net.atomos.zenith.api.ZenithVec3;
import net.atomos.zenith.api.minecraft.ZenithMinecraftWindApi;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.Vec3;

/**
 * 帆船实体：风力驱动，玩家骑乘操控（W/S 调帆、A/D 转舵）。
 */
public class SailboatEntity extends Entity {
    private static final EntityDataAccessor<Float> DATA_SAIL_TRIM =
            SynchedEntityData.defineId(SailboatEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Float> DATA_HEADING =
            SynchedEntityData.defineId(SailboatEntity.class, EntityDataSerializers.FLOAT);

    private double velX, velZ;
    private double headingRadians;

    public SailboatEntity(EntityType<? extends SailboatEntity> type, Level level) {
        super(type, level);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        builder.define(DATA_SAIL_TRIM, 0.7f);
        builder.define(DATA_HEADING, 0f);
    }

    @Override
    public void tick() {
        super.tick();
        if (level().isClientSide) {
            // 客户端：插值跟随服务端同步的艏向
            setYRot((float) Math.toDegrees(entityData.get(DATA_HEADING)));
            return;
        }
        ServerLevel serverLevel = (ServerLevel) level();

        // 浮力：找水面
        BlockPos pos = blockPosition();
        double waterY = findWaterSurface(pos);
        if (Double.isNaN(waterY)) {
            // 不在水上：下沉/搁浅
            setDeltaMovement(getDeltaMovement().add(0, -0.08, 0));
            move(net.minecraft.world.entity.MoverType.SELF, getDeltaMovement());
            return;
        }
        double targetY = waterY + 0.15 + Math.sin(tickCount * 0.08) * 0.05;
        setPos(getX(), targetY, getZ());

        // 采样真风
        GameplayWindSample wind = ZenithMinecraftWindApi.sampleGameplay(
                serverLevel, position().add(0, 1, 0));
        double windX = 0, windZ = 0;
        if (wind.isTrustedForGameplay()) {
            ZenithVec3 v = wind.meanVelocityVector();
            windX = v.x();
            windZ = v.z();
        }

        // 骑乘者输入
        double rudder = 0;
        Entity rider = getControllingPassenger();
        if (rider instanceof Player player) {
            rudder = -player.xxa * 0.9; // A/D
            float trim = entityData.get(DATA_SAIL_TRIM);
            trim += player.zza * 0.008f; // W/S 调帆
            trim = Math.max(0, Math.min(1, trim));
            entityData.set(DATA_SAIL_TRIM, trim);
        }
        float sailTrim = entityData.get(DATA_SAIL_TRIM);

        double dt = 0.05;
        double[] acc = SailingPhysics.step(windX, windZ, velX, velZ, headingRadians, sailTrim, rudder);
        velX += acc[0] * dt;
        velZ += acc[1] * dt;
        // 水阻尼上限
        double spd = Math.hypot(velX, velZ);
        if (spd > 14) {
            velX *= 14 / spd;
            velZ *= 14 / spd;
        }
        headingRadians = SailingPhysics.updateHeading(headingRadians, rudder, spd, dt);
        entityData.set(DATA_HEADING, (float) headingRadians);

        setPos(getX() + velX * dt, getY(), getZ() + velZ * dt);
        setYRot((float) Math.toDegrees(headingRadians));
    }

    private double findWaterSurface(BlockPos pos) {
        for (int dy = 2; dy >= -3; dy--) {
            BlockPos p = pos.offset(0, dy, 0);
            if (level().getFluidState(p).is(Fluids.WATER)) {
                // 向上找到水面
                while (level().getFluidState(p.above()).is(Fluids.WATER)) p = p.above();
                return p.getY() + 1 - level().getFluidState(p).getOwnHeight();
            }
        }
        return Double.NaN;
    }

    @Override
    public InteractionResult interact(Player player, InteractionHand hand) {
        if (!level().isClientSide && getPassengers().isEmpty()) {
            player.startRiding(this);
            return InteractionResult.SUCCESS;
        }
        return InteractionResult.PASS;
    }

    @Override
    public net.minecraft.world.entity.LivingEntity getControllingPassenger() {
        Entity p = getFirstPassenger();
        return p instanceof net.minecraft.world.entity.LivingEntity le ? le : null;
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
        headingRadians = tag.getDouble("Heading");
        velX = tag.getDouble("VelX");
        velZ = tag.getDouble("VelZ");
        entityData.set(DATA_SAIL_TRIM, tag.getFloat("SailTrim"));
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
        tag.putDouble("Heading", headingRadians);
        tag.putDouble("VelX", velX);
        tag.putDouble("VelZ", velZ);
        tag.putFloat("SailTrim", entityData.get(DATA_SAIL_TRIM));
    }

    @Override
    public boolean isPushable() { return true; }

    @Override
    public boolean canBeCollidedWith() { return true; }

    public float getSailTrim() { return entityData.get(DATA_SAIL_TRIM); }
}
