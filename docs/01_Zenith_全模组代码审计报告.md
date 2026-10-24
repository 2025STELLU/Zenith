# Zenith 全模组代码审计报告

> 审计对象：`Zenith-main`（mod_version `0.1.1-alpha`，NeoForge `21.1.256` / Minecraft `1.21.1`，JDK 21）
> 代码规模：99 个 Java 文件，约 7,976 行；资源：6 种粒子、1 个音效、4 方块 / 7 物品 / 1 实体
> 审计方式：逐文件人工通读 + 关键公式离线数值复现（Python）+ 对照 NeoForge 1.21.1 官方 Javadoc / 原版源码行为 + 气象与空气动力学文献
> 审计重点：物理计算（L0/L1/L2/天气现象/帆船/翼型极线）、物理 API、图形学与渲染（详细建议另见文档 02）

---

## 0. 执行摘要

Zenith 的**架构构想是对的**：驱动器 → L0 → L1 → L2 的多尺度嵌套、服务端权威 / 客户端视觉分离、开放 API + 信任等级、天气现象插件化（`WeatherPhenomenon`）。这套骨架在 MC 模组生态里很少见，值得继续投入。

但当前实现存在**大量"静默的错误"**：代码能跑、不崩，但算出来的风场在多个关键维度上是错的，而且错误会叠加。最严重的几类：

| # | 问题 | 后果 | 级别 |
|---|------|------|------|
| 1 | **坐标手性错误**：MC 是 +x 东 / +z 南，代码按数学坐标写旋转，地转风、台风、气旋全部转反 | 北半球台风顺时针转、低压周围风向反了 | P1 |
| 2 | **日变化相位错 6 小时**：`sin(2πt − π/2)` 在 MC 的 dayTime 体系下峰值落在日落（18:00），正值区间是正午→午夜 | 热泡/海风/尘卷风在傍晚最强、半夜还在 | P1 |
| 3 | **L1 垂直层用绝对高度建、按离地高度读** | 地表在 y≈60 以上时，近地面风恒为 L0 的约 **28%** | P0（物理） |
| 4 | **L2 把地面当障碍物**：平地上站立高度出现 **+11 m/s 伪上升气流**，0.5 格处风被反向 | 涡轮探针、风扇、所有带 L2 的玩法采样被污染 | P0（物理） |
| 5 | **龙卷在 L0（256 格网格）上被混叠掉**，并且以"格"为单位直接发给客户端渲染 | 龙卷几乎没有风场；画出来的位置在原点附近（坐标缩小 256 倍） | P0（功能） |
| 6 | **多人/焦点模型**：所有网格围绕"玩家平均坐标"，L0 平移不迁移状态，海风只跟第一个玩家 | 多人服里玩家相距 >1 km 时风场对所有人都错 | P1 |
| 7 | **运行时单例不清理**：单人游戏切换存档时沿用旧世界的天气状态并泄漏整个 `ServerLevel` | 内存泄漏 + 新存档带着旧种子的天气 | P0 |
| 8 | **服务端同步加载区块**：热源扫描、闪电落点、API 任意点采样都走 `level.getBlockState` / `getHeightmapPos` | 卡顿尖峰，甚至生成新区块 | P1 |
| 9 | **指令无权限检查** | 任何玩家可在服务器上召唤台风 / 雷暴（真闪电点火） | P0（安全） |
| 10 | **客户端多处"做了但不生效"**：雾事件未 cancel、风声音量从 0 起播被引擎跳过、客户端指令注册在 MOD 总线、帆船 `isPickable()` 未覆写 | 雾、风声、`/zenithc`、上船 四个功能实际不可用 | P0（功能） |
| 11 | **粒子单位混用 m/s 与 blocks/tick**，风力以累加加速度方式施加 | 粒子稳态速度是真实风速的 25–50 倍 | P1 |
| 12 | 翼型极线：零升迎角由厚度推导（对称翼在 0° 产生升力）、失速后阻力在 90° 达到 **25.9** | 外部模组用 `runPolar` 会得到错误气动数据 | P1 |

**结论**：建议在继续加新天气系统之前，先做一个以"正确性"为主题的 0.1.2 / 0.2 版本（见文档 03 路线图），把坐标约定、时间约定、高度基准、插值/边界、生命周期这五个**横切问题**一次性收敛。修完这些以后，现有大部分天气现象的观感会立刻显著提升，因为很多"不像"的地方其实是 bug 而不是模型粗糙。

---

## 1. 范围、方法与严重级别

### 1.1 文件分组

| 模块 | 文件 | 行数(约) |
|------|------|------|
| 风场核心 `wind/` | WorldScaleDriver, BackgroundMetGrid(L0), MesoscaleGrid(L1), LocalFlowSolver(L2), ZenithServerRuntime, NoiseUtil, NestedBoundaryCoupler, JetSourceRegistry, HashedSeedTerrainProvider, SeedTerrainProvider, ZenithCommands | 2,200 |
| 天气现象 `weather/` | Thermal, SeaBreeze, StormCell, Typhoon, Front, SquallLine, DustDevil, MountainWave, Monsoon, Season, FogModel, WeatherContext, WeatherPhenomenon, WindContribution | 1,700 |
| 公开 API `api/` | ZenithWindApi, ZenithWeatherApi, ZenithTerrainApi, 样本/请求/结果类型、翼型、客户端 API、MC 桥 | 1,300 |
| 客户端 `client/` + `mixin/` | ClientWindState, 粒子控制、雾、风声、气象图、可视化、渲染器 ×4、Mixin ×3 | 1,600 |
| 玩法 `block/ entity/ item/` | 风扇、风道、风向标、涡轮探针、帆船、风速计、气象图 | 900 |
| 网络 `network/` | CoarseWindPacket, WeatherSnapshotPacket | 320 |

### 1.2 严重级别

- **P0**：崩溃 / 功能完全不生效 / 安全或数据问题 / 物理结果数量级错误
- **P1**：物理结果明显错误或方向性错误；明显性能尖峰；多人环境下错误
- **P2**：模型粗糙、不连续、语义不一致，影响观感或可扩展性
- **P3**：代码质量、轻微浪费、文档不符

### 1.3 坐标与时间约定（后文所有判断的基准）

- Minecraft 世界：**+X = 东，+Z = 南，+Y = 上**。俯视（北在上）时，从 +X 转向 +Z 是**顺时针**。
- `level.getDayTime() % 24000`：0 = 06:00 日出，6000 = 正午，12000 = 日落，18000 = 午夜。
- 实体 yaw：前向向量为 `(-sin(yaw), cos(yaw))`，yaw=0 朝南。
- 北半球气旋（低压）俯视**逆时针**旋转；地转风满足"背风而立，低压在左"。

---

## 2. 架构与数据流复核

```
WorldScaleDriver（每 tick, dt=0.05s）
   ├─ 气旋单体 ×6、对流团 ×6、龙卷 ≤2（单位：驱动格，1格=256方块）
   └─ sample(cellX,cellZ) ← 由 L0 在每个 L0 格中心调用
L0 BackgroundMetGrid 41×41 @256（每 256 tick 刷新，dt=12.8s）
L1 MesoscaleGrid 33×33×8 @64×40（每 64 tick 刷新，纯诊断，无记忆）
天气现象 ×9（每 tick 推进；采样时逐点叠加到 L1 风上）
L2 LocalFlowSolver（逐点诊断：9³ 方块扫描 + 热源 + 射流）
ZenithServerRuntime.sampleInternal = L1 + Σ现象 + 眼区衰减 + L2
CoarseWindPacket：9×9@32 格，**只含 L1**，每 40 tick，以玩家为中心
WeatherSnapshotPacket：全维度现象列表，每 100 tick
客户端：ClientWindState 只有粗场双线性 + 正弦抖动（README 所说"客户端本地高分辨率求解器"并不存在）
```

**文档与实现不符**（P3，但会误导协作者与 API 用户）：
1. README / WEATHER_SYSTEMS.md 称客户端有本地 L2 求解器，实际客户端没有 L2。
2. 文档称 L2 有海绵层（`NestedBoundaryCoupler.spongeBlend`），全仓库无调用。
3. 文档称"服务端每 2 秒广播粗风场，客户端求解器提供细节"，但粗风场不含任何天气现象（台风、风暴外流、锋面、季风），客户端视觉看不到它们的风。
4. 文档称冷锋"锋后降温、气压上升、风向顺转"，代码里锋面只有动量贡献，没有温度/气压/风向转变。
5. README 写 License "All Rights Reserved"，`neoforge.mods.toml` 写 `license="MIT"`，`LICENSE` 是自定义非商业许可，且引用的 `THIRD_PARTY_LICENSES/` 目录实际是一个文件 `THIRD_PARTY_LICENSES_Aerodynamics4MC-Core`。三处冲突需统一。

