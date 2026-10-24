package net.atomos.zenith.block;

import net.atomos.zenith.api.GameplayWindSample;
import net.atomos.zenith.api.minecraft.ZenithMinecraftWindApi;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * 涡轮探针方块实体：风速 → 红石 0–15（每 10 tick 更新）。
 *
 * <p><b>BLK-04</b>：旧实现读 {@code effectiveSpeedMetersPerSecond()}，那是
 * {@code |mean + gust + localModifier|} 三个不同物理量之和：
 * <ul>
 *   <li>{@code gust} 是阵风**峰值**，不是持续功率输入；</li>
 *   <li>{@code localModifier} 是 L2 的绕流/遮蔽/伪上升修正，不是"新增能量"——
 *       一个 12 m/s 的风扇对着探针吹，L2 会把它算成 23 m/s，红石直接顶满；</li>
 *   <li>阵风每 tick 抖动，风力机功率 ∝ v³，输出会跟着跳。</li>
 * </ul>
 *
 * <p>现在：
 * <ol>
 *   <li><b>功率输入</b>取 {@code meanVelocity} 的水平模长，再对最近
 *       {@value #AVERAGING_TICKS} 次读数做滑动平均（≈10 秒）。风力机的转子惯量本来
 *       就滤掉了秒级阵风，滑动平均是物理上正确的做法，也让红石信号稳定。</li>
 *   <li><b>过载信号</b>：瞬时阵风超过持续风 {@value #OVERLOAD_RATIO} 倍时，
 *       把输出再抬高 1 格（不超过 15）。这是"阵风单独作为过载指示"的落地，
 *       而不是把阵风混进持续功率里。</li>
 * </ol>
 *
 * <p>注意仍然读不到"这个探针周围有风扇射流"的加成就——射流属于
 * {@code localModifier}，按 BLK-04 的结论不该进功率输入。想让风道给涡轮加速，
 * 应该用 {@code pipelineVelocityVector()} 的语义另做方块（BLK-07 的接力已覆盖风道本身）。
 */
public class WindTurbineProbeBlockEntity extends BlockEntity {
    /** 读数周期（tick）。 */
    private static final int SAMPLE_INTERVAL_TICKS = 10;
    /** 滑动平均窗口（tick）。10 秒。 */
    private static final int AVERAGING_TICKS = 200;
    /** 满格风速（m/s）：持续风到 18 m/s 输出 15。 */
    private static final double FULL_SCALE_MPS = 18.0;
    /** 阵风 / 持续风超过这个比值算一次过载。 */
    private static final double OVERLOAD_RATIO = 1.6;

    private int tickCounter = 0;
    /** 滑动平均的环形缓冲：每 {@value #SAMPLE_INTERVAL_TICKS} tick 写一格。 */
    private final double[] history = new double[AVERAGING_TICKS / SAMPLE_INTERVAL_TICKS];
    private int historyIndex = 0;
    private int historyCount = 0;

    public WindTurbineProbeBlockEntity(BlockPos pos, BlockState state) {
        super(ZenithBlocks.TURBINE_PROBE_BE.get(), pos, state);
    }

    public void tick() {
        if (!(level instanceof ServerLevel serverLevel)) return;
        if (++tickCounter % SAMPLE_INTERVAL_TICKS != 0) return;
        GameplayWindSample wind = ZenithMinecraftWindApi.sampleGameplay(
                serverLevel, Vec3.atCenterOf(getBlockPos().above()));

        int power = 0;
        if (wind.isTrustedForGameplay()) {
            // 持续风：只取平均风的水平分量（垂直分量对水平轴风力机不做功）
            double sustained = wind.meanVelocityVector().horizontalLength();
            history[historyIndex] = sustained;
            historyIndex = (historyIndex + 1) % history.length;
            if (historyCount < history.length) historyCount++;
            double avg = 0;
            for (int i = 0; i < historyCount; i++) avg += history[i];
            avg /= historyCount;

            power = (int) Math.min(15, Math.round(avg / FULL_SCALE_MPS * 15));

            // 过载：阵风显著高于持续风时额外抬 1 格（峰值指示，不进平均）
            double gust = wind.gustVelocityVector().horizontalLength();
            if (gust > avg * OVERLOAD_RATIO && power < 15) power++;
        } else {
            // 不可信：清空历史，避免恢复后还带着旧世界的平均
            historyCount = 0;
            historyIndex = 0;
        }

        BlockState state = getBlockState();
        if (state.getValue(WindTurbineProbeBlock.POWER) != power) {
            level.setBlock(getBlockPos(), state.setValue(WindTurbineProbeBlock.POWER, power), 3);
            level.updateNeighborsAt(getBlockPos(), state.getBlock());
        }
    }
}
