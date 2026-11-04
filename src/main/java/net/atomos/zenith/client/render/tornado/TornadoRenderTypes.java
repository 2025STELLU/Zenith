package net.atomos.zenith.client.render.tornado;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.atomos.zenith.ZenithMod;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;

/**
 * 龙卷用的两个 RenderType。继承 {@link RenderType} 只是为了拿到受保护的 {@code RenderStateShard}。
 *
 * <p>用粒子着色器（{@code zenith:tornado_mesh}，即原版 {@code particle} 去掉 alpha 丢弃）而不是 {@code position_color_lightmap}：
 * <ul>
 *   <li>有贴图采样：可以滚动 UV 做"往上卷、绕轴转"的云纹，这是从"灰色塑料锥"变成"云"的关键；</li>
 *   <li>自带原版雾（含 1.21 的圆柱雾形状），不用再在 CPU 上逐顶点手算雾色；</li>
 *   <li>自带光照贴图，夜里、雷暴下自然变暗；</li>
 *   <li>光影包（Iris）认得粒子着色器，会替换成它自己的版本，不会画成一片白。</li>
 * </ul>
 * 半透明、双面、深度测试开、不写深度。
 */
public final class TornadoRenderTypes extends RenderType {
    private TornadoRenderTypes(String n, VertexFormat f, VertexFormat.Mode m, int b,
                               boolean c, boolean s, Runnable a, Runnable r) {
        super(n, f, m, b, c, s, a, r);
        throw new UnsupportedOperationException();
    }

    public static final ResourceLocation FUNNEL_TEXTURE =
            ResourceLocation.fromNamespaceAndPath(ZenithMod.MOD_ID, "textures/environment/tornado_funnel.png");
    public static final ResourceLocation DECK_TEXTURE =
            ResourceLocation.fromNamespaceAndPath(ZenithMod.MOD_ID, "textures/environment/tornado_deck.png");
    public static final ResourceLocation PUFF_TEXTURE =
            ResourceLocation.fromNamespaceAndPath(ZenithMod.MOD_ID, "textures/environment/tornado_puffs.png");

    /** 自己的软边粒子着色器；光影包在用或加载失败时，{@link TornadoShaders#meshShader()} 交还原版粒子着色器。 */
    private static final ShaderStateShard PARTICLE = new ShaderStateShard(TornadoShaders::meshShader);

    private static RenderType make(String name, ResourceLocation tex) {
        return RenderType.create(
                name, DefaultVertexFormat.PARTICLE, VertexFormat.Mode.QUADS, 1 << 16,
                false, false,
                CompositeState.builder()
                        .setShaderState(PARTICLE)
                        // blur = true：线性过滤，云边缘才是软的；贴图本身 clamp=false，UV 可以无限滚动
                        .setTextureState(new TextureStateShard(tex, true, false))
                        .setTransparencyState(TRANSLUCENT_TRANSPARENCY)
                        .setCullState(NO_CULL)
                        .setLightmapState(LIGHTMAP)
                        .setDepthTestState(LEQUAL_DEPTH_TEST)
                        .setWriteMaskState(COLOR_WRITE)
                        .createCompositeState(false));
    }

    /** 漏斗壳层、云底盘：可平铺的条纹云纹。 */
    public static final RenderType FUNNEL = make("zenith_tornado_funnel", FUNNEL_TEXTURE);
    /** 云底盘：各向同性的可平铺团块云纹。 */
    public static final RenderType DECK = make("zenith_tornado_deck", DECK_TEXTURE);
    /** 云团精灵：2×2 图集，四种形状。 */
    public static final RenderType PUFF = make("zenith_tornado_puff", PUFF_TEXTURE);
}
