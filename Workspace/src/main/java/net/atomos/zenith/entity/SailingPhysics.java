package net.atomos.zenith.entity;

/**
 * 帆船物理：视风 × 帆角 × 迎角 → 驱动力。
 *
 * <p><b>坐标约定（SAIL-02）</b>：{@code headingRadians} 就是 MC 实体的 {@code yaw}（弧度），
 * 艏向单位矢量 {@code fwd = (-sin h, cos h)}——与 {@code Entity#getLookAngle()} 在水平面上的
 * 投影同号。于是 {@code h = 0} 朝 +Z（南）、{@code h = +90°} 朝 -X（西）、{@code h = -90°} 朝 +X（东）。
 * 0.1.4 用的是 {@code (sin h, cos h)}，与 MC 差一个 x 符号，渲染器只好用 {@code 180 - yaw}
 * 硬掰回来，导致"物理往东、实体朝向标西"。
 *
 * <p>简化模型：视风 A = 真风 − 船速；受风角 &lt; 30° 是逆风死区（用 smoothstep 过渡，
 * 不再有"偏 1° 就有 10% 推力"的阶跃）；横风最快、正顺风保留 45%；
 * 驱动力 F = ½ρ|A|²·帆面积·driveCoeff·帆 trim；船体阻力 ∝ v²；
 * 横向水阻力 c ≈ 600 N/(m/s)（τ = m/c ≈ 1.3 s），横漂（leeway）自然就小。
 */
public final class SailingPhysics {
    private SailingPhysics() {}

    public static final double AIR_DENSITY = 1.225;
    public static final double SAIL_AREA_M2 = 12.0;
    /** 船体沿航向的二次阻力系数：F = K·v²。 */
    public static final double HULL_DRAG_K = 28.0;
    /**
     * 横向（横漂）线性阻尼，单位 N/(m/s)（SAIL-04）。
     *
     * <p>船质量 800 kg，横漂衰减时间常数 τ = m/c = 800/600 ≈ 1.33 s，落在真实小帆船
     * 龙骨/稳向板 1–2 s 的区间里。0.1.4 是 320 再乘 0.05（等于 16）→ τ = 50 s，
     * 松舵后船会在冰上一样滑半分钟。
     */
    public static final double LATERAL_RESISTANCE_K = 600.0;
    public static final double NO_GO_ZONE_RADIANS = Math.toRadians(30);
    /** 逆风死区外沿的 smoothstep 过渡宽度（占"死区→正顺风"整个区间的比例）。 */
    public static final double NO_GO_FADE = 0.12;
    /** 正顺风时保留的推进系数上限（对应约 45% 的横风速度）。 */
    public static final double DOWNWIND_CEILING = 0.45;
    /** 船体质量（kg）。 */
    public static final double HULL_MASS_KG = 800.0;

