package net.atomos.zenith.client;

import net.atomos.zenith.api.client.ZenithClientWindApi;
import net.atomos.zenith.block.WindVaneBlockEntity;
import net.atomos.zenith.block.ZenithBlocks;
import net.atomos.zenith.client.render.SailboatRenderer;
import net.atomos.zenith.client.render.WindVaneRenderer;
import net.atomos.zenith.entity.ZenithEntities;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.RegisterParticleProvidersEvent;

/** 客户端装配（MOD 总线事件）。 */
@EventBusSubscriber(value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
public final class ZenithClientSetup {
    private ZenithClientSetup() {}

    @SubscribeEvent
    public static void registerParticles(RegisterParticleProvidersEvent event) {
        ZenithParticleProviders.registerAll(event);
        ZenithClientWindApi.bindRuntime(ClientWindState.get());
    }

    @SubscribeEvent
    public static void registerRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerBlockEntityRenderer(
                ZenithBlocks.WIND_VANE_BE.get(), WindVaneRenderer::new);
        event.registerEntityRenderer(
                ZenithEntities.SAILBOAT.get(), SailboatRenderer::new);
    }
}
