package net.atomos.zenith.api;

public record ZenithFrontInfo(
        // 取值只能是 "COLD" 或 "WARM"
        String type,
        double x1, double z1, double x2, double z2,
        double intensity01) {}
