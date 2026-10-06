package net.atomos.zenith.api;

/** 飑线信息（API 数据载体）。 */
public record ZenithSquallLineInfo(
        double x1, double z1, double x2, double z2,
        double intensity01,
        int microburstCount) {}
