package net.atomos.zenith.weather;

import java.util.Random;

/**
 * 可序列化的确定性随机源（SplitMix64）。
 *
 * <p><b>为什么不用 {@code new Random()}</b>：现象系统原来每个都 {@code new Random()}，种子来自
 * {@code System.nanoTime()}，同一个存档重启后天气完全不同，也没法复现玩家报告的"上一局那个龙卷"
 * （SYS-08）。{@link java.util.Random} 不暴露内部 48 位种子，存档里存不回去。
 *
 * <p><b>为什么继承 {@code Random}</b>：{@code Random} 的所有公开方法（{@code nextDouble} /
 * {@code nextInt} / {@code nextBoolean} / {@code nextGaussian}）最终都走 {@code next(int bits)}，
 * 覆写它就能完全掌控序列，而现有代码一行都不用改。SplitMix64 的周期是 2⁶⁴、状态就是一个
 * {@code long}，直接进 NBT。
 *
 * <p>两个实例只要 {@link #state()} 相同，之后的序列就完全一致，与平台/时间无关。
 */
public final class ZenithRandom extends Random {
    private static final long GOLDEN_GAMMA = 0x9E3779B97F4A7C15L;

    private long state;

    public ZenithRandom(long seed) {
        super(0L);
        this.state = seed;
    }

    @Override
    protected int next(int bits) {
        state += GOLDEN_GAMMA;
        long z = state;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        z ^= z >>> 31;
        return (int) (z >>> (64 - bits));
    }

    /** 当前 64 位状态，直接写进 NBT。 */
    public long state() {
        return state;
    }

    public void setState(long state) {
        this.state = state;
    }

    /** 用世界种子重新播种。 */
    public void reseed(long seed) {
        this.state = seed;
    }
}
