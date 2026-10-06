package net.atomos.zenith.api;

import java.util.Objects;

/** 命名空间标识（namespace:path），与 Minecraft ResourceLocation 同构但无 MC 依赖。 */
public final class ZenithId {
    private final String namespace;
    private final String path;

    private ZenithId(String namespace, String path) {
        this.namespace = namespace;
        this.path = path;
    }

    public static ZenithId of(String namespace, String path) {
        Objects.requireNonNull(namespace, "namespace");
        Objects.requireNonNull(path, "path");
        return new ZenithId(namespace, path);
    }

    public static ZenithId parse(String s) {
        int i = s.indexOf(':');
        if (i < 0) return of("minecraft", s);
        return of(s.substring(0, i), s.substring(i + 1));
    }

    public String namespace() { return namespace; }
    public String path() { return path; }

    @Override public boolean equals(Object o) {
        if (!(o instanceof ZenithId id)) return false;
        return namespace.equals(id.namespace) && path.equals(id.path);
    }

    @Override public int hashCode() { return Objects.hash(namespace, path); }

    @Override public String toString() { return namespace + ":" + path; }
}
