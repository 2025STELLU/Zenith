package net.atomos.zenith.entity;

import net.atomos.zenith.api.GameplayWindSample;
import net.atomos.zenith.api.ZenithVec3;
import net.atomos.zenith.api.minecraft.ZenithMinecraftWindApi;
import net.atomos.zenith.item.ZenithItems;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
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
    // 受击状态：照抄原版船（VehicleEntity）那套，客户端靠它抖
    private static final EntityDataAccessor<Float> DATA_DAMAGE =
            SynchedEntityData.defineId(SailboatEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Integer> DATA_HURT_TIME =
            SynchedEntityData.defineId(SailboatEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> DATA_HURT_DIR =
            SynchedEntityData.defineId(SailboatEntity.class, EntityDataSerializers.INT);

    /** 拆船阈值：和原版船一样，伤害累积超过 40 才真拆（空拳约 4 下） */
    private static final float BREAK_DAMAGE = 40.0f;

    private double velX, velZ;
    private double headingRadians;

    public SailboatEntity(EntityType<? extends SailboatEntity> type, Level level) {
        super(type, level);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        builder.define(DATA_SAIL_TRIM, 0.7f);
        builder.define(DATA_HEADING, 0f);
        builder.define(DATA_DAMAGE, 0f);
        builder.define(DATA_HURT_TIME, 0);
        builder.define(DATA_HURT_DIR, 1);
    }

    public float getDamage() { return entityData.get(DATA_DAMAGE); }
    public void setDamage(float damage) { entityData.set(DATA_DAMAGE, damage); }
    public int getHurtTime() { return entityData.get(DATA_HURT_TIME); }
    public void setHurtTime(int time) { entityData.set(DATA_HURT_TIME, time); }
    public int getHurtDir() { return entityData.get(DATA_HURT_DIR); }
    public void setHurtDir(int dir) { entityData.set(DATA_HURT_DIR, dir); }

    @Override
    public void tick() {
        super.tick();
        // 受击晃动和伤害随时间衰减（原版船就这么干的）
        if (getHurtTime() > 0) setHurtTime(getHurtTime() - 1);
        if (getDamage() > 0) setDamage(getDamage() - 1.0f);
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
        // 船速上限 14 m/s
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

    /**
     * 找水面高度。
     * <p>返回的是**水面 y**（连续坐标）。源水 {@code getOwnHeight()≈0.875}，所以水面 = {@code y + ownHeight}；
     * 旧式 {@code y + 1 - ownHeight} 把源水算成 {@code y+0.125}，船体因此半沉 0.75 格（SAIL-09）。
     */
    private double findWaterSurface(BlockPos pos) {
        for (int dy = 2; dy >= -3; dy--) {
            BlockPos p = pos.offset(0, dy, 0);
            if (level().getFluidState(p).is(Fluids.WATER)) {
                while (level().getFluidState(p.above()).is(Fluids.WATER)) p = p.above();
                double ownHeight = level().getFluidState(p).getOwnHeight();
                if (ownHeight <= 0) ownHeight = 8.0 / 9.0; // 兜底：退化值按源水算
                return p.getY() + ownHeight;
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

    // 准星射线得能选中船，不然右键上不了船
    @Override
    public boolean isPickable() { return true; }

    // 受击：照抄原版船的逻辑——伤害累积，攒够了才拆，创造模式不掉东西
    @Override
    public boolean hurt(DamageSource source, float amount) {
        if (level().isClientSide || isRemoved()) return true;
        if (isInvulnerableTo(source)) return false;
        setHurtDir(-getHurtDir());
        setHurtTime(10);
        markHurt();
        setDamage(getDamage() + amount * 10.0f);
        boolean creative = source.getEntity() instanceof Player player
                && player.getAbilities().instabuild;
        if (!creative && getDamage() <= BREAK_DAMAGE) return true; // 阈值以下：只晃，不拆
        if (!creative) {
            spawnAtLocation(ZenithItems.SAILBOAT.get());
        }
        discard();
        return true;
    }

    // 创造模式中键选取：给帆船物品
    @Override
    public ItemStack getPickResult() {
        return new ItemStack(ZenithItems.SAILBOAT.get());
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