---

## 3. 横切性系统问题（优先修）

### SYS-01 【P1】坐标手性错误：旋转方向整体反了

**位置**
- `BackgroundMetGrid.computeGeostrophicWind`（L207–208）：`ug = -dPdz*K; vg = dPdx*K`
- `TyphoonSystem.sample`（L131）：切向 `(-dz, dx)·spin`，`spin=1` 注释为"北半球逆时针"
- `WorldScaleDriver.sample` 气旋 / 龙卷：`(-dz, dx)·spin`

**分析**：在 MC 坐标中，中心以东的点（dx>0, dz=0）上 `(-dz, dx)` = `(0, +)`，即向 **+Z（南）**，俯视为**顺时针**，是南半球气旋 / 北半球反气旋的方向。地转风公式同理：数学坐标的 `u=-∂p/∂y, v=∂p/∂x` 直接搬到 y→z 时忘了 MC 的 z 轴指南。

**影响**：台风顺时针转，但 β 漂移按北半球写（向西北），二者自相矛盾；L0 地转风与驱动器给的旋转风目标打架（`lerp(drv.target, geo, 0.18–0.32)` 混合两个相反方向的场）；雨带螺旋手性随之错误。

**修复**
```java
/** MC 坐标(+x东,+z南)下，北半球气旋式（俯视逆时针）切向单位向量 */
static double tanX(double dx, double dz, double r) { return  dz / r; }
static double tanZ(double dx, double dz, double r) { return -dx / r; }

// 地转风（北半球）：
double ugX =  dPdz * K;   // 东向分量
double ugZ = -dPdx * K;   // 南向分量
```
并把"北半球/南半球"做成一个 `hemisphereSign` 配置（默认 +1），所有旋转统一乘它。气旋单体的 `spin` 不应随机：低压 = 气旋式，高压 = 反气旋式（DRV-05）。

---

### SYS-02 【P1】日变化相位错位 6 小时（共 7 处）

**位置**：`WorldScaleDriver` L98、`BackgroundMetGrid` 太阳加热、`MesoscaleGrid` 坡风 L140、`ThermalSystem` L62（注释"0.25=日出、0.5=正午"本身就错）、`SeaBreezeSystem` L42、`DustDevilSystem` L47、`FogModel.solarFactor` L37。

**分析**：`timeOfDay01 = dayTime%24000/24000`，0 是日出。`sin(2πt − π/2)` 在 t=0.5（**日落 18:00**）取最大值，在 t∈(0.25, 0.75)（**正午→午夜**）为正。

| 现象 | 代码实际行为 | 应有 |
|------|------|------|
| 热泡 | 正午才开始生成，傍晚最强，持续到午夜 | 上午生成，13–15 时最强，日落后消失 |
| 海风 | 正午到午夜向岸吹，午夜到正午离岸吹 | 白天向岸、夜间离岸 |
| 尘卷风 | 夜里出现 | 午后 |
| 坡风 | 午夜到正午下坡 | 白天上坡、夜间下坡 |
| 风暴活动日变化 | 傍晚峰值（偶然接近真实午后对流） | 午后峰值 |

**修复**：统一使用一个函数：
```java
/** 太阳高度因子 [-1,1]，正午=1、午夜=-1；兼容固定时间维度 */
static double solarElevation(Level level) {
    return Math.cos(level.getTimeOfDay(1.0f) * 2.0 * Math.PI); // getTimeOfDay: 0=正午
}
```
若需相位滞后（地表加热滞后约 2 h），在此基础上加相位偏移，并集中定义为常量。

---

### SYS-03 【P1】高度基准混乱：绝对 Y 与离地高度（AGL）混用

| 位置 | 写法 | 问题 |
|------|------|------|
| `MesoscaleGrid.refresh` L113 | `hMid=(l+0.5)*40` 视为**绝对高度** | 与 `sample` 的 AGL 解读不一致，见 L1-01 |
| `ThermalSystem.sample` L114 | `groundY = 60` 写死 | 山地热泡在地表以下"开始"；y<60 的低地永远没有热泡风 |
| `SeaBreezeSystem.sample` L130 | `agl = y − 64` | 高原海岸完全没有海风衰减逻辑 |
| `DustDevilSystem.sample` L108 | `y > heightBlocks + 60`（heightBlocks 是 20–60 的**柱高**） | 当成绝对高度用：地表 y=70 的沙漠里尘卷风几乎被 hDecay 掐完 |
| `TyphoonSystem` | `y − 200` | 绝对高度 |
| `MonsoonSystem` | `y < 150` | 绝对高度 |
| StormCell / SquallLine / Front | 完全不随高度变化 | 阵风锋外流在 300 格高空和地面一样强 |

1.18+ 世界高度为 −64…320，山地地表常在 150–250。**所有现象应以 `terrainHeight(x,z)` 为基准计算 AGL**。建议在 `WeatherContext` / 采样接口中把 `groundY` 作为参数传入（采样点所在柱的地表高度只查一次）。

---

### SYS-04 【P1】单位混用：m/s 与 blocks/tick

1 m/s = 1 block/s = **0.05 block/tick**。客户端多处把 m/s 直接当 block/tick 用，详见第 11 节 CLI-01。

---

### SYS-05 【P1】空间离散与域边界：最近邻 + 夹边外推 + 硬截断

1. **L1 水平最近邻**（`MesoscaleGrid.sample` L212 `Math.round`）：风场每 64 格阶跃一次。玩法采样、粗风场包、风向标都受影响。
2. **夹边外推**：L0、L1、SeaBreeze、客户端粗场在域外都 `clamp` 到边缘格，相当于把边缘值**无限外推**。SeaBreeze 尤其严重：远离海岸 10 km 的玩家也会拿到网格边缘的海风值。
3. **硬截断**：台风影响半径处 12.1 m/s 断崖（Vmax=40, Rmax=55, R=600）；台风眼边缘 13.1 m/s 断崖；飑线前沿 18 m/s 断崖；尘卷风核心边缘 5–12 m/s 断崖；L2 扫描半径 4 处修正值骤降为 0。

统一原则：所有场**双线性/三线性插值**；域外**返回"无贡献"或回退到上一层**，不外推；所有现象包络用 C¹ 连续的平滑窗（如 `smoothstep` 或 `exp(−(r/R)^n)` 尾巴）。

---

### SYS-06 【P1】焦点模型不适合多人，且 L0 平移不迁移状态

- `ZenithServerRuntime.updateFocus`：焦点取**所有玩家的平均坐标**。两名玩家分别在 (0,0) 和 (10000,0) 时，焦点在 (5000,0)，L1（覆盖 ±1024 格）对两人都不覆盖 → 两人都拿到夹边外推值。
- `BackgroundMetGrid.setFocus`：焦点移动超过 1 格就**直接改中心坐标，但格子数组不平移**，等于把整张天气图"贴在玩家身上"带着走（风暴、气压异常跟着玩家跑）。焦点也不对齐到 256 格整数倍，导致驱动器采样点漂移。
- `SeaBreezeSystem.tick`：每 tick 用 `players.get(0)` 更新 `focusX/Z`，但场每 100 tick 才重算一次；在两次重算之间，旧场按新焦点解读 → 海风场跟着玩家滑动。另有半格偏移：构建用 `GRID/2`（16），采样用 `GRID/2.0`（16.5），系统性偏 32 格。

**建议架构**：改为**世界锚定的瓦片（tile）缓存**：L0/L1 按世界坐标对齐的瓦片存储（例如 L1 每瓦片 16×16 列），以每个玩家为中心激活所需瓦片，LRU 回收。这样多人互不干扰、状态随世界而不是随玩家，且天然支持持久化（见文档 03）。

---

### SYS-07 【P0】生命周期：静态单例跨世界残留、内存泄漏

