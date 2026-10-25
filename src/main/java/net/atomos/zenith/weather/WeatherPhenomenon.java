package net.atomos.zenith.weather;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;

/** 天气现象通用接口（服务端 tick + 逐点采样）。 */
public interface WeatherPhenomenon {
    void tick(ServerLevel level, double dtSeconds, WeatherContext ctx);

    WindContribution sample(double x, double y, double z, double baseWindX, double baseWindZ);

    /** 是否有活跃现象（用于网络同步与可视化）。 */
    boolean hasActive();

    /** 活跃现象数量（诊断用）。 */
    default int activeCount() { return hasActive() ? 1 : 0; }

    /**
     * 现象状态的存档键（SYS-08）。同一维度里必须唯一。
     * 返回 {@code null} 表示这个现象无状态、不需要持久化。
     */
    default String saveKey() { return null; }

    /** 运行时状态 → NBT（SYS-08）。 */
    default CompoundTag save() { return new CompoundTag(); }

    /** 从 NBT 恢复运行时状态（SYS-08）。 */
    default void load(CompoundTag tag) {}
}
