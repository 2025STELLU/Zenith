package net.atomos.zenith.weather;

import net.minecraft.server.level.ServerLevel;

/** 天气现象通用接口（服务端 tick + 逐点采样）。 */
public interface WeatherPhenomenon {
    void tick(ServerLevel level, double dtSeconds, WeatherContext ctx);

    WindContribution sample(double x, double y, double z, double baseWindX, double baseWindZ);

    /** 是否有活跃现象（用于网络同步与可视化）。 */
    boolean hasActive();

    /** 活跃现象数量（诊断用）。 */
    default int activeCount() { return hasActive() ? 1 : 0; }
}