- `ZenithServerRuntime.INSTANCE` 与 `dimensions` Map 从不清理。单人游戏退出存档再进入另一个存档时：
  - 维度键 `minecraft:overworld` 相同 → 复用**旧世界的 DimensionState**（旧种子的气旋、旧台风）；
  - `McTerrainAdapter` 和 L2 的 lambda 持有**旧的 `ServerLevel` 引用** → 整个旧世界无法被 GC，并且地形查询打到已关闭的世界上。
- `JetSourceRegistry` 是 JVM 级静态表：不分维度（下界的风扇会吹主世界同坐标处）、不分世界、`clear()` 从未被调用；单人模式下集成服务器与客户端共享同一份。
- `ClientWindState` 在登出 / 换维度时不清空，旧服务器的台风/龙卷会继续画在新世界里。

**修复**：监听 `ServerStoppedEvent` 清空 `dimensions` 与射流表；`LevelEvent.Unload` 移除对应维度状态；客户端监听 `ClientPlayerNetworkEvent.LoggingOut` 与维度切换清空 `ClientWindState`；射流表改为按 `ResourceKey<Level>` 分桶并挂在 `DimensionState` 下。

---

### SYS-08 【P1】无持久化、随机源不可复现

天气状态（台风、锋面、风暴、驱动器相位）全部在内存中，重启即重置；驱动器每次以世界种子重新初始化（同一批气旋重新从初始位置出发）。现象系统使用无种子 `new Random()`，`FrontSystem` 甚至在字段初始化里用 `Math.random()`。

**建议**：`SavedData`（每维度一个）序列化驱动器 + 现象列表；随机源统一由 `RandomSource.create(seed ^ dim.hash ^ salt)` 派生，便于复现 bug 与做黄金测试。

---

### SYS-09 【P1】缺少维度门控

`onServerTick` 对 `server.getAllLevels()` 全部运行。下界与末地会生成热泡、风暴、**真闪电**（`setVisualOnly(false)` 可点火）。下界 `WORLD_SURFACE` 高度图是顶层基岩，`surfaceAt` 会把基岩当草地。应以 `level.dimensionType().hasSkyLight() && !hasCeiling()` 为默认门控，并提供维度白名单配置。

---

### SYS-10 【P1】服务端同步区块加载

| 位置 | 调用 | 风险 |
|------|------|------|
| `refreshHeatCache` | 以**焦点**为中心 `level.getBlockState` 49×49×21 ≈ 5 万次 | 焦点在两名玩家中间的未加载区时触发同步加载/生成 |
| `StormCellSystem.strikeLightning` / `SquallLineSystem.strikeLightning` | `level.getHeightmapPos` 于玩家 600–1200 格外 | 远超视距，每 2–18 秒一次同步加载 |
| `LocalFlowSolver` 障碍探针 | `level.getBlockState` | 任何模组通过 API 在任意点采样都会加载区块 |

**修复**：所有世界查询先 `level.hasChunkAt(pos)` / `getChunkSource().getChunkNow(cx,cz)`，未加载则跳过或用种子地形；闪电只在已加载且 `isLoaded` 的区块中落地，否则仅做客户端视觉闪光。

---

### SYS-11 【P2】线程安全未定义

- `stateFor()` 在 API 采样路径中 `HashMap.computeIfAbsent`，其他模组从工作线程调用时会并发修改。
- `ZenithMinecraftWindApi.sample(Level, …)` 接受客户端 `Level`，构造 `ZenithWorldRef.client(...)` 后仍走**服务端运行时**；单人模式下客户端渲染线程会直接读写集成服务器的 `ServerLevel`。
- **建议**：API 文档明确"仅服务端主线程"，入口处 `assert server.isSameThread()`；或者提供线程安全的只读快照（每 tick 发布不可变的 `WindFieldSnapshot`）。

---

## 4. WorldScaleDriver（行星尺度驱动）

### DRV-01 【P1】驱动网格坐标映射与周期性

- 气旋/对流团在 `[0, 384)` **驱动格**内生成并周期回卷，但 `sample()` 用的是绝对格号 `floor(blockX/256)`，**不做周期取模、不用最小镜像距离**。结果：
  - 气旋只覆盖世界坐标 `[0, 98304)` 象限；出生点附近 x<0 或 z<0 的区域只能吃到高斯尾巴，基本没有天气系统；
  - 气旋跨越回卷边界时瞬移 9.8 万格。
- **修复**：`dx = wrapDelta(cellX − c.x, GRID_CELLS)`（最小镜像），并将 `cellX` 取模。

### DRV-02 【P1】对流团每约 100 秒瞬移

`ConvectiveCluster.advance` L290：`host = cyclones.get((int)(|orbitAngle| % size))`。`orbitAngle` 以 0.01 rad/s 增长，每 100 秒宿主气旋索引 +1，对流团位置在不同气旋之间**跳跃**（可达数万格）。对流团在生成时应记住 `hostIndex`。

### DRV-03 【P0·功能】龙卷被 L0 网格混叠掉

龙卷核心半径 10–24 格、影响半径 48–128 格，却在 **L0 的 256 格网格中心**上被采样（`r > influenceRadius → continue`）。只有当龙卷中心恰好落在某个 L0 格中心 ±128 格内才有非零值，而且即便命中，`exp(−(r/rc)²)` 在 r≈100 时也约为 0。

**后果**：龙卷几乎从不进入 L0/L1 → 玩法采样中没有龙卷风；`intensityEnvelope()` 也没有被乘进风速。

**修复**：龙卷与台风一样应作为**逐点叠加的 `WeatherPhenomenon`**（Burgers–Rott 涡：`v(r)=Γ/(2πr)·(1−e^{−r²/rc²})`，处处光滑），而不是塞进 L0 强迫。L0 只接收其大尺度的气压/加热影响。

### DRV-04 【P0·功能】龙卷坐标单位错误地发给客户端

`WeatherSnapshotPacket.build` 直接发送 `TornadoVortex.x/z`（**驱动格单位**），`TornadoRenderer` 当方块坐标绘制 → 龙卷画在真实位置的 1/256 处（原点附近 384 格范围内）。需乘 `CELL_SIZE_BLOCKS`（并在修好 DRV-01 后处理回卷）。

### DRV-05 【P2】气旋旋向与气压独立随机

`spin = ±1` 随机，`lowPressure` 也独立随机 → 一半低压是反气旋式旋转，物理不一致；并与 L0 由气压场导出的地转风对冲。应 `spin = lowPressure ? +1 : −1`（再乘半球符号）。

### DRV-06 【P2】分量独立钳制

`u,w` 各自钳制到 ±12 m/s（盒形），对角方向最大可达 17 m/s 且会扭转风向。应按模长钳制。

### DRV-07 【P3】随机游走的时间尺度

`synopticLull += (rand−0.5)·dt·0.02`：方差 ∝ dt²，改变 tick 步长会改变随机过程的统计特性。应写为 `σ·sqrt(dt)·N(0,1)`（Ornstein–Uhlenbeck 更好：带均值回复）。

---

## 5. L0 BackgroundMetGrid

| ID | 级别 | 问题 | 建议 |
|----|------|------|------|
| L0-01 | P1 | 地转风手性（SYS-01） | 见 SYS-01 |
| L0-02 | P1 | 平移不迁移状态（SYS-06）；焦点不对齐网格 | 焦点对齐 `floor(x/256)*256`；平移时整体移位数组，新暴露的格子用驱动器初始化 |
| L0-03 | P2 | **步长相关的"常量混合"**：扩散 `lerp 0.16`、气压向驱动器 `lerp 0.35`、温度 `±0.01·solar` 每次调用固定量而非 ×dt。当前 dt=12.8 s 时，温度日变化稳态偏置约 `(0.06−0.02)/(12.8/1200) ≈ +3.75 K`；改变刷新频率会改变气候 | 全部改成速率形式 `1 − exp(−dt/τ)`，τ 用物理含义的时间常数 |
| L0-04 | P2 | 地形形阻：先改 `targetX` 再用新的 `hypot(targetX,targetZ)` 算 Z 分量（顺序依赖） | 先缓存模长 |
| L0-05 | P2 | 生物群系温度映射 `273.15 + t·30`：沙漠(2.0)=333 K(60 °C)；递减率用绝对 y 而非相对海平面 | 用分段映射（如 t∈[−0.5,2] → 255–310 K），递减率用 `(h − seaLevel)` |
| L0-06 | P2 | 粗糙度拖曳与松弛同时作用，平衡风速 ≈ `k/(k+drag)` 倍目标值（粗糙度 0.5 时约 0.64），与 L1 的对数律重复计算地表摩擦 | L0 只做自由大气（地转 + 平流），近地摩擦交给 L1 |
| L0-07 | P2 | 气压异常没有输出到 API（运行时 `pressureAnomalyPa` 恒 0） | 透传 L0 气压，API 才有意义 |
| L0-08 | P3 | 每格每次刷新 `new CellState()` ×2、`new double[]` ×2 | 复用暂存对象 |

