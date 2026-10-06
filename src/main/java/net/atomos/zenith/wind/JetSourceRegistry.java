package net.atomos.zenith.wind;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 风扇射流注册表：风扇方块实体在此登记/注销定向射流，
 * {@link LocalFlowSolver} 通过它查询附近射流。
 */
public final class JetSourceRegistry {
    private static final List<LocalFlowSolver.Jet> JETS = new CopyOnWriteArrayList<>();

    private JetSourceRegistry() {}

    public static void register(LocalFlowSolver.Jet jet) {
        unregisterAt(jet.x(), jet.y(), jet.z());
        JETS.add(jet);
    }

    public static void unregisterAt(double x, double y, double z) {
        JETS.removeIf(j -> j.x() == x && j.y() == y && j.z() == z);
    }

    public static List<LocalFlowSolver.Jet> jetsNear(double x, double y, double z, double radius) {
        List<LocalFlowSolver.Jet> out = new ArrayList<>();
        double r2 = radius * radius;
        for (LocalFlowSolver.Jet j : JETS) {
            double dx = j.x() - x, dy = j.y() - y, dz = j.z() - z;
            if (dx * dx + dy * dy + dz * dz <= r2) out.add(j);
        }
        return out;
    }

    public static void clear() {
        JETS.clear();
    }
}
