package net.atomos.zenith.client;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.ViewportEvent;

/** 客户端 tick 驱动（FORGE 总线）：粒子风控 / 天气风控 / 可视化 / 雾 / 风声。 */
@EventBusSubscriber(value = Dist.CLIENT)
public final class ZenithClientTick {
    private ZenithClientTick() {}

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        ParticleWindController.clientTick();
        WeatherWindController.clientTick();
        ZenithVisualizer.clientTick();
        WindSoundManager.clientTick();
        FogController.clientTick();
    }

    @SubscribeEvent
    public static void onComputeFog(ViewportEvent.RenderFog event) {
        FogController.onComputeFog(event);
    }
}
