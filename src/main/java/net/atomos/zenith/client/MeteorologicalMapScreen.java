package net.atomos.zenith.client;

import net.atomos.zenith.network.packet.CoarseWindPacket;
import net.atomos.zenith.network.packet.WeatherSnapshotPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * 气象图界面：显示 coarse 风场箭头、台风、风暴单体、锋面、龙卷与风暴活动度。
 *
 * <p><b>CLI-10 修正</b>：
 * <ul>
 *   <li>旧实现固定 ±1200 格，而 {@code CoarseWindPacket} 只有 ±384 格
 *       （{@code radiusCells 6 × cellSize 64}）。超出部分的 {@code sampleCoarse}
 *       全被 clamp 到最外圈格点，图上 90% 的箭头画的是"边缘那一格的风"，方向完全错误。
 *       现在范围跟随包半径，并提供滚轮缩放档（384/256/192/128/96/64/48/32 格）。</li>
 *   <li>旧实现用 emoji（🌀 ⛈ 🧊）当图标，MC 默认字体没有这些码位，实际渲染成方块/空白。
 *       现在改用 ASCII 字母 + 颜色区分（T 台风 / S 风暴 / * 冰雹 / ! 龙卷 / d 尘卷）。</li>
 *   <li>{@code tornadoCount} 以前只在底栏文字里出现，现在用 {@code tornadoes()}
 *       在图上画龙卷图标，底栏只做计数校验。</li>
 *   <li>旧实现把 {@code player.getY()} 传进 {@code sampleCoarse}，但该函数只做 x/z 插值，
 *       y 被完全忽略——"飞起来看图"和"站在地上看图"完全一样。现在不再假装有高度维度，
 *       标题里直接标明这是 L1 近地面层（AGL 10 m）的场。</li>
 * </ul>
 */
public class MeteorologicalMapScreen extends Screen {
    private static final int MAP_SIZE = 220;
    /** 没有收到 coarse 包时的兜底半范围（格）。 */
    private static final double FALLBACK_RANGE_BLOCKS = 384.0;
    /** 缩放档：半范围（格）。索引越大越近。 */
    private static final double[] ZOOM_RANGES = {384, 256, 192, 128, 96, 64, 48, 32};
    /** 风场箭头网格边长（{@code GRID × GRID} 个采样点）。 */
    private static final int GRID = 11;

    private int zoomIndex = 0;

    public MeteorologicalMapScreen() {
        super(Component.translatable("screen.zenith.meteorological_map"));
    }

    /** 当前显示的半范围（格）：既不超过缩放档，也不超过服务端给的包半径。 */
    private double rangeBlocks() {
        double r = ZOOM_RANGES[Math.max(0, Math.min(zoomIndex, ZOOM_RANGES.length - 1))];
        CoarseWindPacket pkt = ClientWindState.get().coarseWind();
        if (pkt == null) return Math.min(r, FALLBACK_RANGE_BLOCKS);
        double packetRadius = (double) pkt.cellSizeBlocks() * pkt.radiusCells();
        return Math.max(16.0, Math.min(r, packetRadius));
    }