---

## 6. L1 MesoscaleGrid

### L1-01 【P0·物理】垂直层构建与读取的高度基准不一致

`refresh` 中第 l 层代表**绝对高度** `(l+0.5)·40`（20, 60, 100…）；`sample` 却按 `hAboveGround/40` 选层（第 0 层 = 离地 0–40）。

当地表高于约 60 时，第 0、1 层位于地下：`hAGL ≤ z0 → prof=0.15`，再经 `0.15+0.85·prof` = **0.2775**。于是：

> 在主世界绝大部分陆地上，离地 0–80 格的风速恒为 L0 风速的约 **28%**，与粗糙度、稳定度无关；坡风、地形抬升、对流强迫也都加在这个"地下层"上。

山地（地表 150–250）则前 4–6 层全在地下。

**修复**：在每列内以 **AGL** 定义层：`hMid_l = (l+0.5)·Δz`（离地），`windProfile(hMid_l)` 使用对数律；`sample` 三线性插值（水平双线性 + 垂直线性），层顶以上返回自由大气风。建议垂直层采用拉伸网格（近地密、高空疏：如 2, 6, 14, 30, 60, 110, 190, 300 格）。

### L1-02 【P1】水平最近邻

见 SYS-05。改为双线性。

### L1-03 【P2】"切变"语义错误

`shearXPerBlock = ∂u/∂x`、`shearZPerBlock = ∂w/∂z` 是**法向应变**（伸缩），不是切变；`meanLowLevelShear` 再被驱动器当作龙卷生成条件。龙卷/超级单体真正依赖的是**垂直风切变**（0–1 km、0–6 km 矢量差）与螺旋度。建议：
- 输出 `bulkShear01 = |V(layer_top) − V(layer_0)|`；
- 输出水平涡度 `ζ = ∂w/∂x − ∂u/∂z`、散度 `δ = ∂u/∂x + ∂w/∂z`；
- 龙卷支持度用 `shear × instability`（类似 STP 指数的极简版）。

### L1-04 【P2】Ekman 螺旋参考系

当前把 L0 风当**地面风**、随高度顺转 0–0.47 rad，层顶以上保持旋转 0.47 rad。物理上应把 L0 视为 ABL 顶的（近）地转风，**地面风相对其逆转（北半球向低压偏）约 20–30°**（陆地）/ 10–15°（海面），随高度回到地转方向。现在的写法让自由大气风系统性偏离 L0 约 27°。

### L1-05 【P2】阵风模型

- `gustY` 的种子每 20 tick 变一次（`seed + tick/20`），而列每 64 tick 才刷新 → 每次刷新垂直阵风完全重采样，和注释"别用纯随机（会闪）"矛盾；
- 阵风只沿平均风方向（纵向），没有横向分量；
- 阵风值每 64 tick 阶跃，没有时间相关结构。

**建议**：阵风改为**时间相关随机过程**：每个采样点用 Dryden 谱的一阶近似（OU 过程，相关时间 `T = L_u / U`，L_u≈200 m 近地），或用 3D 值噪声 `noise(x/L, z/L, t·U/L)` 做"冻结湍流"（Taylor 假设：湍流随平均风平移），这样阵风会**随风移动**，视觉与玩法都更可信。

### L1-06 【P2】其他

- `gustAmp` 用的是上一次刷新的 `turbulenceIntensity`（计算顺序）；
- 对流/龙卷强迫只加在第 0 层，其他层没有 → 垂直结构断裂；
- `Sample.pressureAnomalyPa` 恒为 0。

---

## 7. L2 LocalFlowSolver

### L2-01 【P0·物理】地面被当成障碍物：伪上升气流与逆风

求解器对 9³ 范围内**所有固体方块**（包括脚下的地面）施加径向"排挤"与尾流亏损，且不归一化。离线复现（平地，地面方块 y≤63，基流 5 m/s 向东，采样点在 (0.5, 64+h, 0.5)）：

| 离地 h | modX (m/s) | modY (m/s) | 有效水平风 | sheltered |
|------|------|------|------|------|
| 0.5 | −14.3 | **+17.8** | −9.3（反向） | true |
| 1.0 | −8.5 | +13.1 | −3.5（反向） | true |
| 1.6（眼高） | −3.7 | **+11.2** | +1.3 | false |
| 2.5 | −0.7 | +6.7 | +4.3 | false |
| 4.0 | 0 | 0 | +5.0 | false |

即：**任何空旷平地上，站立高度存在 11 m/s 的垂直上升气流**，并在 4 格高度处突然消失。涡轮探针（采样 `above()`）因此几乎总在高读数；风扇射流、尾流都叠加在这个伪场上；`effectiveVelocityVector` 被污染。

**修复路线**（短期 → 长期）：
1. **短期**：只把"突出于局地地表"的方块当障碍：先取采样点周围 5×5 列的 `MOTION_BLOCKING_NO_LEAVES` 高度最小值作为局地地面 `g`，忽略 `y < g` 的方块；排挤与亏损各自**归一化并限幅**（亏损总和 ≤ 0.9·baseSpeed，排挤 ≤ 0.5·baseSpeed）；扫描半径边缘加平滑窗。
2. **中期**：每个"活跃 L2 区块"维护一个 32³ 体素占据网格（异步构建，方块更新时增量刷新），用**势流 + 尾流参数化**（如每个障碍簇按迎风截面积计算阻塞系数）。
3. **长期**：异步数值求解器（见文档 03：Stable Fluids 或 D3Q19 LBM，工作线程运行，主线程只读快照）。

### L2-02 【P1】性能

每次采样 729 次 `getBlockState` + 热源/射流线性扫描。风向标（服务端每 20 tick，且结果从不使用）、涡轮探针（每 10 tick）、风道（每 20 tick）、帆船（**每 tick**）都在调用。100 个探针 ≈ 每 tick 7,300 次方块查询。并且对未加载区块会同步加载（SYS-10）。

**建议**：L2 结果按 `(blockPos, epoch)` 缓存（同一方块在同一 L1 刷新周期内复用）；障碍扫描改为读取上面提到的体素缓存；采样 API 提供 `quality` 参数（`COARSE/LOCAL/FULL`）。

### L2-03 【P2】热源缓存

`refreshHeatCache` 步长 2（只扫偶数坐标，**漏检 7/8 的方块**）、只扫海平面 −16…+24、以焦点为中心半径 48。建议改为监听方块变化 / 区块加载时登记热源（`BlockEvent` + 方块实体 ticker），按区块索引存储。

### L2-04 【P2】射流

- 射流不受方块遮挡（风扇对着墙吹，墙后照样有风）；
- 全局静态、无维度（SYS-07）；
- 风道"接力"读取的是**包含射流本身**的 L2 风 → 正反馈（每级放大 1.15 倍，直到 14 m/s 上限）。风道应只读取 L1 + 上游风扇的射流，不读自己或下游。

### L2-05 【P3】

`confidence` 恒为 1；`NestedBoundaryCoupler` 未使用；`isSolid` 的上界判断应为 `y >= maxBuildHeight`。

---

## 8. 天气现象

### 8.1 ThermalSystem 热对流

| ID | 级别 | 问题 |
|----|------|------|
| TH-01 | P1 | 日相位（SYS-02） |
| TH-02 | P1 | `groundY = 60` 写死（SYS-03） |
| TH-03 | P2 | 下沉环固定 −0.5 m/s，不随上升强度缩放，质量通量不守恒（上升通量 `w0·πR²/2`，下沉约 `1.2R²`，w0=2–6 时失衡）。建议采用 **Allen (2006, NASA TM-2006-213475) 热泡模型**：上升核心 + 环形下沉，系数由守恒约束求出，并随高度 `z/zi` 变化 |
| TH-04 | P2 | 热泡不随玩家离开而回收，不持久化；生成锚点随机选玩家导致多人时密度不均 |
| TH-05 | P3 | `topY` 用种子地形 + 120…240，但采样不检查地表 |

### 8.2 SeaBreezeSystem 海风