    /** 单步物理推进（dt 秒），返回 (ax, az, driveCoeff)。 */
    public static double[] step(
            double windX, double windZ,       // 真风 m/s
            double velX, double velZ,         // 当前船速 m/s
            double headingRadians,            // 艏向 = MC yaw 弧度（0=+Z）
            double sailTrim01,                // 帆 trim 0..1
            double rudderInput) {             // 舵 -1..1（本函数不改艏向，只算力）

        // 视风
        double ax = windX - velX;
        double az = windZ - velZ;
        double apparentSpeed = Math.hypot(ax, az);

        // 风的来向（MC yaw 约定：dir_yaw(v) = atan2(-v.x, v.z)）。
        // 来向矢量为 -wind，代入得 yaw_from = atan2(windX, -windZ)。
        double windFrom = Math.atan2(windX, -windZ);
        double pointOfSail = angleDiff(windFrom, headingRadians);
        double absPoint = Math.abs(pointOfSail);

        double driveCoeff = driveCoefficient(absPoint, apparentSpeed);

        double force = 0.5 * AIR_DENSITY * apparentSpeed * apparentSpeed
                * SAIL_AREA_M2 * driveCoeff * sailTrim01;

        // 推力方向：基本沿艏向，再叠一个**随风舷侧**的小偏角（leeway）。
        //
        // 偏角取 0.12·sin(pointOfSail) 而不是 0.12·pointOfSail：真实的横漂是风压的
        // 侧向分量推出来的，正顶风与正顺风时侧向分量都为 0，不该有偏航；
        // 旧式按整角线性加权，正顺风时会偏 0.12π ≈ 22°，船"斜着跑"。
        double pushDir = headingRadians - 0.12 * Math.sin(pointOfSail);
        double fx = -Math.sin(pushDir) * force;
        double fz = Math.cos(pushDir) * force;

        // 船体阻力（沿速度反方向）
        double speed = Math.hypot(velX, velZ);
        if (speed > 1e-4) {
            double drag = HULL_DRAG_K * speed * speed;
            fx -= velX / speed * drag;
            fz -= velZ / speed * drag;
        }

        // 横向水阻力（抑制横漂）。fwd = (-sin h, cos h)，其"右手侧"法向取 n = (-fwdZ, fwdX)，
        // 于是 lateralVel = -(v·n)，力取 +lateralVel·n·c 恰好与横漂反向。
        double fwdX = -Math.sin(headingRadians), fwdZ = Math.cos(headingRadians);
        double lateralVel = velX * fwdZ - velZ * fwdX;
        fx -= fwdZ * lateralVel * LATERAL_RESISTANCE_K;
        fz += fwdX * lateralVel * LATERAL_RESISTANCE_K;

        return new double[]{fx / HULL_MASS_KG, fz / HULL_MASS_KG, driveCoeff};
    }

    /**
     * 受风角 → 推进系数（SAIL-05）。
     *
     * <p>{@code t} 是"从死区外沿到正顺风"的归一化角度：{@code t=0} 为 30°、{@code t=0.5} 为 105°、
     * {@code t=1} 为 180°。
     *
     * <ul>
     *   <li>横风主峰 {@code 0.85·sin(πt)^0.7}：{@code t=0.5} 取 0.85，两侧平滑降到 0；</li>
     *   <li>顺风下限 {@code 0.45·smoothstep(t)}：正顺风 0.45，使球帆/蝴蝶帆那种"顺风也能跑"的
     *       物理存在（0.1.4 顺风只有 0.1，比横风慢 10 倍）；</li>
     *   <li>死区外沿 {@code smoothstep(t/0.2)}：消除"正顶风停、偏 1° 突然有 10% 推力"的阶跃。</li>
     * </ul>
     */
    public static double driveCoefficient(double absPointRadians, double apparentSpeed) {
        if (apparentSpeed < 0.2) return 0;
        double t = (absPointRadians - NO_GO_ZONE_RADIANS)
                / (Math.PI - NO_GO_ZONE_RADIANS);
        if (t <= 0) return 0;
        if (t > 1) t = 1;
        double gate = smoothstep(t / NO_GO_FADE);
        double crossWind = 0.85 * Math.pow(Math.sin(Math.PI * t), 0.7);
        double downwind = DOWNWIND_CEILING * smoothstep(t);
        return gate * Math.max(crossWind, downwind);
    }

    /** 艏向更新：舵效随船速增加。rudder &gt; 0 = 右转（yaw 增大）。 */
    public static double updateHeading(double heading, double rudderInput, double speed, double dt) {
        double turnRate = rudderInput * Math.min(1.2, speed * 0.25) * 0.9;
        return heading + turnRate * dt;
    }

    /** 经典 Hermite smoothstep，输入夹到 [0,1]。 */
    public static double smoothstep(double t) {
        double x = t <= 0 ? 0 : (t >= 1 ? 1 : t);
        return x * x * (3 - 2 * x);
    }

    private static double angleDiff(double a, double b) {
        double d = a - b;
        while (d > Math.PI) d -= Math.PI * 2;
        while (d < -Math.PI) d += Math.PI * 2;
        return d;
    }
}
