import net.atomos.zenith.wind.BackgroundMetGrid;
import net.atomos.zenith.wind.MesoscaleGrid;
import net.atomos.zenith.wind.WorldScaleDriver;

public class L1Probe {
    public static void main(String[] a) {
        BackgroundMetGrid l0 = new BackgroundMetGrid();
        MesoscaleGrid l1 = new MesoscaleGrid(424242L);
        WorldScaleDriver drv = new WorldScaleDriver(424242L);
        BackgroundMetGrid.TerrainProbe t0 = new BackgroundMetGrid.TerrainProbe() {
            public double terrainHeightBlocks(double bx, double bz) { return 64.0; }
            public double roughness01(double bx, double bz) { return 0.30; }
            public double biomeTemperatureKelvin(double bx, double bz) { return 284.0; }
        };
        MesoscaleGrid.TerrainProbe t1 = new MesoscaleGrid.TerrainProbe() {
            public double terrainHeightBlocks(double bx, double bz) { return 64.0; }
            public double roughness01(double bx, double bz) { return 0.30; }
            public double[] slopeVector(double bx, double bz) { return new double[]{0, 0}; }
        };
        l0.setFocus(0, 0);
        for (int i = 0; i < 60; i++) {
            drv.advance(12.8, 0.5, false, false, new WorldScaleDriver.Feedback(0.1, 0.01, 0.0));
            l0.refresh(drv, t0, 12.8, 0.5, false, 0.0);
        }
        l1.setFocus(0, 0);
        l1.refresh(l0, t1, 0.5, false, 0L, 64 * 0.05);
        BackgroundMetGrid.Sample base = l0.sample(0, 0);
        double baseSpd = Math.hypot(base.windX(), base.windZ());
        System.out.printf("baseSpd=%.6f%n", baseSpd);
        for (double h : new double[]{1.62, 2, 6, 10, 14, 30, 60, 110, 190, 300}) {
            MesoscaleGrid.Sample s = l1.sample(0.5, 64 + h, 0.5);
            System.out.printf("agl=%6.2f  spd=%8.4f ratio=%8.4f  w=%8.4f  p=%9.3f%n",
                    h, Math.hypot(s.windX(), s.windZ()),
                    Math.hypot(s.windX(), s.windZ()) / baseSpd, s.windY(), s.pressureAnomalyPa());
        }
        System.out.printf("layerAgl: ");
        for (int i = 0; i < MesoscaleGrid.MAX_LAYERS; i++) {
            System.out.printf("%.1f ", MesoscaleGrid.layerAglBlocks(i));
        }
        System.out.println();
        MesoscaleGrid.Sample sTop = l1.sample(0.5, 364.0, 0.5);
        MesoscaleGrid.Sample sNear = l1.sample(0.5, 66.0, 0.5);
        double dirL0 = Math.atan2(base.windZ(), base.windX());
        System.out.printf("turnNear=%.3f turnTop=%.3f%n",
                WorldScaleDriver.wrapDelta(Math.toDegrees(Math.atan2(sNear.windZ(), sNear.windX()) - dirL0), 360),
                WorldScaleDriver.wrapDelta(Math.toDegrees(Math.atan2(sTop.windZ(), sTop.windX()) - dirL0), 360));
    }
}
