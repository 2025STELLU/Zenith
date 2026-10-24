package net.atomos.zenith.network.packet;

import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.DecoderException;
import net.atomos.zenith.ZenithMod;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 服务端 → 客户端：粗风场同步。
 *
 * <p>以玩家为中心、{@value #DEFAULT_CELL_SIZE_BLOCKS} 格间距的 13×13 网格。
 * 半径 {@value #DEFAULT_RADIUS_CELLS} 格 = <b>±384 格</b>，覆盖 12 区块视距（±192）并留余量。
 *
 * <p><b>三层修正</b>（NET-01 / RT-01）：
 * <ol>
 *   <li>旧实现只发 L1，客户端 {@code Source.L1_COARSE} + {@code confidence 0.6} 无法区分
 *       "完整风场"与"只有背景风"——台风 30 m/s 的眼墙、飑线阵风锋、尘卷风在客户端全部不可见；</li>
 *   <li>旧分辨率 32 格/R=4（±128 格）<b>比 L1 源网格（64 格）还细</b>：客户端在对阶梯场再采样，
 *       只把台阶磨圆，不增加信息；而 ±128 又小于视距 192，远处一律是边缘格外推的假风；</li>
 *   <li>旧包不带维度（NET-03），跨维度同名时客户端会误用旧包。</li>
 * </ol>
 *
 * <p><b>增量</b>（RT-04）：{@link #changed()} 非空时，六个数组里只有这些索引的值，
 * 客户端把它们合并进上一帧。40 tick 内多数格点不变（L1 是 64 tick 刷新），
 * 全量重发等于每 2 秒重传一张几乎相同的图。变更比例超过
 * {@value #FULL_RESEND_FRACTION} 时退回全量（索引表的开销不划算）。
 *
 * <p>数值量化到 int16（NET-06）：0.01 m/s 定点，±327 m/s 量程远超任何天气现象。
 */
public record CoarseWindPacket(
        String dimension,
        double centerX, double centerY, double centerZ,
        int cellSizeBlocks, int radiusCells,
        int[] changed,
        float[] vx, float[] vy, float[] vz,
        float[] phx, float[] phy, float[] phz,
        long tick) implements CustomPacketPayload {

    /** 默认网格间距（格）：与 {@code MesoscaleGrid.CELL_SIZE_BLOCKS} 一致，不引入假精度。 */
    public static final int DEFAULT_CELL_SIZE_BLOCKS = 64;
    /** 默认半径（格）：±6×64 = ±384 格。 */
    public static final int DEFAULT_RADIUS_CELLS = 6;
    /** 定格精度（m/s 每 1 个 LSB）。 */
    public static final double FIXED_POINT_MPS = 0.01;
    /** 变更量阈值（m/s）：小于它的差异客户端看不出来，不值得占带宽。 */
    public static final double DIRTY_EPSILON_MPS = 0.1;
    /** 变更格点占比超过它就不再发增量，直接全量。 */
    public static final double FULL_RESEND_FRACTION = 0.6;

    /** 网格半径上限：63×63，同时是恶意包的天花板（NET-07）。 */
    private static final int MAX_RADIUS_CELLS = 31;
    /** 单个数组长度上限，防 `VAR_INT` 声明 2^31 直接 OOM（NET-05）。 */
    private static final int MAX_ARRAY_LENGTH = 4096;

    public CoarseWindPacket {
        // 恶意/损坏包不得让客户端除零、越界或分配巨额数组（NET-07）
        cellSizeBlocks = Math.max(1, Math.min(cellSizeBlocks, 4096));
        radiusCells = Math.max(1, Math.min(radiusCells, MAX_RADIUS_CELLS));
        int cells = (radiusCells * 2 + 1) * (radiusCells * 2 + 1);
        if (changed == null) changed = new int[0];
        // changed 为空 = 全量；否则数组长度等于变更格点数
        int expected = changed.length == 0 ? cells : changed.length;
        if (vx.length != expected || vy.length != expected || vz.length != expected
                || phx.length != expected || phy.length != expected || phz.length != expected) {
            throw new DecoderException("coarse_wind 数组长度不匹配：期望 " + expected
                    + "，实际 " + vx.length + "/" + vy.length + "/" + vz.length
                    + "/" + phx.length + "/" + phy.length + "/" + phz.length);
        }
        int prev = -1;
        for (int idx : changed) {
            if (idx < 0 || idx >= cells || idx <= prev) {
                throw new DecoderException("coarse_wind 变更索引非法：" + idx);
            }
            prev = idx;
        }
        if (!Double.isFinite(centerX) || !Double.isFinite(centerY) || !Double.isFinite(centerZ)) {
            throw new DecoderException("coarse_wind 中心点不是有限值");
        }
        if (dimension == null || dimension.isEmpty() || dimension.length() > 128) {
            throw new DecoderException("coarse_wind 维度键非法");
        }
    }

    /** 是否全量帧。 */
    public boolean isFullFrame() { return changed.length == 0; }

    public static final CustomPacketPayload.Type<CoarseWindPacket> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(ZenithMod.MOD_ID, "coarse_wind"));

    /**
     * float[] ⇄ int16 定点编解码（NET-06）。
     *
     * <p>量程 ±327.67 m/s、精度 0.01 m/s：台风眼墙 40 m/s、龙卷 60 m/s 都远在量程内，
     * 而 0.01 m/s 远低于任何可感知的风差异。带宽相对 float 直接减半。
     * 非有限值写 0，解码端不检查也不会拿到 NaN。
     */
    private static final StreamCodec<ByteBuf, float[]> VECTOR_ARRAY_CODEC = new StreamCodec<>() {
        @Override
        public float[] decode(ByteBuf buf) {
            int n = ByteBufCodecs.VAR_INT.decode(buf);
            if (n < 0 || n > MAX_ARRAY_LENGTH) {
                throw new DecoderException("coarse_wind 数组长度越界：" + n);
            }
            float[] arr = new float[n];
            for (int i = 0; i < n; i++) arr[i] = (float) (buf.readShort() * FIXED_POINT_MPS);
            return arr;
        }

        @Override
        public void encode(ByteBuf buf, float[] arr) {
            ByteBufCodecs.VAR_INT.encode(buf, arr.length);
            for (float f : arr) {
                int q = Float.isFinite(f)
                        ? (int) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE,
                                Math.round(f / FIXED_POINT_MPS)))
                        : 0;
                buf.writeShort(q);
            }
        }
    };

    /** 变更索引表：0 个 = 全量帧。索引严格递增（构造里校验）。 */
    private static final StreamCodec<ByteBuf, int[]> INDEX_ARRAY_CODEC = new StreamCodec<>() {
        @Override
        public int[] decode(ByteBuf buf) {
            int n = ByteBufCodecs.VAR_INT.decode(buf);
            if (n < 0 || n > MAX_ARRAY_LENGTH) {
                throw new DecoderException("coarse_wind 变更索引数越界：" + n);
            }
            int[] arr = new int[n];
            for (int i = 0; i < n; i++) arr[i] = ByteBufCodecs.VAR_INT.decode(buf);
            return arr;
        }

        @Override
        public void encode(ByteBuf buf, int[] arr) {
            ByteBufCodecs.VAR_INT.encode(buf, arr.length);
            for (int v : arr) ByteBufCodecs.VAR_INT.encode(buf, v);
        }
    };

    public static final StreamCodec<ByteBuf, CoarseWindPacket> STREAM_CODEC = StreamCodec.of(
            (buf, p) -> {
                ByteBufCodecs.STRING_UTF8.encode(buf, p.dimension());
                ByteBufCodecs.DOUBLE.encode(buf, p.centerX());
                ByteBufCodecs.DOUBLE.encode(buf, p.centerY());
                ByteBufCodecs.DOUBLE.encode(buf, p.centerZ());
                ByteBufCodecs.VAR_INT.encode(buf, p.cellSizeBlocks());
                ByteBufCodecs.VAR_INT.encode(buf, p.radiusCells());
                INDEX_ARRAY_CODEC.encode(buf, p.changed());
                VECTOR_ARRAY_CODEC.encode(buf, p.vx());
                VECTOR_ARRAY_CODEC.encode(buf, p.vy());
                VECTOR_ARRAY_CODEC.encode(buf, p.vz());
                VECTOR_ARRAY_CODEC.encode(buf, p.phx());
                VECTOR_ARRAY_CODEC.encode(buf, p.phy());
                VECTOR_ARRAY_CODEC.encode(buf, p.phz());
                ByteBufCodecs.VAR_LONG.encode(buf, p.tick());
            },
            buf -> new CoarseWindPacket(
                    ByteBufCodecs.STRING_UTF8.decode(buf),
                    ByteBufCodecs.DOUBLE.decode(buf),
                    ByteBufCodecs.DOUBLE.decode(buf),
                    ByteBufCodecs.DOUBLE.decode(buf),
                    ByteBufCodecs.VAR_INT.decode(buf),
                    ByteBufCodecs.VAR_INT.decode(buf),
                    INDEX_ARRAY_CODEC.decode(buf),
                    VECTOR_ARRAY_CODEC.decode(buf),
                    VECTOR_ARRAY_CODEC.decode(buf),
                    VECTOR_ARRAY_CODEC.decode(buf),
                    VECTOR_ARRAY_CODEC.decode(buf),
                    VECTOR_ARRAY_CODEC.decode(buf),
                    VECTOR_ARRAY_CODEC.decode(buf),
                    ByteBufCodecs.VAR_LONG.decode(buf)));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public int gridSize() {
        return radiusCells * 2 + 1;
    }

    /** 网格索引 → 世界坐标。 */
    public double blockX(int i) {
        return centerX + (i - radiusCells) * cellSizeBlocks;
    }

    public double blockZ(int j) {
        return centerZ + (j - radiusCells) * cellSizeBlocks;
    }

    public static void handle(CoarseWindPacket pkt, IPayloadContext ctx) {
        ctx.enqueueWork(() -> net.atomos.zenith.client.ClientWindState.onCoarseWind(pkt));
    }
}
