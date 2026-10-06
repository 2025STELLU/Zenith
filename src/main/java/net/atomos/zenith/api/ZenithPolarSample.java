package net.atomos.zenith.api;

/** 极线采样点：迎角（度）→ 升力/阻力/力矩系数。 */
public record ZenithPolarSample(double angleDegrees, double cl, double cd, double cm) {}
