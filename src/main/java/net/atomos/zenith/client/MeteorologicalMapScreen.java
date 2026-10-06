package net.atomos.zenith.client;

import net.atomos.zenith.network.packet.WeatherSnapshotPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * 气象图界面：显示 coarse 风场箭头、台风、风暴单体、风暴活动度。
 */
public class MeteorologicalMapScreen extends Screen {
    private static final int MAP_SIZE = 220;
    private static final double MAP_RANGE_BLOCKS = 1200.0;

    public MeteorologicalMapScreen() {
        super(Component.translatable("screen.zenith.meteorological_map"));
    }

    @Override
    public void render(GuiGraphics gfx, int mouseX, int mouseY, float partialTick) {
        super.render(gfx, mouseX, mouseY, partialTick);
        int cx = width / 2, cy = height / 2;
        int left = cx - MAP_SIZE / 2, top = cy - MAP_SIZE / 2;

        // 背景色铺底
        gfx.fill(left - 8, top - 28, left + MAP_SIZE + 8, top + MAP_SIZE + 8, 0xE0101018);
        gfx.fill(left, top, left + MAP_SIZE, top + MAP_SIZE, 0xFF0A1A33);
        gfx.drawString(font, title, left, top - 20, 0xFFFFFF);

        var player = Minecraft.getInstance().player;
        if (player == null) return;
        double px = player.getX(), pz = player.getZ();

        var state = ClientWindState.get();
        var snap = state.snapshot();

        // 风场箭头网格
        int grid = 9;
        for (int i = 0; i < grid; i++) {
            for (int j = 0; j < grid; j++) {
                double wx = px + (i - grid / 2.0) * (MAP_RANGE_BLOCKS * 2 / grid);
                double wz = pz + (j - grid / 2.0) * (MAP_RANGE_BLOCKS * 2 / grid);
                var v = state.sampleCoarse(wx, player.getY(), wz);
                double speed = v.horizontalLength();
                if (speed < 0.3) continue;
                int sx = left + (int) ((wx - (px - MAP_RANGE_BLOCKS)) / (MAP_RANGE_BLOCKS * 2) * MAP_SIZE);
                int sy = top + (int) ((wz - (pz - MAP_RANGE_BLOCKS)) / (MAP_RANGE_BLOCKS * 2) * MAP_SIZE);
                double ang = Math.atan2(v.x(), v.z());
                int len = (int) Math.min(14, 3 + speed);
                int ex = sx + (int) (Math.sin(ang) * len);
                int ey = sy + (int) (Math.cos(ang) * len);
                int color = speed > 15 ? 0xFFFF5050 : speed > 8 ? 0xFFFFB050 : 0xFF70C0FF;
                plotLine(gfx, sx, sy, ex, ey, color);
                double a1 = ang + 2.6, a2 = ang - 2.6;
                plotLine(gfx, ex, ey, ex + (int) (Math.sin(a1) * 4), ey + (int) (Math.cos(a1) * 4), color);
                plotLine(gfx, ex, ey, ex + (int) (Math.sin(a2) * 4), ey + (int) (Math.cos(a2) * 4), color);
            }
        }

        // 玩家位置的绿点
        gfx.fill(cx - 2, cy - 2, cx + 2, cy + 2, 0xFF00FF00);

        // 台风
        if (snap != null) {
            for (WeatherSnapshotPacket.TyphoonInfo t : snap.typhoons()) {
                int sx = worldToMapX(t.x(), px, left);
                int sy = worldToMapY(t.z(), pz, top);
                if (sx < left || sx > left + MAP_SIZE || sy < top || sy > top + MAP_SIZE) continue;
                // 螺旋示意：同心圆
                plotCircle(gfx, sx, sy, 10, 0xFFFF4040);
                plotCircle(gfx, sx, sy, 6, 0xFFFF8080);
                gfx.fill(sx - 2, sy - 2, sx + 2, sy + 2, 0xFF202020); // 风眼
                gfx.drawString(font, "🌀 " + t.name() + " " + (int) t.vmaxMps() + "m/s",
                        sx + 12, sy - 4, 0xFFFF8080);
            }
            // 风暴单体
            for (WeatherSnapshotPacket.StormInfo s : snap.storms()) {
                int sx = worldToMapX(s.x(), px, left);
                int sy = worldToMapY(s.z(), pz, top);
                if (sx < left || sx > left + MAP_SIZE || sy < top || sy > top + MAP_SIZE) continue;
                int color = switch (s.phaseOrdinal()) {
                    case 0 -> 0xFFFFFF80; // 发展：黄
                    case 1 -> 0xFFFF5050; // 成熟：红
                    default -> 0xFF808080; // 消散：灰
                };
                plotCircle(gfx, sx, sy, 7, color);
                gfx.drawString(font, "⛈", sx + 9, sy - 5, color);
                if (s.hail01() > 0.4) {
                    gfx.drawString(font, "🧊", sx - 4, sy + 8, 0xFFB0E0FF);
                }
            }
            // 锋面：冷锋蓝 / 暖锋红
            for (WeatherSnapshotPacket.FrontInfo f : snap.fronts()) {
                int x1 = worldToMapX(f.x1(), px, left), y1 = worldToMapY(f.z1(), pz, top);
                int x2 = worldToMapX(f.x2(), px, left), y2 = worldToMapY(f.z2(), pz, top);
                int color = "COLD".equals(f.type()) ? 0xFF4060FF : 0xFFFF5060;
                plotLine(gfx, x1, y1, x2, y2, color);
                plotLine(gfx, x1, y1 + 1, x2, y2 + 1, color);
            }
            // 尘卷风：棕色小点
            for (WeatherSnapshotPacket.DevilInfo d : snap.dustDevils()) {
                int sx = worldToMapX(d.x(), px, left);
                int sy = worldToMapY(d.z(), pz, top);
                if (sx < left || sx > left + MAP_SIZE || sy < top || sy > top + MAP_SIZE) continue;
                gfx.fill(sx - 1, sy - 1, sx + 1, sy + 1, 0xFFB08040);
            }
            // 飑线：紫色粗线
            for (WeatherSnapshotPacket.SquallInfo q : snap.squallLines()) {
                int x1 = worldToMapX(q.x1(), px, left), y1 = worldToMapY(q.z1(), pz, top);
                int x2 = worldToMapX(q.x2(), px, left), y2 = worldToMapY(q.z2(), pz, top);
                plotLine(gfx, x1, y1, x2, y2, 0xFFA040FF);
                plotLine(gfx, x1 + 1, y1, x2 + 1, y2, 0xFFA040FF);
            }
        }

        // 底栏：天气数字
        int y = top + MAP_SIZE + 12;
        if (snap != null) {
            gfx.drawString(font, String.format("风暴活动度: %.0f%%  龙卷: %d  热泡: %d  台风: %d",
                    snap.stormActivity() * 100, snap.tornadoCount(),
                    snap.thermalCount(), snap.typhoons().size()), left, y, 0xFFC0C0C0);
            y += 12;
            gfx.drawString(font, String.format("海风锋离岸: %.0f m", snap.seaBreezeFrontInland()),
                    left, y, 0xFFC0C0C0);
        } else {
            gfx.drawString(font, "等待服务端天气数据…", left, y, 0xFF808080);
        }
    }

    private int worldToMapX(double wx, double px, int left) {
        return left + (int) ((wx - (px - MAP_RANGE_BLOCKS)) / (MAP_RANGE_BLOCKS * 2) * MAP_SIZE);
    }

    private int worldToMapY(double wz, double pz, int top) {
        return top + (int) ((wz - (pz - MAP_RANGE_BLOCKS)) / (MAP_RANGE_BLOCKS * 2) * MAP_SIZE);
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

    /** 参数圆（fill 逐点）。 */
    private static void plotCircle(GuiGraphics gfx, int cx, int cy, int r, int color) {
        for (int a = 0; a < 360; a += 6) {
            int x = cx + (int) (Math.cos(Math.toRadians(a)) * r);
            int y = cy + (int) (Math.sin(Math.toRadians(a)) * r);
            gfx.fill(x, y, x + 1, y + 1, color);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
