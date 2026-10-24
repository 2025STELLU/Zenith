package net.atomos.zenith.entity;

/**
 * 帆船物理：视风 × 帆角 × 迎角 → 驱动力。
 *
 * 简化模型：视风 A = 真风 − 船速；受风角（风向与船艏夹角）<30° 是逆风死区，
 * ~90° 横风最快；驱动力 F = ½ρ|A|²·帆面积·driveCoeff·帆 trim；
 * 船体阻力 ∝ v²；横向水阻力大，横漂（leeway）自然就小。
 */
public final class SailingPhysics {
    private SailingPhysics() {}

    public static final double AIR_DENSITY = 1.225;
    public static final double SAIL_AREA_M2 = 12.0;
    public static final double HULL_DRAG_K = 28.0;
    public static final double LATERAL_RESISTANCE_K = 320.0;
    public static final double NO_GO_ZONE_RADIANS = Math.toRadians(30);

    /** 单步物理推进（dt 秒），返回 (ax, az, driveCoeff)。 */
    public static double[] step(
            double windX, double windZ,       // 真风 m/s
            double velX, double velZ,         // 当前船速 m/s
            double headingRadians,            // 艏向（atan2 约定：0=+Z）
            double sailTrim01,                // 帆 trim 0..1
            double rudderInput) {             // 舵 -1..1（本函数不改艏向，只算力）

        // 视风
        double ax = windX - velX;
        double az = windZ - velZ;
        double apparentSpeed = Math.hypot(ax, az);

        // 风的来向
        double windFrom = Math.atan2(-windX, -windZ);
        double pointOfSail = angleDiff(windFrom, headingRadians);
        double absPoint = Math.abs(pointOfSail);

        double driveCoeff;
        if (absPoint < NO_GO_ZONE_RADIANS || apparentSpeed < 0.2) {
            driveCoeff = 0;
        } else {
            // 横风 (~90°) 系数最大，顺风次之
            double t = (absPoint - NO_GO_ZONE_RADIANS) / (Math.PI - NO_GO_ZONE_RADIANS);
            driveCoeff = Math.sin(Math.PI * Math.min(1, 0.15 + t * 0.85)) * 0.9 + 0.1;
            driveCoeff *= 0.55 + 0.45 * t; // 顺风略弱于横风
        }

        double force = 0.5 * AIR_DENSITY * apparentSpeed * apparentSpeed
                * SAIL_AREA_M2 * driveCoeff * sailTrim01;

        // 推力方向：基本沿艏向，带少量视风偏转
        double pushDir = headingRadians + pointOfSail * 0.12;
        double fx = Math.sin(pushDir) * force;
        double fz = Math.cos(pushDir) * force;

        // 船体阻力（沿速度反方向）
        double speed = Math.hypot(velX, velZ);
        if (speed > 1e-4) {
            double drag = HULL_DRAG_K * speed * speed;
            fx -= velX / speed * drag;
            fz -= velZ / speed * drag;
        }
        // 横向水阻力（抑制横漂）
        double fwdX = Math.sin(headingRadians), fwdZ = Math.cos(headingRadians);
        double lateralVel = velX * fwdZ - velZ * fwdX; // 右舷为正的横向速度
        fx -= fwdZ * lateralVel * LATERAL_RESISTANCE_K * 0.05;
        fz += fwdX * lateralVel * LATERAL_RESISTANCE_K * 0.05;

        // 船质量 ~800kg
        double mass = 800.0;
        return new double[]{fx / mass, fz / mass, driveCoeff};
    }

    /** 艏向更新：舵效随船速增加。 */
    public static double updateHeading(double heading, double rudderInput, double speed, double dt) {
        double turnRate = rudderInput * Math.min(1.2, speed * 0.25) * 0.9;
        return heading + turnRate * dt;
    }

    private static double angleDiff(double a, double b) {
        double d = a - b;
        while (d > Math.PI) d -= Math.PI * 2;
        while (d < -Math.PI) d += Math.PI * 2;
        return d;
    }
}
