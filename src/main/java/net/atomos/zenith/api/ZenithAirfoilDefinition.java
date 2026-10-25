package net.atomos.zenith.api;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** 翼型定义：标识 + 有序坐标点（上表面前缘→后缘→下表面后缘→前缘）。 */
public final class ZenithAirfoilDefinition {
    private final ZenithId id;
    private final String displayName;
    private final List<ZenithAirfoilCoordinate> coordinates;

    public ZenithAirfoilDefinition(ZenithId id, String displayName,
                                   List<ZenithAirfoilCoordinate> coordinates) {
        this.id = Objects.requireNonNull(id);
        this.displayName = Objects.requireNonNull(displayName);
        this.coordinates = Collections.unmodifiableList(coordinates);
    }

    public ZenithId id() { return id; }
    public String displayName() { return displayName; }
    public List<ZenithAirfoilCoordinate> coordinates() { return coordinates; }

    /**
     * 最大相对厚度：上下表面对应站位的 y 差最大值。
     *
     * <p>旧实现是 {@code 2 · max|y|}（API-10 相关）：有弯度时最大 y 与最小 y 通常
     * 落在不同弦向位置，2·max|y| 会把弯度算成厚度（NACA 2412 报 ~0.24 而不是 0.12）。
     *
     * <p>这里按 {@link ZenithAirfoilDefinition} 的约定解码点序：前 N+1 个是上表面
     * （前缘→后缘），随后 N−1 个是下表面（后缘→前缘）。别按 x 分桶——NACA 的
     * 上下表面 x 相差 {@code 2·yt·sinθ}（前缘附近可达 1e-4..1e-2），分桶会错位。
     * 点序不符约定时退回 {@code 2·max|y|}（对对称翼型仍然正确）。
     */
    public double maxThickness() {
        int total = coordinates.size();
        if (total >= 4 && total % 2 == 0) {
            int upperCount = total / 2 + 1;   // N+1
            int lowerCount = total / 2 - 1;   // N-1
            if (lowerCount >= 1 && isUpperLowerLayout(upperCount, lowerCount)) {
                double max = 0;
                for (int i = 1; i <= lowerCount; i++) {
                    // 上表面第 i 站 ↔ 下表面第 (lowerCount - i) 个（下表面是倒序的）
                    ZenithAirfoilCoordinate up = coordinates.get(i);
                    ZenithAirfoilCoordinate lo = coordinates.get(upperCount + (lowerCount - i));
                    max = Math.max(max, Math.abs(up.y() - lo.y()));
                }
                return max;
            }
        }
        double max = 0;
        for (ZenithAirfoilCoordinate c : coordinates) max = Math.max(max, Math.abs(c.y()));
        return max * 2.0;
    }

    /** 校验"上表面前缘→后缘，下表面后缘→前缘"的点序是否成立。 */
    private boolean isUpperLowerLayout(int upperCount, int lowerCount) {
        ZenithAirfoilCoordinate lead = coordinates.get(0);
        ZenithAirfoilCoordinate trail = coordinates.get(upperCount - 1);
        if (Math.abs(lead.x()) > 0.05 || Math.abs(trail.x() - 1.0) > 0.05) return false;
        // 上表面 x 单调不减，下表面 x 单调不增。
        //
        // 容差 1e-2 是必需的：NACA 点按**表面法向**偏移生成，前缘附近
        // x 会被 yt·sinθ 往负方向拉一点（4412 的第 1 站 x ≈ −2.2e-4），
        // 严格的 0 容差会让它误判成"点序不符"从而退回 2·max|y|（API-10）。
        // 真正的乱序（下表面被排到前面）偏差是 O(0.1) 量级，1e-2 足够区分。
        final double xTolerance = 1e-2;
        for (int i = 1; i < upperCount; i++) {
            if (coordinates.get(i).x() < coordinates.get(i - 1).x() - xTolerance) return false;
        }
        for (int i = 1; i < lowerCount; i++) {
            if (coordinates.get(upperCount + i).x()
                    > coordinates.get(upperCount + i - 1).x() + xTolerance) {
                return false;
            }
        }
        return true;
    }
}
