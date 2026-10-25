package net.atomos.zenith.weather;

/**
 * 海陆距离场：倒角距离变换（chamfer 1:√2）求"到最近异类格点的距离"。
 *
 * <p>海风（{@link SeaBreezeSystem}）和季风的 L0 基础流偏置都要用同一个场：
 * 它们其实是同一个"海陆热力差"机制的两个尺度，各自实现一份距离变换迟早会算出
 * 两套不一致的岸线（WX-MS-01 / WX-SB-02）。
 */
public final class CoastDistance {
    private CoastDistance() {}

    /**
     * 每个格点到最近的、满足 {@code mask == want} 的格点的距离（格为单位，非格点则为 {@code 1e9}）。
     *
     * @param mask    海陆掩膜（{@code true} = 海洋）
     * @param want    要找的类别
     */
    public static double[][] chamfer(boolean[][] mask, boolean want) {
        int h = mask.length;
        int w = h == 0 ? 0 : mask[0].length;
        final double INF = 1e9;
        double[][] d = new double[h][w];
        for (int i = 0; i < h; i++)
            for (int j = 0; j < w; j++)
                d[i][j] = (mask[i][j] == want) ? 0 : INF;

        final double DIAG = Math.sqrt(2.0);
        for (int i = 0; i < h; i++) {
            for (int j = 0; j < w; j++) {
                double v = d[i][j];
                if (i > 0) v = Math.min(v, d[i - 1][j] + 1);
                if (j > 0) v = Math.min(v, d[i][j - 1] + 1);
                if (i > 0 && j > 0) v = Math.min(v, d[i - 1][j - 1] + DIAG);
                if (i > 0 && j + 1 < w) v = Math.min(v, d[i - 1][j + 1] + DIAG);
                d[i][j] = v;
            }
        }
        for (int i = h - 1; i >= 0; i--) {
            for (int j = w - 1; j >= 0; j--) {
                double v = d[i][j];
                if (i + 1 < h) v = Math.min(v, d[i + 1][j] + 1);
                if (j + 1 < w) v = Math.min(v, d[i][j + 1] + 1);
                if (i + 1 < h && j + 1 < w) v = Math.min(v, d[i + 1][j + 1] + DIAG);
                if (i + 1 < h && j > 0) v = Math.min(v, d[i + 1][j - 1] + DIAG);
                d[i][j] = v;
            }
        }
        return d;
    }

    /**
     * 有符号离岸距离（blocks）：陆地为正、海上为负，岸线处为 0。
     *
     * <p>整片同质（全陆或全海）时距离是 {@code 1e9}，直接进梯度会算出 INF−INF=NaN。
     * 这里夹到网格对角线的有限饱和值（合法距离绝不超过它，所以不会误夹真值）：
     * 饱和区内梯度为 0，调用方据此判定"离岸太远、没有海陆效应"，而不是拿到一个随机方向。
     */
    public static double[][] signedDistanceBlocks(boolean[][] mask, double cellSize) {
        int h = mask.length;
        int w = h == 0 ? 0 : mask[0].length;
        double[][] toSea = chamfer(mask, true);
        double[][] toLand = chamfer(mask, false);
        double cap = cellSize * Math.hypot(w, h);
        double[][] out = new double[h][w];
        for (int i = 0; i < h; i++) {
            for (int j = 0; j < w; j++) {
                double v = mask[i][j] ? -toLand[i][j] * cellSize : toSea[i][j] * cellSize;
                if (!Double.isFinite(v) || Math.abs(v) > cap) v = mask[i][j] ? -cap : cap;
                out[i][j] = v;
            }
        }
        return out;
    }
}
