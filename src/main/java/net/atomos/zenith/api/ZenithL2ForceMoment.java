package net.atomos.zenith.api;

/** 力/力矩（牛顿 / 牛顿·米），绕给定参考点。 */
public final class ZenithL2ForceMoment {
    private final ZenithVec3 force;
    private final ZenithVec3 moment;

    private ZenithL2ForceMoment(ZenithVec3 force, ZenithVec3 moment) {
        this.force = force;
        this.moment = moment;
    }

    public static ZenithL2ForceMoment of(ZenithVec3 force, ZenithVec3 moment) {
        return new ZenithL2ForceMoment(force, moment);
    }

    public static ZenithL2ForceMoment zero() {
        return new ZenithL2ForceMoment(ZenithVec3.ZERO, ZenithVec3.ZERO);
    }

    public ZenithVec3 force() { return force; }
    public ZenithVec3 moment() { return moment; }
}