| ID | 级别 | 问题 |
|----|------|------|
| SB-01 | P1 | 焦点/场不同步 + 半格偏移（SYS-06） |
| SB-02 | P1 | 域外夹边外推 → 全世界都吹网格边缘的海风 |
| SB-03 | P1 | `players.get(0)`：多人只为第一个玩家计算 |
| SB-04 | P2 | `isOceanAt` 对任意水面为真（湖、河、甚至水池）→ 小池塘也会产生海风、台风可以在湖面生成 |
| SB-05 | P2 | 每 100 tick 一次性做 33×33×8×15 ≈ **13 万次** `isOceanAt`（未加载区走 fbm），产生约数十毫秒尖峰 | 
| SB-06 | P2 | 8 方向射线、32 格步长 → 海岸距离与法向量粗糙且随焦点抖动 |

**建议**：海岸线是静态的 → 预计算并缓存**海陆距离场**（按 256×256 区域瓦片，使用海洋生物群系标签 `BiomeTags.IS_OCEAN` 而非任意水面，用 8SSEDT/Jump Flooding 求有符号距离场，梯度即为离岸法向），只在区块首次加载时更新；海风强度用海陆温差驱动（L0 的 `surfaceTempK − seaSurfaceTemp`），而非纯日相位。

### 8.3 StormCellSystem 风暴单体

| ID | 级别 | 问题 |
|----|------|------|
| SC-01 | P1 | 闪电落点同步加载区块（SYS-10）；真闪电可点火，无 gamerule/配置开关；下界/末地也会劈 |
| SC-02 | P2 | 风场不随高度变化：成熟期 −4.5 m/s 下沉和 12 m/s 外流在所有高度一样强。外流应限于离地约 0–100 格，并按 `z·exp(−z/h)` 型廓线（参见 Oseguera–Bowles 1988 / Vicroy 1992 下击暴流模型） |
| SC-03 | P2 | 风暴单体与原版天气脱钩：单体处于成熟期、降水 0.9，但 `level.isRaining()` 可能为 false → 客户端不下雨，只有闪电 |
| SC-04 | P2 | 冰雹强度只由半径决定（`(radius−70)/50`），与上升气流/湿度无关；伤害使用 `generic` 伤害源 → 应注册自定义 `DamageType`（带 `bypasses_armor` 与否的标签、死亡消息） |
| SC-05 | P3 | 移动速度生成时固定，不随引导气流更新 |

### 8.4 TyphoonSystem 台风

| ID | 级别 | 问题 |
|----|------|------|
| TY-01 | P1 | 旋向错误（SYS-01），且与 β 漂移方向自相矛盾 |
| TY-02 | P1 | 影响半径处硬截断：Vmax=40, Rmax=55, R=600 时边缘风速 12.1 m/s 瞬间归零 |
| TY-03 | P1 | 眼区 `r < eye → continue` 跳过切向风，但眼外 `v = Vmax·r/Rmax` 在 r=eye=18 时已有 13.1 m/s → 断崖；再叠加 `eyeDampening` 对总风的乘法衰减（包括 L1 背景风），两套机制重复 |
| TY-04 | P2 | 剖面：修正 Rankine（外部指数 0.5）可用，但建议改为 **Holland (1980)** 剖面 `V(r)=√[(B/ρ)(Rmax/r)^B Δp e^{−(Rmax/r)^B} + (rf/2)²] − rf/2`，B≈1–2.5，能平滑过眼墙且自然衰减到 0；并引入随高度的暖心结构（低层最强、高层出流反气旋） |
| TY-05 | P2 | 高度衰减用绝对 y；登陆判定 `isOceanAt` 对湖泊为真 |
| TY-06 | P2 | 台风不反馈 L0 气压场，导致地转风与台风风场不一致；远离所有玩家后不回收，占满 2 个名额长达 30 天 |
| TY-07 | P3 | 雨带螺旋手性需随 SYS-01 一并翻转 |

### 8.5 FrontSystem 锋面 / SquallLineSystem 飑线

**FR/SQ-01 【P1】线走向公式错误**：
```java
double lineAng = Math.atan2(windX, windZ) + Math.PI/2;   // atan2(x,z) 是"从 +Z 量起"的角
lineDir = (cos(lineAng), sin(lineAng));                   // 却按"从 +X 量起"解释
```
复现：

| 引导风 (x,z) | 线方向 | 与风向夹角余弦 | 期望 |
|------|------|------|------|
| (1, 0) 东风去向 | (−1, 0) | −1.0（**平行**） | 0（垂直） |
| (0, 1) 南向 | (0, 1) | +1.0（**平行**） | 0 |
| (1, 1) | (−0.707, 0.707) | 0.0（垂直） | 0 |
| (−1, 0.3) | (0.958, 0.287) | −0.835 | 0 |

只有对角风向才碰巧正确；正东/正南风下飑线与锋面**平行于风**，然后沿自身长度方向滑动（锋面沿法向 × 风速移动 → 横风漂移）。

**修复**：`lineDir = (−wz, wx)/|w|`（与风垂直），`normal = (wx, wz)/|w|`（指向移动方向）。

**SQ-02 【P1】阵风带在线段延长线上无限延伸**：`ahead` 只测法向距离，端点外侧的点只要法向距离 0–160 就吃到 18 m/s 阵风 —— 沿线方向无限长的条带。应同时要求沿线投影参数 `t ∈ [−ε, 1+ε]` 并在端点做圆角衰减。

**SQ-03 【P2】** 线后侧 0、线前侧 18 m/s 的断崖；所有贡献与高度无关；闪电每 2–8 秒一次的同步加载。

**FR-02 【P2】** 锋面无热力学：没有锋后降温/升压、没有风向转变（`n·gust` 只是沿法向推一把）；法向量符号任意（取决于端点顺序）。建议锋面作为 L0 的**温度/湿度/气压边界条件**注入，而风场改变由 L0 自然产生；短期内至少做"冷锋过境后风向顺转 30–60°、气温 −3…−8 K"。

### 8.6 DustDevilSystem 尘卷风

- **DD-01 【P2】** 核心内 (r<R) 没有切向风，核心边缘 `ring=1` 时切向风满值 → 5–12 m/s 断崖。用 Rankine/Burgers 剖面。
- **DD-02 【P2】** 高度按绝对 y（SYS-03）。
- 建议旋向保持随机（观测上尘卷风两向都常见，这一点代码注释是对的）。

### 8.7 MountainWaveSystem 背风波

- **MW-01 【P2】** `λ = 2πU/N`，N=0.012 时 U≥0.42 m/s 就超过 220 上限 → λ 实际恒为 220 格，公式形同虚设。若想保留压缩尺度，应写 `λ = clamp(2π·U/N · worldScale, …)` 并显式定义 `worldScale`（如 1 格 = 1 m 时 λ 为 0.5–5 km，远大于游戏可视范围；可以按 0.05–0.1 的比例压缩）。
- **MW-02 【P2】** 山脊搜索只在 {80,160,320,560,800} 五个点取样 → `crestDist` 离散跳变 → 相位跳变 → 垂直速度在空间上不连续。
- **MW-03 【P2】** 已加载区用真实高度、未加载区用 fbm 假地形，交界处会凭空出现"山脊"产生假背风波。
- 建议：使用 Scorer 参数判据（`l² = N²/U² − U''/U`，随高度递减才捕获波），短期至少加 Froude 数 `Fr = U/(N·h)` 判据：Fr<1 时阻塞绕流、Fr≈1 时强波、Fr≫1 时平滑越过。

### 8.8 MonsoonSystem / SeasonSystem / FogModel

- 季风：全维度均匀、不进粗风场包（客户端看不到）、在所有维度运行；强度在 `annual=0` 处符号突变（幅值小，影响有限）。建议季风改为调制 L0 的基流目标与海陆温差，而不是逐点叠加常矢量。
- 季节：实现正确（doy=45 盛夏）。建议提供配置（年长度、是否与 Serene Seasons 等模组同步）。
- 雾：`solarFactor` 相位错；湿度来自焦点处单点；见文档 02。

---

## 9. 运行时编排（ZenithServerRuntime）

