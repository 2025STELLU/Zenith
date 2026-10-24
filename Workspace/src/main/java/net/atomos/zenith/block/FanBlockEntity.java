package net.atomos.zenith.block;

import net.atomos.zenith.api.GameplayWindSample;
import net.atomos.zenith.api.ZenithVec3;
import net.atomos.zenith.api.minecraft.ZenithMinecraftWindApi;
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
 * <p>档位规则：blockstate 的 SPEED 永远是用户档位；红石通电只覆盖"有效输出"为满档，
 * 断电后自动回到用户档位，不用记两份。
 *
 * <p><b>串联（BLK-07）</b>：进风口处如果已经有上游吹来的气流，取
 * {@code max(用户档位, 上游沿轴风速)} 作为出口风速——风扇 A 吹风扇 B，B 就按 A 的风继续吹。
 * 用 {@code max} 而不是相加，是为了让"多台串联"收敛到一个有限值而不是互相叠加发散。
 * 采样用 {@link GameplayWindSample#pipelineVelocityVector()}（含射流、不含阵风），
 * 否则读到的永远是环境平均风，风扇永远接不上力。
 */
public class FanBlockEntity extends BlockEntity {
    /**
     * 射流长度 = 出口速度 × 这个秒数。真实射流的射程正比于出口速度（{@code L ∝ v·D}，
     * 口径 D 固定时 {@code L ∝ v}），所以这里**必须是纯正比**、不能加常数偏移：
     * 旧实现 {@code 10 + 8·level} 让 4/8/12 m/s 对应 18/26/34 格，{@code L/v} 从 4.5 掉到 2.83，
     * 高挡位"加风速"几乎不"加射程"（BLK-08）。现在 {@code L/v} 恒为 3.0 s。
     */
    public static final double JET_LENGTH_SECONDS = 3.0;
    /** 串联后出口风速上限（与风道一致）。 */
    public static final double MAX_OUTLET_MPS = 14.0;

    private boolean redstoneOverride = false;
    private boolean redstoneDirty = false; // 区块刚加载完，首个 tick 再读邻居红石
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
        // 区块加载中不碰 level 读邻居：相邻区块可能还没就绪，
        // 跨区块读可能触发额外加载或拿到不完整数据，留到首个 tick 再读
        this.redstoneDirty = true;
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

    /** 进风口坐标（出风口的反向偏移），用于读上游射流。 */
    private double[] inletPos() {
        BlockPos p = getBlockPos();
        Direction facing = getBlockState().getValue(FanBlock.FACING);
        return new double[]{
                p.getX() + 0.5 - facing.getStepX() * 0.6,
                p.getY() + 0.5 - facing.getStepY() * 0.6,
                p.getZ() + 0.5 - facing.getStepZ() * 0.6};
    }

    /**
     * 进风口处沿本风扇朝向的既有风速（m/s）。
     *
     * <p>只取沿轴分量：上游风扇对着我吹才有效，横着飘过来的环境风不该被当成动力
     * （否则一台放在风口的风扇会凭空加速）。读不到可信数据时返回 0。
     */
    private double upstreamSpeedAlongAxis(Level level, Direction facing) {
        if (!(level instanceof net.minecraft.server.level.ServerLevel serverLevel)) return 0;
        double[] in = inletPos();
        GameplayWindSample sample = ZenithMinecraftWindApi.sampleGameplay(
                serverLevel, new BlockPos((int) Math.floor(in[0]),
                        (int) Math.floor(in[1]), (int) Math.floor(in[2])));
        if (sample == null || !sample.isTrustedForGameplay()) return 0;
        ZenithVec3 v = sample.pipelineVelocityVector();
        double along = v.x() * facing.getStepX() + v.y() * facing.getStepY() + v.z() * facing.getStepZ();
        return Math.max(0, along);
    }

    public void tick() {
        if (level == null || level.isClientSide) return;
        if (redstoneDirty) {
            // 首个服务端 tick：区块都就绪了，这时读邻居红石才安全
            redstoneDirty = false;
            setRedstoneOverride(level.hasNeighborSignal(getBlockPos()));
        }
        if (++tickCounter % 20 != 0) return;
        BlockState state = getBlockState();
        int speedLevel = redstoneOverride ? FanBlock.maxSpeedLevel() : state.getValue(FanBlock.SPEED);
        float userSpeed = FanBlock.speedMps(speedLevel);
        Direction facing = state.getValue(FanBlock.FACING);
        ResourceKey<Level> dim = level.dimension();
        double[] o = outletPos();

        // 串联：上游沿轴风速与用户档位取 max（不相加，避免闭环发散）
        double upstream = upstreamSpeedAlongAxis(level, facing);
        double speed = Math.min(MAX_OUTLET_MPS, Math.max(userSpeed, upstream));

        if (speed <= 0) {
            JetSourceRegistry.unregisterAt(dim, o[0], o[1], o[2]);
            return;
        }
        float length = (float) (JET_LENGTH_SECONDS * speed);
        JetSourceRegistry.register(dim, new LocalFlowSolver.Jet(
                o[0], o[1], o[2],
                facing.getStepX(), facing.getStepY(), facing.getStepZ(),
                (float) speed, 2.0, length));
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