    @Override
    public void render(GuiGraphics gfx, int mouseX, int mouseY, float partialTick) {
        super.render(gfx, mouseX, mouseY, partialTick);
        int cx = width / 2, cy = height / 2;
        int left = cx - MAP_SIZE / 2, top = cy - MAP_SIZE / 2;

        // 背板
        gfx.fill(left - 8, top - 28, left + MAP_SIZE + 8, top + MAP_SIZE + 8, 0xE0101018);
        gfx.fill(left, top, left + MAP_SIZE, top + MAP_SIZE, 0xFF0A1A33);
        gfx.drawString(font, title, left, top - 20, 0xFFFFFF);

        var player = Minecraft.getInstance().player;
        if (player == null) return;
        double px = player.getX(), pz = player.getZ();
        double range = rangeBlocks();

        var state = ClientWindState.get();
        var snap = state.snapshot();

        // 风场箭头网格：sampleCoarse 只做 x/z 双线性插值，所以这里不再传 y（CLI-10）。
        for (int i = 0; i < GRID; i++) {
            for (int j = 0; j < GRID; j++) {
                double wx = px + (i - (GRID - 1) / 2.0) * (range * 2 / (GRID - 1));
                double wz = pz + (j - (GRID - 1) / 2.0) * (range * 2 / (GRID - 1));
                var v = state.sampleCoarse(wx, 0, wz);
                double speed = v.horizontalLength();
                if (speed < 0.3) continue;
                int sx = worldToMapX(wx, px, left, range);
                int sy = worldToMapY(wz, pz, top, range);
                double ang = Math.atan2(v.x(), v.z());
                int len = (int) Math.min(16, 3 + speed * 0.7);
                int ex = sx + (int) Math.round(Math.sin(ang) * len);
                int ey = sy + (int) Math.round(Math.cos(ang) * len);
                int color = speed > 15 ? 0xFFFF5050 : speed > 8 ? 0xFFFFB050 : 0xFF70C0FF;
                plotLineClipped(gfx, sx, sy, ex, ey, color, left, top);
                double a1 = ang + 2.6, a2 = ang - 2.6;
                plotLineClipped(gfx, ex, ey,
                        ex + (int) Math.round(Math.sin(a1) * 4),
                        ey + (int) Math.round(Math.cos(a1) * 4), color, left, top);
                plotLineClipped(gfx, ex, ey,
                        ex + (int) Math.round(Math.sin(a2) * 4),
                        ey + (int) Math.round(Math.cos(a2) * 4), color, left, top);
            }
        }

        // 玩家位置
        gfx.fill(cx - 2, cy - 2, cx + 2, cy + 2, 0xFF00FF00);

        if (snap != null) {
            // 台风：同心圆 + 风眼 + "T" 标签
            for (WeatherSnapshotPacket.TyphoonInfo t : snap.typhoons()) {
                int sx = worldToMapX(t.x(), px, left, range);
                int sy = worldToMapY(t.z(), pz, top, range);
                if (!inMap(sx, sy, left, top)) continue;
                plotCircle(gfx, sx, sy, 10, 0xFFFF4040, left, top);
                plotCircle(gfx, sx, sy, 6, 0xFFFF8080, left, top);
                gfx.fill(sx - 2, sy - 2, sx + 2, sy + 2, 0xFF202020); // 风眼
                gfx.drawString(font, "T " + t.name() + " " + (int) t.vmaxMps() + "m/s",
                        sx + 12, sy - 4, 0xFFFF8080);
            }
            // 风暴单体：S + 颜色表示阶段，冰雹另加 "*"
            for (WeatherSnapshotPacket.StormInfo s : snap.storms()) {
                int sx = worldToMapX(s.x(), px, left, range);
                int sy = worldToMapY(s.z(), pz, top, range);
                if (!inMap(sx, sy, left, top)) continue;
                int color = switch (s.phaseOrdinal()) {
                    case 0 -> 0xFFFFFF80; // 发展：黄
                    case 1 -> 0xFFFF5050; // 成熟：红
                    default -> 0xFF808080; // 消散：灰
                };
                plotCircle(gfx, sx, sy, 7, color, left, top);
                gfx.drawString(font, "S", sx + 9, sy - 5, color);
                if (s.hail01() > 0.4) {
                    gfx.drawString(font, "*", sx - 4, sy + 8, 0xFFB0E0FF);
                }
            }
            // 锋面：冷锋蓝 / 暖锋红
            for (WeatherSnapshotPacket.FrontInfo f : snap.fronts()) {
                int x1 = worldToMapX(f.x1(), px, left, range), y1 = worldToMapY(f.z1(), pz, top, range);
                int x2 = worldToMapX(f.x2(), px, left, range), y2 = worldToMapY(f.z2(), pz, top, range);
                int color = "COLD".equals(f.type()) ? 0xFF4060FF : 0xFFFF5060;
                plotLineClipped(gfx, x1, y1, x2, y2, color, left, top);
                plotLineClipped(gfx, x1, y1 + 1, x2, y2 + 1, color, left, top);
            }
            // 尘卷风：棕色小点
            for (WeatherSnapshotPacket.DevilInfo d : snap.dustDevils()) {
                int sx = worldToMapX(d.x(), px, left, range);
                int sy = worldToMapY(d.z(), pz, top, range);
                if (!inMap(sx, sy, left, top)) continue;
                gfx.fill(sx - 1, sy - 1, sx + 1, sy + 1, 0xFFB08040);
            }
            // 飑线：紫色粗线
            for (WeatherSnapshotPacket.SquallInfo q : snap.squallLines()) {
                int x1 = worldToMapX(q.x1(), px, left, range), y1 = worldToMapY(q.z1(), pz, top, range);
                int x2 = worldToMapX(q.x2(), px, left, range), y2 = worldToMapY(q.z2(), pz, top, range);
                plotLineClipped(gfx, x1, y1, x2, y2, 0xFFA040FF, left, top);
                plotLineClipped(gfx, x1 + 1, y1, x2 + 1, y2, 0xFFA040FF, left, top);
            }
            // 龙卷（CLI-10）：以前只有底栏计数，现在逐个体现在图上
            for (WeatherSnapshotPacket.TornadoInfo t : snap.tornadoes()) {
                int sx = worldToMapX(t.x(), px, left, range);
                int sy = worldToMapY(t.z(), pz, top, range);
                if (!inMap(sx, sy, left, top)) continue;
                plotCircle(gfx, sx, sy, 4, 0xFFFF3030, left, top);
                gfx.drawString(font, "!", sx - 1, sy - 4, 0xFFFF8080);
            }
        }

        // 比例尺：右下角一段固定像素代表多少格
        drawScaleBar(gfx, left, top, range);

        // 底栏
        int y = top + MAP_SIZE + 12;
        if (snap != null) {
            gfx.drawString(font, String.format("风暴活动度: %.0f%%  龙卷: %d  热泡: %d  台风: %d",
                    snap.stormActivity() * 100, snap.tornadoCount(),
                    snap.thermalCount(), snap.typhoons().size()), left, y, 0xFFC0C0C0);
            y += 12;
            gfx.drawString(font, String.format("海风锋离岸: %.0f m", snap.seaBreezeFrontInland()),
                    left, y, 0xFFC0C0C0);
            y += 12;
        } else {
            gfx.drawString(font, "等待服务端天气数据...", left, y, 0xFF808080);
            y += 12;
        }
        gfx.drawString(font, String.format("范围: ±%.0f 格  L1 近地面(AGL 10m)  滚轮缩放",
                range), left, y, 0xFF909090);
    }

