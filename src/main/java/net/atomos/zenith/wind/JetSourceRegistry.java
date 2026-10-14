package net.atomos.zenith.wind;

import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 风扇射流注册表：风扇/风道方块实体在此登记/注销定向射流，
 * {@link LocalFlowSolver} 通过它查询附近射流。
 *
 * JVM 级静态表，按维度分桶：下界的风扇吹不到主世界同坐标。
 * 存档关闭时 {@link #clear()} 全清，不把旧存档的射流带到新存档。
 */
public final class JetSourceRegistry {
    private static final Map<ResourceKey<Level>, List<LocalFlowSolver.Jet>> JETS =
            new ConcurrentHashMap<>();

    private JetSourceRegistry() {}

    public static void register(ResourceKey<Level> dim, LocalFlowSolver.Jet jet) {
        unregisterAt(dim, jet.x(), jet.y(), jet.z());
        JETS.computeIfAbsent(dim, k -> new CopyOnWriteArrayList<>()).add(jet);
    }

    public static void unregisterAt(ResourceKey<Level> dim, double x, double y, double z) {
        List<LocalFlowSolver.Jet> jets = JETS.get(dim);
        if (jets != null) {
            jets.removeIf(j -> j.x() == x && j.y() == y && j.z() == z);
        }
    }

    public static List<LocalFlowSolver.Jet> jetsNear(ResourceKey<Level> dim,
                                                     double x, double y, double z, double radius) {
        List<LocalFlowSolver.Jet> out = new ArrayList<>();
        List<LocalFlowSolver.Jet> jets = JETS.get(dim);
        if (jets == null) return out;
        double r2 = radius * radius;
        for (LocalFlowSolver.Jet j : jets) {
            double dx = j.x() - x, dy = j.y() - y, dz = j.z() - z;
            if (dx * dx + dy * dy + dz * dz <= r2) out.add(j);
        }
        return out;
    }

    /** 存档关闭时全清：静态表不清，旧世界的射流会残留到下个存档。 */
    public static void clear() {
        JETS.clear();
    }
}
