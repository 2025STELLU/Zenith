package net.atomos.zenith.client;

import net.atomos.zenith.particle.ZenithParticles;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * 客户端"降水丝"：下雨时的横向风丝、雷暴增强的冰雹。
 *
 * <p><b>CLI-12</b>：去掉 {@code static final Random} 与 {@code static int tickCounter}
 * （重连/换世界不清零，相位会漂），改用 {@link ClientWindState} 的集中 tick 计数与
 * {@link RandomSource}；{@code BlockPos} 的 {@code (int)} 截断改 {@code BlockPos.containing}
 * （内部走 {@code Mth.floor}；负坐标下 {@code (int)} 向 0 截断，与 floor 差 1 格）。
 *
 * <p><b>CLI-13</b>：旧实现由 {@code level.isRaining()} 直接早退——那是**全局天气**，
 * 不是"玩家头顶是否在下雨"。局部雷暴在 {@code isRaining() == false} 时一滴水花都没有；
 * 反过来全局下雨而玩家在沙漠/洞穴里，"降水丝"照样出现。
 * 现在改成三件事一起判：
 * <ul>
 *   <li>{@code getRainLevel(1.0)}：原版的平滑降水强度（局部雷暴由 WX-SC-04 写回）；</li>
 *   <li>玩家上方是否露天（{@code MOTION_BLOCKING} 高度图）：头顶有方块就不该有雨丝；</li>
 *   <li>{@code stormBoost}：台风/雷暴按到玩家的距离衰减，不再"全图任意一个台风就拉满"。</li>
 * </ul>
 */
@OnlyIn(Dist.CLIENT)
public final class WeatherWindController {
    /** 生成水花/冰雹的降水强度门限（0..1）。原版刚起雨时约 0.1，稳态 1.0。 */
    private static final double RAIN_GATE = 0.10;
    /** 雷暴/台风对玩家"风暴感"的影响半径（格）；超出后线性衰减到 0。 */
    private static final double STORM_INFLUENCE_BLOCKS = 400.0;

    private WeatherWindController() {}

    public static void clientTick() {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null || mc.player == null || mc.isPaused()) return;

        long tickCounter = ClientWindState.get().tickCounter();
        if ((tickCounter & 1L) != 0L) return;

        double px = mc.player.getX(), py = mc.player.getY(), pz = mc.player.getZ();

        // CLI-13：全局雨量 + 头顶是否露天 = "玩家这里的降水强度"
        BlockPos playerPos = BlockPos.containing(px, py, pz);
        int ceilingY = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING, playerPos).getY();
        boolean openSky = py >= ceilingY - 1;
        double rainLevel = level.getRainLevel(1.0f);
        if (!openSky) rainLevel = 0;
        if (rainLevel <= RAIN_GATE) return;

        var snap = ClientWindState.get().snapshot();
        double stormBoost = ClientWindState.get().stormBoostAt(px, pz);

        RandomSource random = level.random;
        int count = rainLevel > 0.7 || stormBoost > 0.6 ? 10 : 4;
        for (int i = 0; i < count; i++) {
            double x = px + (random.nextDouble() - 0.5) * 40;
            double z = pz + (random.nextDouble() - 0.5) * 40;
            double y = py + 8 + random.nextDouble() * 10;
            BlockPos bp = BlockPos.containing(x, y, z);
            // 采样点也必须露天，否则雨丝会出现在悬崖内部/屋檐下
            if (level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING, bp).getY() > bp.getY()) continue;
            // 风丝本身走 spray 粒子，水平速度按 block/tick 给（m/s ÷ 20）
            var wind = ClientWindState.get().sampleCoarse(x, y, z);
            double fallSpeed = 0.45; // 9 m/s：约 1.6~2.2 block/tick 的视觉下落
            level.addParticle(ZenithParticles.SPRAY.get(), x, y, z,
                    wind.x() * 0.05, -fallSpeed, wind.z() * 0.05);
        }

        // 冰雹：只在玩家确实处在冰雹单体影响范围内时生成
        if (snap != null && tickCounter % 4 == 0) {
            for (var storm : snap.storms()) {
                if (storm.hail01() < 0.5) continue;
                double dx = storm.x() - px, dz = storm.z() - pz;
                if (dx * dx + dz * dz > storm.radiusBlocks() * storm.radiusBlocks() * 4) continue;
                for (int i = 0; i < 4; i++) {
                    double x = px + (random.nextDouble() - 0.5) * 30;
                    double z = pz + (random.nextDouble() - 0.5) * 30;
                    double y = py + 10 + random.nextDouble() * 8;
                    var wind = ClientWindState.get().sampleCoarse(x, y, z);
                    level.addParticle(ZenithParticles.HAIL.get(), x, y, z,
                            wind.x() * 0.05, 0, wind.z() * 0.05);
                }
            }
        }
    }
}