| ID | 级别 | 问题 | 建议 |
|----|------|------|------|
| RT-01 | P1 | 粗风场包只采 L1，不含现象 → 客户端粒子、风声、雾看不到台风/风暴外流/锋面/季风 | 粗风场改用完整采样（不含 L2），或客户端基于快照本地重建现象（文档 02 推荐） |
| RT-02 | P1 | 焦点模型（SYS-06）、热源缓存（L2-03）、区块加载（SYS-10） | — |
| RT-03 | P2 | 驱动器每 tick 推进，固定 dt=0.05；`/tick rate` 改变服务器 tick 率时模拟时间与真实时间脱钩；睡觉跳夜时日相位跳 12000 而驱动器时间不跳 | 以 `gameTime` 差值或 `dayTime` 差值驱动；跳夜时推进"快速模式" |
| RT-04 | P2 | 快照每 100 tick 发全维度所有现象给所有玩家 | 按玩家距离裁剪（例如 2,000 格内），并只在变化时发送 |
| RT-05 | P2 | 维度查找用 `toString().equals()` 线性遍历，每次 API 采样都做 | 缓存 `ZenithId → ResourceKey<Level>` |
| RT-06 | P3 | `Source` 标为 `L1_COARSE` 而实际含 L2；`confidence` 恒 0.9 | 见 API |

**粗糙度/生物群系识别**（P1）：`McTerrainAdapter.roughness01` 与客户端落叶逻辑都用 `biome.toString().contains("forest")`。`Biome` 类没有覆写 `toString()`，得到的是 `net.minecraft.world.level.biome.Biome@1a2b3c` 这样的字符串，**永远匹配不到** → 粗糙度恒为 0.3，落叶粒子永不生成。应使用 `Holder<Biome>.is(BiomeTags.IS_FOREST)` / `IS_OCEAN` / `IS_MOUNTAIN` 等标签，或 `holder.unwrapKey()`。`surfaceAt` 用 `block.toString()` 子串匹配（"stone" 会匹配 "redstone_ore"、"sand" 会匹配 "sandstone"），应改为方块标签（`BlockTags.SAND`, `BlockTags.BASE_STONE_OVERWORLD`, `BlockTags.LEAVES` …）。

---

## 10. 公开 API 审计

### 10.1 设计优点
- 不依赖 MC 类型的纯 API 层（`ZenithVec3/BlockPos/Id`）+ 单独的 MC 桥，便于跨版本；
- 信任等级（`Authority` + `isTrustedForGameplay`）的概念清晰；
- 运行时提供者接口 + `volatile` 绑定。

### 10.2 问题

| ID | 级别 | 问题 | 建议 |
|----|------|------|------|
| API-01 | P1 | `SamplePolicy` 在运行时**完全被忽略**；`SERVER_COARSE_ONLY` 仍包含 L2；`Source` 标签与实际来源不符 | 按策略真正选择层级；`Source` 改为位集合（`L0|L1|PHENOMENA|L2`） |
| API-02 | P1 | `runL2` 返回 `ZenithL2Result.of(zero)`（`supported=true` 且力/力矩为 0） | 未实现前返回 `unsupported()`，否则调用方会以为"零气动力"是计算结果 |
| API-03 | P1 | `runPolar` 物理错误：①零升迎角由**厚度**推出（`α0 = −2 − 8t`），NACA 0012（对称）在 0° 给出 Cl=0.232（AR=5）；②`maxThickness()` 取 `2·max|y|`，对有弯度翼型把弯度算进厚度（4412 约 0.19）；③失速后 `cd` 含 `0.02·(α/15)^4`，在 45° 为 1.63、**90° 为 25.9**（平板实际约 1.98）；④`cm` 对称翼取 −0.098（应≈0）；⑤雷诺数被忽略；⑥`stepDegrees ≤ 0` 未校验（除零 → 巨大循环）；⑦`ZenithSurfaceDescriptor.controlDeflectionDegrees` 未使用 | 用薄翼理论求 α0：`α0 = −(1/π)∫ dyc/dx (cosθ−1) dθ`（NACA 4 位数有闭式近似，2412≈−2.1°、4412≈−4.2°）；失速后采用 Viterna–Corrigan 外推（`CD_max ≈ 1.11 + 0.018·AR`）；厚度用上下表面差值；Re 影响 `cd0`（如 `0.074/Re^0.2` 湍流平板摩阻）；参数校验 |
| API-04 | P2 | 地形 API 两条路径：`ZenithTerrainApi.sample` 总是返回 `unknown`，`ZenithWindRuntimeProvider.sampleTerrain` 才有实现 | 合并 |
| API-05 | P2 | 无 API 版本号、无事件（现象生成/消亡、闪电、台风登陆）、无批量采样（飞行器多翼面需要一次采样几十个点）、无线程契约、`TornadoVortex` 等内部类以 public 可变字段暴露 | 增加 `ZenithApi.VERSION`；NeoForge 事件 `ZenithWeatherEvent.*`；`sampleBatch(world, double[] xyz)`；内部类收紧可见性 |
| API-06 | P2 | `ZenithMinecraftWindApi.sample(Level)` 对客户端 Level 仍走服务端运行时（SYS-11） | 客户端 Level 路由到 `ZenithClientWindApi` |
| API-07 | P2 | `pressureAnomalyPa` 恒 0；`hasTemperature` 总是 true 但温度来自错误的 L1 层 | 先修 L1 再暴露 |
| API-08 | P3 | `GameplayWindSample.empty()` 的温度为 NaN（`hasTemperature=false` 时可接受，但应在 Javadoc 写明） | 文档 |
| API-09 | P3 | `ZenithAirfoilJson` 手写 JSON 解析，遇到科学计数法/嵌套会出错 | 用 Gson（MC 自带） |
| API-10 | P3 | NACA 坐标生成：首段循环生成的是**下**表面（`yc − yt·cosθ`），与 `ZenithAirfoilDefinition` 注释"上表面前缘→后缘"相反；前后缘点重复 | 修正注释或顺序 |

---

## 11. 网络

| ID | 级别 | 问题 |
|----|------|------|
| NET-01 | P1 | 粗风场：仅 L1、最近邻、9×9@32（只覆盖 ±128 格）、每 2 秒、客户端无时间插值 → 每 2 秒风向"跳一下" |
| NET-02 | P1 | 龙卷单位错误（DRV-04） |
| NET-03 | P2 | 快照不带维度标识；换维度/登出不清空（SYS-07） |
| NET-04 | P2 | 快照全量广播（RT-04） |
| NET-05 | P3 | `FLOAT_ARRAY_CODEC` 读取长度不设上限（服务器→客户端方向，风险低，但建议 ≤ 4096） |
| NET-06 | P3 | 粗风场每格 3×float + 每包 3×double 中心，可量化为 int16（0.01 m/s 精度）把包体积减半 |

---

## 12. 客户端与渲染（概要，细节见文档 02）

| ID | 级别 | 问题 |
|----|------|------|
| CLI-01 | P1 | **粒子单位与积分方式**：`WindDrivenParticle.applyWind` 每 tick 加 `0.05·factor·w`，原版粒子 friction=0.98 → 稳态速度 `2.5·factor·w` block/tick = **50·factor 倍**真实风速；风迹粒子初速 `0.9·w` block/tick（18 倍）后被 3 block/tick（60 m/s）上限截断；雨丝 `0.35·w`（7 倍）且下落 1.6–2.2 block/tick（32–44 m/s，真实雨滴终速约 9 m/s）。应改为"向风速松弛"：`v += (w/20 − v)·k` |
| CLI-02 | P0 | **雾不生效**：NeoForge 1.21.1 的 `ViewportEvent.RenderFog` 必须 `setCanceled(true)` 才会应用修改（官方 Javadoc）。且未判断 `getMode()`/`getType()`，修好后会覆盖水下、岩浆、失明、黑暗效果的雾 |
| CLI-03 | P0 | **风声不响**：`WindLoopSound` 初始 `volume=0`，原版 `SoundEngine.play` 对 volume=0 且 `canStartSilent()==false` 的实例直接跳过；`isActive` 为 false → 每 tick 新建一个实例 → 永远不播放。需覆写 `canStartSilent()` 返回 true |
| CLI-04 | P0 | **客户端指令**：`ZenithClientCommands` 用 `@EventBusSubscriber(bus = MOD)` 订阅 `RegisterClientCommandsEvent`，而该事件在 **游戏总线**（`NeoForge.EVENT_BUS`）上触发。NeoForge 的 MOD 总线只接受 `IModBusEvent`，通常会在加载时抛异常；即使不抛，`/zenithc` 也不会注册 |
| CLI-05 | P1 | 雨丝不检查生物群系降水类型与头顶遮挡：沙漠、雪原、屋内、洞穴里都会"下雨" |
| CLI-06 | P1 | 落叶依赖 `biome.toString()`（永不匹配） |
| CLI-07 | P1 | 龙卷渲染：坐标单位错（DRV-04）；`bufferSource().getBuffer()` 后未 `endBatch(renderType)`，依赖后续隐式 flush（可能在手部渲染阶段以不同矩阵绘制或延迟一帧）；半透明却写深度 |
| CLI-08 | P2 | 风向标渲染 `YP.rotationDegrees(−yaw)` 把 +Z 映射到 `(−sin yaw, cos yaw)` → **东西镜像**；更新每 20 tick 一次且不做 partialTick 插值 → 每秒跳一下；服务端分支的计算结果从不同步、纯浪费 |
| CLI-09 | P2 | 帆船/风向标使用 `POSITION_COLOR` + `NO_LIGHTMAP` → 夜间自发光、不受雾影响、与光影包（Iris/Oculus）不兼容 |
| CLI-10 | P2 | 气象图：显示范围 ±1200 格，但粗风场只覆盖 ±128 格，外圈箭头全是夹边外推值；使用 🌀⛈🧊 等 Emoji，MC 默认字体没有这些字形（显示为方框）；线段不裁剪 |
| CLI-11 | P2 | 风速计：`Math.toDegrees(dirDeg)` 对已是角度的值再转一次；罗盘换算 `(dirDeg+180)` 应为 `(180−dirDeg)`，导致**东西互换** |
| CLI-12 | P3 | 多个静态 `Random`、静态 tickCounter；`new BlockPos((int)x, …)` 对负坐标截断偏一格（应 `Mth.floor`） |

