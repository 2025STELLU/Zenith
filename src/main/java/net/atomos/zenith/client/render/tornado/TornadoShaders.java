package net.atomos.zenith.client.render.tornado;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.logging.LogUtils;
import net.atomos.zenith.ZenithMod;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterShadersEvent;
import org.slf4j.Logger;

import javax.annotation.Nullable;

/**
 * 龙卷的两个自定义着色器，以及光影包检测。
 *
 * <ul>
 *   <li>{@code tornado_mesh}：原版粒子着色器去掉 {@code alpha < 0.1 discard}。
 *       原版那条丢弃让软边云团在 10% 透明度处被切出一圈硬边，大面积叠云时非常显眼；</li>
 *   <li>{@code tornado_volume}：可选的体积光线步进（"体积云"画质）。</li>
 * </ul>
 * 任一着色器加载失败，或检测到光影包在用，都退回原版粒子着色器 / 网格画质，不会崩。
 */
@EventBusSubscriber(value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
public final class TornadoShaders {
    private TornadoShaders() {}

    private static final Logger LOG = LogUtils.getLogger();

    @Nullable private static ShaderInstance mesh;
    @Nullable private static ShaderInstance volume;

    @SubscribeEvent
    public static void onRegisterShaders(RegisterShadersEvent event) {
        try {
            event.registerShader(new ShaderInstance(event.getResourceProvider(),
                    ResourceLocation.fromNamespaceAndPath(ZenithMod.MOD_ID, "tornado_mesh"),
                    DefaultVertexFormat.PARTICLE), s -> mesh = s);
        } catch (Exception e) {
            mesh = null;
            LOG.warn("[Zenith] tornado_mesh shader failed to load, falling back to vanilla particle shader", e);
        }
        try {
            event.registerShader(new ShaderInstance(event.getResourceProvider(),
                    ResourceLocation.fromNamespaceAndPath(ZenithMod.MOD_ID, "tornado_volume"),
                    DefaultVertexFormat.POSITION), s -> volume = s);
        } catch (Exception e) {
            volume = null;
            LOG.warn("[Zenith] tornado_volume shader failed to load, volumetric tornado disabled", e);
        }
    }

    /** 网格用的着色器：自己的优先；光影包在用时交还原版粒子着色器，让光影包接管。 */
    public static ShaderInstance meshShader() {
        if (mesh == null || shaderPackInUse()) return GameRenderer.getParticleShader();
        return mesh;
    }

    @Nullable
    public static ShaderInstance volumeShader() {
        return shaderPackInUse() ? null : volume;
    }

    // ---------------------------------------------------------------- Iris

    private static long irisCheckedAt = Long.MIN_VALUE;
    private static boolean irisInUse;
    private static Object irisApi;
    private static java.lang.reflect.Method irisMethod;
    private static boolean irisMissing;

    /** 反射查 Iris，不加编译期依赖；每秒最多查一次。 */
    public static boolean shaderPackInUse() {
        if (irisMissing) return false;
        long now = System.currentTimeMillis();
        if (now - irisCheckedAt < 1000) return irisInUse;
        irisCheckedAt = now;
        try {
            if (irisApi == null) {
                Class<?> c = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
                irisApi = c.getMethod("getInstance").invoke(null);
                irisMethod = c.getMethod("isShaderPackInUse");
            }
            irisInUse = (Boolean) irisMethod.invoke(irisApi);
        } catch (ClassNotFoundException e) {
            irisMissing = true;
            irisInUse = false;
        } catch (ReflectiveOperationException | RuntimeException e) {
            irisInUse = false;
        }
        return irisInUse;
    }
}