    private void drawScaleBar(GuiGraphics gfx, int left, int top, double range) {
        // 选一个 1/2/5×10^n 的"整齐"距离，让它大约占 40~70 像素
        double target = range * 0.25;
        double pow = Math.pow(10, Math.floor(Math.log10(Math.max(1e-6, target))));
        double nice = pow;
        for (double m : new double[]{1, 2, 5, 10}) {
            if (pow * m >= target) { nice = pow * m; break; }
        }
        int px = (int) Math.round(nice / (range * 2) * MAP_SIZE);
        if (px < 8 || px > MAP_SIZE - 8) return;
        int x0 = left + MAP_SIZE - px - 6;
        int y0 = top + MAP_SIZE - 8;
        gfx.fill(x0, y0, x0 + px, y0 + 1, 0xFFB0B0B0);
        gfx.fill(x0, y0 - 3, x0 + 1, y0 + 2, 0xFFB0B0B0);
        gfx.fill(x0 + px - 1, y0 - 3, x0 + px, y0 + 2, 0xFFB0B0B0);
        gfx.drawString(font, String.format("%.0fm", nice), x0, y0 - 12, 0xFFB0B0B0);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (scrollY > 0) {
            zoomIndex = Math.min(zoomIndex + 1, ZOOM_RANGES.length - 1);
        } else if (scrollY < 0) {
            zoomIndex = Math.max(zoomIndex - 1, 0);
        }
        return true;
    }

    private static boolean inMap(int sx, int sy, int left, int top) {
        return sx >= left && sx <= left + MAP_SIZE && sy >= top && sy <= top + MAP_SIZE;
    }

    private int worldToMapX(double wx, double px, int left, double range) {
        return left + (int) Math.round((wx - (px - range)) / (range * 2) * MAP_SIZE);
    }

    private int worldToMapY(double wz, double pz, int top, double range) {
        return top + (int) Math.round((wz - (pz - range)) / (range * 2) * MAP_SIZE);
    }

    /**
     * Bresenham 直线（fill 逐点），带矩形裁剪。
     *
     * <p>旧实现不裁剪，锋面/飑线端点在地图外时会画到面板外面去（CLI-10）。
     */
    private static void plotLineClipped(GuiGraphics gfx, int x0, int y0, int x1, int y1, int color,
                                        int left, int top) {
        int minX = left, minY = top, maxX = left + MAP_SIZE, maxY = top + MAP_SIZE;
        // Liang–Barsky
        double dx = x1 - x0, dy = y1 - y0;
        double t0 = 0, t1 = 1;
        double[] p = {-dx, dx, -dy, dy};
        double[] q = {x0 - minX, maxX - x0, y0 - minY, maxY - y0};
        for (int i = 0; i < 4; i++) {
            if (Math.abs(p[i]) < 1e-9) {
                if (q[i] < 0) return;
                continue;
            }
            double t = q[i] / p[i];
            if (p[i] < 0) {
                if (t > t1) return;
                if (t > t0) t0 = t;
            } else {
                if (t < t0) return;
                if (t < t1) t1 = t;
            }
        }
        int ax = (int) Math.round(x0 + dx * t0), ay = (int) Math.round(y0 + dy * t0);
        int bx = (int) Math.round(x0 + dx * t1), by = (int) Math.round(y0 + dy * t1);
        plotLine(gfx, ax, ay, bx, by, color);
    }

    /** Bresenham 直线（fill 逐点）。 */
    private static void plotLine(GuiGraphics gfx, int x0, int y0, int x1, int y1, int color) {
        int dx = Math.abs(x1 - x0), dy = Math.abs(y1 - y0);
        int sx = x0 < x1 ? 1 : -1, sy = y0 < y1 ? 1 : -1;
        int err = dx - dy;
        while (true) {
            gfx.fill(x0, y0, x0 + 1, y0 + 1, color);
            if (x0 == x1 && y0 == y1) break;
            int e2 = 2 * err;
            if (e2 > -dy) { err -= dy; x0 += sx; }
            if (e2 < dx) { err += dx; y0 += sy; }
        }
    }

    /** 参数圆（fill 逐点），裁剪到地图矩形。 */
    private static void plotCircle(GuiGraphics gfx, int cx, int cy, int r, int color,
                                   int left, int top) {
        for (int a = 0; a < 360; a += 6) {
            int x = cx + (int) Math.round(Math.cos(Math.toRadians(a)) * r);
            int y = cy + (int) Math.round(Math.sin(Math.toRadians(a)) * r);
            if (!inMap(x, y, left, top)) continue;
            gfx.fill(x, y, x + 1, y + 1, color);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
