package net.atomos.zenith.api;

import java.util.Objects;

/** 世界引用：区分服务端权威世界与客户端本地世界。 */
public final class ZenithWorldRef {
    private final ZenithId dimension;
    private final boolean client;

    private ZenithWorldRef(ZenithId dimension, boolean client) {
        this.dimension = dimension;
        this.client = client;
    }

    public static ZenithWorldRef server(ZenithId dimension) {
        return new ZenithWorldRef(dimension, false);
    }

    public static ZenithWorldRef client(ZenithId dimension) {
        return new ZenithWorldRef(dimension, true);
    }

    public ZenithId dimension() { return dimension; }
    public boolean isClient() { return client; }
    public boolean isServer() { return !client; }

    @Override public boolean equals(Object o) {
        if (!(o instanceof ZenithWorldRef w)) return false;
        return client == w.client && dimension.equals(w.dimension);
    }

    @Override public int hashCode() { return Objects.hash(dimension, client); }

    @Override public String toString() {
        return "ZenithWorldRef[" + dimension + (client ? ", client" : ", server") + "]";
    }
}