---

## 13. 玩法内容

### 13.1 帆船（SailboatEntity / SailingPhysics）

| ID | 级别 | 问题 |
|----|------|------|
| SAIL-01 | P0 | 未覆写 `isPickable()`（`Entity` 默认 false）→ 玩家的准星射线选不中实体 → **无法右键上船**；也未覆写 `hurt()` → 无法打掉回收 |
| SAIL-02 | P1 | 航向约定与 MC 不一致：物理前向 `(sin h, cos h)`，MC 前向 `(−sin yaw, cos yaw)`，代码 `setYRot(deg(h))` → 视觉朝向与运动方向在东西方向镜像；舵输入 `−xxa` 叠加后左右可能反向；渲染器又转 `180 − yaw`。需要统一约定后实测 |
| SAIL-03 | P1 | 每 tick `setPos` 直接移动，**无碰撞**（穿过陆地、方块、其他实体） | 
| SAIL-04 | P1 | 横向阻力系数 `320·0.05 = 16 N/(m/s)`，质量 800 kg → 横漂衰减时间常数 **50 秒**：转向后船会长时间侧滑。真实龙骨的侧向阻力比纵向大 1–2 个数量级 |
| SAIL-05 | P2 | 不可航区、帆效率用**真风**而非**视风**判断；正顺风驱动系数仅 0.1（实际顺风跑虽慢于横风但远不止 10%）；推力方向沿船艏、不分解升力/阻力 |
| SAIL-06 | P2 | 服务端权威移动 + 默认追踪更新间隔 → 骑乘时客户端位置抖动；原版船是"控制者客户端权威 + 服务端校验" |
| SAIL-07 | P2 | 使用 `meanVelocity` 采样 y+1：受 L1-01 影响，海面风只有 L0 的 28% |
| SAIL-08 | P3 | 物品放置时 `setYRot(playerYaw)` 但 `headingRadians=0`，下一 tick 被覆盖为朝南 |

**建议的帆船模型**（中期）：在船体坐标系分解速度 `(u_fwd, u_lat)`；帆力 = 视风动压 × 帆面积 × `(CL(α), CD(α))`（α = 视风角 − 帆角，直接复用修正后的 `runPolar`），龙骨侧力 = `½ρ_w·A_keel·CL_keel(β)`（β 为漂角）；横倾力矩 → 横倾角（渲染用）；船体阻力 = 摩擦 + 兴波（`∝ Fr⁴` 的近似）。这会自然得到真实的航行极线（迎风 45°、横风最快、顺风较慢）。

### 13.2 方块

| ID | 级别 | 问题 |
|----|------|------|
| BLK-01 | P1 | **风扇关不掉**：注册射流的坐标是"出风口"（中心 + 0.6·朝向），但 `speed=0` 和 `setRemoved()` 时注销的是**方块中心** → 关闭或拆除后射流永久残留（直到 JVM 重启） |
| BLK-02 | P2 | 红石覆盖：通电时写入 SPEED=3，断电后读到的"用户档位"已是 3，无法恢复原档位 |
| BLK-03 | P2 | 风道正反馈（L2-04） |
| BLK-04 | P2 | 涡轮探针读取 `effectiveSpeed`，受 L2-01 伪上升气流影响长期偏高 |
| BLK-05 | P1 | 无 `data/` 目录：方块**没有战利品表**（破坏无掉落）、**没有配方**（生存不可获得）、`requiresCorrectToolForDrops` 却**没有 `mineable/pickaxe` 标签**（挖掘极慢且无掉落） |
| BLK-06 | P3 | 风向标服务端 ticker 计算的角度不同步也不使用 |

### 13.3 指令

- **CMD-01 【P0·安全】** `/zenith storm|typhoon|thermal` 等**没有 `.requires(src -> src.hasPermission(2))`**，任何玩家可在服务器上召唤成熟期雷暴（真闪电、可点火、冰雹伤害）。
- CMD-02 【P3】 指令反馈为硬编码中文字符串，应使用 `Component.translatable`。

---

## 14. 工程与发布

| ID | 级别 | 问题 |
|----|------|------|
| ENG-01 | P1 | 许可证三处冲突（见第 2 节）；README 引用的第三方许可路径不存在 |
| ENG-02 | P1 | 仓库缺少 `gradle/wrapper/gradle-wrapper.jar`（`.gitignore` 有例外规则但压缩包内没有）→ `./gradlew build` 直接失败 |
| ENG-03 | P1 | 无配置系统（`ModConfigSpec`）：闪电点火、冰雹伤害、维度白名单、粒子密度、刷新频率都不可调 |
| ENG-04 | P2 | 无任何单元测试；物理核心类（L0/L1/L2/现象）与 MC 解耦程度其实不错，完全可以纯 JUnit 测试 |
| ENG-05 | P2 | `tools/gen_textures.py` 写死 `~/workspace/zenith` 路径 |
| ENG-06 | P3 | 注释风格口语化（"偷懒""别深究"），对外 API 的 Javadoc 不足以支撑第三方集成 |
| ENG-07 | P3 | `mods.toml` 缺 `displayURL`、`logoFile`；`issueTrackerURL` 指向的仓库名需确认 |

---

## 15. 性能评估（服务端，单玩家，估算）

| 项目 | 频率 | 每次开销（估） | 摊薄到每 tick | 尖峰风险 |
|------|------|------|------|------|
| 驱动器 advance | 每 tick | ~14 个对象更新 | 极低 | 无 |
| 9 个现象 tick | 每 tick | 低（海风除外） | 低 | — |
| 海风场重建 | 每 100 tick | ~13 万次 `isOceanAt`（含 fbm） | ~1,300 次/tick | **高：单 tick 数十 ms** |
| L0 刷新 | 每 256 tick | 1,681 格 × (驱动器采样 + 5 次地形探针) | 低 | 中 |
| L1 刷新 | 每 64 tick | 1,089 列 × 8 层 + 地形 5 次 | 低 | 低 |
| 热源扫描 | 每 200 tick | ~5 万次 `getBlockState` | ~250 次/tick | **高（可能加载区块）** |
| 粗风场广播 | 每 40 tick/玩家 | 81 次 L1 采样 | 极低 | 无 |
| 玩法采样（含 L2） | 帆船每 tick、探针 1/10、风道/风向标 1/20 | 729 次方块查询 + 9 个现象 + 6 次地形 | 随方块数线性增长 | 中 |
| 闪电 | 2–18 秒 | 同步区块加载 | — | **高** |

**主要建议**：海风距离场缓存化；热源事件化；L2 体素缓存 + 结果缓存；所有重计算分帧（time-slicing，每 tick 处理固定预算的格子）；引入 `/zenith profile` 指令输出各子系统 ms 占用。

---

