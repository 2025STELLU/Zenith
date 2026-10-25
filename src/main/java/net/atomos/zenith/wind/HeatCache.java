package net.atomos.zenith.wind;

import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.ToDoubleFunction;

/**
 * L2 热源索引（RT-02 / L2-03）。
 *
 * <h2>旧实现的四个问题</h2>
 * <ol>
 *   <li><b>玩家分散即互相失效</b>：扫描盒以全体玩家的<b>算术平均</b>为中心，两人相距 2000 格时
 *       焦点落在中间无人区，双方的热源都扫不到；</li>
 *   <li><b>高度带写死</b>：{@code cy = seaLevel}、{@code dy ∈ [-16,24]} 只覆盖 y=47..87，
 *       山地（y=120）、深洞（y=-40）、下界全部错位；</li>
 *   <li><b>每 200 tick 全量重建</b> 49×49×21 = 50,421 次 {@code getBlockState}，
 *       折合 ~252 次/tick 的常驻成本；</li>
 *   <li><b>步长 2</b> 只访问偶数坐标（漏检 7/8 的方块），且 {@code size() > 256} 直接
 *       {@code return}——从 {@code dx = -R} 处提前中断，盒子另一半永远没有热源（静默的各向异性 bug）。</li>
 * </ol>
 *
 * <h2>现在的做法</h2>
 * <ul>
 *   <li><b>按玩家分桶</b>：每个在线玩家各自贡献一个 32 格盒，扫描结果按 {@code 16³} 区块段
 *       归并，玩家分散也不会互相抵消；</li>
 *   <li><b>跟随玩家 y</b>：垂直范围取玩家脚下方块 y 的 ±{@value #SCAN_BELOW_BLOCKS}..+{@value #SCAN_ABOVE_BLOCKS}，
 *       洞穴/山地/下界都对；</li>
 *   <li><b>段级预筛</b>：先用 {@link LevelChunkSection#maybeHas} 查调色板——绝大多数段里
 *       一个热源都没有，一次谓词判断就能整段跳过（这是性能的关键，不是"少扫几格"）；</li>
 *   <li><b>增量</b>：每个段记 {@code scannedAt}，{@value #RESCAN_INTERVAL_TICKS} tick 内不重扫；
 *       方块变化时 {@link #invalidate} 立刻作废该段；</li>
 *   <li><b>步长 1</b>，且<b>不再中途截断</b>：查询时按距离裁剪最近的
 *       {@value #MAX_NEAR_SOURCES} 个，而不是"扫到第 257 个就停"。</li>
 * </ul>
 */
final class HeatCache {
    /** 每个玩家贡献的扫描半径（格）。 */
    static final int SCAN_RADIUS_BLOCKS = 32;
    /** 扫描盒向下延伸（格）：洞穴/矿道里的岩浆。 */
    static final int SCAN_BELOW_BLOCKS = 32;
    /** 扫描盒向上延伸（格）：山顶营地。 */
    static final int SCAN_ABOVE_BLOCKS = 40;
    /** 同一段的重新扫描间隔（tick）。10 秒。 */
    static final int RESCAN_INTERVAL_TICKS = 200;
    /** 每隔多少 tick 才跑一次扫描调度（避免每 tick 遍历上百个段）。 */
    static final int SCHEDULE_INTERVAL_TICKS = 20;
    /** 单次查询最多返回多少个最近的热源（按距离裁剪，不截断扫描）。 */
    static final int MAX_NEAR_SOURCES = 128;

    private final ToDoubleFunction<BlockState> powerOf;
    private final Map<Long, List<LocalFlowSolver.HeatSource>> bySection = new HashMap<>();
    private final Map<Long, Long> scannedAt = new HashMap<>();
    private final List<LocalFlowSolver.HeatSource> near = new ArrayList<>();

    HeatCache(ToDoubleFunction<BlockState> powerOf) {
        this.powerOf = powerOf;
    }

    /**
     * 调度一次增量扫描（每 {@value #SCHEDULE_INTERVAL_TICKS} tick 调一次即可）。
     *
     * @param players 该维度在线玩家；空列表时不做任何事（不要用"出生点"假装玩家）
     */
    void refresh(ServerLevel level, List<ServerPlayer> players, long tick) {
        for (ServerPlayer p : players) {
            int px = p.getBlockX(), py = p.getBlockY(), pz = p.getBlockZ();
            int minSx = SectionPos.blockToSectionCoord(px - SCAN_RADIUS_BLOCKS);
            int maxSx = SectionPos.blockToSectionCoord(px + SCAN_RADIUS_BLOCKS);
            int minSz = SectionPos.blockToSectionCoord(pz - SCAN_RADIUS_BLOCKS);
            int maxSz = SectionPos.blockToSectionCoord(pz + SCAN_RADIUS_BLOCKS);
            int minSy = SectionPos.blockToSectionCoord(py - SCAN_BELOW_BLOCKS);
            int maxSy = SectionPos.blockToSectionCoord(py + SCAN_ABOVE_BLOCKS);
            for (int sx = minSx; sx <= maxSx; sx++) {
                for (int sz = minSz; sz <= maxSz; sz++) {
                    ChunkAccess chunk = level.getChunkSource()
                            .getChunk(sx, sz, ChunkStatus.FULL, false);
                    if (chunk == null) continue; // 未加载：跳过，绝不触发同步加载（SYS-10）
                    for (int sy = minSy; sy <= maxSy; sy++) {
                        // getSectionIndex 要的是方块 y；段起点 y = sy << 4，落在这段的 16³ 内
                        int idx = chunk.getSectionIndex(sy << 4);
                        if (idx < 0 || idx >= chunk.getSections().length) continue;
                        scanSection(level, chunk, sx, sy, sz, idx, tick);
                    }
                }
            }
        }
    }

