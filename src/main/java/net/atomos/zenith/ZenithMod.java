package net.atomos.zenith;

import net.atomos.zenith.api.ZenithWindApi;
import net.atomos.zenith.block.ZenithBlocks;
import net.atomos.zenith.entity.ZenithEntities;
import net.atomos.zenith.item.ZenithItems;
import net.atomos.zenith.network.ZenithNetwork;
import net.atomos.zenith.particle.ZenithParticles;
import net.atomos.zenith.sound.ZenithSounds;
import net.atomos.zenith.wind.ZenithCommands;
import net.atomos.zenith.wind.ZenithServerRuntime;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Zenith --- Minecraft的多尺度实时风与天气模拟。
 *
 * <p>架构（移植自Aerodynamics4MC-Core，MIT 许可，见LICENSE。在此感谢Firedoge大佬）：
 * 目前实现了行星尺度驱动（气旋/对流团/龙卷/台风）→ L0 背景天气网格 → L1 中尺度网格 →
 * L2 本地流场求解器（纯 Java 实现，原版为 native LBM JNI）。
 * 另新增了：热对流、 海风、 风暴单体、 台风。</p>
 */
@Mod(ZenithMod.MOD_ID)
public class ZenithMod {
    public static final String MOD_ID = "zenith";
    public static final Logger LOGGER = LoggerFactory.getLogger("zenith");

    public ZenithMod(IEventBus modEventBus, ModContainer modContainer) {
        ZenithBlocks.BLOCKS.register(modEventBus);
        ZenithBlocks.BLOCK_ENTITIES.register(modEventBus);
        ZenithItems.ITEMS.register(modEventBus);
        ZenithItems.CREATIVE_TABS.register(modEventBus);
        ZenithEntities.ENTITY_TYPES.register(modEventBus);
        ZenithParticles.PARTICLE_TYPES.register(modEventBus);
        ZenithSounds.register(modEventBus);
        ZenithNetwork.register(modEventBus);

        modEventBus.addListener(this::commonSetup);

        ZenithServerRuntime.init();
        ZenithWindApi.bindRuntime(ZenithServerRuntime.get());
        ZenithCommands.init();
        LOGGER.info("Zenith {} initialized: multi-scale wind & weather simulation online.",
                modContainer.getModInfo().getVersion());
    }

    private void commonSetup(FMLCommonSetupEvent event) {
        event.enqueueWork(() -> LOGGER.info("Zenith common setup complete."));
    }
}