## 16. 物理模型对照表（当前 vs 文献 vs 建议）

| 过程 | 当前实现 | 参考模型 | 建议 |
|------|------|------|------|
| 近地风廓线 | 对数律，但层定义错误（L1-01） | 对数律 `u(z)=u*/κ·ln(z/z0)` + Monin–Obukhov 稳定度修正（Stull 1988） | AGL 分层；z0 典型值：海 0.0002 m、草地 0.03、农田 0.1、森林 0.5–1.0、城镇 1–2（当前海面 0.095 偏大） |
| Ekman 偏转 | L0 风作地面风、向上顺转 | 地面风相对地转风逆转 20–30°（陆）/10–15°（海） | 以 L0 为 ABL 顶风 |
| 阵风/湍流 | 每列常数、64 tick 阶跃 | Dryden / von Kármán 谱；冻结湍流（Taylor 假设） | 随平均风平移的 3D 噪声 + OU 时间过程 |
| 台风 | 修正 Rankine（α=0.5）+ 硬截断 | Holland (1980)；Willoughby (2006) | Holland 剖面 + 平滑外缘 + 正确旋向 |
| 龙卷 / 尘卷风 | 高斯核切向风 / 分段（不连续） | Rankine、Burgers–Rott、Sullivan 双胞涡 | Burgers–Rott（处处光滑，含径向入流与上升） |
| 热泡 | 抛物线核 + 固定下沉环 | Allen (2006) NASA 自主滑翔热泡模型 | 用 Allen 模型的守恒系数与 z/zi 垂直结构 |
| 下击暴流 | 线性核 + 正弦外流环、与高度无关 | Oseguera–Bowles (1988)、Vicroy (1992) | 带高度廓线的轴对称解析模型 |
| 海风 | 纯日相位 × 指数衰减 | 海陆温差驱动；海风锋重力流速度 `≈ 0.7·√(g'·h)` | 由 L0 地表温差驱动、锋面速度由密度差导出 |
| 背风波 | λ 恒 220、相位离散 | 线性山地波理论、Scorer 参数、Froude 数 | Fr 判据 + 连续相位 |
| L2 绕流 | 方块径向排挤 + 尾流锥（含地面） | 势流 + 尾流参数化 → 数值 CFD | 见 L2-01 修复路线 |
| 翼型极线 | α0 由厚度推出、失速后 cd 爆炸 | 薄翼理论 + Viterna–Corrigan 失速外推 | 见 API-03 |
| 帆船 | 系数插值 + 弱横向阻尼 | 帆/龙骨双翼型平衡（VPP 思路） | 见 13.1 |

---

## 17. 推荐修复顺序（≈ 0.1.2 热修 + 0.2 正确性）

**第一批（1–3 天，几乎都是一两行）**
1. CMD-01 指令权限
2. SAIL-01 `isPickable()` / `hurt()`
3. CLI-02 雾 `setCanceled(true)` + 模式/流体判断
4. CLI-03 风声 `canStartSilent()`
5. CLI-04 客户端指令改游戏总线
6. BLK-01 风扇注销坐标
7. SYS-07 生命周期清理（`ServerStoppedEvent`、`LoggingOut`）
8. DRV-04 龙卷坐标 ×256
9. FR/SQ-01 线走向公式
10. CLI-11 风速计角度
11. ENG-02 补 wrapper jar、ENG-01 统一许可证

**第二批（约 1–2 周，横切重构）**
1. SYS-01 手性 + SYS-02 日相位：抽出 `Atmos.solarElevation()`、`Atmos.cyclonicTangent()`，全仓替换
2. SYS-03 + L1-01：AGL 分层与三线性插值
3. L2-01：地面排除 + 归一化
4. SYS-05：插值与边界、平滑包络
5. SYS-09/10：维度门控与区块安全查询
6. 生物群系/方块识别改用标签
7. 配置系统 + `data/`（战利品表、配方、标签）

**第三批**：多人瓦片化（SYS-06）、持久化（SYS-08）、客户端现象重建（RT-01）—— 见文档 03。

---

## 附录 A：关键修复代码片段

**A.1 统一大气工具类**
```java
public final class Atmos {
    public static final double HEMISPHERE = +1; // 可配置：+1 北半球
    /** 正午=1、午夜=-1 */
    public static double solarElevation(Level level, float partial) {
        return Math.cos(level.getTimeOfDay(partial) * 2.0 * Math.PI);
    }
    /** MC 坐标下的气旋式切向单位向量（北半球俯视逆时针） */
    public static void cyclonicTangent(double dx, double dz, double r, double[] out) {
        double inv = r > 1e-6 ? 1.0 / r : 0;
        out[0] =  dz * inv * HEMISPHERE;
        out[1] = -dx * inv * HEMISPHERE;
    }
    /** C¹ 连续的外缘包络：r<r0 为 1，r>r1 为 0 */
    public static double taper(double r, double r0, double r1) {
        if (r <= r0) return 1; if (r >= r1) return 0;
        double t = (r - r0) / (r1 - r0); return 1 - t * t * (3 - 2 * t);
    }
}
```

**A.2 Burgers–Rott 涡（替换龙卷/尘卷风/台风眼内剖面）**
```java
/** 切向风速：Γ 为环量，rc 为核心半径；r→0 时 → 0，r≫rc 时 → Γ/(2πr) */
static double burgersRott(double r, double gamma, double rc) {
    if (r < 1e-6) return 0;
    return gamma / (2 * Math.PI * r) * (1 - Math.exp(-(r * r) / (rc * rc)));
}
// 最大风速约出现在 r ≈ 1.12·rc，Vmax ≈ 0.638·Γ/(2π·rc) → 由 Vmax 反推 Γ
```

**A.3 雾事件（NeoForge 1.21.1）**
```java
@SubscribeEvent
public static void onRenderFog(ViewportEvent.RenderFog e) {
    if (e.getType() != FogType.NONE) return;                // 水下/岩浆/粉雪交给原版
    var cam = e.getCamera().getEntity();
    if (cam instanceof LivingEntity le && (le.hasEffect(MobEffects.BLINDNESS) || le.hasEffect(MobEffects.DARKNESS))) return;
    double d = FogState.smoothedDensity();                  // 时间平滑后的浓度
    if (d < 0.02) return;
    float far = Mth.lerp((float) d, e.getFarPlaneDistance(), 24f);
    e.setFarPlaneDistance(far);
    e.setNearPlaneDistance(Math.min(e.getNearPlaneDistance(), far * 0.1f));
    e.setCanceled(true);                                    // 1.21.1 必须 cancel 才生效
}
```

**A.4 风声**
```java
@Override public boolean canStartSilent() { return true; }
```

**A.5 飑线/锋面走向**
```java
double w = Math.hypot(wx, wz); if (w < 0.3) { wx = 1; wz = 0; w = 1; }
double dirX = -wz / w, dirZ = wx / w;   // 线方向 ⟂ 风
double nX = wx / w,  nZ = wz / w;       // 法向 = 移动/阵风锋方向
```

**A.6 L2 地面排除（短期）**
```java
int g = localGroundMin(x, z);          // 5×5 列 MOTION_BLOCKING_NO_LEAVES 最小值
if (by + dy < g) continue;              // 忽略地面以下方块
// 循环外：
double defic = Math.min(0.9, deficitSum) * baseSpeed;
double pushMag = Math.hypot(pushX, pushZ);
if (pushMag > 0.5 * baseSpeed) { pushX *= 0.5 * baseSpeed / pushMag; pushZ *= 0.5 * baseSpeed / pushMag; }
```

## 附录 B：数值复现脚本说明

- L2 地面伪上升气流：用与 `LocalFlowSolver.solve` 完全相同的公式在 Python 中复现（平地 y≤63，基流 5 m/s 东向），结果见 7 节表格。
- 飑线/锋面走向：对四组风向计算 `lineDir · ŵ`，结果见 8.5 节表格。
- L0 温度偏置：`(0.01·6 − 0.01·2)/(12.8/1200) ≈ 3.75 K`。
- 极线：NACA 0012（AR=5）`α0=−2.96°`，`Cl(0°)=0.232`；`cd(45°)=1.63`，`cd(90°)=25.93`。
- 台风断崖：`40·√(55/600) = 12.1 m/s`（外缘），`40·18/55 = 13.1 m/s`（眼缘）。
- 帆船横漂：`τ = m/k = 800/16 = 50 s`。
