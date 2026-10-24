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
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.Vec3;

/**
 * 帆船实体：风力驱动，玩家骑乘操控（W/S 调帆、A/D 转舵）。
 *
 * <p><b>艏向约定（SAIL-02）</b>：{@code headingRadians} 就是 MC 的 {@code yaw} 弧度，
 * 艏向 {@code (-sin h, cos h)}，与 {@code Entity#getLookAngle()} 同号。渲染器直接用
 * {@code EntityRenderer} 传来的 yaw，不再做 {@code 180 - yaw} 补偿。
 *
 * <p><b>位移（SAIL-03）</b>：水平位移走 {@link #move(MoverType, Vec3)}，会做碰撞、
 * 推实体、更新 {@code onGround}/{@code horizontalCollision}；垂直方向用
 * {@code setDeltaMovement} 做水面软约束，不再每 tick 两次 {@code setPos} 硬吸。
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

    /** 物理步长（秒）：MC 固定 20 tick/s。 */
    public static final double PHYSICS_DT = 0.05;
    /** 船速上限 14 m/s（≈ 50 km/h，已经偏快了，别再加）。 */
    public static final double MAX_SPEED_MPS = 14.0;
    /** 每 tick 的帆 trim 变化率（SAIL-06：旧值 0.008 → 收满要 6.25 s）。 */
    public static final float TRIM_RATE_PER_TICK = 0.05f;
    /** 采样高度：帆面中心离船体原点约 1.8 格（SAIL-07）。 */
    public static final double SAIL_CENTER_AGL = 1.8;
    /** 水面软约束：每 tick 把垂直速度朝"目标水面 - 当前 y"修正的比例。 */
    private static final double BUOYANCY_GAIN = 0.35;
    /** 水面软约束的最大垂直速度（格/tick），避免掉水时弹射。 */
    private static final double MAX_VERTICAL_STEP = 0.25;
    /**
     * 吃水深度（格）。实体原点在船体**底面**（渲染器的船体盒从 y=0 起），
     * 所以要让水线落在船体 0.45 格高的 2/3 处，原点必须在水平面下 0.30 格。
     *
     * <p>报告的修复建议写的是 {@code targetY = waterY + 0.05}，但那是按"实体原点=水线"算的；
     * 本模组的渲染几何把原点放在船底，直接照抄会让整条船浮在水面之上（吃水 0）。
     * 这里改成 {@code waterY - 0.30}，几何上等价于"吃水 0.3 格"，与 SAIL-09 的目标一致。
     */
    public static final double FLOAT_DRAFT = 0.30;

    private double velX, velZ;
    private double headingRadians;
    /** 首次 tick 之前 {@code headingRadians} 还没从 {@link #getYRot()} 同步过。 */
    private boolean headingInitialized;
    /** 客户端专用：帆 trim 的本地预测值，-1 表示尚未初始化（SAIL-06）。 */
    private float clientTrimPrediction = -1f;

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

    /** 艏向（弧度，等价于 MC yaw）。 */
    public float getHeading() { return (float) headingRadians; }

    /**
     * 设定艏向（弧度，等价于 MC yaw）。
     *
     * <p>{@code SailboatItem} 放置时调用，或由 {@link #readAdditionalSaveData} 之后的
     * 首 tick 从 {@link #getYRot()} 兜底（SAIL-08）。
     */
    public void setHeading(float headingRadians) {
        this.headingRadians = headingRadians;
        this.headingInitialized = true;
        entityData.set(DATA_HEADING, headingRadians);
        setYRot((float) Math.toDegrees(headingRadians));
    }

    @Override
    public void tick() {
        super.tick();
        // 受击晃动和伤害随时间衰减（原版船就这么干的）
        if (getHurtTime() > 0) setHurtTime(getHurtTime() - 1);
        if (getDamage() > 0) setDamage(getDamage() - 1.0f);
        if (level().isClientSide) {
            // 客户端：艏向直接用服务端同步值；位置由原版位置包插值。
            setYRot((float) Math.toDegrees(entityData.get(DATA_HEADING)));
            // SAIL-06 帆的本地预测：trim 完全由玩家自己的输入决定，等一个 RTT 才看到帆动
            // 是很糟的手感。这里只在**客户端渲染层**维护一份预测值（不动 entityData，
            // 避免与服务端同步打架）；松手后每 tick 向权威值收敛 25%。
            // 物理仍完全以服务端为准，预测值不外泄、不参与任何玩法判定。
            Entity rider = getControllingPassenger();
            float authoritative = entityData.get(DATA_SAIL_TRIM);
            if (rider instanceof Player player) {
                if (clientTrimPrediction < 0) clientTrimPrediction = authoritative;
                if (player.zza != 0) {
                    clientTrimPrediction += player.zza * TRIM_RATE_PER_TICK;
                    clientTrimPrediction = Math.max(0, Math.min(1, clientTrimPrediction));
                } else {
                    // 松手后向权威值收敛，误差不会累积
                    clientTrimPrediction += (authoritative - clientTrimPrediction) * 0.25f;
                }
            } else {
                clientTrimPrediction = authoritative;
            }
            return;
        }
        ServerLevel serverLevel = (ServerLevel) level();

        // SAIL-08：从存档/放置以外的地方进来的船（/summon、结构生成）没有 heading，
        // 首 tick 从 MC 的 yaw 初始化，避免"放下去自己转 180°"。
        if (!headingInitialized) {
            headingRadians = Math.toRadians(getYRot());
            entityData.set(DATA_HEADING, (float) headingRadians);
            headingInitialized = true;
        }

        // 浮力：找水面
        BlockPos pos = blockPosition();
        double waterY = findWaterSurface(pos);
        if (Double.isNaN(waterY)) {
            // 不在水上：自由下落 / 搁浅（SAIL-03）。
            // 水平速度快速衰减（每 tick ×0.6，约 5 tick 内停住），并不会把旧速度带进水里；
            // 垂直方向给终端速度 -1.2 格/tick，旧实现是无限累加，掉进虚空会越掉越快。
            velX *= 0.6;
            velZ *= 0.6;
            double dvy = Math.max(getDeltaMovement().y - 0.08, -1.2);
            setDeltaMovement(velX * PHYSICS_DT, dvy, velZ * PHYSICS_DT);
            move(MoverType.SELF, getDeltaMovement());
            return;
        }
        double targetY = waterY - FLOAT_DRAFT // 船底没入水下 0.30 格（SAIL-09）
                + Math.sin(tickCount * 0.08) * 0.03; // 随浪轻微起伏，靠软约束自然阻尼

        // 采样真风：帆面中心高度、有效风（含阵风与本地修正，SAIL-07）
        GameplayWindSample wind = ZenithMinecraftWindApi.sampleGameplay(
                serverLevel, position().add(0, SAIL_CENTER_AGL, 0));
        double windX = 0, windZ = 0;
        if (wind.isTrustedForGameplay()) {
            ZenithVec3 v = wind.effectiveVelocityVector();
            windX = v.x();
            windZ = v.z();
        }

        // 骑乘者输入
        double rudder = 0;
        Entity rider = getControllingPassenger();
        if (rider instanceof Player player) {
            // A/D：xxa > 0 是向左平移；左转 = yaw 减小 = 逆时针，所以取负（SAIL-02 重算）。
            rudder = -player.xxa * 0.9;
            float trim = entityData.get(DATA_SAIL_TRIM);
            trim += player.zza * TRIM_RATE_PER_TICK; // W/S 调帆
            trim = Math.max(0, Math.min(1, trim));
            entityData.set(DATA_SAIL_TRIM, trim);
        }
        float sailTrim = entityData.get(DATA_SAIL_TRIM);

        double dt = PHYSICS_DT;
        double[] acc = SailingPhysics.step(windX, windZ, velX, velZ, headingRadians, sailTrim, rudder);
        velX += acc[0] * dt;
        velZ += acc[1] * dt;
        double spd = Math.hypot(velX, velZ);
        if (spd > MAX_SPEED_MPS) {
            velX *= MAX_SPEED_MPS / spd;
            velZ *= MAX_SPEED_MPS / spd;
            spd = MAX_SPEED_MPS;
        }
        headingRadians = SailingPhysics.updateHeading(headingRadians, rudder, spd, dt);
        entityData.set(DATA_HEADING, (float) headingRadians);
        setYRot((float) Math.toDegrees(headingRadians));

        // 位移走 move()：碰撞、推实体、onGround 状态都正确（SAIL-03）。
        // 旧实现每 tick 两次 setPos，14 m/s × 0.05 s = 0.7 格/tick，能直接穿过一格薄墙。
        double dx = velX * dt;
        double dz = velZ * dt;
        double yErr = targetY - getY();
        double vy = Math.max(-MAX_VERTICAL_STEP,
                Math.min(MAX_VERTICAL_STEP, yErr * BUOYANCY_GAIN));
        // 搁浅保护（SAIL-03）：水平几乎停住、又明显沉在目标水线以下、碰撞箱底下就顶着方块
        // → 给一点上抬，让船能蹭过浅滩，而不是永远卡在离水面十几厘米的位置。
        // 阈值 0.05 是为了避开正常随浪起伏（那时 yErr 在 0 附近抖动，幅度 ±0.03）。
        if (Math.hypot(velX, velZ) < 0.5 && yErr > 0.05 && hasCollisionBelow()) {
            vy = Math.max(vy, 0.08);
        }

        Vec3 before = position();
        move(MoverType.SELF, new Vec3(dx, vy, dz));
        Vec3 moved = position().subtract(before);

        // 撞到方块：吃掉被挡住的那部分速度，避免贴墙时"顶着墙还在加速"
        if (Math.abs(moved.x - dx) > 1e-4) velX = moved.x / dt;
        if (Math.abs(moved.z - dz) > 1e-4) velZ = moved.z / dt;
        // 把船速写回 deltaMovement（单位是格/tick），供原版推实体/落水判定使用
        setDeltaMovement(velX * dt, moved.y, velZ * dt);
    }

    /** 碰撞箱正下方 0.05 格处是否有实心方块（搁浅判定用，比"往下看两格"精确）。 */
    private boolean hasCollisionBelow() {
        BlockPos below = BlockPos.containing(getX(), getBoundingBox().minY - 0.05, getZ());
        return !level().getBlockState(below).getCollisionShape(level(), below).isEmpty();
    }

    /**
     * 找水面高度。
     * <p>返回的是**水面 y**（连续坐标）。源水 {@code getOwnHeight()≈0.875}，所以水面 = {@code y + ownHeight}；
     * 旧式 {@code y + 1 - ownHeight} 把源水算成 {@code y+0.125}，船体因此半沉 0.75 格（SAIL-09）。
     * <p>搜索窗 ±5 格：覆盖 1 格浅水、以及被浪推高一格的情形；旧实现只有 [-3,+2]。
     */
    private double findWaterSurface(BlockPos pos) {
        for (int dy = 5; dy >= -5; dy--) {
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
        if (tag.contains("Heading")) {
            headingRadians = tag.getDouble("Heading");
            headingInitialized = true;
        }
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

    /** 帆 trim（0..1）。客户端返回本地预测值，服务端返回权威值。 */
    public float getSailTrim() {
        return (level() != null && level().isClientSide && clientTrimPrediction >= 0)
                ? clientTrimPrediction
                : entityData.get(DATA_SAIL_TRIM);
    }
}
