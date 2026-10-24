package net.atomos.zenith.api;

/**
 * 公开 API 的版本与能力集（API-05）。
 *
 * <h2>为什么需要它</h2>
 * 旧版 {@code api/} 目录 21 个文件里没有任何版本常量：第三方模组无法在编译期或运行期
 * 判断"这个 Zenith 支不支持 {@code runL2} / {@code sampleBatch} / 地形采样"。
 * {@code isAvailable()} 只能说明 provider 非空。
 *
 * <h2>版本规则</h2>
 * <ul>
 *   <li>向后兼容的新增（新方法、新枚举值、新常量）→ 提升 {@link #API_VERSION_MINOR}；</li>
 *   <li>删除/改签名/改语义（比如枚举顺序、返回单位）→ 提升 {@link #API_VERSION_MAJOR}。</li>
 * </ul>
 *
 * <p><b>0.2 相对 0.1 的不兼容变更</b>：{@link ZenithWindSample.Source} 增加了
 * {@link ZenithWindSample.Source#PHENOMENA} 与 {@link ZenithWindSample.Source#COMPOSITE}，
 * 且 {@code Source} 不再等于"实际参与层"——多层的合成结果会标成 {@code COMPOSITE}。
 * 依赖 {@code source == L1_COARSE} 判断"只有背景风"的代码必须改用
 * {@link ZenithWindSample#layers()}。
 */
public final class ZenithApiVersion {
    private ZenithApiVersion() {}

    /** 当前 API 版本（{@code major.minor}）。 */
    public static final String API_VERSION = "0.2";

    public static final int API_VERSION_MAJOR = 0;
    public static final int API_VERSION_MINOR = 2;

    /** 是否至少是给定版本。用于"这个能力有没有"的粗判。 */
    public static boolean isAtLeast(int major, int minor) {
        return API_VERSION_MAJOR > major
                || (API_VERSION_MAJOR == major && API_VERSION_MINOR >= minor);
    }
}
