package net.atomos.zenith.client.render.tornado;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.MapColor;

/**
 * 一个龙卷在某一帧的全部可视参数。网格渲染器和体积渲染器共用，保证两档画质外形一致。
 */
public final class TornadoFrame {
    // 世界坐标（地面接触点）
    public double cx, cz, groundY;
    /** 云底离地高度（格）。 */
    public float height;
    /** 视觉核心半径（格）：服务端核心半径 × 系数，绳状期收细。 */
    public float coreR;
    /** 无量纲强度，见 {@link TornadoShape#strength}。 */
    public float strength;
    /** 漏斗下沿 zeta。 */
    public float bottom;
    /** 地面碎屑强度。 */
    public float debris;
    /** 绳状期 [0,1]。 */
    public float rope;
    /** 整体可见度（淡入淡出）。 */
    public float vis;
    public float tiltX, tiltZ, wobble, seed, spin, time, flash;
    /** 云底处漏斗半径（格），也是墙云内径。 */
    public float topR;
    /** 云底盘外径（格）。 */
    public float baseR;
    /** 墙云下沉量（格）。 */
    public float lowering;
    /** 碎屑颜色（取自地表方块）。 */
    public float dr = 0.42f, dg = 0.37f, db = 0.31f;

    private final float[] tmp = new float[2];

    /** 云底在离地多高（格）：80–112，并保证不顶破建筑上限。 */
    static float cloudBaseHeight(ClientLevel level, double groundY, float strength) {
        float h = 82f + 22f * Math.min(1f, strength);
        float maxY = level.getMaxBuildHeight() - 8f;
        return (float) Math.max(40.0, Math.min(h, maxY - groundY));
    }

    public void compute(TornadoTracker.Tracked t, ClientLevel level, float pt, float timeSec) {
        cx = t.x(pt);
        cz = t.z(pt);
        groundY = t.groundY(pt);
        float intensity = t.intensity(pt);
        rope = t.rope(pt);
        spin = t.spin == 0 ? 1f : t.spin;
        seed = t.seed;
        time = timeSec;
        flash = t.flash(pt);

        strength = TornadoShape.strength(intensity, t.maxWind);
        // 绳状期：真实龙卷消散时漏斗变细、拉长、扭成 S 形，但往往仍然连着地面，直到最后才断开。
        // 光靠强度衰减漏斗会直接缩回云里，所以绳状期给外形一个强度下限（核心半径另外收细），
        // 强度快到 0 时这个下限也跟着撤掉，漏斗最后才从下往上断开。
        strength = Math.max(strength, 0.92f * rope * TornadoShape.smoothstep(0.03f, 0.25f, intensity));
        vis = TornadoShape.smoothstep(0.01f, 0.18f, intensity);
        // 服务端核心半径 10–24 格；漏斗看上去比风核略细才对（风比云宽）
        coreR = Math.max(3.5f, t.coreRadius * 0.85f) * (1f - 0.55f * rope);
        height = cloudBaseHeight(level, groundY, strength);
        bottom = TornadoShape.funnelBottom(strength);
        debris = TornadoShape.groundDebris(strength) * (1f - 0.6f * rope);

        // 倾斜：地面受摩擦拖在后面，顶部领先；速度 12 格/秒时倾斜约 0.3
        double sp = Math.hypot(t.vx(), t.vz());
        float tilt = (float) Math.min(0.35, sp / 40.0) + 0.12f * rope;
        if (sp > 0.3) {
            tiltX = (float) (t.vx() / sp) * tilt;
            tiltZ = (float) (t.vz() / sp) * tilt;
        } else {
            tiltX = 0.08f * (float) Math.cos(seed);
            tiltZ = 0.08f * (float) Math.sin(seed);
        }
        wobble = coreR * (0.25f + 2.2f * rope);

        topR = coreR * Math.max(1.2f, TornadoShape.radiusFactor(0.97f, Math.max(strength, 0.2f)));
        // 母云云底要铺满大半个天空：太小就成了一朵孤零零的"蘑菇云"
        baseR = Math.max(220f, coreR * 14f);
        lowering = 5f + 10f * Math.min(1f, strength);

        sampleDebrisColor(level);
    }

    public float zetaToY(float zeta) {
        return (float) groundY + zeta * height;
    }

    /** 该高度漏斗半径（格），不凝结返回 0。 */
    public float radiusAt(float zeta) {
        return coreR * TornadoShape.radiusFactor(zeta, strength);
    }

    /** 该高度轴心的世界水平偏移，写进 out。 */
    public void axisAt(float zeta, float[] out) {
        TornadoShape.axisOffset(zeta, height, tiltX, tiltZ, wobble, seed, time, out);
    }

    public float axisX(float zeta) {
        axisAt(zeta, tmp);
        return tmp[0];
    }

    public float axisZ(float zeta) {
        axisAt(zeta, tmp);
        return tmp[1];
    }

    /** 云底某半径处的下沉（墙云）。 */
    public float baseLoweringAt(float r) {
        float k = r / Math.max(1f, topR * 2.2f);
        return lowering * (float) Math.exp(-k * k);
    }

    private void sampleDebrisColor(ClientLevel level) {
        BlockPos p = BlockPos.containing(cx, groundY - 1, cz);
        BlockState s = level.getBlockState(p);
        int col = 0;
        try {
            MapColor mc = s.getMapColor(level, p);
            if (mc != null) col = mc.col;
        } catch (RuntimeException ignored) {
            // 个别模组方块在客户端取地图色会抛错：退回默认土色
        }
        if (col == 0) {
            dr = 0.42f; dg = 0.37f; db = 0.31f;
            return;
        }
        float r = ((col >> 16) & 0xFF) / 255f;
        float g = ((col >> 8) & 0xFF) / 255f;
        float b = (col & 0xFF) / 255f;
        // 草地的地图色是鲜绿：碎屑云是被打碎的土、草屑和水汽，去饱和、压暗，再往土色拉
        float l = 0.3f * r + 0.59f * g + 0.11f * b;
        r = l + (r - l) * 0.35f;
        g = l + (g - l) * 0.35f;
        b = l + (b - l) * 0.35f;
        dr = 0.5f * r * 0.8f + 0.5f * 0.42f;
        dg = 0.5f * g * 0.8f + 0.5f * 0.37f;
        db = 0.5f * b * 0.8f + 0.5f * 0.31f;
    }
}
