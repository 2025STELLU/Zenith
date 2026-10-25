package net.atomos.zenith.wind;

/**
 * 半球约定与坐标手性（SYS-01 / DRV-08 / WX-TY-01 的统一出处）。
 *
 * <p><b>坐标约定</b>：+X 东、+Z 南、+Y 上，yaw 0 朝南。这是一个**左手系**
 * （东 × 上 = 南，而右手系应收敛到北），所以把数学里的 (x, y) 映射到 (x, z) 时
 * 要额外翻一次符号。所有"旋转"公式都必须经过这里，不要各自写死符号。
 *
 * <p><b>南北半球</b>：科里奥利参数 {@code f = 2Ω·sin(φ)}。北半球 {@code f > 0}，
 * 低压环流是**逆时针**；南半球 {@code f < 0}，低压是**顺时针**。
 * {@link #coriolisSign()} 就是 {@code sign(f)}。
 *
 * <p><b>配置</b>：默认北半球（{@code +1}）。{@link #setHemisphereSign(double)} 由配置项
 * {@code zenith-server.toml: [weather] hemisphere = north|south} 在启动时调用；
 * 未调用时保持北半球，行为与 0.1.4 的文档描述一致。
 */
public final class HemisphereConvention {
    /** 北半球：低压逆时针、地转风按右手法则。 */
    public static final double NORTH = 1.0;
    /** 南半球：低压顺时针、地转风反向。 */
    public static final double SOUTH = -1.0;

    private static volatile double coriolisSign = NORTH;

    private HemisphereConvention() {}

    /** 当前科里奥利符号：北半球 {@code +1}、南半球 {@code -1}。 */
    public static double coriolisSign() {
        return coriolisSign;
    }

    /** 由配置调用。任何非负值都当北半球，只有明确传负值才是南半球。 */
    public static void setHemisphereSign(double sign) {
        coriolisSign = sign < 0 ? SOUTH : NORTH;
    }

    public static String name() {
        return coriolisSign > 0 ? "north" : "south";
    }

    /**
     * 切向单位向量：给出从中心指向采样点的 {@code (dx, dz)}，返回该点的切向。
     *
     * <p>右手系里逆时针是 {@code (-dz, dx)}；MC 的 (x,z) 是左手系，所以逆时针是
     * {@code (dz, -dx)}。乘上 {@code coriolisSign} 后：北半球低压 → 逆时针，
     * 南半球低压 → 顺时针。
     *
     * @param lowPressure 低压（气旋式）为 {@code true}，高压（反气旋式）为 {@code false}
     * @return {@code {tangentialX, tangentialZ}}，模长与 {@code (dx, dz)} 相同
     */
    public static double[] tangential(double dx, double dz, boolean lowPressure) {
        double orientation = lowPressure ? coriolisSign : -coriolisSign;
        return new double[]{dz * orientation, -dx * orientation};
    }

    /**
     * 地转风：{@code u = +∂p/∂z·K}、{@code w = −∂p/∂x·K}（北半球）。
     *
     * <p>推导：数学坐标里 {@code u = −(1/ρf)·∂p/∂y}、{@code v = +(1/ρf)·∂p/∂x}。
     * MC 的 +Z 是南（数学 +y 是北），故 {@code ∂p/∂y = −∂p/∂z}，代入得
     * {@code u = +∂p/∂z·K}。{@code v} 是北向分量，转成"南向的 w"再翻一次符号，
     * 得 {@code w = −∂p/∂x·K}。旧代码 {@code (-∂p/∂z, +∂p/∂x)} 两个分量都反，
     * 等于整体转 180°（SYS-01）。
     *
     * @return {@code {ug, wg}}（东向、南向，m/s）
     */
    public static double[] geostrophic(double dpDx, double dpDz, double scale) {
        double ug = dpDz * scale * coriolisSign;
        double wg = -dpDx * scale * coriolisSign;
        return new double[]{ug, wg};
    }

    /**
     * 径向分量符号：低压辐合（指向中心）、高压辐散（离开中心）。
     *
     * <p>调用方用 {@code (dx, dz)} = 中心→采样点的**外向**单位向量，
     * 因此需要乘 {@code -1}（辐合）或 {@code +1}（辐散）。旧代码
     * {@code inflowSign = lowPressure ? +1 : -1} 直接乘在外向上，得到的是
     * 低压辐散、高压辐合，与注释正好相反（DRV-08）。
     *
     * @return {@code -1} 低压辐合 / {@code +1} 高压辐散
     */
    public static double radialSign(boolean lowPressure) {
        return lowPressure ? -1.0 : 1.0;
    }
}
