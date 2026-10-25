import net.atomos.zenith.api.ZenithAirfoilPresets;
import net.atomos.zenith.api.ZenithAirfoilDefinition;

public class AirfoilProbe {
    public static void main(String[] a) {
        for (ZenithAirfoilDefinition d : ZenithAirfoilPresets.defaults()) {
            var pts = d.coordinates();
            int total = pts.size();
            int upper = total / 2 + 1;
            int lower = total / 2 - 1;
            double paired = 0;
            int bi = -1;
            for (int i = 1; i <= lower; i++) {
                double dy = Math.abs(pts.get(i).y() - pts.get(upper + (lower - i)).y());
                if (dy > paired) { paired = dy; bi = i; }
            }
            double maxAbs = 0;
            for (var p : pts) maxAbs = Math.max(maxAbs, Math.abs(p.y()));
            System.out.printf("%s total=%d upper=%d lower=%d paired=%.6f at i=%d 2max=%.6f maxThickness=%.6f%n",
                    d.id(), total, upper, lower, paired, bi, maxAbs * 2, d.maxThickness());
            if (bi > 0) {
                System.out.printf("   up=(%.5f,%.5f) lo=(%.5f,%.5f)%n",
                        pts.get(bi).x(), pts.get(bi).y(),
                        pts.get(upper + (lower - bi)).x(), pts.get(upper + (lower - bi)).y());
            }
        }
    }
}
