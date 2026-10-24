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
 * 半径 {@value #DEFAULT_RADIUS_CELLS} 格 = **±384 格**，覆盖 12 区块视距（±192）并留余量。
 *
 * <p><b>三层修正</b>（NET-01 / RT-01）：
 * <ol>
 *   <li>旧实现只发 L1，客户端 {@code Source.L1_COARSE} + {@code confidence 0.6} 无法区分
 *       "完整风场"与"只有背景风"——台风 30 m/s 的眼墙、飑线阵风锋、尘卷风在客户端全部不可见；
 *   <li>旧分辨率 32 格/R=4（±128 格）**比 L1 源网格（64 格）还细**：客户端在对阶梯场再采样，
 *       只把台阶磨圆，不增加信息；而 ±128 又小于视距 192，远处一律是边缘格外推的假风；
 *   <li>旧包不带维度（NET-03），跨维度同名时客户端会误用旧包。
 * </ol>
 *
 * <p>现在：分辨率与 L1 对齐（64 格）、覆盖视距、额外发一条**现象层合成矢量**
 * （台风/飑线/尘卷风/热泡的贡献之和），并带维度键。量化到 int16（NET-06）：
 * 0.01 m/s 定点，±327 m/s 量程远超任何天气现象。
 */
public record CoarseWindPacket(
        String dimension,
        double centerX, double centerY, double centerZ,
        int cellSizeBlocks, int radiusCells,
        float[] vx, float[] vy, float[] vz,
        float[] phx, float[] phy, float[] phz,
        long tick) implements CustomPacketPayload {

    /** 默认网格间距（格）：与 {@code MesoscaleGrid.CELL_SIZE_BLOCKS} 一致，不引入假精度。 */
    public static final int DEFAULT_CELL_SIZE_BLOCKS = 64;
    /** 默认半径（格）：11×11…13×13。12 → 25×25 = ±768 格，足够气象图近场。 */
    public static final int DEFAULT_RADIUS_CELLS = 6;
    /** 定格精度（m/s 每 1 个 LSB）。 */
    public static final double FIXED_POINT_MPS = 0.01;

    /** 网格半径上限：63×63，同时是恶意包的天花板（NET-07）。 */
    private static final int MAX_RADIUS_CELLS = 31;
    /** 单个数组长度上限，防 `VAR_INT` 声明 2^31 直接 OOM（NET-05） */
    private static final int MAX_ARRAY_LENGTH = 4096;

    public CoarseWindPacket {
        // 恶意/损坏包不得让客户端除零、越界或分配巨额数组（NET-07）
        cellSizeBlocks = Math.max(1, Math.min(cellSizeBlocks, 4096));
        radiusCells = Math.max(1, Math.min(radiusCells, MAX_RADIUS_CELLS));
        int expected = (radiusCells * 2 + 1) * (radiusCells * 2 + 1);
        if (vx.length != expected || vy.length != expected || vz.length != expected
                || phx.length != expected || phy.length != expected || phz.length != expected) {
            throw new DecoderException("coarse_wind 数组长度不匹配：期望 " + expected
                    + "，实际 " + vx.length + "/" + vy.length + "/" + vz.length
                    + "/" + phx.length + "/" + phy.length + "/" + phz.length);
        }
        if (!Double.isFinite(centerX) || !Double.isFinite(centerY) || !Double.isFinite(centerZ)) {
            throw new DecoderException("coarse_wind 中心点不是有限值");
        }
        if (dimension == null || dimension.isEmpty() || dimension.length() > 128) {
            throw new DecoderException("coarse_wind 维度键非法");
        }
    }

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

    public static final StreamCodec<ByteBuf, CoarseWindPacket> STREAM_CODEC = StreamCodec.of(
            (buf, p) -> {
                ByteBufCodecs.STRING_UTF8.encode(buf, p.dimension());
                ByteBufCodecs.DOUBLE.encode(buf, p.centerX());
                ByteBufCodecs.DOUBLE.encode(buf, p.centerY());
                ByteBufCodecs.DOUBLE.encode(buf, p.centerZ());
                ByteBufCodecs.VAR_INT.encode(buf, p.cellSizeBlocks());
                ByteBufCodecs.VAR_INT.encode(buf, p.radiusCells());
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
