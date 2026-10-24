package net.atomos.zenith.weather;

import net.atomos.zenith.ZenithConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LightningBolt;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.BaseFireBlock;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * 模组闪电的统一落雷入口（WX-SC-02 / WX-SQ-02 / SYS-10）。
 *
 * <p>旧实现直接 {@code level.getHeightmapPos(WORLD_SURFACE, pos)} 然后
 * {@code addFreshEntity}，带来四个问题：
 *
 * <ol>
 *   <li>未加载区块上取高度图会触发**同步区块加载**（SYS-10）；</li>
 *   <li>下界/末地本无天气，照样落雷（SYS-09 的天气侧表现）；</li>
 *   <li>{@code setVisualOnly(false)} 无条件造成伤害并点火，玩家看不到的地方也在烧山；</li>
 *   <li>没有配置项，管理员无法关掉。</li>
 * </ol>
 *
 * <p>这里加四道闸：① 维度必须有天空光且无天花板；② 区块已加载才落雷；
 * ③ 离玩家超过 {@code lightningMaxDistanceFromPlayer} 不落雷；
 * ④ 伤害与点火分别由 {@code lightningDamage} / {@code lightningSetsFire} 控制。
 * 只有两者都为真时才走原版 {@code setVisualOnly(false)}，否则用视觉闪电 +
 * 手动施加对应效果，避免"只想点火却连伤害一起打开"。
 */
public final class LightningGate {
    private LightningGate() {}

    /**
     * 在给定坐标落一道闪电。
     *
     * @param bx,bz 目标方块坐标（闪电会落到该列的最高方块上）
     * @return 是否真的生成了闪电实体
     */
    public static boolean strike(ServerLevel level, int bx, int bz) {
        // 闸 1：维度必须"像天空"
        if (!level.dimensionType().hasSkyLight() || level.dimensionType().hasCeiling()) return false;

        // 闸 2：区块必须已加载。getChunk(..., FULL, false) 不会同步加载；null 表示未加载
        ChunkPos cp = new ChunkPos(bx, bz);
        ChunkAccess chunk = level.getChunkSource().getChunk(cp.x, cp.z, ChunkStatus.FULL, false);
        if (chunk == null) return false;

        // 闸 3：离最近的玩家太远就不落（看不见的雷不该烧东西、也不该耗性能）
        int maxDist = ZenithConfig.lightningMaxDistanceFromPlayer();
        boolean nearPlayer = false;
        for (ServerPlayer p : level.players()) {
            if (p.distanceToSqr(bx + 0.5, p.getY(), bz + 0.5) <= (double) maxDist * maxDist) {
                nearPlayer = true;
                break;
            }
        }
        if (!nearPlayer) return false;

        // 高度图用已加载的 chunk 读，避免再走一次可能触发加载的路径
        int surfaceY = Math.max(chunk.getHeight(Heightmap.Types.WORLD_SURFACE, bx & 15, bz & 15),
                level.getMinBuildHeight());
        BlockPos pos = new BlockPos(bx, surfaceY, bz);

        LightningBolt bolt = EntityType.LIGHTNING_BOLT.create(level);
        if (bolt == null) return false;
        bolt.moveTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);

        // 闸 4：伤害与点火分开控制。
        boolean damage = ZenithConfig.lightningDamage();
        boolean fire = ZenithConfig.lightningSetsFire();
        if (damage && fire) {
            // 两者都要：交给原版闪电（伤害 + 点火 + 特效）
            bolt.setVisualOnly(false);
        } else {
            // 任一关闭：纯视觉闪电 + 手动施加需要的那一半效果
            bolt.setVisualOnly(true);
            if (damage) hurtNearbyPlayers(level, pos);
            if (fire) igniteAt(level, bx, bz, surfaceY);
        }
        level.addFreshEntity(bolt);
        return true;
    }

    /**
     * 闪电的手动伤害（当需要"有伤害但不点火"时使用）。
     * 范围与伤害值对齐原版 {@code LightningBolt}：3 格内 5 点、6 格内递减。
     */
    private static void hurtNearbyPlayers(ServerLevel level, BlockPos pos) {
        for (ServerPlayer p : level.players()) {
            double d2 = p.distanceToSqr(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
            if (d2 > 36.0) continue;
            float dmg = d2 <= 9.0 ? 5.0f : (float) (5.0 * (6.0 - Math.sqrt(d2)) / 3.0);
            if (dmg <= 0.2f) continue;
            p.hurt(level.damageSources().lightningBolt(), dmg);
        }
    }

    /** 手动点火（当需要"点火但不伤害"时使用）：落在最高方块顶面，概率对齐原版约 1/3。 */
    private static void igniteAt(ServerLevel level, int bx, int bz, int surfaceY) {
        if (level.random.nextFloat() > 0.33f) return;
        BlockPos above = new BlockPos(bx, surfaceY, bz);
        if (!level.isEmptyBlock(above)) return;
        var fire = BaseFireBlock.getState(level, above);
        if (fire.canSurvive(level, above)) {
            level.setBlockAndUpdate(above, fire);
        }
    }
}
