package net.atomos.zenith.weather;

import net.atomos.zenith.api.ZenithId;
import net.atomos.zenith.api.ZenithWorldRef;
import net.atomos.zenith.wind.BackgroundMetGrid;
import net.atomos.zenith.wind.MesoscaleGrid;
import net.atomos.zenith.wind.SeedTerrainProvider;
import net.atomos.zenith.wind.WorldScaleDriver;
import net.minecraft.server.level.ServerLevel;

/** 天气现象 tick 上下文（服务端）。 */
public record WeatherContext(
        ServerLevel level,
        WorldScaleDriver driver,
        BackgroundMetGrid l0,
        MesoscaleGrid l1,
        SeedTerrainProvider terrain,
        long tick,
        double timeOfDay01,
        boolean raining,
        boolean thundering,
        SeasonSystem.SeasonInfo seasonInfo) {

    /**
     * 当前维度的服务端世界引用（API-05 事件用）。
     *
     * <p>现象系统在生成/消散时要把坐标连同维度一起发出去——第三方监听器拿到的
     * 一个裸坐标是没用的，它得知道是哪个维度。
     */
    public ZenithWorldRef worldRef() {
        return ZenithWorldRef.server(ZenithId.parse(level.dimension().location().toString()));
    }
}
