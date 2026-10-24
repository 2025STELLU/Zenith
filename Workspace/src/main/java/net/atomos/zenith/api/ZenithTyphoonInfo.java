package net.atomos.zenith.api;

/** 台风信息（API 数据载体）。 */
public record ZenithTyphoonInfo(
        String name,
        double x, double z,
        double vmaxMps,
        double rmaxBlocks,
        double eyeRadiusBlocks,
        double intensity01) {}
