package net.atomos.zenith.wind;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DRV-01：环面最小镜像位移。
 *
 * <p>这是整个 L0 采样链路的地基——气旋、对流团、龙卷足迹都靠它把 {@code [0, 384)} 的
 * 格坐标差折到 {@code [-192, 192)}。旧实现直接相减，跨接缝时算出 383 格的距离，
 * 于是接缝两侧的风场不连续、{@code x<0} / {@code z<0} 的象限只能吃到高斯尾巴。
 */
class WorldScaleDriverTest {

    /** {@code GRID_CELLS}：L0 网格边长（格）。 */
    private static final int TAU = 384;

    @Test
    @DisplayName("wrapDelta 值域恒为 [-T/2, T/2)")
    void wrapDeltaStaysInHalfOpenRange() {
        for (double d = -3000; d <= 3000; d += 0.5) {
            double w = WorldScaleDriver.wrapDelta(d, TAU);
            assertTrue(w >= -192.0 - 1e-9 && w < 192.0 + 1e-9,
                    "wrapDelta(" + d + ") = " + w + " 越界");
        }
    }

    @Test
    @DisplayName("wrapDelta 除环面接缝外无断裂")
    void wrapDeltaIsContinuousExceptAtSeam() {
        // ±T/2 处两个方向严格等价，跳变是环面拓扑的固有奇异点，不是 bug
        for (double d = -600; d <= 600; d += 0.5) {
            double w1 = WorldScaleDriver.wrapDelta(d, TAU);
            double w2 = WorldScaleDriver.wrapDelta(d + 0.5, TAU);
            if (Math.abs(w2 - w1) <= 0.5 + 1e-9) continue;
            boolean atSeam = Math.abs(Math.abs(w1) - 192.0) < 1e-9
                    || Math.abs(Math.abs(w2) - 192.0) < 1e-9;
            assertTrue(atSeam, "非接缝处断裂：" + d + " → " + w1 + " / " + w2);
        }
    }

    @Test
    @DisplayName("wrapDelta 关键点")
    void wrapDeltaKnownValues() {
        assertEquals(-0.5, WorldScaleDriver.wrapDelta(383.5, TAU), 1e-9);
        assertEquals(0.0, WorldScaleDriver.wrapDelta(768, TAU), 1e-9);
        assertEquals(1.0, WorldScaleDriver.wrapDelta(1, TAU), 1e-9);
        assertEquals(-1.0, WorldScaleDriver.wrapDelta(383, TAU), 1e-9);
        assertEquals(-1.0, WorldScaleDriver.wrapDelta(-1, TAU), 1e-9);
        // 整周期倍数必须精确归零，否则气旋会在接缝处"抖"一个格
        assertEquals(0.0, WorldScaleDriver.wrapDelta(-384, TAU), 1e-9);
        assertEquals(0.0, WorldScaleDriver.wrapDelta(1152, TAU), 1e-9);
    }

    @Test
    @DisplayName("wrapDelta 换算成方块后与格距一致")
    void wrapDeltaConvertsToBlocks() {
        double cellSize = 256.0;
        assertEquals(cellSize, WorldScaleDriver.wrapDelta(1, TAU) * cellSize, 1e-9);
        assertEquals(-cellSize, WorldScaleDriver.wrapDelta(383, TAU) * cellSize, 1e-9);
    }
}
