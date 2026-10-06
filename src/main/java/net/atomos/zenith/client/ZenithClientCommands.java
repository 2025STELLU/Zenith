package net.atomos.zenith.client;

import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;

/** 客户端指令：/zenithc visualizer|map（MOD 总线）。 */
@EventBusSubscriber(value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
public final class ZenithClientCommands {
    private ZenithClientCommands() {}

    @SubscribeEvent
    public static void register(RegisterClientCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> d = event.getDispatcher();
        d.register(Commands.literal("zenithc")
                .then(Commands.literal("visualizer").executes(ctx -> {
                    ZenithVisualizer.toggle();
                    ctx.getSource().sendSuccess(() -> Component.literal(
                            "风场可视化: " + (ZenithVisualizer.isEnabled() ? "开" : "关")), false);
                    return 1;
                }))
                .then(Commands.literal("map").executes(ctx -> {
                    Minecraft.getInstance().tell(ClientHooks::openMeteorologicalMap);
                    return 1;
                })));
    }
}
