package net.atomos.zenith.network.packet;

import io.netty.buffer.ByteBuf;
import net.atomos.zenith.ZenithMod;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 服务端 → 客户端：粗风场同步。
 * 以玩家为中心、32 格间距的 9×9 网格 L1 风速，供客户端本地求解器与可视化使用。
 */
public record CoarseWindPacket(
        double centerX, double centerY, double centerZ,
        int cellSizeBlocks, int radiusCells,
        float[] vx, float[] vy, float[] vz,
        long tick) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<CoarseWindPacket> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(ZenithMod.MOD_ID, "coarse_wind"));

    private static final StreamCodec<ByteBuf, float[]> FLOAT_ARRAY_CODEC = new StreamCodec<>() {
        @Override
        public float[] decode(ByteBuf buf) {
            int n = ByteBufCodecs.VAR_INT.decode(buf);
            float[] arr = new float[n];
            for (int i = 0; i < n; i++) arr[i] = buf.readFloat();
            return arr;
        }

        @Override
        public void encode(ByteBuf buf, float[] arr) {
            ByteBufCodecs.VAR_INT.encode(buf, arr.length);
            for (float f : arr) buf.writeFloat(f);
        }
    };

    public static final StreamCodec<ByteBuf, CoarseWindPacket> STREAM_CODEC = StreamCodec.of(
            (buf, p) -> {
                ByteBufCodecs.DOUBLE.encode(buf, p.centerX());
                ByteBufCodecs.DOUBLE.encode(buf, p.centerY());
                ByteBufCodecs.DOUBLE.encode(buf, p.centerZ());
                ByteBufCodecs.VAR_INT.encode(buf, p.cellSizeBlocks());
                ByteBufCodecs.VAR_INT.encode(buf, p.radiusCells());
                FLOAT_ARRAY_CODEC.encode(buf, p.vx());
                FLOAT_ARRAY_CODEC.encode(buf, p.vy());
                FLOAT_ARRAY_CODEC.encode(buf, p.vz());
                ByteBufCodecs.VAR_LONG.encode(buf, p.tick());
            },
            buf -> new CoarseWindPacket(
                    ByteBufCodecs.DOUBLE.decode(buf),
                    ByteBufCodecs.DOUBLE.decode(buf),
                    ByteBufCodecs.DOUBLE.decode(buf),
                    ByteBufCodecs.VAR_INT.decode(buf),
                    ByteBufCodecs.VAR_INT.decode(buf),
                    FLOAT_ARRAY_CODEC.decode(buf),
                    FLOAT_ARRAY_CODEC.decode(buf),
                    FLOAT_ARRAY_CODEC.decode(buf),
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
