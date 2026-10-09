package net.atomos.zenith.client;

import net.atomos.zenith.api.ZenithVec3;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * 原版粒子的风响应：mixin 钩子调这里，采样源是客户端粗风场。
 * 算法搬自 Aerodynamics4MC（MIT），只换了采样实现。
 */
public final class ZenithParticleWind {
    private static final double MIN_WIND_SPEED = 0.01;
    private static final double METERS_PER_SECOND_TO_BLOCKS_PER_TICK = 1.0 / 20.0;

    private ZenithParticleWind() {}

    public static Vec3 applyCampfireSmoke(ClientLevel level, double x, double y, double z, Vec3 velocity) {
        ZenithVec3 wind = sampleWind(x, y + 0.6, z);
        if (wind.length() < MIN_WIND_SPEED) {
            return velocity;
        }
        Vec3 next = applyHorizontalResponse(velocity, wind, 0.035, 0.18);
        double updraft = Math.max(0.0, wind.y()) * 0.015;
        return new Vec3(next.x, Math.min(next.y + updraft, 0.28), next.z);
    }

    public static Vec3 applyTorchSmoke(ClientLevel level, double x, double y, double z, Vec3 velocity) {
        ZenithVec3 wind = sampleWind(x, y + 0.25, z);
        if (wind.length() < MIN_WIND_SPEED) {
            return velocity;
        }
        Vec3 next = applyHorizontalResponse(velocity, wind, 0.024, 0.10);
        return new Vec3(next.x, next.y + Math.max(0.0, wind.y()) * 0.006, next.z);
    }

    public static Vec3 applyTorchFlame(ClientLevel level, double x, double y, double z, Vec3 velocity) {
        ZenithVec3 wind = sampleWind(x, y + 0.12, z);
        if (wind.length() < MIN_WIND_SPEED) {
            return velocity;
        }
        Vec3 next = applyHorizontalResponse(velocity, wind, 0.012, 0.050);
        double vertical = Mth.clamp(velocity.y + wind.y() * 0.003, -0.02, 0.12);
        return new Vec3(next.x, vertical, next.z);
    }

    public static Vec3 groundDustTargetVelocity(
            ClientLevel level, double x, double y, double z, double windCoupling, double maxHorizontalSpeed) {
        ZenithVec3 wind = sampleWind(x, y + 0.08, z);
        if (wind.length() < MIN_WIND_SPEED) {
            return Vec3.ZERO;
        }
        return horizontalWindVelocity(wind, windCoupling, maxHorizontalSpeed);
    }

    private static ZenithVec3 sampleWind(double x, double y, double z) {
        return ClientWindState.get().sampleCoarse(x, y, z);
    }

    private static Vec3 applyHorizontalResponse(Vec3 velocity, ZenithVec3 wind, double response, double maxHorizontalSpeed) {
        double nextX = velocity.x + wind.x() * response;
        double nextZ = velocity.z + wind.z() * response;
        double horizontalSpeed = Math.sqrt(nextX * nextX + nextZ * nextZ);
        if (horizontalSpeed > maxHorizontalSpeed && horizontalSpeed > 1.0e-6) {
            double scale = maxHorizontalSpeed / horizontalSpeed;
            nextX *= scale;
            nextZ *= scale;
        }
        return new Vec3(nextX, velocity.y, nextZ);
    }

    private static Vec3 horizontalWindVelocity(ZenithVec3 wind, double windCoupling, double maxHorizontalSpeed) {
        double targetX = wind.x() * METERS_PER_SECOND_TO_BLOCKS_PER_TICK * windCoupling;
        double targetZ = wind.z() * METERS_PER_SECOND_TO_BLOCKS_PER_TICK * windCoupling;
        double horizontalSpeed = Math.sqrt(targetX * targetX + targetZ * targetZ);
        if (horizontalSpeed > maxHorizontalSpeed && horizontalSpeed > 1.0e-6) {
            double scale = maxHorizontalSpeed / horizontalSpeed;
            targetX *= scale;
            targetZ *= scale;
        }
        return new Vec3(targetX, 0.0, targetZ);
    }
}
