package net.atomos.zenith.client.render.tornado;

import com.mojang.blaze3d.vertex.VertexConsumer;
import org.joml.Matrix4f;

/**
 * 可排序的半透明四边形缓存。坐标是相机相对坐标（相机在原点），所以深度就是质心到原点的距离平方。
 * 每帧复用，不产生垃圾。
 */
final class QuadList {
    /** 每个顶点：x y z u v r g b a。 */
    private static final int VF = 9;
    private static final int QF = VF * 4;

    private float[] data = new float[QF * 256];
    private int[] light = new int[256];
    private float[] depth = new float[256];
    private int[] order = new int[256];
    private int count;

    void clear() { count = 0; }

    int size() { return count; }

    private void ensure() {
        if (count < depth.length) return;
        int n = depth.length * 2;
        data = java.util.Arrays.copyOf(data, n * QF);
        light = java.util.Arrays.copyOf(light, n);
        depth = java.util.Arrays.copyOf(depth, n);
        order = java.util.Arrays.copyOf(order, n);
    }

    /** 开始一个四边形，返回它的写入偏移；随后用 {@link #vtx} 依次写 4 个顶点。 */
    int begin(int packedLight) {
        ensure();
        light[count] = packedLight;
        return count++ * QF;
    }

    void vtx(int base, int k, float x, float y, float z, float u, float v,
             float r, float g, float b, float a) {
        int o = base + k * VF;
        data[o] = x; data[o + 1] = y; data[o + 2] = z;
        data[o + 3] = u; data[o + 4] = v;
        data[o + 5] = r; data[o + 6] = g; data[o + 7] = b; data[o + 8] = a;
    }

    /** 写完 4 个顶点后算深度。 */
    void end(int base) {
        int q = base / QF;
        float cx = 0, cy = 0, cz = 0;
        for (int k = 0; k < 4; k++) {
            int o = base + k * VF;
            cx += data[o]; cy += data[o + 1]; cz += data[o + 2];
        }
        cx *= 0.25f; cy *= 0.25f; cz *= 0.25f;
        depth[q] = cx * cx + cy * cy + cz * cz;
    }

    /** 由远到近排序（希尔排序：几百个元素、每帧接近有序，比装箱 sort 快得多）。 */
    void sortBackToFront() {
        for (int i = 0; i < count; i++) order[i] = i;
        for (int gap = count / 2; gap > 0; gap /= 2) {
            for (int i = gap; i < count; i++) {
                int key = order[i];
                float kd = depth[key];
                int j = i;
                while (j >= gap && depth[order[j - gap]] < kd) {
                    order[j] = order[j - gap];
                    j -= gap;
                }
                order[j] = key;
            }
        }
    }

    void emit(VertexConsumer vc, Matrix4f m) {
        for (int i = 0; i < count; i++) {
            int q = order[i];
            int base = q * QF;
            int l = light[q];
            for (int k = 0; k < 4; k++) {
                int o = base + k * VF;
                vc.addVertex(m, data[o], data[o + 1], data[o + 2])
                        .setUv(data[o + 3], data[o + 4])
                        .setColor(data[o + 5], data[o + 6], data[o + 7], data[o + 8])
                        .setLight(l);
            }
        }
    }
}
