package net.atomos.zenith.client;

import net.atomos.zenith.api.client.ZenithClientWindApi;
import net.atomos.zenith.block.WindVaneBlockEntity;
import net.atomos.zenith.block.ZenithBlocks;
import net.atomos.zenith.client.render.SailboatRenderer;
import net.atomos.zenith.client.render.TornadoRenderer;
import net.atomos.zenith.client.render.WindVaneRenderer;
import net.atomos.zenith.entity.ZenithEntities;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.RegisterParticleProvidersEvent;
import net.neoforged.neoforge.common.NeoForge;

/**
 * 客户端接线总入口（CLI-14）。
 *
 * <p>以前 {@code TornadoRenderer} 自带 {@code @EventBusSubscriber}，在
 * {@code AFTER_TRANSLUCENT_BLOCKS} 阶段自己 flush 一次 bufferSource；而帆船/风向标
 * 走实体渲染器。两套渲染生命周期散在两个文件里，"想改渲染入口"要翻两处。
 * 现在统一在这里注册：
 * <ul>
 *   <li>MOD 总线：粒子 provider、实体/方块实体渲染器；</li>
 *   <li>GAME 总线（{@code NeoForge.EVENT_BUS}）：龙卷/漏斗的世界空间几何体。</li>
 * </ul>
 */
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

    /**
     * 世界空间临时几何体（龙卷漏斗、尘环）。
     *
     * <p>挂 GAME 总线而不是 MOD 总线：{@code RenderLevelStageEvent} 是每帧的运行时事件。
     */
    @SubscribeEvent
    public static void clientSetup(FMLClientSetupEvent event) {
        event.enqueueWork(() ->
                NeoForge.EVENT_BUS.addListener(TornadoRenderer::onRenderLevelStage));
    }
}
