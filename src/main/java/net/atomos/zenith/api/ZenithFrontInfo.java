package net.atomos.zenith.api;

/** 锋面信息（API 数据载体）。 */
public record ZenithFrontInfo(
        /** "COLD" 或 "WARM"。 */
        String type,
        double x1, double z1, double x2, double z2,
        double intensity01) {}
