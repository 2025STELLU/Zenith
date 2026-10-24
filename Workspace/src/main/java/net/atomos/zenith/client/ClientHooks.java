package net.atomos.zenith.client;

import net.minecraft.client.Minecraft;

/** 客户端钩子（供物品等调用，避免直接引用客户端类导致服务端崩溃）。 */
public final class ClientHooks {
    private ClientHooks() {}

    public static void openMeteorologicalMap() {
        Minecraft mc = Minecraft.getInstance();
        mc.setScreen(new MeteorologicalMapScreen());
    }
}
