package net.atomos.zenith.api;

/** 季节信息（API 数据载体）。 */
public record ZenithSeasonInfo(
        /** "SPRING"/"SUMMER"/"AUTUMN"/"WINTER"。 */
        String season,
        int dayOfYear,
        /** 气温偏置（K）。 */
        double tempBiasKelvin,
        /** 日照强度乘子。 */
        double solarMultiplier) {}