    private void scanSection(ServerLevel level, ChunkAccess chunk, int sx, int sy, int sz,
                             int sectionIdx, long tick) {
        long key = SectionPos.asLong(sx, sy, sz);
        Long last = scannedAt.get(key);
        if (last != null && tick - last < RESCAN_INTERVAL_TICKS) return;
        LevelChunkSection section = chunk.getSection(sectionIdx);
        scannedAt.put(key, tick);
        if (section == null || section.hasOnlyAir()) {
            bySection.remove(key);
            return;
        }
        // 段级预筛：调色板里一个热源都没有就整段跳过（没有这一步就是 4096 次 getBlockState）
        if (!section.maybeHas(st -> heatPower(st) > 0)) {
            bySection.remove(key);
            return;
        }
        List<LocalFlowSolver.HeatSource> found = null;
        int baseX = sx << 4, baseY = sy << 4, baseZ = sz << 4;
        for (int x = 0; x < 16; x++) {
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    int by = baseY + y;
                    if (by < level.getMinBuildHeight() || by >= level.getMaxBuildHeight()) continue;
                    BlockState st = section.getBlockState(x, y, z);
                    double power = heatPower(st);
                    if (power <= 0) continue;
                    if (found == null) found = new ArrayList<>();
                    found.add(new LocalFlowSolver.HeatSource(
                            baseX + x + 0.5, by + 0.5, baseZ + z + 0.5, power, 6));
                }
            }
        }
        if (found == null) bySection.remove(key);
        else bySection.put(key, found);
    }

    /**
     * 取球内最近的若干个热源。
     *
     * <p>单遍收集 + 超限时按距离裁剪（{@link #MAX_NEAR_SOURCES}）。旧实现是"扫到第 257 个
     * 就 {@code return}"，结果取决于遍历顺序——同一个岩浆湖，靠西边和靠东边的玩家看到的
     * 热源数量不同。
     */
    List<LocalFlowSolver.HeatSource> near(double x, double y, double z, double radius) {
        near.clear();
        double r2 = radius * radius;
        double sectionReach = radius + 16.0;
        for (Map.Entry<Long, List<LocalFlowSolver.HeatSource>> e : bySection.entrySet()) {
            long key = e.getKey();
            double cx = (SectionPos.x(key) << 4) + 8;
            double cy = (SectionPos.y(key) << 4) + 8;
            double cz = (SectionPos.z(key) << 4) + 8;
            // 段中心到查询点的距离粗筛：留一个段半对角线（≈13.9）的余量
            double dx = cx - x, dy = cy - y, dz = cz - z;
            if (dx * dx + dy * dy + dz * dz > (sectionReach + 14) * (sectionReach + 14)) continue;
            for (LocalFlowSolver.HeatSource h : e.getValue()) {
                double hx = h.x() - x, hy = h.y() - y, hz = h.z() - z;
                if (hx * hx + hy * hy + hz * hz <= r2) near.add(h);
            }
        }
        if (near.size() <= MAX_NEAR_SOURCES) return List.copyOf(near);
        near.sort((a, b) -> Double.compare(
                sqr(a.x() - x) + sqr(a.y() - y) + sqr(a.z() - z),
                sqr(b.x() - x) + sqr(b.y() - y) + sqr(b.z() - z)));
        return List.copyOf(near.subList(0, MAX_NEAR_SOURCES));
    }

    /** 方块变化时作废该段（掉落/放置/燃烧状态变更立即生效，不用等 10 秒）。 */
    void invalidate(int blockX, int blockY, int blockZ) {
        int sx = SectionPos.blockToSectionCoord(blockX);
        int sy = SectionPos.blockToSectionCoord(blockY);
        int sz = SectionPos.blockToSectionCoord(blockZ);
        long key = SectionPos.asLong(sx, sy, sz);
        scannedAt.remove(key);
        bySection.remove(key);
    }

    /** 全部作废（调试命令 / 维度卸载）。 */
    void clear() {
        scannedAt.clear();
        bySection.clear();
        near.clear();
    }

    /** 当前索引里的热源总数（诊断用）。 */
    int size() {
        int n = 0;
        for (List<LocalFlowSolver.HeatSource> l : bySection.values()) n += l.size();
        return n;
    }

    /** 已扫描的段数（诊断用）。 */
    int scannedSections() { return scannedAt.size(); }

    private double heatPower(BlockState st) {
        return powerOf.applyAsDouble(st);
    }

    private static double sqr(double v) { return v * v; }
}
