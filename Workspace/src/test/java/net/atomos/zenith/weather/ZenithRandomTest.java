package net.atomos.zenith.weather;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SYS-08：可序列化随机源。
 *
 * <p>核心不变量只有一条：{@code state()} 相同 ⇒ 之后序列完全相同。
 * 存档只存一个 {@code long}，重启后天气必须从原处继续，否则"上一局那个龙卷"永远复现不了。
 */
class ZenithRandomTest {

    @Test
    @DisplayName("同种子序列逐位一致")
    void sameSeedSameSequence() {
        ZenithRandom a = new ZenithRandom(12345L);
        ZenithRandom b = new ZenithRandom(12345L);
        for (int i = 0; i < 1000; i++) {
            assertEquals(a.nextDouble(), b.nextDouble(), 0.0, "第 " + i + " 个样本不同");
        }
    }

    @Test
    @DisplayName("不同种子序列不同")
    void differentSeedsDiverge() {
        ZenithRandom a = new ZenithRandom(1L);
        ZenithRandom b = new ZenithRandom(2L);
        assertNotEquals(a.nextLong(), b.nextLong());
    }

    @Test
    @DisplayName("state()/setState() 往返可复现")
    void stateRoundTripIsReproducible() {
        ZenithRandom source = new ZenithRandom(999L);
        source.nextDouble();                 // 先走一步，避免落在初始状态上
        long snapshot = source.state();
        double expected = source.nextDouble();

        ZenithRandom restored = new ZenithRandom(0L);
        restored.setState(snapshot);
        assertEquals(expected, restored.nextDouble(), 0.0, "存档往返后序列断开");
    }

    @Test
    @DisplayName("reseed() 回到种子起点")
    void reseedRewindsSequence() {
        ZenithRandom r = new ZenithRandom(42L);
        double first = r.nextDouble();
        r.nextDouble();
        r.reseed(42L);
        assertEquals(first, r.nextDouble(), 0.0);
    }

    @Test
    @DisplayName("nextDouble 均值 ≈ 0.5、nextInt(n) 落在 [0,n)")
    void outputIsWellFormed() {
        ZenithRandom r = new ZenithRandom(7L);
        int n = 200_000;
        double sum = 0;
        for (int i = 0; i < n; i++) sum += r.nextDouble();
        assertEquals(0.5, sum / n, 0.005, "均值偏离");

        ZenithRandom bounded = new ZenithRandom(3L);
        for (int i = 0; i < 100_000; i++) {
            int v = bounded.nextInt(7);
            assertTrue(v >= 0 && v < 7, "nextInt(7) 越界：" + v);
        }
    }

    @Test
    @DisplayName("周期远大于存档尺度：连续 10 万个状态不重复")
    void stateDoesNotRepeatQuickly() {
        ZenithRandom r = new ZenithRandom(2026L);
        long previous = r.state();
        for (int i = 0; i < 100_000; i++) {
            r.nextLong();
            long now = r.state();
            assertNotEquals(previous, now, "状态在 " + i + " 步后重复");
            previous = now;
        }
    }
}
