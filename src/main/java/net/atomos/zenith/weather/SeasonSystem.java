package net.atomos.zenith.weather;

/**
 * 季节系统：120 天一年（30 天一季），影响气温偏置与日照强度，
 * 进而驱动季风与热对流的年循环。
 */
public final class SeasonSystem {
    /** 一年天数。 */
    public static final int DAYS_PER_YEAR = 120;
    public static final int DAYS_PER_SEASON = 30;

    public enum Season { SPRING, SUMMER, AUTUMN, WINTER }

    /** 季节快照。 */
    public record SeasonInfo(Season season, int dayOfYear,
                             /** 气温偏置（K），夏季 +7 / 冬季 −7。 */
                             double tempBiasKelvin,
                             /** 日照强度乘子（影响热对流）。 */
                             double solarMultiplier) {}

    private SeasonSystem() {}

    public static SeasonInfo compute(long dayTime) {
        long day = dayTime / 24000L;
        int doy = (int) (day % DAYS_PER_YEAR);
        Season season = doy < 30 ? Season.SPRING
                : doy < 60 ? Season.SUMMER
                : doy < 90 ? Season.AUTUMN : Season.WINTER;
        // 年循环：doy=45（夏中）→ +1，doy=105（冬中）→ −1
        double annual = Math.sin((doy - 15) / (double) DAYS_PER_YEAR * Math.PI * 2.0);
        return new SeasonInfo(season, doy, 7.0 * annual, 1.0 + 0.35 * annual);
    }
}
