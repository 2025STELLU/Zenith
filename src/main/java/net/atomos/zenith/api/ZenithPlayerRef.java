package net.atomos.zenith.api;

import java.util.Objects;
import java.util.UUID;

/** 玩家引用（无 MC 依赖的轻量标识）。 */
public final class ZenithPlayerRef {
    private final UUID uuid;
    private final ZenithWorldRef world;

    private ZenithPlayerRef(UUID uuid, ZenithWorldRef world) {
        this.uuid = uuid;
        this.world = world;
    }

    public static ZenithPlayerRef of(UUID uuid, ZenithWorldRef world) {
        return new ZenithPlayerRef(Objects.requireNonNull(uuid), Objects.requireNonNull(world));
    }

    public UUID uuid() { return uuid; }
    public ZenithWorldRef world() { return world; }

    @Override public boolean equals(Object o) {
        if (!(o instanceof ZenithPlayerRef p)) return false;
        return uuid.equals(p.uuid) && world.equals(p.world);
    }

    @Override public int hashCode() { return Objects.hash(uuid, world); }
}
