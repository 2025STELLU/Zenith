package net.atomos.zenith.wind;

/** 轻量哈希值噪声（阵风、湍流、地形变化用），无外部依赖。 */
public final class NoiseUtil {
    private NoiseUtil() {}

    private static long hash(long x, long y, long seed) {
        long h = x * 0x9E3779B97F4A7C15L + y * 0xBF58476D1CE4E5B9L + seed * 0x94D049BB133111EBL;
        h ^= h >>> 30;
        h *= 0xBF58476D1CE4E5B9L;
        h ^= h >>> 27;
        h *= 0x94D049BB133111EBL;
        h ^= h >>> 31;
        return h;
    }

    private static double hash01(long x, long y, long seed) {
        return (hash(x, y, seed) >>> 11) * (1.0 / 9007199254740992.0);
    }

    private static double smooth(double t) { return t * t * (3 - 2 * t); }

    /** 2D 值噪声，输出 [0,1]。 */
    public static double valueNoise2(double x, double y, long seed) {
        long xi = (long) Math.floor(x), yi = (long) Math.floor(y);
        double xf = x - xi, yf = y - yi;
        double a = hash01(xi, yi, seed);
        double b = hash01(xi + 1, yi, seed);
        double c = hash01(xi, yi + 1, seed);
        double d = hash01(xi + 1, yi + 1, seed);
        double u = smooth(xf), v = smooth(yf);
        return a + (b - a) * u + (c - a) * v + (a - b - c + d) * u * v;
    }

    /** 1D 值噪声，输出 [0,1]。 */
    public static double valueNoise1(double x, long seed) {
        long xi = (long) Math.floor(x);
        double xf = x - xi;
        double a = hash01(xi, 0, seed);
        double b = hash01(xi + 1, 0, seed);
        double u = smooth(xf);
        return a + (b - a) * u;
    }

    /** 分形叠加（octaves 层）。 */
    public static double fbm2(double x, double y, int octaves, long seed) {
        double sum = 0, amp = 0.5, freq = 1, norm = 0;
        for (int o = 0; o < octaves; o++) {
            sum += amp * valueNoise2(x * freq, y * freq, seed + o * 101);
            norm += amp;
            amp *= 0.5;
            freq *= 2.03;
        }
        return sum / norm;
    }
}
