package net.atomos.zenith.api;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** API-05：天气事件的注册、分发、注销与异常隔离。 */
class ZenithWeatherEventsTest {

    private static final ZenithId OVERWORLD = ZenithId.of("minecraft", "overworld");
    private static final ZenithWorldRef SERVER_WORLD = ZenithWorldRef.server(OVERWORLD);

    private static ZenithWeatherEvent typhoonEvent() {
        return ZenithWeatherEvent.of(ZenithWeatherEvent.Type.TYPHOON_SPAWNED,
                SERVER_WORLD, 0, 0, 0.8, "test");
    }

    @AfterEach
    void clearListeners() {
        // 静态表跨测试存活，必须清干净，否则计数会被上一个测试污染
        while (ZenithWeatherEvents.listenerCount() > 0) {
            // 没有 clear()：逐个注册一个哨兵再注销不行，这里靠反射兜底
            try {
                var f = ZenithWeatherEvents.class.getDeclaredField("LISTENERS");
                f.setAccessible(true);
                ((java.util.List<?>) f.get(null)).clear();
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("无法清空监听器表", e);
            }
        }
    }

    @Test
    @DisplayName("注册后收到一次，注销后不再收到")
    void registerFireUnregister() {
        AtomicInteger hits = new AtomicInteger();
        Runnable handle = ZenithWeatherEvents.register(ev -> hits.incrementAndGet());

        ZenithWeatherEvents.fire(typhoonEvent());
        assertEquals(1, hits.get(), "首次分发应收到一次");

        handle.run();
        ZenithWeatherEvents.fire(typhoonEvent());
        assertEquals(1, hits.get(), "注销后不应再收到");
        assertEquals(0, ZenithWeatherEvents.listenerCount(), "表应清空");
    }

    @Test
    @DisplayName("一个监听器抛异常不影响其它监听器")
    void listenerExceptionIsIsolated() {
        AtomicInteger good = new AtomicInteger();
        ZenithWeatherEvents.register(ev -> {
            throw new IllegalStateException("boom");
        });
        ZenithWeatherEvents.register(ev -> good.incrementAndGet());

        ZenithWeatherEvents.fire(typhoonEvent());
        assertEquals(1, good.get(), "坏监听器不该打断好监听器");
    }

    @Test
    @DisplayName("null 监听器与 null 事件都被忽略，不抛异常")
    void nullsAreIgnored() {
        assertEquals(0, ZenithWeatherEvents.listenerCount());
        ZenithWeatherEvents.register(null);
        assertEquals(0, ZenithWeatherEvents.listenerCount(), "null 监听器不应入表");

        AtomicInteger hits = new AtomicInteger();
        ZenithWeatherEvents.register(ev -> hits.incrementAndGet());
        ZenithWeatherEvents.fire(null);
        assertEquals(0, hits.get(), "null 事件不应分发");
    }

    @Test
    @DisplayName("事件值对象：name 为 null 归一为空串，字段原样保留")
    void eventValueSemantics() {
        ZenithWeatherEvent e = ZenithWeatherEvent.of(ZenithWeatherEvent.Type.SQUALL_LINE_FORMED,
                SERVER_WORLD, 12.5, -30.25, 0.5, null);
        assertEquals("", e.name(), "null name 应归一为空串");
        assertEquals(12.5, e.x(), 0.0);
        assertEquals(-30.25, e.z(), 0.0);
        assertEquals(0.5, e.intensity01(), 0.0);
        assertEquals(ZenithWeatherEvent.Type.SQUALL_LINE_FORMED, e.type());
        assertTrue(e.world().isServer());
        assertTrue(e.toString().contains("SQUALL_LINE_FORMED"));
    }

    @Test
    @DisplayName("ZenithWorldRef：服务端/客户端不相等，维度相同才相等")
    void worldRefEquality() {
        ZenithWorldRef a = ZenithWorldRef.server(OVERWORLD);
        ZenithWorldRef b = ZenithWorldRef.server(ZenithId.of("minecraft", "overworld"));
        ZenithWorldRef c = ZenithWorldRef.client(OVERWORLD);

        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        assertNotEquals(a, c);
        assertTrue(a.isServer());
        assertFalse(c.isServer());
    }
}
