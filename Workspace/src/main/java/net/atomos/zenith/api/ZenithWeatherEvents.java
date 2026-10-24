package net.atomos.zenith.api;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * 天气事件的注册与分发（API-05）。
 *
 * <p>用 {@link CopyOnWriteArrayList}：监听器在模组初始化时注册、之后只读；
 * 分发发生在服务端 tick 线程，读操作无需加锁。取消注册用返回的句柄。
 */
public final class ZenithWeatherEvents {
    private ZenithWeatherEvents() {}

    private static final List<Consumer<ZenithWeatherEvent>> LISTENERS = new CopyOnWriteArrayList<>();

    /**
     * 注册监听器，返回取消句柄。
     *
     * <p>务必在 {@code FMLClientSetup}/{@code FMLCommonSetup} 或模组构造里注册一次，
     * 别在 tick 里反复注册。
     */
    public static Runnable register(Consumer<ZenithWeatherEvent> listener) {
        if (listener == null) return () -> {};
        LISTENERS.add(listener);
        return () -> LISTENERS.remove(listener);
    }

    /** 分发一个事件。监听器抛异常不得打断服务端 tick：逐条 try/catch。 */
    public static void fire(ZenithWeatherEvent event) {
        if (event == null || LISTENERS.isEmpty()) return;
        for (Consumer<ZenithWeatherEvent> l : LISTENERS) {
            try {
                l.accept(event);
            } catch (RuntimeException ignored) {
                // 第三方监听器的问题不该让天气停摆
            }
        }
    }

    /** 当前注册的监听器数量（诊断用）。 */
    public static int listenerCount() { return LISTENERS.size(); }
}
