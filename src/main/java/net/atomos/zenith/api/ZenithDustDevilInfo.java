package net.atomos.zenith.api;

/** 尘卷风信息（API 数据载体）。 */
public record ZenithDustDevilInfo(
        double x, double z,
        double radiusBlocks,
        double heightBlocks,
        double tangentialMps) {}
