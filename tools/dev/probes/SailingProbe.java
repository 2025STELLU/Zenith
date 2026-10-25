import net.atomos.zenith.entity.SailingPhysics;

/**
 * Spot-check the sail drive curve (SAIL-05) after a change to
 * SailingPhysics.driveCoefficient().
 *
 * Expected values as of 0.1.5-alpha (recorded in docs/04 §17.2):
 *   0/20/30 deg -> 0        35 -> 0.0330    45 -> 0.3459
 *   90 -> 0.8207           105 -> 0.8500   180 -> 0.4500
 *
 * Usage:
 *   powershell -NoProfile -File tools/dev/probe.ps1 SailingProbe
 */
public class SailingProbe {
    public static void main(String[] args) {
        double[] deg = {0, 20, 30, 35, 39, 45, 60, 90, 105, 120, 150, 170, 179, 180};
        for (double d : deg) {
            System.out.printf("%6.1f deg -> %.6f%n", d,
                    SailingPhysics.driveCoefficient(Math.toRadians(d), 10.0));
        }
        System.out.println("apparent 0.1 -> " + SailingPhysics.driveCoefficient(Math.toRadians(90), 0.1));
        System.out.println("apparent 0.2 -> " + SailingPhysics.driveCoefficient(Math.toRadians(90), 0.2));

        double[] r = SailingPhysics.step(0, 10, 0, 0, 0, 1.0, 0);
        System.out.printf("step wind=(0,10) heading=0 trim=1 -> fx=%.4f fz=%.4f drive=%.4f%n",
                r[0], r[1], r[2]);
    }
}
