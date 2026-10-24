package net.atomos.zenith.api;

/** 翼型坐标点（弦向 x∈[0,1]，y 为厚度方向，弦长归一）。 */
public record ZenithAirfoilCoordinate(double x, double y) {}
