package net.atomos.zenith.weather;

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
        SeasonSystem.SeasonInfo seasonInfo) {}
