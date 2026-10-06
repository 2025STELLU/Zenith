package net.atomos.zenith.network.packet;

import io.netty.buffer.ByteBuf;
import net.atomos.zenith.ZenithMod;
import net.atomos.zenith.weather.DustDevilSystem;
import net.atomos.zenith.weather.FrontSystem;
import net.atomos.zenith.weather.SquallLineSystem;
import net.atomos.zenith.weather.StormCellSystem;
import net.atomos.zenith.weather.TyphoonSystem;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.ArrayList;
import java.util.List;

/**
 * 服务端 → 客户端：天气现象快照（台风/风暴单体/热对流计数/海风锋/风暴活动度/
 * 锋面/尘卷风/飑线/湿度）。
 * 供气象图界面与客户端特效使用。
 */
public record WeatherSnapshotPacket(
        float stormActivity,
        int tornadoCount,
        int thermalCount,
        float seaBreezeFrontInland,
        float humidity01,
        List<TyphoonInfo> typhoons,
        List<StormInfo> storms,
        List<FrontInfo> fronts,
        List<DevilInfo> dustDevils,
        List<SquallInfo> squallLines) implements CustomPacketPayload {

    public record TyphoonInfo(String name, double x, double z,
                              float vmaxMps, float rmaxBlocks, float eyeRadiusBlocks,
                              float intensity01) {}

    public record StormInfo(double x, double z, float radiusBlocks, int phaseOrdinal, float hail01) {}

    /** type: "COLD" 或 "WARM"。 */
    public record FrontInfo(String type, double x1, double z1, double x2, double z2) {}

    public record DevilInfo(double x, double z, float radiusBlocks) {}

    public record SquallInfo(double x1, double z1, double x2, double z2) {}

    public static final CustomPacketPayload.Type<WeatherSnapshotPacket> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(ZenithMod.MOD_ID, "weather_snapshot"));

    private static final StreamCodec<ByteBuf, TyphoonInfo> TYPHOON_CODEC = StreamCodec.of(
            (buf, info) -> {
                ByteBufCodecs.STRING_UTF8.encode(buf, info.name());
                ByteBufCodecs.DOUBLE.encode(buf, info.x());
                ByteBufCodecs.DOUBLE.encode(buf, info.z());
                ByteBufCodecs.FLOAT.encode(buf, info.vmaxMps());
                ByteBufCodecs.FLOAT.encode(buf, info.rmaxBlocks());
                ByteBufCodecs.FLOAT.encode(buf, info.eyeRadiusBlocks());
                ByteBufCodecs.FLOAT.encode(buf, info.intensity01());
            },
            buf -> new TyphoonInfo(
                    ByteBufCodecs.STRING_UTF8.decode(buf),
                    ByteBufCodecs.DOUBLE.decode(buf),
                    ByteBufCodecs.DOUBLE.decode(buf),
                    ByteBufCodecs.FLOAT.decode(buf),
                    ByteBufCodecs.FLOAT.decode(buf),
                    ByteBufCodecs.FLOAT.decode(buf),
                    ByteBufCodecs.FLOAT.decode(buf)));

    private static final StreamCodec<ByteBuf, StormInfo> STORM_CODEC = StreamCodec.composite(
            ByteBufCodecs.DOUBLE, StormInfo::x,
            ByteBufCodecs.DOUBLE, StormInfo::z,
            ByteBufCodecs.FLOAT, StormInfo::radiusBlocks,
            ByteBufCodecs.VAR_INT, StormInfo::phaseOrdinal,
            ByteBufCodecs.FLOAT, StormInfo::hail01,
            StormInfo::new);

    private static final StreamCodec<ByteBuf, FrontInfo> FRONT_CODEC = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, FrontInfo::type,
            ByteBufCodecs.DOUBLE, FrontInfo::x1,
            ByteBufCodecs.DOUBLE, FrontInfo::z1,
            ByteBufCodecs.DOUBLE, FrontInfo::x2,
            ByteBufCodecs.DOUBLE, FrontInfo::z2,
            FrontInfo::new);

    private static final StreamCodec<ByteBuf, DevilInfo> DEVIL_CODEC = StreamCodec.composite(
            ByteBufCodecs.DOUBLE, DevilInfo::x,
            ByteBufCodecs.DOUBLE, DevilInfo::z,
            ByteBufCodecs.FLOAT, DevilInfo::radiusBlocks,
            DevilInfo::new);

    private static final StreamCodec<ByteBuf, SquallInfo> SQUALL_CODEC = StreamCodec.composite(
            ByteBufCodecs.DOUBLE, SquallInfo::x1,
            ByteBufCodecs.DOUBLE, SquallInfo::z1,
            ByteBufCodecs.DOUBLE, SquallInfo::x2,
            ByteBufCodecs.DOUBLE, SquallInfo::z2,
            SquallInfo::new);

    private static <T> StreamCodec<ByteBuf, java.util.List<T>> listOf(StreamCodec<ByteBuf, T> element) {
        return new StreamCodec<>() {
            @Override
            public java.util.List<T> decode(ByteBuf buf) {
                int n = ByteBufCodecs.VAR_INT.decode(buf);
                java.util.List<T> list = new ArrayList<>(n);
                for (int i = 0; i < n; i++) list.add(element.decode(buf));
                return list;
            }

            @Override
            public void encode(ByteBuf buf, java.util.List<T> list) {
                ByteBufCodecs.VAR_INT.encode(buf, list.size());
                for (T t : list) element.encode(buf, t);
            }
        };
    }

    public static final StreamCodec<ByteBuf, WeatherSnapshotPacket> STREAM_CODEC = StreamCodec.of(
            (buf, p) -> {
                ByteBufCodecs.FLOAT.encode(buf, p.stormActivity());
                ByteBufCodecs.VAR_INT.encode(buf, p.tornadoCount());
                ByteBufCodecs.VAR_INT.encode(buf, p.thermalCount());
                ByteBufCodecs.FLOAT.encode(buf, p.seaBreezeFrontInland());
                ByteBufCodecs.FLOAT.encode(buf, p.humidity01());
                listOf(TYPHOON_CODEC).encode(buf, p.typhoons());
                listOf(STORM_CODEC).encode(buf, p.storms());
                listOf(FRONT_CODEC).encode(buf, p.fronts());
                listOf(DEVIL_CODEC).encode(buf, p.dustDevils());
                listOf(SQUALL_CODEC).encode(buf, p.squallLines());
            },
            buf -> new WeatherSnapshotPacket(
                    ByteBufCodecs.FLOAT.decode(buf),
                    ByteBufCodecs.VAR_INT.decode(buf),
                    ByteBufCodecs.VAR_INT.decode(buf),
                    ByteBufCodecs.FLOAT.decode(buf),
                    ByteBufCodecs.FLOAT.decode(buf),
                    listOf(TYPHOON_CODEC).decode(buf),
                    listOf(STORM_CODEC).decode(buf),
                    listOf(FRONT_CODEC).decode(buf),
                    listOf(DEVIL_CODEC).decode(buf),
                    listOf(SQUALL_CODEC).decode(buf)));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static WeatherSnapshotPacket build(double stormActivity, int tornadoCount,
                                              int thermalCount, double seaBreezeFront,
                                              double humidity01,
                                              List<TyphoonSystem.Typhoon> typhoons,
                                              List<StormCellSystem.StormCell> storms,
                                              List<FrontSystem.Front> fronts,
                                              List<DustDevilSystem.DustDevil> devils,
                                              List<SquallLineSystem.SquallLine> squallLines) {
        List<TyphoonInfo> ti = new ArrayList<>();
        for (TyphoonSystem.Typhoon t : typhoons) {
            ti.add(new TyphoonInfo(t.name, t.x, t.z, (float) t.vmaxMps,
                    (float) t.rmaxBlocks, (float) t.eyeRadiusBlocks, (float) t.intensity01));
        }
        List<StormInfo> si = new ArrayList<>();
        for (StormCellSystem.StormCell c : storms) {
            si.add(new StormInfo(c.x, c.z, (float) c.radiusBlocks, c.phase().ordinal(),
                    (float) c.hail01));
        }
        List<FrontInfo> fi = new ArrayList<>();
        for (FrontSystem.Front f : fronts) {
            fi.add(new FrontInfo(f.type.name(), f.x1, f.z1, f.x2, f.z2));
        }
        List<DevilInfo> di = new ArrayList<>();
        for (DustDevilSystem.DustDevil d : devils) {
            di.add(new DevilInfo(d.x, d.z, (float) d.radiusBlocks));
        }
        List<SquallInfo> qi = new ArrayList<>();
        for (SquallLineSystem.SquallLine l : squallLines) {
            qi.add(new SquallInfo(l.x1, l.z1, l.x2, l.z2));
        }
        return new WeatherSnapshotPacket((float) stormActivity, tornadoCount, thermalCount,
                (float) seaBreezeFront, (float) humidity01, ti, si, fi, di, qi);
    }

    public static void handle(WeatherSnapshotPacket pkt, IPayloadContext ctx) {
        ctx.enqueueWork(() -> net.atomos.zenith.client.ClientWindState.onWeatherSnapshot(pkt));
    }
}
