package net.atomos.zenith.network;

import net.atomos.zenith.network.packet.CoarseWindPacket;
import net.atomos.zenith.network.packet.WeatherSnapshotPacket;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/** 网络通道注册。 */
public final class ZenithNetwork {
    private ZenithNetwork() {}

    public static void register(IEventBus modEventBus) {
        modEventBus.addListener(ZenithNetwork::registerPayloads);
    }

    private static void registerPayloads(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("1");
        registrar.playToClient(
                CoarseWindPacket.TYPE,
                CoarseWindPacket.STREAM_CODEC,
                CoarseWindPacket::handle);
        registrar.playToClient(
                WeatherSnapshotPacket.TYPE,
                WeatherSnapshotPacket.STREAM_CODEC,
                WeatherSnapshotPacket::handle);
    }
}
