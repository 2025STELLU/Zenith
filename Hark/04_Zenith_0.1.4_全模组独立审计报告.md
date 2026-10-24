# Zenith 0.1.4-alpha 全模组独立审计报告

> 审计对象：`Workspace`（`mod_version = 0.1.4-alpha`，NeoForge `21.1.256` / Minecraft `1.21.1`，JDK 21，包名 `net.atomos.zenith`）
> 实测规模：**106 个 Java 文件 / 8,348 行（含空行；有效代码约 7,300 行）**；`src/main/resources` **57 个文件 / 38 个 JSON**
> 审计方式：逐文件通读 + 关键公式离线数值复现 + 与 `Workspace/docs/01`–`03`（0.1.4 自述）及 `Hark/01`–`03`（0.1.1 基线）逐条交叉核对 + 构建尝试
> 审计立场：**只读审计**。未修改任何源码、资源或既有文档。
> 编号约定：沿用 `SYS / DRV / L0 / L1 / L2 / API / NET / CLI / SAIL / BLK / ENG`；天气现象统一为 `WX-<系统>-<序号>`，旧天气编号（TH/SB/SC/TY/FR/SQ/DD/MW/MS）作为历史别名保留；新横切问题顺延到本家族下一个可用编号。

---

## 0. 执行摘要

### 0.1 一句话结论

**0.1.4 把"表现层能单独修的部分"基本修完了，但把"物理核心"整块留在了 0.1.1 的状态；同时本次审计发现一个新的 P0：地形适配器读取的是地表上方的空气格，已加载区块的海陆判定与地形高度整体失真。** 因此 0.1.4 的观感提升是真实的，但"风还是不像风"的根因（L1 分层、L2 伪上升、手性、AGL）一个都没解决，且海风/台风/尘卷风/雾在新代码路径下比 0.1.1 更不可信。

### 0.2 十条最重要发现

| # | 编号 | 级别 | 发现 | 直接后果 |
|---|------|------|------|----------|
| 1 | **SYS-12** | **P0（新）** | `McTerrainAdapter` 用 `getHeight(WORLD_SURFACE)` 直接取方块，而该 API 返回**地表上方第一格空气**；`isOceanAt` 读空气 → 恒 `false`，`surfaceAt` 读空气 → 恒 `GRASS`，`terrainHeightBlocks` 比真实地表格高 1 | 已加载区：海风几乎无风、台风一进加载区即判"登陆"衰减、尘卷风找不到热地表、服务端雾 `overWater` 恒 false、L1/L0 地形基准整体 +1 |
| 2 | **L1-01** | **P0（未修）** | L1 用绝对高度 `(l+0.5)·40` 建层、却按 AGL 选层（`MesoscaleGrid.java:115-119` vs `:218-220`） | 地表 y≥60 处，近地风恒为 L0 的 **0.2775×**；眼高再乘 `f·0.6`，实测 **0.1715×** |
| 3 | **L2-01** | **P0（未修）** | 9³ 扫描把脚下地面当障碍物，排挤与尾流不归一化（`LocalFlowSolver.java:63-95`） | 平地眼高 **+11.2 m/s 伪上升**（基流 5）、**+22.5 m/s**（基流 10）；涡轮探针、风道、帆船全部被污染 |
| 4 | **SYS-01 / DRV-08** | P1（未修 / 新） | 地转风两分量符号都反（`BackgroundMetGrid.java:210-211`）；低压径向分量符号也反（`WorldScaleDriver.java:161-164`）；台风 `spin=1` 在 MC 坐标下是顺时针（`TyphoonSystem.java:31,131`） | 三个独立的方向错误叠加：低压周围**辐散而不是辐合**，北半球气旋**顺时针**，地转风与驱动器目标风互相抵消 |
| 5 | **SYS-10 / WX-SC-02 / WX-SQ-02** | P1（未修） | 风暴与飑线闪电仍用 `level.getHeightmapPos(...)`（`StormCellSystem.java:122-124`、`SquallLineSystem.java:142-144`），`setVisualOnly(false)`，无配置、无权限、无维度门控 | 视距外同步加载/生成区块；下界与末地也会落真闪电点火 |
| 6 | **SAIL-09 / SAIL-10** | P1 / P2（新） | `findWaterSurface` 返回 `p.y+1-ownHeight`（`SailboatEntity.java:135`），源水 `ownHeight≈1` → 船体原点在水面下 0.85 格；`SailboatItem` 用 `SOURCE_ONLY` 只保证"命中点不是水"，任意方块命中都会放置（`SailboatItem.java:29-35`） | 船半沉、可停在陆地/屋顶；再叠加 `setPos` 无碰撞（`:126`）→ 穿地形 |
| 7 | **NET-01 / NET-07 / CLI-14 / CLI-15** | P1 / P2 / P3 | 粗风场仍只有 9×9@32（±128 格，`ZenithServerRuntime.java:93-94`），气象图仍按 ±1200 格绘制（`MeteorologicalMapScreen.java:14,42-44`）；包解码不校验长度/参数（`CoarseWindPacket.java:26-30`、`WeatherSnapshotPacket.java:118-122`）；无台风渲染器（`ZenithClientSetup.java:27-31`）；任意台风存在即加密雨丝（`WeatherWindController.java:35-37`） | 地图 90% 区域是钳边值；恶意/损坏包可 OOM 或除零；台风在客户端"只有雨没有形" |
| 8 | **WX-FR-02 / WX-FR-01** | P2 / P1 | 锋面生成时 `intensity01 = 0.5+0.5·stormAct`（`:94`），下一 tick 立刻被 `t/0.2` 覆盖（`:108-109`），t≈0.0004 → 强度≈**0.002** | 每条锋面前 20% 寿命（24–48 分钟）几乎无风无雨；且仍无锋后降温/升压/风向顺转 |
| 9 | **WX-SC-01** | P2（新） | 冰雹伤害只看 `hail01 ≥ 0.5` 与 `canSeeSky`（`StormCellSystem.java:137-148`），不看 `isRaining`、不看是否在降水核心、伤害源是 `generic` | 晴天/无雨维度的成熟单体照样砸人；1 点/2 秒无来源、无死亡消息、无护甲语义 |
| 10 | **ENG-01/03/04/08** | P1–P3 | 许可证三处仍互相矛盾（`README.md:68` 禁止修改 vs `LICENSE:19-22` 允许署名修改）；无配置系统、无 `SavedData`、无任何测试；`docs/WEATHER_SYSTEMS.md` 仍写旧太阳公式与不存在的海绵层 | 发布合规风险 + 无法调参 + 无法回归；文档继续误导协作者 |

### 0.3 与 0.1.4 自述结论的差异

| 自述（`Workspace/docs/`） | 独立复核 |
|---|---|
| `01` §0：0.1.3 的 10 个新增问题，0.1.4 修了 9 个 | 同意 `N-10/12/13/14/15/16/17/19` 共 8 项已修；`N-11` 只修了客户端，服务端仍用错误地形判定；`N-18` 修了 ticker 但引入 `N-20` 风声回归。**净修复 8 项，不是 9 项** |
| `01` §2.3：未修列表 = L1-01、L2-01、SYS-01/03/05、DRV-01/02/03、海风、SYS-08/09/10、API、SAIL、NET | 同意，且遗漏 **SYS-12**（地形适配器）这一比 L1-01 更靠上游的 P0；`DRV-05/06/07`、`L0-03…08`、`L1-02…06`、`FR-02`、`TY-02…07`、`SC-02…05` 也应在这张表里 |
| `01` §1 表：0.1.4 视觉侧"9 项做了 8 项正确" | 同意 8 项；但 `02` §0"不需要改数据通路就能修的视觉问题已经全部清零"**不成立**：T-4（双 pass）、R-1（光照/雾/光影兼容）、T-5（尺寸不用快照）都属于纯渲染层，仍未做 |
| `03` §5 KPI：物理 P0 = 2（L1-01、L2-01） | **实际 3 个**：新增 SYS-12。且 SYS-12 会让"修完 L1/L2 就正常"的验收标准失效——地形基准错 1 格、海陆判断失效会继续污染海风/台风/尘卷风/雾 |
| `01` §2.2：N-11 剩余只是"两套口径" | 实际更重：服务端 `fogDensityAt` 的 `overWater` 依赖 `surfaceAt`，而 `surfaceAt` 在已加载区恒 `GRASS` → **服务端 API 的平流雾永远不会触发** |

### 0.4 值得肯定的部分（不计入问题）

- `N-10` 的太阳时间统一做得干净：7 处日相位全部收敛到 `FogModel.solarFactor()`，`grep "PI / 2"` 已无残留；`solarFactor` 注释明确记录了旧错误。
- `SYS-07` 生命周期修复完整：`ServerStoppedEvent` 清 `dimensions` + `JetSourceRegistry`（`ZenithServerRuntime.java:183-188`），射流按维度分桶（`JetSourceRegistry.java:20-27`），客户端 `LoggingOut/Clone` 按维度清理（`ClientWindState.java:55-77`）。
- `DRV-04` 龙卷坐标单位已乘 `CELL_SIZE_BLOCKS`（`WeatherSnapshotPacket.java:199`），渲染不再画到原点。
- `FR/SQ-01` 线走向、`SQ-02` 端点无限延伸都已正确修复（`FrontSystem.java:72-87`、`SquallLineSystem.java:203-208`）。
- `CLI-02/03/04` 三个 P0 功能失效全部修好：雾事件 `setCanceled` + 类型/失明守卫（`FogController.java:32-44`）、`canStartSilent()`（`WindSoundManager.java:68`）、客户端指令挂 `Bus.GAME`（`ZenithClientCommands.java:15`）。
- `CLI-08/11` 修正正确：风向标 yaw 去负号 + `partialTick` 插值（`WindVaneRenderer.java:39-40`），罗盘换算 `(180 - dir + 360) % 360`（`WindMeterItem.java:33`）。
- `SAIL-01` 已修：`isPickable() = true`、`hurt()` 完整（`SailboatEntity.java:152-161`），上船与拆船都可用。
- 资源侧干净：4 方块 / 7 物品配方、4 战利品表、`mineable/pickaxe` 标签齐全；38 个 JSON 全部可解析；`en_us` 与 `zh_cn` 键集合一致（各 26 条，差集 0）。

---

## 1. 范围、方法与严重级别

### 1.1 审计范围

| 模块 | 覆盖内容 |
|------|----------|
| 风场核心 `wind/` | `WorldScaleDriver`、`BackgroundMetGrid`(L0)、`MesoscaleGrid`(L1)、`LocalFlowSolver`(L2)、`ZenithServerRuntime`、`NestedBoundaryCoupler`、`JetSourceRegistry`、`HashedSeedTerrainProvider`、`ZenithCommands` |
| 天气 `weather/` | Thermal、SeaBreeze、StormCell、Typhoon、Front、SquallLine、DustDevil、MountainWave、Monsoon、Season、FogModel、WeatherContext、WeatherPhenomenon、WindContribution |
| 公开 API `api/` | 风/天气/地形外观、采样策略与信任规则、L2/极线请求结果、翼型、客户端 API、MC 桥 |
| 客户端 `client/` + `mixin/` | `ClientWindState`、粒子三件套（Provider/Controller/Weather）、雾、风声、气象图、可视化、渲染器 ×3、Mixin ×3 |
| 玩法 `block/ entity/ item/` | 风扇、风道、风向标、涡轮探针、帆船、风速计、气象图 |
| 网络 `network/` | `CoarseWindPacket`、`WeatherSnapshotPacket`、注册器 |
| 资源与构建 | `assets/`、`data/`、`META-INF/neoforge.mods.toml`、`build.gradle`、wrapper、`tools/`、`docs/`、`README`、`LICENSE` |

### 1.2 严重级别定义

- **P0**：崩溃 / 功能完全不生效 / 安全或数据问题 / 物理结果数量级错误
- **P1**：物理结果明显错误或方向性错误；明显性能尖峰；多人环境错误；发布合规问题
- **P2**：模型粗糙、不连续、语义不一致，影响观感或可扩展性
- **P3**：代码质量、轻微浪费、文档不符

### 1.3 现存问题统计（按标题逐条计数，与 §14 矩阵自洽）

| 级别 | 现存条目 | 其中 0.1.4 新编号 |
|------|----------|-------------------|
| P0 | **3** | 1（`SYS-12`） |
| P1 | **37** | 2（`DRV-08`、`SAIL-09`） |
| P2 | **55** | 6 |
| P3 | **27** | 9 |
| **合计** | **122** | **18** |

**计数口径**：只统计仍以 `###/####` 标题登记为"现存/部分修"的条目；已关闭的 14 条以单独标题列出（`CLI-01/02/03/04/05/06/08/11`、`BLK-01/02/05/06`、`CMD-01`、`ENG-02`），不重复计入。全库标题总数 `122 + 14 = 136`。

**与 0.1.1 台账（`Hark/01`）的关系**：

| 口径 | 数量 | 说明 |
|------|------|------|
| `Hark/01` 登记的项目 | **126** | 含组合编号 `FR/SQ-01`（下表记作 `SQ-01`） |
| 其中已关闭 | **24** | 见 §14.1 |
| 其中部分修复 | **5** | `FR-02`、`CLI-07`、`CLI-09`、`CMD-02`、`ENG-01` |
| 其中仍未修 | **97** | 见 §14.3 |
| 现存旧问题合计 | **102** | 5 + 97 = 102 ✓（另有 24 条已关闭，合计 126） |

这 102 项旧问题（加上旧报告 §8.8 里未编号的季风描述）在本次报告里被重新组织成 **107 个标题**：跨系统的横向问题被拆开（`SYS-03` 拆出 4 条、`SYS-05`/`SYS-06`/`SYS-10`/`SC-01` 各拆出 1–2 条，净 +8），同一根因的重复登记被合并（`BLK-03`+`L2-04` → `BLK-07`、`SC-04` → `WX-SC-01`、`RT-06` → `API-11`、`SB-04` → `WX-SB-03`、`NET-04` → `RT-04`、`SB-01` → `SYS-06`、`TH-04` → `SYS-08`/`SYS-06`，净 −4）。**107 + 15（本版新发现）= 122**。因此不能用简单加减配平；§14 以逐编号表格给出状态，读者可按编号自查。

**18 个"本版首次出现"的编号**（≠ 18 个新缺陷：其中 **3 个**是旧缺陷的重新编号/归并，另有 `WX-MS-01` 是旧报告未编号的散文条目首次给号、不在下表 18 之列）：

| 级别 | 数量 | 编号 |
|------|------|------|
| P0 | 1 | `SYS-12` |
| P1 | 2 | `DRV-08`、`SAIL-09` |
| P2 | 6 | `SAIL-10`、`L0-09`、`NET-07`、`BLK-07`、`WX-FRONT-01`、`WX-SC-01` |
| P3 | 9 | `SYS-13`、`API-11`、`BLK-08`、`WX-FOG-01`、`CLI-13`、`CLI-14`、`CLI-15`、`CLI-16`、`ENG-08` |

### 1.4 判断基准（与旧报告一致，重新声明）

- Minecraft：**+X 东、+Z 南、+Y 上**；俯视北在上时，从 +X 转向 +Z 是**顺时针**。
- `dayTime % 24000`：0 = 06:00 日出、6000 = 正午、12000 = 日落、18000 = 午夜。
- 实体 yaw：前向 `(-sin yaw, cos yaw)`，yaw = 0 朝南。
- 北半球低压：俯视**逆时针**；地转风"背风而立，低压在左"。
- `ChunkAccess.getHeight(Heightmap.Types.WORLD_SURFACE, x, z)` 返回该列**最高非空气方块之上**的第一格，即空气格；需要方块 y 时必须 `-1`。`FogController` 与 `ParticleWindController` 在"粒子出生高度"场景下直接用 `h` 是**正确**的（出生在空气里），`McTerrainAdapter` 在"地形高度/方块判定"场景下直接用 `h` 是**错误**的。

---

## 2. 横切系统问题（优先修）

### SYS-12 【P0·新】地形适配器读取空气格：海陆判定与地形高度整体失真

**位置**
- `ZenithServerRuntime.java:653-660` `terrainHeightBlocks` → `c.getHeight(WORLD_SURFACE, ...)`
- `ZenithServerRuntime.java:712-722` `isOceanAt` → `new BlockPos(x, h, z)` 后 `getBlockState`
- `ZenithServerRuntime.java:725-743` `surfaceAt` → 同上

**分析**：`WORLD_SURFACE` 返回的是"地表之上第一格空气"。对海面（水方块 y=62、水面高 62.875）返回 63；对草地（方块 y=63）返回 64。于是：

| 查询 | 期望 | 实际（已加载区块） |
|------|------|--------------------|
| `isOceanAt(ocean)` | `true` | `getBlockState(y=63)` = 空气 → **false** |
| `surfaceAt(desert)` | `DESERT_SAND` | 空气 → 落到末尾 → **GRASS** |
| `terrainHeightBlocks(plain)` | 64（草方块顶 = 64） | **65**（多 1 格） |
| `terrainHeightBlocks(ocean)` | 62.875 | **63** |

未加载区块走 `HashedSeedTerrainProvider`（`:659`、`:721`、`:727`），该路径按 fbm 高度判断，反而"正常"。**同一世界内因此出现两套互相矛盾的地形语义，且分界线随玩家移动而移动。**

**影响链（逐条可追）**

1. **海风**：`SeaBreezeSystem.findCoast` 用 `isOceanAt` 找海陆分界（`:88,95`）。已加载区全判"陆地" → 找不到海岸 → `bestDist = MAX_VALUE` → 返回 `{1e9,0,0}`（`:106`）→ 海风贡献≈0。玩家在海岸边几乎感受不到海风；而未加载的远处却能算出来。
2. **台风**：`TyphoonSystem.tick` 用 `!isOceanAt` 判登陆（`:100`）。台风一旦漂进玩家已加载范围就立刻按"登陆"指数衰减（时间常数 6 小时，`:102`）；在 800–1500 格未加载区生成与加强却正常。表现为"**台风总在靠近玩家时突然变弱**"。
3. **尘卷风**：`DustDevilSystem` 用 `surfaceAt` 判热地表（`:57-61`）。已加载区恒 `GRASS`（加热率 0.55），沙漠/岩石/沙滩判定失效，只剩 20% 概率硬生成。
4. **地形阻力/坡风/L1 基准**：`terrainHeightBlocks` +1 使 `hAboveGround` 少 1，L1 的 `prof` 与遮蔽阈值整体偏移；`slopeVector` 用 ±8 格差分，+1 常数在差分中抵消，但绝对基准偏移会传导到 `col.terrainHeight` 与 `hMid - terrainHeight`。
5. **服务端雾 API**：`fogDensityAt` 的 `overWater` 由 `surfaceAt == OCEAN` 决定（`:588-589`），恒 false → 平流雾分支（`FogModel.java:25-27`）在 API 侧永不触发。客户端走自己的上风水面采样（`FogController.java:92-101`），于是**玩家看得到雾、附属模组查不到雾**。

**修复**

```java
// 1) 方块高度：取空气格下方一格
int h = c.getHeight(Heightmap.Types.WORLD_SURFACE, x & 15, z & 15) - 1;
// 更稳妥：用 MOTION_BLOCKING_NO_LEAVES 并显式说明语义

// 2) 地形高度返回"地表方块顶面"还是"地表 y"，必须与 L1 的 hMid 语义统一
//    建议 terrainHeightBlocks 返回 h + 1.0（顶面），并改名为 terrainTopBlocks

// 3) surfaceAt 对水单独处理：h 格或 h-1 格任一为水即 OCEAN
BlockState at = c.getBlockState(new BlockPos(x, h, z));
BlockState below = c.getBlockState(new BlockPos(x, h - 1, z));
```

**验收**：`isOceanAt` 在真实海洋返回 true、在沙滩返回 false；`surfaceAt(沙漠)` 返回 `DESERT_SAND`；`fogDensityAt` 在海岸能返回 >0；台风靠近玩家不再无故衰减。

### SYS-13 【P3·新】生物群系固定按 block y = 64 采样

**位置**：`ZenithServerRuntime.java:668`、`:689` —— `c.getNoiseBiome(x >> 2, 64 >> 2, z >> 2)`。

**分析**：quart y 写死为 `16`（block y = 64）。1.18+ 地表范围 −64…320，山地地表 150–250、海底 30–50、下界顶 120+。用 y=64 采样会拿到"该列在 y=64 处的群系"，而不是"地表群系"：

- 高山（地表 200）：拿到山体中部的群系，往往仍是山麓的森林/草地 → 粗糙度 0.75 而不是 0.85；
- 深海（海底 40）：拿到水下 y=64 的群系，可能已是海岸/河流；
- 洞穴/峡谷：拿到上方群系，与地表类型不一致。

`roughness01` 因此对地形几乎不敏感（森林 0.75 与山地 0.85 的差别也常被误判抹平），L1 的对数律 `z0 = 0.05 + r·1.5` 与 L0 的粗糙度拖曳都建立在这个值上。

**修复**：`int surfaceQuartY = Mth.clamp((int)(terrainHeightBlocks(bx,bz)) >> 2, level.getMinBuildHeight() >> 2, level.getMaxBuildHeight() >> 2);` 再 `getNoiseBiome(x>>2, surfaceQuartY, z>>2)`；并缓存每列结果。

### SYS-01 【P1】坐标手性错误（未修）

**位置**
- `BackgroundMetGrid.java:204-213`：`ug = -dPdz*K`、`vg = +dPdx*K`
- `TyphoonSystem.java:131`：`tx = -dz*inv*spin`、`tz = +dx*inv*spin`，`spin = 1`（`:31`）
- `WorldScaleDriver.java:160-164`：`swirl` 同号结构

**分析**：MC 的 +Z 指南。数学坐标 `u = -∂p/∂y, v = +∂p/∂x` 映射到 (x,z) 时，`y → z` 需要变号，正确形式是：

```java
ug = +dPdz * K;   // 东向
vg = -dPdx * K;   // 南向
```

代码两个分量**都**取反，等价于整体旋转 180°，不是"旋向反了"而是"风往反方向吹"。同理 `(-dz, dx)` 在 MC 里是**顺时针**（东侧点 → +Z 南），是南半球气旋方向。

**影响**：北半球台风顺时针；`betaX/betaZ = (-1,-1.1)`（`:95`）却是向北偏西（北半球正确），两者自相矛盾；L0 地转风与驱动器目标风以 `blend ∈ [0.18,0.32]`（`:127-130`）混合两个相反方向的场，结果既不是气旋也不是反气旋。

**修复**：统一 `hemisphereSign`（默认 +1），所有旋转乘它；地转风改正号；气旋 `spin = lowPressure ? +1 : -1`（见 DRV-05）。

### SYS-03 【P1】高度基准混乱（未修）

| 位置 | 写法 | 问题 |
|------|------|------|
| `MesoscaleGrid.java:115` | `hMid = (l+0.5)*40` 当绝对高度 | 与 `:218` 的 AGL 读取冲突（L1-01） |
| `ThermalSystem.java:114` | `groundY = 60` 写死 | 山地热泡在地表以下"开始"，y<60 的低地永远没风 |
| `SeaBreezeSystem.java:131` | `agl = max(0, y-64)` | 高原海岸的海风衰减完全失真 |
| `DustDevilSystem.java:108-110` | `heightBlocks`（柱高 20–60）当绝对 y 用 | 地表 y=70 的沙漠里 `hDecay` 几乎归零 |
| `TyphoonSystem.java:137` | `exp(-max(0, y-200)/400)` | 绝对 y；海平面 62 处恒为 1，山顶 250 处才衰减 |
| `MonsoonSystem.java:33` | `y < 150 ? 1 : ...` | 绝对 y 门控 |
| `StormCellSystem.java:167-203`、`SquallLineSystem.java:156-201` | 完全无高度项 | 300 格高空的外流与地面一样强 |

**修复**：`WeatherContext` 增加 `groundYAt(x,z)`；所有现象用 `agl = y - groundY`；`ThermalSystem` 用 `t.baseY = terrainHeight(ax,az)`。这项必须和 SYS-12 一起做，否则 `groundY` 仍然是错的。

### SYS-05 【P1】离散化与边界（未修）

1. **L1 水平最近邻**：`MesoscaleGrid.java:214-215` 用 `Math.round`，风场每 64 格阶跃；粗风场包、玩法采样、风向标全部继承。
2. **域外夹边外推**：L0（`BackgroundMetGrid.java:287-291`）、L1（`:214-215`）、海风（`SeaBreezeSystem.java:121-124`）、客户端粗场（`ClientWindState.java:93-95`）全部 `clamp` 到边缘格，相当于把边缘值无限外推。
3. **硬截断**：台风外缘 12.11 m/s 瞬间归零（`TyphoonSystem.java:124`）、眼缘 13.09 m/s（`:125`）、飑线前沿 18 m/s（`SquallLineSystem.java:173`）、尘卷风核心边缘（`DustDevilSystem.java:111-119`）、L2 扫描半径 4（`LocalFlowSolver.java:63`）。

**修复原则**：所有场双线性/三线性插值；域外返回"无贡献"或回退上层；现象包络用 C¹ 连续的 `smoothstep` 或 `exp(-(r/R)^n)`。

### SYS-06 【P1】焦点模型（未修）

- `ZenithServerRuntime.java:250-265`：焦点取**所有玩家平均坐标**。两人相距 10 km 时焦点在中间，L1（±1024 格）对两人都不覆盖 → 两人都吃夹边值。
- `ZenithServerRuntime.java:231-233`：L1 每 64 tick **无条件**跟随焦点，网格整体平移，风场"贴在玩家身上"。
- `BackgroundMetGrid.java:72-79`：L0 焦点移动 >256 格就直接改中心，**数组不平移**，状态与地理脱钩；焦点也不对齐 256 格整数倍，驱动器采样点持续漂移。
- `SeaBreezeSystem.java:36-56`：焦点每 tick 更新，场每 100 tick 才重算 → 两次重算之间旧场按新焦点解读，海风跟着玩家滑动；构建用 `GRID/2`（16），采样用 `GRID/2.0`（16.5），**系统性偏 32 格**（`：55-56` vs `:121-122`）。

**修复**：改为世界锚定瓦片（L1 每瓦片 16×16 列，L0 按 256 对齐），以玩家为中心激活、LRU 回收；`SamplePolicy` 按距离选取活跃瓦片。

### SYS-08 【P2】无持久化、随机源不可复现（未修）

- 全文无 `SavedData`（grep 确认）；台风/锋面/风暴/驱动器相位重启即重置。
- 现象系统全用 `new Random()`：`FrontSystem.java:28` 甚至在**字段初始化**里用 `Math.random()`；`ThermalSystem.java:56`、`TyphoonSystem.java:46`、`DustDevilSystem.java:42`、`StormCellSystem` 同。
- `new Random(seed)` 只在 `WorldScaleDriver` 与 `MesoscaleGrid` 中使用，且每次启动同种子 → 同一批气旋从同一位置出发，无法复现"第 N 天"的状态。

**修复**：`SavedData` 每维度序列化驱动器 + 现象列表；随机源统一 `RandomSource.create(seed ^ dim.location().hashCode() ^ salt)`；锋面寿命改由构造参数注入。

### SYS-09 【P1】缺少维度门控（未修）

`ZenithServerRuntime.java:191-196` 对 `server.getAllLevels()` 全部 tick；`DimensionState.phenomena()`（`:151-154`）包含全部现象。下界/末地会生成热泡、风暴、真闪电、台风、尘卷风。下界 `WORLD_SURFACE` 是顶层基岩，`surfaceAt` 会把基岩判成 `ROCK`（`BlockTags.BASE_STONE_OVERWORLD` 不含基岩，实际落到 `GRASS`），雾与热对流全部无意义。

**修复**：默认门控 `level.dimensionType().hasSkyLight() && !level.dimensionType().hasCeiling()`；提供维度白名单配置。

### SYS-10 【P1】服务端同步区块访问（未修）

| 位置 | 调用 | 风险 |
|------|------|------|
| `ZenithServerRuntime.java:431-449` | 焦点周围 `49×49×21 ≈ 50,421` 次 `getBlockState`，每 200 tick | 焦点在两人中间/未加载区时同步加载；每维度周期性尖峰 |
| `StormCellSystem.java:122-129` | `getHeightmapPos` + `addFreshEntity` | 离玩家 600–1200 格，每 4–18 秒 |
| `SquallLineSystem.java:142-149` | 同上 | 每 2–8 秒 |
| `ZenithServerRuntime.java:163-168` | L2 障碍探针 `level.getBlockState`，任意点采样都会触发 | 其他模组调 API 即加载区块 |

**修复**：先 `level.hasChunkAt(pos)` / `getChunkSource().getChunkNow`；未加载则跳过或用种子地形；闪电只在已加载区块落地，否则给客户端视觉闪光；热源改事件驱动（方块放置/破坏/燃烧状态变化时增量更新）。

### SYS-11 【P2】线程安全未定义（未修）

- `stateFor` 在采样路径里 `computeIfAbsent`（`ZenithServerRuntime.java:159-172`），其他模组从工作线程调用会并发改 `HashMap`。
- `ZenithMinecraftWindApi.sample(Level, pos, policy)`（`:33-43`）接受客户端 `Level`，构造 `ZenithWorldRef.client(...)` 后仍走服务端运行时；单人游戏渲染线程会读集成服务器的 `ServerLevel`。
- `ZenithWindSample` 的 `confidence` 恒 0.9（`ZenithServerRuntime.java:344`），不反映数据新鲜度。

**修复**：入口 `assert server.isSameThread()`；每 tick 发布不可变 `WindFieldSnapshot` 供跨线程读；`confidence` 按 `tick - freshnessEpoch` 计算。

---

## 3. WorldScaleDriver（行星尺度）

### DRV-08 【P1·新】低压径向分量符号反：低压向外辐散

**位置**：`WorldScaleDriver.java:161-164`

```java
double radial = 3.0 * c.intensity * (0.70*gOuter + 1.20*gCore) * c.inflowSign;
u += -dz*inv*swirl + dx*inv*radial;
w +=  dx*inv*swirl + dz*inv*radial;
```

`inflowSign = lowPressure ? +1 : -1`（`:248`），而 `(dx,dz)` 是**从气旋中心指向采样点**的外向单位向量。`radial > 0` 时相加 = 向外 → **低压辐散**，高压辐合。与注释 `低压辐合(+1)/高压辐散(-1)`（`:236`）完全相反。

**修复**：`double radial = -3.0 * ... * c.inflowSign;`（或把注释改为"外流为正"并让低压取 −1）。验收：低压中心东侧点的径向分量应指向 −X。

### DRV-01 【P1】驱动网格未周期化（未修）

`WorldScaleDriver.java:138-154` 用绝对格号 `floor(blockX/256)`，但气旋生成在 `[0, 384)` 并在 `advance` 里回卷（`:259-262`）。`sample` 不做取模、不用最小镜像距离：

- 气旋只覆盖世界坐标 `[0, 98304)` 象限；出生点附近 `x<0` 或 `z<0` 的区域只吃高斯尾巴；
- 气旋跨回卷边界时瞬移 9.8 万格。

**修复**：`dx = wrapDelta(cellX - c.x, GRID_CELLS)`，`wrapDelta` 返回 `[-GRID/2, GRID/2)`。

### DRV-02 【P1】对流团每约 100 秒瞬移（未修）

`WorldScaleDriver.java:287-294`：`host = cyclones.get((int)(Math.abs(orbitAngle) % cyclones.size()))`。`orbitAngle` 以 0.01 rad/s 增长，每约 628 秒（不是 100 秒，旧报告此处笔误；按 `2π/0.01 ≈ 628 s`）宿主索引 +1，位置在不同气旋间跳跃数万格。

**修复**：`ConvectiveCluster` 生成时记录 `hostIndex`，`advance` 只读该索引。

### DRV-03 【P1】龙卷被 L0 网格混叠（未修）

`WorldScaleDriver.java:193-206`：龙卷核心 10–24 格、影响半径 48–128 格，却在 L0 的 256 格网格中心采样。`if (r > t.influenceRadius) continue;` → 只有中心恰好落在 ±128 格内才有值；即便命中，`exp(-(r/rc)²)` 在 r≈100 时≈0。

**且** `tangential = t.maxWind * coreG`（`:200`）没有乘 `t.intensityEnvelope()`（`:336`），寿命包络只发给客户端渲染，物理侧不衰减。

**修复**：龙卷改为逐点 `WeatherPhenomenon`（Burgers–Rott：`v(r)=Γ/(2πr)(1-e^{-r²/rc²})`），L0 只接收其大尺度气压/加热；`tangential *= t.intensityEnvelope()`。

### DRV-05 【P2】气旋旋向与气压独立随机（未修）

`WorldScaleDriver.java:246-248`：`spin = random.nextBoolean() ? 1 : -1`、`lowPressure = random.nextDouble() < 0.6` 相互独立 → 一半低压是反气旋式。应与 `lowPressure` 绑定并乘 `coriolisSign`。

### DRV-06 【P2】分量独立钳制（未修）

`WorldScaleDriver.java:208-209`：`u`、`w` 各自钳到 ±12 m/s，对角方向最大 17 m/s 且会扭转风向。应按模长钳制：

```java
double spd = Math.hypot(u, w);
if (spd > MAX) { u *= MAX/spd; w *= MAX/spd; }
```

### DRV-07 【P3】随机游走的时间尺度（未修）

`WorldScaleDriver.java:116`：`synopticLull += (rand-0.5)*dt*0.02`，方差 ∝ dt²，改变 tick 步长会改变随机过程统计。应为 `σ·sqrt(dt)·N(0,1)`（或 OU 过程）。

## 4. L0 BackgroundMetGrid（天气尺度）

> 网格：33×33 格、每格 256 格（±4,096 格），每 256 tick 刷新一次（`dt = 12.8 s`）。刷新链路 `ZenithServerRuntime.java:225-229`。

### L0-01 【P1】地转风手性错误（未修，归 SYS-01）

**位置**：`BackgroundMetGrid.java:204-213`（`ug = -dPdz*K`、`vg = +dPdx*K`），消费点 `:127-130`。

**分析**：MC 中 `+X` 东、`+Z` 南，把数学坐标 `(x,y) → (x,z)` 时 `∂/∂y → ∂/∂z` 且方向反转。正确形式是 `ug = +dPdz*K`、`vg = -dPdx*K`；代码两分量同时取反，等于把地转风整体旋转 180°，不是"旋向错"而是"吹反"。

**后果**：L0 目标风 `lerp(drv.target, geo, blend ∈ [0.18,0.32])`（`:127-130`）把方向相反的驱动器风与地转风混合，低压周围既不成气旋也不成反气旋；再叠加 `DRV-08` 的径向符号错误，行星尺度风场基本无物理意义。

**修复**：改符号并新增单元测试：给定 `∂p/∂x>0`（东侧高压），北半球 `vg<0`（向北）。见 §3 `SYS-01`。

### L0-02 【P1】焦点移动不平移状态（未修，归 SYS-06）

**位置**：`BackgroundMetGrid.java:72-79`（`setFocus` 只改中心）、`:81-82`（`cellBlockX/Z` 由中心实时推算）、`:88-91`（`copyInto(cells, prev)`）。

**分析**：焦点移动超过 256 格后直接改 `focusBlockX/Z`，但 `cells` 数组内容不平移。数组下标到地理坐标的映射整体跳变，原属 A 地的风场被解释成 B 地。L1、粗风场包、客户端全部继承这一错位。焦点也不对齐 256 格整数倍，驱动器采样点 `floor(cellBlockX/256)`（`:95-96`）持续漂移。

**后果**：玩家跑图时背景风"跟着人走"而不是"地方的风"；L0 的日变化/地形拖曳积累在错误的格子上。

**修复**：焦点对齐 `floor(x/256)*256`；中心移动时按整数格 `arraycopy` 平移，新暴露的格子用 `driver.sample()` 冷启动，旧格保留（或整体迁移到世界锚定瓦片 + LRU）。

### L0-03 【P2】刷新步长写死，扩散/气压混合与 `dt` 无关（未修）

**位置**：`BackgroundMetGrid.java:24-25`（`WIND_DIFFUSE_BLEND=0.16`、`PRESSURE_DIFFUSE_BLEND=0.10`）、`:115-120`、`:174-179`（`solarHeating*0.01 - clearCooling*0.01`）。

**分析**：风/气压的邻域混合是"每次调用固定比例"，等效扩散系数 `D ∝ blend·Δx²/Δt`。当前 `Δt=12.8 s`、`Δx=256`，`D≈819 m²/s`；若把刷新改成 64 tick（`Δt=3.2 s`），`D` 变成 4 倍，平滑度与振幅全变。温度项更直接：`±0.01 K` 是每次刷新的固定增量，稳态偏差 `≈(0.06-0.02)/(dt/1200)`，`dt=12.8 s` 时约 **+3.75 K**（与旧报告一致），改成 64 tick 后变 +15 K。

**后果**：调刷新频率会改气候；温度日变化幅度不可预测；这也是 `L0` 无法做单元测试的原因之一。

**修复**：统一为速率形式 `1-exp(-dt/τ)`（风 τ≈60 s、气压 τ≈180 s），温度收支改为 `dT/dt = (Q_solar - Q_longwave)/C` 的显式积分。

### L0-04 【P2】地形形阻顺序依赖（未修）

**位置**：`BackgroundMetGrid.java:137-140`。

```java
targetX -= gx/gradMag * drag * Math.hypot(targetX, targetZ);  // 已改 targetX
targetZ -= gz/gradMag * drag * Math.hypot(targetX, targetZ);  // 用改后的 targetX
```

**分析**：`drag` 由 `uphill` 决定，而 `uphill` 又由 `targetX/Z` 决定；随后 Z 分量的缩放因子用的是**已被修改的** `targetX`。结果与 `(gx,gz)` 的符号相关，不是各向同性衰减：`gx>0` 时衰减偏强，`gx<0` 时偏弱。

**后果**：迎风坡/背风坡的风速削减不对称且不可复现；与 L1 的坡风叠加后出现"同一山脊两侧差 20%"。

**修复**：`double mag = Math.hypot(targetX, targetZ);` 先缓存，再分别乘；并改用 `smoothstep` 而非硬 `clamp`。

### L0-05 【P2】群系温度映射与递减率（未修，且受 SYS-13 污染）

**位置**：`BackgroundMetGrid.java:170-172`、`:288-291`（`ALTITUDE_LAPSE_RATE_K_PER_BLOCK`）、`ZenithServerRuntime.java:684-694`（`biomeTemperatureKelvin = 273.15 + t*30`）。

**分析**：`273.15 + t*30` 在沙漠（`t=2.0`）给 **333.15 K = 60 °C**，在雪原（`t=-0.5`）给 258.15 K；`t` 是 MC 的"群系温度"，不是 °C。递减率用 `c.terrainHeight`（绝对高度，且被 SYS-12 多算 1 格）而非相对海平面，海平面附近也扣 `0.0065*64 ≈ 0.42 K`（量小但语义错）。叠加 SYS-13（y=64 取群系）后，高山拿到山麓群系温度。

**后果**：沙漠地表过热 → `ablStability` 恒负 → ABL 高度恒 460 格 → L1 近地风廓线被系统性抬高；雪原相反。

**修复**：分段映射 `t∈[-0.5,2] → 255…310 K`；递减率用 `max(0, h - seaLevel)`；群系采样改用实际地表 quart y（SYS-13）。

### L0-06 【P2】粗糙度拖曳与松弛重复计算地表摩擦（未修）

**位置**：`BackgroundMetGrid.java:148-156`。

**分析**：先向 `target` 松弛（`k = dt·FLOW_RELAXATION_PER_SECOND`），再乘 `(1-roughDrag)`，`roughDrag = clamp(dt·(0.0025+0.01·r),0,0.22)`。稳态解约为 `k/(k+drag) · target`：`r=0.5`、`dt=12.8` 时 ≈ **0.64**。L1 又用 `z0 = 0.05+1.5r` 的对数律再算一次地表摩擦（`MesoscaleGrid.java:117-121`）。

**后果**：地表风被扣两次：L0 先削 36%，L1 对数律再削到 0.55（10 格）或 0.31（1.6 格）；叠加 L1-01 的 0.2775 后，海面 10 格高只剩 `0.64×0.55×0.2775 ≈ 0.098` 的"应有风速"。

**修复**：L0 只做自由大气（地转 + 平流 + 大尺度辐合），近地摩擦全部交给 L1 的廓线；若保留 L0 拖曳，则 L1 的 `z0` 应改为"残余粗糙度"。

### L0-07 【P2】气压异常不进 API（未修）

**位置**：`BackgroundMetGrid.java:167`（写入 `c.pressureAnomalyPa`）、`:298`（`sample` 返回）、`ZenithServerRuntime.java:335-346`（API 组装）。

**分析**：`sample()` 已经把气压双线性插值出来，但运行时组装 `ZenithWindSample` 时没有 `.pressureAnomalyPa(...)`，`ZenithWindSample` 的该字段保持默认 0（见 `API-07`）。同时 `MesoscaleGrid.Sample` 的 `pressureAnomalyPa` 也恒 0（`MesoscaleGrid.java:242`）。

**后果**：第三方（气压计、飞行器、天气 UI）拿不到任何气压信息；`/zenith status` 也无法显示低压中心。

**修复**：L1 `CellColumn` 增加 `pressureAnomalyPa[]`（按层或单值），`sample` 透传；运行时写入 `ZenithWindSample`；补一条"低压中心 < -1000 Pa"的集成测试。

### L0-08 【P3】每格刷新产生临时对象（未修）

**位置**：`BackgroundMetGrid.java:229-247`（`neighborMean` 里 `new CellState()` + 9 次累加）、`:255`（`bilinear` 里 `new CellState()`）。每次刷新 1,089 格 × 2 个对象 ≈ 2,178 个临时 `CellState`，另有 `computeGeostrophicWind`/`terrainGradient` 的 `double[]`。

**后果**：256 tick 一次的 GC 小尖峰；在 20 维度服务器上放大约 20 倍（虽然 `SYS-09` 本身就该禁止）。

**修复**：用 `ThreadLocal` 或成员暂存对象；`computeGeostrophicWind` 改成写两个 `double` 字段。

### L0-09 【P2·新】对流/龙卷的热量与湿量是死数据

**位置**：写入 `BackgroundMetGrid.java:190-199`（`convHeatK/convMoist/torHeatK/torMoist`），双线性 `:302-310`，`Sample` 记录 `:49-57`。

**分析**：对全仓 grep `convHeatK|convMoist|torHeatK|torMoist`，**除 `BackgroundMetGrid` 自身外没有任何读取点**。`MesoscaleGrid.java:152-154` 只用了 `convEnv`、`convInX/Z`、`torUpdraft`、`torWindX/Z`；`WeatherContext` 也没有暴露它们。

**后果**：

1. 对流团的 `convHeat += 2.5·intensity·g`（`WorldScaleDriver.java:187`）不加热空气，`taxTempK` 与 `surfaceTempK` 完全不受对流影响 → 对流与热力场解耦；
2. 龙卷的 `torHeat += 3.0·coreG`、`torMoist += 0.25·coreG` 同样无效，客户端只能靠渲染器"看起来像"；
3. 未来若有人把 `convHeatK` 接进 L1，会突然改变全部行为，属于隐藏的未完成契约。

**修复**：要么在 `BackgroundMetGrid.refresh` 里把 `convHeatK` 加进 `targetAirT`（`+ convHeatK`）与 `humidity`（`+ convMoist`），要么删掉这些字段并在文档里标注"未实现"，避免死代码误导读代码的人。

---

## 5. L1 MesoscaleGrid（中尺度）

> 网格：33×33 列、每列 64 格；垂直 8 层、每层 40 格；每 64 tick 刷新（`ZenithServerRuntime.java:231-234`）。

### L1-01 【P0】垂直层用绝对高度构建、却按 AGL 读取（未修）

**位置**：构建 `MesoscaleGrid.java:114-133`（`hMid = (l+0.5)*40`，`hAboveGround = hMid - terrainHeight`）；读取 `:218-220`（`hAboveGround = blockY - terrainHeight`，`layer = hAGL/40`）。

**分析**：构建时第 `l` 层的**物理高度**是 `(l+0.5)*40`（20/60/100/…/300 格绝对 y），但 `hAboveGround` 只用来算廓线系数 `prof`；读取时却把 `hAGL` 当作层索引。两者只在 `terrainHeight = 0` 时一致。

平地（地表方块 y=63，`terrainHeight≈64/65`）时，第 0 层中心绝对 y=20 → `hAGL = -44` → `hAboveGround <= z0` → `prof = 0.15` → `0.15+0.85×0.15 = 0.2775`。第 0、1 层（y=20、60）都在地下，采样点 64–144 全部落在第 0→1 层的插值区间，因此：

> **地表以上 0–80 格，风速恒为 L0 的 0.2775 倍，与粗糙度、稳定度、坡度完全无关。**

`roughness=0.3`（`z0=0.5`）、`ABL=280` 时，`prof(1.6)=0.3062`、`prof(10)=0.5524`、`prof(50)=0.7686`，本应是 0.42/0.62/0.77（经 `0.15+0.85·prof`），实际全部被压成 0.2775。再叠加 `:231-234` 的 `f·0.6`（`hAGL<2` 时 `f=(hAGL+4)/6`，眼高 1.6 时 `f=0.933`），**眼高总系数 ≈ 0.1715**。

**后果**：`/zenith wind` 报 1–2 m/s 是"系统正常"，而 L0 实际有 6–12 m/s；帆船（`SAIL-07`）、粒子（`CLI-01`）、风声（`NET-01`）全部建立在这个 17% 上。山地（地表 150–250）前 4–6 层在地下，坡风/对流强迫加在"地下层"上，垂直结构断裂。

**修复**（0.2 的第一优先级）：层定义改为 **AGL**：

```java
// 建议拉伸网格（离地）：2, 6, 14, 30, 60, 110, 190, 300
double hAglMid = LAYER_AGL[l];
double prof = log((hAglMid + z0)/z0) / log((ablHeight + z0)/z0);
```

`sample` 改为三线性（水平双线性 + 垂直线性），层顶以上返回自由大气（L0）；`hAGL<0` 返回 0 并标 `sheltered`。

**验收**：平地 10 格高、`r=0.3` 时风速 ∈ [0.5, 0.65]×L0；1.6 格高 ∈ [0.35, 0.45]×L0；山地 200 格处近地风不为 0。

### L1-02 【P1】水平最近邻（未修，归 SYS-05）

**位置**：`MesoscaleGrid.java:212-215`（`Math.round` + `clamp`）。

**分析**：整张 L1 风场是 64 格一块的分段常数。粗风场包、`ZenithWindApi`、风向标、涡轮探针、风道、帆船全部继承。玩家以 5 m/s 走过一个格边界时，风速/风向瞬间阶跃；风向标 `0.1` 的插值只是把阶跃抹成 10 tick 的滑动。

**修复**：水平双线性（`i0=floor(fi)`、`fx=fi-i0`），并对域外做"回退 L0 + 权重衰减"而不是 `clamp`。

### L1-03 【P2】"切变"其实是法向应变（未修）

**位置**：`MesoscaleGrid.java:190-196`（`du = ∂u/∂x`、`dw = ∂w/∂z`，`shearPerBlock = hypot(du,dw)`），消费点 `WorldScaleDriver.java:94`（`meanLowLevelShear()/0.02` 提高风暴活动度）、`:127-128`（龙卷支持度）。

**分析**：`∂u/∂x` 与 `∂w/∂z` 是**正应变率**（伸缩/辐散），不是切变。真正的切变是 `∂u/∂z`、`∂w/∂x`（垂直风切变）或 `∂u/∂z - ∂w/∂x`（涡度）。当前把"风场拉伸"当成"切变"，而龙卷/超级单体依赖的是 0–1 km、0–6 km 的**矢量差**与螺旋度。

**后果**：`maxPositiveMoistureConvergence` 与 `meanLowLevelShear` 都是错量，风暴活动度与龙卷生成条件在物理上无意义；`ZenithWindSample.windShearPerBlock` 对外也是错的。

**修复**：按层输出 `bulkShear = |V(top)-V(0)| / Δz`、`ζ = ∂w/∂x - ∂u/∂z`、`δ = ∂u/∂x + ∂w/∂z`；龙卷支持度用 `STP ≈ MLCAPE × bulkShear` 的极简代理。

### L1-04 【P2】Ekman 螺旋把 L0 当地面风（未修）

**位置**：`MesoscaleGrid.java:123-128`（`dir = dir0 + ekmanTurn`，`ekmanTurn` 从 0 增到 0.52×0.9 = 0.468 rad）。

**分析**：物理上 L0（自由大气/地转风）应作为 ABL 顶的参考，地面风相对它**逆转** 20–30°（北半球向低压偏），随高度回到地转方向。代码把 L0 当**地面风**，随高度**顺转** 0→27°，层顶以上保持 27° 偏差。

**后果**：自由大气风系统性偏离 L0 约 27°，与 L0 的地转风、驱动器的目标风三方不一致；`SYS-01` 修正后这个 27° 仍然存在。

**修复**：`dir(l) = dir0 - ekmanTurn(l)`（符号随半球），`ekmanTurn` 在 `h=0` 最大、`h=ABL` 归零；并用 `u*` 摩擦速度而不是 `prof` 的 `0.15` 下限。

### L1-05 【P2】阵风模型的时间/空间结构不成立（未修）

**位置**：`MesoscaleGrid.java:156-163`。

**分析**：三个独立问题：

1. `gustY` 的种子是 `seed + tick/20`，而列每 64 tick 才刷新 → 每次刷新垂直阵风完全重采样，与注释"别用纯随机（会闪）"矛盾；
2. `gustX/Z` 只沿平均风方向（`cos(dir0)/sin(dir0)`），没有横向分量；
3. 阵风值每 64 tick 阶跃，没有时间相关结构；`gustAmp` 用的是**上一次**刷新的 `turbulenceIntensity`（`:159` 读 `col.turbulenceIntensity`，`:166` 才更新）。

**后果**：粒子/风声在 3.2 s 周期上出现"阵风跳变"；风向标在阵风上抖动。

**修复**：改用 OU 过程或"冻结湍流"：`gust = noise3(x/L, z/L, t·U/L)`（Taylor 假设），`L≈200 m`；`gustAmp` 用当前 `turbulenceIntensity`；横向分量按 `perpendicular(dir0)` 加 0.3 权重。

### L1-06 【P2】强迫只加在第 0 层（未修，兼 API 气压恒 0）

**位置**：`MesoscaleGrid.java:135-154`（坡风、坡面抬升、`convEnv`、`torUpdraft`、`convInX/Z`、`torWindX/Z` 全部只写 `[0]`）；`:130-132`（温度/湿度按 `hMid` 递减，与层 AGL 无关）；`:238-242`（`Sample` 的 `pressureAnomalyPa` 恒 0）。

**分析**：地形与对流强迫只作用于最低层，第 1–7 层仍是"平坦地面的对数廓线"。真实边界层里坡风与对流加热会改变整个 0–1 km 的廓线；现在 `windY[1..7]` 恒 0（`:129`），只有 `[0]` 有垂直速度，采样在层间线性插值时垂直速度在 40 格处突然归零。

**后果**：山谷风只在贴地层存在；热泡/龙卷的上升气流在 40 格以上消失；`L2` 采样拿到的 `baseY` 因此几乎总是 0，`LocalFlowSolver` 的射流/尾流没有垂直背景。

**修复**：坡风与对流强迫按 `exp(-hAgl/h_scale)` 分配到各层；`windY` 由连续方程 `∂w/∂z = -∇·V_h` 积分得到，而不是硬写 0；`pressureAnomalyPa` 从 L0 透传。

---

## 6. L2 LocalFlowSolver（局地）

> 每次采样现场扫描 9³ = 729 格，叠加障碍排挤、尾流、热源、射流；无缓存、无异步。

### L2-01 【P0】地面被当成障碍物：平地伪上升（未修）

**位置**：`LocalFlowSolver.java:63-95`（`r=4` 的 9³ 扫描，`if (!obstacles.isSolid(...)) continue;` 不区分"脚下地面"与"突出障碍"；排挤 `push` 与尾流亏损都不归一化）。

**离线复现**（平地，地面方块 `y≤63`，采样 `(0.5, 64+h, 0.5)`，基流 5 m/s 向东，`baseY=0`）：

| 离地 h | modX (m/s) | modY (m/s) | 有效水平风 | sheltered |
|---|---|---|---|---|
| 0.5 | −14.3 | **+17.8** | −9.3（反向） | true |
| 1.0 | −8.5 | +13.1 | −3.5（反向） | true |
| 1.6（眼高） | −3.7 | **+11.2** | +1.3 | false |
| 2.5 | −0.7 | +6.7 | +4.3 | false |
| 4.0 | 0 | 0 | +5.0 | false |

基流 10 m/s 时 h=1.6 的 `modY` 达 **+22.5**；基流 2.775（≈L1 平地实际值）时 `modY ≈ +6.2`——**伪上升与基流同阶**。

**后果**：
- `/zenith wind` 的 `effectiveVelocityVector` 含 11 m/s 垂直分量，玩家看到"平地上升气流"；
- 涡轮探针取 `effectiveSpeedMetersPerSecond()`（`WindTurbineProbeBlockEntity.java:27`）→ 长期满功率 15；
- 风道取 `meanVelocity`（`DuctBlockEntity.java:36`）→ 伪上升被当成"输入风速"再放大 1.15 倍；
- 帆船每 tick 采样（`SailboatEntity.java:92`）→ 每 tick 多算 729 次方块查询，且吃伪垂直风；
- 4 格以上突然归零 → 粒子/实体在 4 格高度"掉出"局部流场。

**修复**（短期即可做完）：
1. 先求局地地面 `g = min over 5×5 of MOTION_BLOCKING_NO_LEAVES`，忽略 `y < g` 的方块；
2. 排挤与亏损分别归一化并限幅（`|Σpush| ≤ 0.5·baseSpeed`、`|Σwake| ≤ 0.9·baseSpeed`）；
3. 扫描边缘加 `smoothstep` 窗，避免 9³ 边界硬切；
4. 结果按 `(blockPos, epoch)` 缓存，同一 L1 刷新周期内复用。

**验收**：空旷平地 `modY ≡ 0`（|modY|<0.05）；1 格高台阶后方出现尾流；墙后无射流。

### L2-02 【P1】采样成本与调用频率（未修）

**位置**：`LocalFlowSolver.java:54-132`（每次 729 次 `isSolid` + 热源/射流线性扫描）；调用方 `SailboatEntity.java:92`（每 tick）、`WindTurbineProbeBlockEntity.java:22`（每 10 tick）、`DuctBlockEntity.java:27`（每 20 tick）、`ZenithServerRuntime.java:329`（每次 API 采样）。

**分析**：单次约 729 次 `getBlockState`（`ZenithServerRuntime.java:163-168`）。100 个探针 ≈ 7,290 次/秒·tick；帆船每 tick 一次 ≈ 729 次/tick/船。`SYS-10` 还使未加载区块被同步加载。

**后果**：多人/多船/多探针场景 TPS 下降；采样点跨区块边界时触发加载尖峰。

**修复**：L2 结果缓存 + 活跃区块体素网格（异步构建）；`sampleGameplay` 增加 `quality` 参数，涡轮探针/风道用 `COARSE`（只 L1），只有玩家交互才 `FULL`。

### L2-03 【P2】热源缓存漏检与范围（未修）

**位置**：`ZenithServerRuntime.java:431-449`。

**分析**：`dx/dz/dy` 步长均为 2，只访问偶数坐标 → **漏检 7/8 的方块**；`cy = seaLevel`、`dy ∈ [-16,24]` → 只覆盖 y=47…87，山地/洞穴/下界全部漏；半径固定 48 格且随焦点移动；`size() > 256` 提前返回，热点密集区直接截断。

**后果**：篝火/熔炉/岩浆的热泡在多数位置不产生；`HEAT_SHIMMER` 粒子（客户端独立扫描）与服务端 L2 热源不一致。

**修复**：改为事件驱动（方块放置/破坏/燃烧状态变化时增量注册），按区块索引；或至少步长 1、按 `level.getMinBuildHeight()..getMaxBuildHeight()` 分带扫描，并缓存结果。

### L2-04 【P2】射流不受遮挡（未修，接力问题转 BLK-07）

**位置**：`LocalFlowSolver.java:117-130`（射流直接加到 `modX/Y/Z`，不检查路径上的方块）。

**分析**：风扇对着墙吹，墙后仍有风；射流强度 `min(14, speed*1.15)`（`DuctBlockEntity.java:46`）只与输入风速有关，与距离/遮挡无关。风道读取 `p.above()` 的**含射流**风场（`:27-36`），形成正反馈（`BLK-07`）。

**后果**：室内风扇"穿透"墙体；风道串联可无限放大到 14 m/s 上限；玩家无法用方块设计风道。

**修复**：射流沿 `facing` 做 DDA 射线，遇固体即截断并记录衰减；风道只读 `L1 + 上游射流`，不读自身/下游（见 `BLK-07`）。

### L2-05 【P3】置信度、未使用模块、越界判断（未修）

**位置**：`LocalFlowSolver.java:132`（`confidence` 恒 1.0）、`NestedBoundaryCoupler.java:15-25`（全仓无调用）、`ZenithServerRuntime.java:165`（`y > level.getMaxBuildHeight()` 应为 `>=`）。

**分析**：`confidence` 不反映"扫描是否被截断/是否在未加载区"；`NestedBoundaryCoupler.spongeBlend/blend` 是完整实现但从未接入（L1/L2 边界仍硬切）；`getMaxBuildHeight()` 是**开区间上界**，取 `y = maxBuildHeight` 会抛 `IllegalStateException`（越界），只是被 try/catch 掩盖。

**修复**：`confidence = f(扫描覆盖率, 区块加载状态, 采样质量)`；把 `NestedBoundaryCoupler` 接进 L2→L1 的边界混合，或删除；修正为 `y >= maxBuildHeight`。

---

## 7. 天气现象（`weather/`）

### 7.0 现象层的三条共同约定（先读这一节）

所有天气都通过 `WeatherPhenomenon.sample()` 返回一个 `WindContribution(vx, vy, vz, turbulenceAdd, precipIntensity01)`，由 `ZenithServerRuntime.java:310-319` **线性相加**。因此有三条横切规则，后面各条都受它影响：

| 规则 | 位置 | 后果 |
|------|------|------|
| 风分量 **直接相加**，不做质量/动量守恒，也不按现象覆盖面积归一 | `WindContribution.java:16-21`、`ZenithServerRuntime.java:314-316` | 台风 + 锋面 + 飑线 + 山地波重叠处风速可以叠到 60 m/s 以上；单个现象"看起来对"，叠加后无意义 |
| `vy` 与高度只有各系统自己写的经验衰减（`exp(-agl/350)`、`exp(-(y-200)/400)` 等），互不一致 | `MountainWaveSystem.java:52`、`TyphoonSystem.java:137`、`SeaBreezeSystem.java:132` | 同一高度上，不同现象的有效厚度差 3–5 倍 |
| `turbulenceAdd` 相加后 `min(1)`，`precipIntensity01` 相加后 `min(1)` | `WindContribution.java:19-20` | 粒子/雾/声音看到的是"最湿的那一个"，而不是总量；与降雨强度脱钩 |

这三条不属于某一个系统，但对"为什么天气看起来不像天气"的贡献大于单个参数错误，所以在 §7 开头单列。修复方向是引入 `PhenomenonStack`：风按**矢量在重叠区做权重归一**，`precip` 取**面积加权和**而不是 `max`，`vy` 统一走 AGL。

---

### 7.1 ThermalSystem（热泡）

> `ThermalSystem.java`，153 行。上限 24 个，生成半径 320 格，寿命 300–900 s。

#### WX-TH-01 【P1】`groundY = 60` 硬编码（旧 `TH-02` / `SYS-03`，未修）

**位置**：`ThermalSystem.java:114`（`double groundY = 60; // 低地假设 60 格高，暂不查真实地形`），消费点 `:116`（`if (y < groundY || y > topY) continue;`）。

**分析**：`topY` 是从 `ctx.terrain().heightAt(ax, az)`（种子哈希地形）算出的**绝对高度**，`groundY` 却是常量 60。两者不在同一基准上，于是：

- 平原（地表 63–70）：热泡从 y=60 就开始，比真实地面**低 3–10 格**，柱体底部埋在地里；
- 高山（地表 150–250）：y=60 到地表之间**整段无效**，热泡"从地下 100 格开始"；
- 海洋（地表 62）：几乎正确，纯属巧合。

**后果**：`/zenith thermal` 在高山/高原上"生成了但没有上升气流"；热泡的 `topY` 与 `groundY` 之间的柱高被系统性拉伸或压缩，`updraftMps` 的物理含义（柱内平均上升速度）不成立。

**修复**：`Thermal` 增加 `groundY` 字段，生成时用 `ctx.terrain().heightAt(ax, az)` 一次算好；`topY` 改为 `groundY + 120…240`；`sample` 用 `agl = y - t.groundY`，`agl < 0 || agl > (topY-groundY)` 时跳过。

#### WX-TH-02 【P2】下沉补偿固定 −0.5 m/s，与上升通量不守恒（旧 `TH-03`，未修）

**位置**：`ThermalSystem.java:125-130`。

```java
double ring = (r - t.radiusBlocks) / (t.radiusBlocks * 1.2);
double sink = -0.5 * Math.sin(ring * Math.PI) * env;
```

**分析**：核心上升通量 `∫w dA ≈ w₀·πR²/2`（因为 `core = 1-(r/R)²`），补偿环是 `R < r < 2.2R`，面积是核心的 **3.84 倍**，峰值只有 −0.5。`w₀ = 2–6` 时上升通量是 3.14–9.4 `R²`，下沉通量最多 0.5×3.84×0.6 ≈ 1.15 `R²`——**差 3–8 倍**。这不是"补偿"，只是一个装饰性的下洗。

**后果**：热泡整体是净质量源，与 `L1` 的连续性无关；叠加 `L2-01` 的 +11 m/s 伪上升后，平地也"有热泡"。文献上这属于经典的 **Allen (2006, NASA TM-2006-213475)** 简化热泡模型要解决的问题：上升 + 环状下沉必须按通量配平，且下沉峰值在 `r ≈ 1.3R` 而不是 `1.6R`。

**修复**：`sinkPeak = w₀ · (R²/2) / (∫ring dA)`，即 `-w₀ · 0.5R² / (1.2R·2π·1.6R·0.6) ≈ -0.083·w₀`；环中心放 `r = 1.3R`；并把 `updraftMps` 随 `agl` 用 `sin(π·agl/H)` 收口到 0（现在是整柱常数）。

#### WX-TH-03 【P2】热泡漂移用的是种子地形高度，与真实地形脱节（未修）

**位置**：`ThermalSystem.java:96`（`ctx.l1().sample(t.x, ctx.terrain().heightAt(t.x, t.z) + 40, t.z)`）、`:74`（生成时 `surfaceAt` 同样走 `ctx.terrain()`）。

**分析**：`ctx.terrain()` 是 `SeedTerrainProvider`（`HashedSeedTerrainProvider`），不是 `McTerrainAdapter`，所以**永远不读真实方块**。漂移采样高度 `heightAt + 40` 在山地可能落在地表以下（若真实地形高于哈希地形），在已改造地形（挖空/填海）上完全错位。

**后果**：热泡随风漂移的轨迹与玩家看到的地形无关；`surfaceAt` 判"沙漠/岩石"时，真实沙漠被挖成水池也照样生成。这与 `SYS-12` 是同一类错误的两个实例：**一套系统两套地形语义**。

**修复**：`WeatherContext` 只暴露一个 `TerrainProbe`，由运行时注入 `McTerrainAdapter`（已加载区）+ 哈希回退（未加载区），`SeedTerrainProvider` 不再直接出现在天气系统里。

#### WX-TH-04 【P3】`topY` 的 120–240 格无物理依据（旧 `TH-05`，未修）

**位置**：`ThermalSystem.java:83`（`t.topY = heightAt + 120 + random*120`）。

**分析**：真实热泡的顶一般在混合层顶（`zi ≈ 1–3 km`，MC 尺度约 60–180 格），且与 `updraftMps`、地表加热率、`ablHeight` 都有关系。代码里 `topY` 与这三个量全部无关，只与随机数有关；`updraftMps` 与 `topY` 可以出现"强上升 + 矮柱"或"弱上升 + 高柱"的任意组合。

**修复**：`topY = groundY + clamp(updraftMps * 25, 40, 200)`，并与 `L1` 的 `ablHeight` 对齐（`min(topY, groundY + ablHeight)`）。

#### WX-TH-05 【P3】夜间热泡不衰减（未修）

**位置**：`ThermalSystem.java:62-67`（`solar = max(0, solarFactor)`，`spawnAccumulator += dt*solar*0.25`）。

**分析**：夜间 `solar = 0` → 不再生成新热泡，但**已存在的热泡不知情**：`envelope()` 只看 `ageSeconds / lifetimeSeconds`（`:47-52`），`updraftMps` 在生成时定死后不再变。于是一个日落前 30 秒生成的热泡，会在午夜之后继续以 4 m/s 上升 600 秒。

**后果**：夜间"平地起热泡"；与 §00 的"太阳时间统一"修复方向相悖（时间统一了，但强度没跟随）。

**修复**：`Thermal` 缓存生成时的 `spawnSolar`，`sample` 里乘 `max(0, solarFactor(now))/max(spawnSolar, 0.1)`；或简单地让夜间 `lifetimeSeconds` 加速消耗。

---

### 7.2 SeaBreezeSystem（海陆风）

> `SeaBreezeSystem.java`，151 行。33×33 网格、每格 64 格（±1,024 格），每 100 tick 重建。

#### WX-SB-01 【P1】焦点绑"第一个玩家"，多人时只有一个人的海风有效（旧 `SB-03`，未修）

**位置**：`SeaBreezeSystem.java:113-116`：

```java
private ServerPlayer pickAnchor(ServerLevel level) {
    var players = level.players();
    return players.isEmpty() ? null : players.get(0);
}
```

**分析**：`level.players()` 的顺序取决于服务器玩家列表的迭代顺序（登录顺序），**不是最近的玩家**。多人服务器里，只要玩家 A 先登录，海风场的中心永远在 A 脚下；玩家 B 在 1,000 格外的海岸线，采样时 `fi/fj` 落在 `[0, GRID)` 之外，被 `clamp` 到网格边缘 → 拿到的是"离 B 最近的 A 附近格子"的风。

**后果**：多人时海风"只对第一个登录的人生效"；其他人要么无风（`distToCoast ≥ 900` 的格子全 0），要么拿到错位的风。这是 `SYS-06`（单人焦点模型）在天气层的具体表现。

**修复**：海风场改为**世界锚定瓦片**（按 `chunkX >> 4` 缓存 `coastDistance/seaDir`），采样时按世界坐标查表 + 双线性；若必须保留单焦点，至少按"最近玩家"而不是"列表第 0 个"选锚点，且把 `hasField` 的判定从"任一玩家"改成"该点 1,024 格内有玩家"。

#### WX-SB-02 【P1】8 方向 × 32 格量化的海岸法向（旧 `SB-06`，未修）

**位置**：`SeaBreezeSystem.java:87-111`（8 个 45° 方向，`r = 32…480` 步长 32，`break` 在第一个海陆分界）。

**分析**：海风方向直接取"最近一次海陆跃变的方向向量"，只有 8 个可能值（45° 一档），且距离量化到 32 格。海岸线若是斜的（常见），相邻格子的最近跃变方向会在两个 45° 档之间来回跳 → 风向呈**棋盘状突变**。`bilerp`（`:138-141`）只能把网格内部抹平，抹不了网格点本身的方向量化。

**后果**：沿海岸走一圈，风向按 45° 阶跃翻转；`frontFactor` 的 `exp(-d²/60²)` 在 `frontInlandDistance` 附近是一条 60 格宽的带，但方向错 45° 时这条带的位置也错。

**修复**：不要用射线求方向，改用**距离场梯度**：对 `isOceanAt` 做一次 33×33 的有符号距离变换（8SSEDT / Jump Flooding），`∇D` 就是指向海的法向，天然连续；`distToCoast` 直接取 `D`。这样也顺手解决 `WX-SB-04` 的 13k 次查询（一次变换代替 1,089×15 次采样）。

#### WX-SB-03 【P1】`isOceanAt` 在已加载区恒 false → 海风几乎完全失效（`SYS-12` 的天气侧表现）

**位置**：`SeaBreezeSystem.java:88`、`:95`（`ctx.terrain().isOceanAt(...)`），实现在 `ZenithServerRuntime.java:712-722`（读 `surfaceAt` 的方块，`SYS-12` 已证该方块是空气）。

**分析**：`isOceanAt` 对已加载区块返回 `false`，于是 `land = !false = true` 对所有点成立。`findCoast` 的 8 条射线都从"陆地"出发找"海洋"，但每个采样点也返回"陆地"（`ocean == land` 恒成立 → 在 `r=32` 就 `break`），`bestDist = 32`，`seaDir` 取该方向。

**后果**（定量）：

- 所有海岸格子的 `distToCoast = +32`（正 = 陆地侧），`decay = exp(-32/170) ≈ 0.83`，看起来"有风"；
- 但 `seaDir` 是**8 个方向里第一个满足条件的**，即 `d=0`（+X），所以整个世界的海风**全部吹向 −X**；
- 更糟的是，`breeze = MAX_BREEZE_MPS·dayFactor − MAX_LANDBREEZE_MPS·nightFactor` 仍然非零，于是**内陆 900 格内所有人**都被一个恒定的 −X 风推着走，并不存在"海岸线"。

这是本次审计中"一个 P0 引发一整片 P1"的典型：`SYS-12` 修好后 `WX-SB-03` 自动消失，但 `WX-SB-01/02` 仍然存在。

**修复**：先修 `SYS-12`（`surfaceAt` 读 `height-1` 的方块）；`findCoast` 增加健全性断言——若 8 个方向都在 `r=32` 内 `break`，说明"整片同质"，返回 `{1e9,0,0}` 而不是 `{32, 1, 0}`。

#### WX-SB-04 【P2】每 100 tick 13,000 次地形查询，且会同步加载区块（旧 `SB-05`，未修）

**位置**：`SeaBreezeSystem.java:53-82`（1,089 格 × `findCoast` 的 8×15 = 120 次 `isOceanAt`，最坏 130,680 次；平均约 13k 次）。

**分析**：`isOceanAt` → `surfaceAt` → `McTerrainAdapter.chunkIfLoaded` → `level.getChunkSource().getChunk(cx, cz, FULL, false)`（`ZenithServerRuntime.java:646-650`）。`false` 表示不生成，但**已生成未加载**的区块仍会走 `ChunkHolder` 的获取路径；`SYS-10` 已确认这条路径在部分情况下会触发同步加载。13k 次/5 秒 ≈ 2,600 次/秒的区块查询，全部在主线程。

**后果**：沿海/群岛地形每 5 秒一次 TPS 尖峰；`SYS-10` 的"服务端区块访问"问题在天气层的最重实例就是这里。

**修复**：同 `WX-SB-02` —— 改成一次 `ChunkAccess` 批量读高度图 + 距离变换；缓存按 `(chunkX, chunkZ, version)` 失效，而不是每 100 tick 全量重算。

#### WX-SB-05 【P2】AGL 用 `y − 64`（旧 `SB-02` 的一部分 / `SYS-03`，未修）

**位置**：`SeaBreezeSystem.java:131`（`double agl = Math.max(0, y - 64);`）、`:132`（`hDecay = exp(-agl/150)`）。

**分析**：海风厚度物理上是"海陆温差驱动的局地环流"，其上限应相对**当地地面**（`MAX_HEIGHT_AGL = 150`），代码却用世界海平面 64 作基准。山地（地表 200）时 `agl = 200 - 64 = 136` → `hDecay = 0.40`，等于"山顶上还有 40% 的海风"，而物理上海风根本不会爬到山顶。

**后果**：山地沿海地区被海风污染；`MAX_HEIGHT_AGL` 的语义（AGL）与实现（ASL）不符。

**修复**：`agl = y - terrainHeight(x, z)`；`terrainHeight` 由 `TerrainProbe` 提供（先修 `SYS-12` 的 +1 偏差）。

#### WX-SB-06 【P3】文档写 400 格，代码是 900 格；射线上限 480 格（未修）

**位置**：类注释 `SeaBreezeSystem.java:13`（"只在近岸 400 格内、150 格 AGL 以下生效"）vs 代码 `:61`（`if (distToCoast < 900)`）、`:94`（`r <= 480`）。

**分析**：三处数字互不一致：注释 400、判定 900、射线 480。`distToCoast` 若为 `1e9`（找不到海岸）会被 `< 900` 挡掉；若找到（≤480），`decay = exp(-480/170) ≈ 0.06`，仍会加一点风。

**后果**：调参时无法判断"哪个数字是真的"；`docs/WEATHER_SYSTEMS.md` 与代码同样不一致。

**修复**：统一为一个常量 `MAX_INLAND_DISTANCE = 480`（与射线上限一致），注释与文档同步。

---

### 7.3 StormCellSystem（对流单体）

> `StormCellSystem.java`，227 行。上限 8 个，生成半径 90 格，寿命 3,000 s（发育 900 / 成熟 1,200 / 消散 900）。

#### WX-SC-01 【P2·新】冰雹伤害不看降雨、不看降水核心，伤害源是 `generic`

**位置**：`StormCellSystem.java:137-149`：

```java
if (c.hail01 < 0.5) return;
for (ServerPlayer p : level.players()) {
    if (dx*dx + dz*dz < c.radiusBlocks * c.radiusBlocks * 0.64) {
        var headPos = p.blockPosition().above(2);
        if (level.canSeeSky(headPos)) p.hurt(level.damageSources().generic(), 1.0f);
    }
}
```

**分析**：四个独立的判定缺失：

1. **不看 `level.isRaining()`**：单体是模组自己的对象，与 MC 天气无关。晴天、下界、末地只要有成熟单体，就砸人；
2. **不看 `precipIntensity01`**：`hail01` 只由半径决定（`:105`，`(radius-70)/50` 映射到 0.2–1.0），与 `sample()` 返回的降水强度是两套数；
3. **`canSeeSky` + `above(2)`**：站在 2 格高的屋檐下仍算"见天"，站在 3 格高的树下反而不算（`canSeeSky` 只看天空光，树叶会挡）。判定与"有没有遮挡"不一致；
4. **`generic()` 伤害源**：无 `DamageType` 标签、无死亡消息、`bypasses_armor` 不成立（走护甲减免）、不触发 `LivingHurtEvent` 的天气专属分支。1 点/2 秒，玩家只会看到"血在掉"。

**后果**：天气与伤害语义脱节；模组作者无法用 `DamageTypeTags` 做兼容（如"冰雹伤害"抗性）。

**修复**：新增 `zenith:hail` `DamageType`（`data/zenith/damage_type/hail.json`，`bypasses_armor: false`、`scaling: when_caused_by_living_non_player`）；伤害前检查 `level.isRaining() && level.canSeeSky(p.blockPosition().above())` 且 `hailAt(x,z) > 0.5`；速率改成 `0.5f / 秒` 并可配置。

#### WX-SC-02 【P1】闪电用 `getHeightmapPos` + `setVisualOnly(false)`，无维度/权限/配置门控（旧 `SC-01`，未修）

**位置**：`StormCellSystem.java:117-130`（`level.getHeightmapPos(WORLD_SURFACE, new BlockPos(bx, 0, bz))`、`bolt.setVisualOnly(false)`、`level.addFreshEntity(bolt)`），调用点 `:98-103`（成熟期每 4–18 s 一次）。

**分析**：`getHeightmapPos(WORLD_SURFACE, ...)` 在**未加载区块**上会触发同步加载（`SYS-10`）；`setVisualOnly(false)` 让闪电造成真实伤害并点燃方块。这个循环对 `level.players()` 里的每个单体都跑，且单体生成在玩家 90 格内随机位置——玩家看不到的地方也在打雷、点火、加载区块。

**后果**：`SYS-10` 的"服务端区块访问"；下界/末地的"雷击"（维度本无天气）；刷怪塔/木屋被随机点燃；`/gamerule doFireTick` 无关（这是 `LightningBolt` 的 `setVisualOnly(false)`，直接 `hurt` + 点火）。

**修复**：`if (!level.dimension().equals(Level.OVERWORLD)) return;`；`if (!level.isRaining()) return;`（或至少 `level.getGameRules().getBoolean(DO_WEATHER_CYCLE)` 且允许模组天气）；`setVisualOnly(!level.getGameRules().getBoolean(DO_FIRE_TICK) && !damageAllowed)` 或直接用 `setVisualOnly(true)` + 自行施加伤害；**区块未加载时跳过**（`chunkIfLoaded == null`）。

#### WX-SC-03 【P2】风场只有"核心下沉 + 环状外流"，无高度结构（旧 `SC-02`，未修）

**位置**：`StormCellSystem.java:181-197`。

**分析**：成熟单体的核心 `norm < 1.0` 给 `vy = -4.5·core·env`（下沉），环带 `1.0 < norm < 2.2` 给 `gust = 12·sin(π·ring)·env`（外流）。两者都是**整柱常数**——从 y=0 到 y=320 完全一样。真实的积雨云是：低层入流（辐合）、中层上升、高层砧状外流，`w` 在 0–100 格是正的、100–200 格最大、200 格以上转负。

**后果**：玩家在 200 格高空飞过单体时，仍然被"核心下沉"往下按；`vy` 与龙卷的 `torUpdraft` 叠加后出现"同一位置既上升又下沉"。

**修复**：引入 `stormVerticalProfile(agl)`：`agl<60` 入流（径向向内 `−0.3·gust`）、`60–180` 上升（`+6·env`）、`>180` 砧状外流（径向向外 `+3·env`）；核心下沉只在 `agl<40` 且 `norm<0.5` 生效。

#### WX-SC-04 【P2】不驱动原版降雨，也不写回 L0/L1（旧 `SC-03`，未修）

**位置**：`StormCellSystem.java:62-115`（`tick` 只更新自己的字段），无 `level.setRainLevel` / `setThunderLevel` 调用；`sample` 返回的 `precipIntensity01` 只被网络快照用（`ZenithServerRuntime.java:318`）。

**分析**：`level.isRaining()` 是原版天气，与模组天气**完全独立**。于是：

- 模组单体在头顶，`precipIntensity01 = 0.9`，客户端 `WeatherWindController` 因为 `!level.isRaining()` 直接 `return`（`:29`）→ **不下雨丝**；
- 原版下雨时，模组单体可能一个都没有 → 客户端有雨丝但 `stormActivity = 0`；
- `FogModel.density` 的 `raining` 参数来自 `level.isRaining()`（`ZenithServerRuntime.java:593`）→ 雾与模组降雨无关。

**后果**：这是"天气看起来不像天气"的最直接原因之一：模组的降水强度只存在于快照里，玩家看不到雨。

**修复**：`DimensionState` 每 tick 计算区域平均 `precipIntensity01` 与 `turbulence`，用 `level.setRainLevel(lerp(...))` / `setThunderLevel(...)` 平滑驱动原版天气（NeoForge 有 `WeatherData` 同步机制）；或反过来——用原版天气做"底"，模组只增强。

#### WX-SC-05 【P3】单体移动速度恒定、半径纯随机（旧 `SC-05`，未修）

**位置**：`StormCellSystem.java:79`（`radiusBlocks = 60 + random*60`）、`:86-95`（`moveX/moveZ` 生成后不变）。

**分析**：`moveX/moveZ` 在生成时从 L1 风取出（未在此段展示，见 `:73-95`），之后**永不更新**——L1 风变了单体也不跟着变。半径 60–120 与 `moveX` 无关，与环境切变无关。

**后果**：单体像"沿直线匀速平移的圆盘"，与背景风脱钩；`DRV-03` 的"龙卷被 L0 网格混叠"是同一类问题的另一实例。

**修复**：每 tick（或每 20 tick）用 `ctx.l1().sample(c.x, 150, c.z)` 更新 `moveX/moveZ`，并加 `0.1` 的时间常数平滑；半径由 `stormActivity` 与 CAPE 代理决定。

---

### 7.4 TyphoonSystem（台风）

> `TyphoonSystem.java`，213 行。上限 2 个，生成在玩家 800–1,500 格外的海面。

#### WX-TY-01 【P1】旋向在 MC 坐标下是顺时针（旧 `TY-01`/`SYS-01`，未修）

**位置**：`TyphoonSystem.java:31`（`public double spin = 1;`）、`:131`（`tx = -dz*inv*t.spin; tz = dx*inv*t.spin;`）。

**分析**：MC 中 `+X` 东、`+Z` 南，`(dx, dz)` 指向外，`(-dz, dx)` 是**顺时针**切向（把 `(1,0)` 东映射到 `(0,1)` 南，即顺时针 90°）。`spin = 1` 于是给出顺时针环流。北半球低压应为逆时针；`spin` 又不受半球/维度影响（`:31` 硬编码，生成时也不随机）。

**后果**：北半球台风"反着转"。与 `SYS-01`（地转风）、`DRV-08`（径向）三个符号错误叠加后，玩家看到的台风是"顺时针 + 向外吹 + 与地转风相反"，几乎不像气旋。

**修复**：`tz = -dx*inv*t.spin`（或 `tx = dz, tz = -dx`），`spin = hemisphereSign(level)`（Overworld 北半球取 +1，`level.dimension()` 可配）。

#### WX-TY-02 【P1】影响半径硬截断 + 眼壁断崖（旧 `TY-02`/`SYS-05`，未修）

**位置**：`TyphoonSystem.java:124`（`if (r > t.influenceRadiusBlocks) continue;`）、`:39-41`（Rankine 剖面）。

**分析**：`tangential(r) = vmax·(rmax/r)^0.5`，在 `r = R = 600`、`vmax = 40`、`rmax = 55` 时 `v = 40·(55/600)^0.5 ≈ 12.1 m/s`，然后 `continue` → **12.1 m/s 直接归零**。这是一条 12 m/s 的速度不连续面，玩家穿过时瞬间失速。

**后果**：台风"外圈是一堵看不见的墙"；粒子/帆船/涡轮探针在边界处跳变；`SYS-05`（空间离散/边界）的核心实例。

**修复**：`v(r) = vmax·(rmax/r)^0.5 · smoothstep(1 - r/R)`（`R` 外再延 200 格），或用 Holland 剖面（见 `WX-TY-04`）自然衰减到 <0.5 m/s 再截断；截断阈值改为 `v < 0.3` 而不是 `r > R`。

#### WX-TY-03 【P1】眼内 `continue` 与 `eyeDampening` 双重处理，眼壁 13 m/s 突变（旧 `TY-03`，未修）

**位置**：`TyphoonSystem.java:125`（`if (r < t.eyeRadiusBlocks) continue;`）、`:185-195`（`eyeDampening` 返回 `f²·0.92·intensity`），消费点 `ZenithServerRuntime.java:322-326`（`vx *= (1-eyeDamp)`）。

**分析**：`sample` 在 `r < eye` 时**完全跳过**（贡献 0），`eyeDampening` 又在 `r < eye` 时把**已有风**乘 `(1-f²·0.92)`。两处叠加的结果：

- `r = eye` 正外侧：`tangential(eye) = vmax·eye/rmax = 40·18/55 ≈ 13.1 m/s`；
- `r = eye` 正内侧：贡献 0，且 `eyeDamp = 0.92` → 剩余风 ×0.08；
- 跨过眼壁 1 格，风速从 13.1 跳到接近 0。

真实台风眼壁是**风速最大值**（`rmax` 处），眼内是**静风**但过渡是连续的（`v ∝ r` 从 0 线性增到 `vmax`）。代码里 `rmax = 55 > eye = 18`，所以眼壁 `r=18` 处 `v = 13`，`r=55` 处 `v = 40`——"眼壁"其实在 `rmax`，而 `eye` 只是一个被硬砍的洞。

**后果**：台风眼是"一个直径 36 格的圆柱形空洞 + 一圈 13 m/s 的台阶"；`eyeDampening` 让眼内的**背景风**也消失（物理上眼内只是没有台风环流，背景风仍在）。

**修复**：删掉 `:125` 的 `continue`，让 Rankine 内区 `v = vmax·r/rmax` 自然给 0；`eyeDampening` 只作用于**台风贡献**而不是总风（需要把 `sample` 的返回值单独缩放，而不是在运行时乘总风）。

#### WX-TY-04 【P2】Rankine 剖面 vs Holland，边界层入流角固定（旧 `TY-04`，未修）

**位置**：`TyphoonSystem.java:37-42`（Rankine）、`:130`（`vr = -vt*0.15`，固定 15% 入流）。

**分析**：Rankine 涡在 `r = rmax` 处导数不连续（内区 `dv/dr = vmax/rmax`，外区 `dv/dr = -0.5·vmax·rmax^0.5·r^-1.5`），且外区衰减指数固定 0.5（真实 0.5–0.8）。入流角固定 `atan(0.15) ≈ 8.5°`，真实边界层入流角 10–30° 且随半径变（眼壁最大）。

**后果**：`rmax` 附近风速剖面有折角；螺旋雨带的入流角不随半径变化，雨带看起来"太直"。

**修复**：Holland (1980)：`V(r) = sqrt((B/ρ)(Rmax/r)^B·Δp·exp(-(Rmax/r)^B) + (rf/2)²) - rf/2`，`B ∈ [1, 2.5]`；入流角 `β(r) = 25°·exp(-(r-rmax)²/(2·rmax²)) + 5°`。

#### WX-TY-05 【P2】高度衰减用绝对 y；登陆判定依赖 `isOceanAt`（旧 `TY-05`，未修）

**位置**：`TyphoonSystem.java:137`（`hDecay = exp(-max(0, y-200)/400)`）、`:100`（`overLand = !ctx.terrain().isOceanAt(t.x, t.z)`）。

**分析**：`y-200` 是绝对高度，海平面 64 处 `y<200` → `hDecay = 1`，即"海平面到 y=200 全部满强度"；山地（地表 200）处从地面起就衰减。物理上台风是**暖心结构**，最强风在 10 m–1 km（约 0–64 格 AGL），随高度减小。用绝对 y 导致"高原上的台风从地面就开始弱"，与 `SYS-03` 同源。

`overLand` 依赖 `isOceanAt`，而 `SYS-12` 让已加载区恒为陆地 → 台风一进加载区就 `exp(-dt/21600)` 衰减（τ = 6 小时），玩家靠近时台风会"自己消失"。

**后果**：`SYS-12` 的第二个直接受害系统；台风登陆衰减的触发条件与真实海陆无关。

**修复**：`hDecay = exp(-max(0, agl-100)/300)`（AGL，`agl < 100` 不衰减）；`overLand` 用修好的 `isOceanAt`，并加"眼壁仍在海上则半衰"的判定。

#### WX-TY-06 【P2】台风不写回 L0 气压场，也不驱动原版雨（旧 `TY-06`，未修）

**位置**：`TyphoonSystem.java:55-111`（`tick` 只动 `x/z/intensity`），`WorldScaleDriver.sample` 里的 `cyclones` 与 `typhoons` 是两个独立列表。

**分析**：`WorldScaleDriver` 有 6 个 `CycloneCell`（`:27`），台风是 `TyphoonSystem` 自己的列表。两者的位置、强度、半径互不关联；`BackgroundMetGrid` 的气压来自 `driver.pressureAnomalyPa()`，而台风**从不写气压**。所以：

- 台风的环流只存在于 `sample()` 的叠加层，L0/L1 的风场里看不到它；
- 气压计/`/zenith status` 看不到台风中心；
- `SYS-05` 的"气压异常不进 API"（`L0-07`）在台风这里再加一层。

**后果**：台风是"叠加在背景上的一个局部扰动"，不参与大尺度场；`DRV-03`（顶层与 L0 尺度不匹配）的同类问题。

**修复**：`TyphoonSystem.tick` 把 `t` 注册/更新为一个 `CycloneCell`（或直接给 `WorldScaleDriver` 提供一个 `externalVortices` 列表），让 L0 的气压/目标风包含台风；`precipIntensity01` 写入 L1 湿度。

#### WX-TY-07 【P3】命名/`spin` 不受半球控制，`nameCounter` 无持久化（旧 `TY-07`，未修）

**位置**：`TyphoonSystem.java:31`、`:49-53`（`NAMES` 数组）、`:78`（`nameCounter++ % NAMES.length`）。

**分析**：`spin`、`NAMES`、`nameCounter` 都是实例状态，服务器重启后 `nameCounter` 归零 → 每次重启都从第一个名字开始；`spin` 永远 +1。

**修复**：`spin = hemisphereSign`；`nameCounter` 存 `SavedData`（与 `SYS-08` 一起做）。

---

### 7.5 FrontSystem（锋面）

> `FrontSystem.java`，161 行。上限 4 条，生成在玩家 500–1,300 格外。

#### WX-FR-01 【P1】生成时设的强度被下一 tick 覆盖为 ~0.002

**位置**：`FrontSystem.java:94`（`f.intensity01 = 0.5 + stormAct * 0.5;`）vs `:108-109`：

```java
double t = f.ageSeconds / f.lifetimeSeconds;
f.intensity01 = t < 0.2 ? t / 0.2 : t > 0.8 ? Math.max(0.2, 1 - (t - 0.8) / 0.2) : 1.0;
```

**分析**：生成发生在 `tick` 的"生成段"（`:59-96`），下一 tick 进入"推进段"（`:98-110`），`ageSeconds` 刚加了 `dt = 0.05`，`lifetimeSeconds = 10800`（3 小时）→ `t ≈ 0.05/10800 ≈ 4.6e-6` → `intensity01 = t/0.2 ≈ **2.3e-5**`。`:94` 的 `0.5–1.0` 只存在一个 tick。

**后果**：每条锋面前 20% 寿命（36 分钟）几乎无风无雨（`e = band · intensity ≈ 1e-5`），玩家看到的是"一条什么都没发生的线"。这也解释了为什么"锋面很难被感知"。

**修复**：`:94` 删除（由 `:109` 统一驱动），或把 `:109` 的升段改为 `max(0.5 + stormAct*0.5, t/0.2)`；更好的做法是**生成时播种 `ageSeconds`**（如 `ageSeconds = 0.2 * lifetime`），让锋面"生出来就是成熟的"。

#### WX-FR-02 【P2】无锋后降温/升压/风向顺转（旧 `FR-02`，部分修）

**位置**：`FrontSystem.java:119-141`（`sample` 只返回风与降水）。

**分析**：0.1.4 修好了线走向与法向符号（`FR/SQ-01`，已确认 `:72-87` 的 `lineDir = (wz, -wx)/w` 与 `normal()` 一致），但**热力学仍是零**：冷锋过境应有 `ΔT = -3…-8 K`、`Δp = +2…+6 hPa`、风向顺转 30–60°。代码里一个都没有——`WindContribution` 的结构里也**没有温度/气压字段**。

**后果**：玩家只能感到"突然刮一阵风"，感觉不到"锋面过境"。这是 0.2 "天气故事性"的第一缺口。

**修复**：`WindContribution` 增加 `tempDeltaK`、`pressureDeltaPa`；`FrontSystem.sample` 按 `signedDistance` 给 `-4K`（锋后）与 `+3 hPa`；`L1` 的 `sample` 把 `tempDeltaK` 加到 `airTempK`，`BackgroundMetGrid` 的 `pressure` 加上 `pressureDeltaPa`。

#### WX-FRONT-01 【P2·新】锋面生成位置纯随机，与驱动器低压/温度场无关

**位置**：`FrontSystem.java:59-96`（`ang = random*2π`、`dist = 500 + random*800`、`cx/cz = anchor + (cos,sin)*dist`）。

**分析**：真实锋面挂在低压中心的槽线上，冷锋从低压中心向西南延伸、暖锋向东南。代码里锋面只是"玩家周围 500–1,300 格的一个随机圆环上的随机方向"，与 `driver.cyclones` 的位置、`BackgroundMetGrid` 的温度梯度**没有任何关系**。`:75-78` 只用了 L1 风向来定线方向（垂直风向），这是唯一的环境耦合。

**后果**：锋面可以在任意位置、任意方向出现；同一时刻可以有一条冷锋和一条暖锋交叉；锋面移动方向由 `normal · steer` 决定（`:92-93`），可能与实际温度平流方向相反（冷锋向暖区移动是正常的，但代码不检查哪边是暖区）。

**修复**：锋面应从 `driver.cyclones` 的低压中心"长出"：取 `cyclone.x/z`，沿 `cyclone` 的槽线方向（与 L0 风切变垂直）放线；`moveX/moveZ` 用 `L0` 的温度平流 `-V·∇T` 的符号决定冷暖。

---

### 7.6 SquallLineSystem（飑线）

> `SquallLineSystem.java`，217 行。上限 2 条，寿命 3 小时。

#### WX-SQ-01 【P2】阵风整柱同速，线后无衰减（旧 `SQ-03`，未修）

**位置**：`SquallLineSystem.java:173-178`。

```java
if (ahead > 0 && ahead < 160) {
    double gust = 18.0 * Math.exp(-ahead / 90.0) * e;
    acc = acc.add(new WindContribution(n[0]*gust, 0.3*gust*0.3, n[1]*gust, ...));
}
```

**分析**：`gust` 与 `y` 完全无关 → 从地面到 320 格整柱 18 m/s。真实飑线是**冷池**驱动的：地面最强（`18–25 m/s`），`500 m` 高度减半，`2 km` 以上回到环境风。`:176` 的 `0.3*gust*0.3` 是 `0.09·gust ≈ 1.6 m/s` 的上升，与 18 m/s 的水平风不匹配（真实飑线前缘是强上升，`w ≈ 0.3–0.5·U`）。

另外 `ahead ∈ (0, 160)` 之外（线后 `ahead < 0`）完全没有风，冷池的"线后强风"缺失。

**后果**：飞行器在 200 格高度遇到 18 m/s 的"飑线"，地面只有同样的 18 m/s；线后是静风而不是冷池出流。

**修复**：`gust *= exp(-agl/300)`（`agl = y - terrain`）；`vy = 0.35 * gust * exp(-agl/500)`；线后加 `if (ahead < 0 && ahead > -120) gust = 10 * exp(ahead/60)` 的冷池出流。

#### WX-SQ-02 【P1】闪电同 `WX-SC-02`，且频率更高（旧 `SC-01` 的飑线侧，未修）

**位置**：`SquallLineSystem.java:138-149`（`getHeightmapPos` + `setVisualOnly(false)`）、`:130-134`（`lightningTimer = 2 + random*6`）。

**分析**：与 `WX-SC-02` 完全同构，但间隔 2–8 s（比单体的 4–18 s 密 2–3 倍）。一条飑线 3 小时 → 约 2,000 次雷击，其中大部分在未加载区块。

**修复**：同 `WX-SC-02`；此外飑线的闪电应沿**线**分布（`l.x1..l.x2` 上随机取点），而不是以 `c.x/c.z` 为中心画圆（`:139-144` 用的是 `l.x1 + random*(x2-x1)`？需确认——实际是 `l` 的线段上取点，这点是对的）。真正缺的仍是维度/加载/配置门控。

#### WX-SQ-03 【P3】微下击暴流的生成/寿命未与单体耦合（未修）

**位置**：`SquallLineSystem.java:20-34`（`Microburst` 类）、`:180-198`（采样）。

**分析**：`Microburst` 有 `radiusBlocks = 30`、`envelope()`，但**没有生成逻辑**（`tick` 里没有 `microbursts.add(...)`），也没有寿命衰减。`WindContribution` 的 `-10·core` 下沉是固定的。全仓 grep `microbursts.add` 无结果 → 这个列表永远是空的。

**后果**：`WX-SQ-03` 是死代码；`WindContribution` 的 `-10 m/s` 分支永远不会执行；`ZenithSquallLineInfo.microburstCount` 恒 0。

**修复**：在飑线成熟期按 `stormActivity` 概率生成微下击暴流（寿命 60–180 s，半径 20–50 格），或删除该分支并在文档标注"未实现"。

---

### 7.7 DustDevilSystem（尘卷风）

> `DustDevilSystem.java`，135 行。上限 12 个，生成半径 200 格。

#### WX-DD-01 【P2】核心区只有上升，无切向风（旧 `DD-01`，未修）

**位置**：`DustDevilSystem.java:111-115`。

```java
if (r < d.radiusBlocks) {
    double core = 1 - r / d.radiusBlocks;
    acc = acc.add(new WindContribution(0, 4.5*core*env*hDecay, 0, ...));
}
```

**分析**：尘卷风的核心是**旋转**（`v_θ` 在 `r = R` 附近最大），代码在 `r < R` 只给垂直上升，切向风只在 `R < r < 3R` 的环里（`:116-122`）。于是"风眼"是一个不转的柱子，玩家走进去只觉得"有上升气流"。

**后果**：`WX-DD-01` 的核心观感错误；`turbulenceAdd` 在核心只有 0.4，而真实尘卷风核心湍流最强。

**修复**：用 Burgers–Rott 涡：`v_θ(r) = (Γ/(2πr))(1 - exp(-r²/rc²))`，`r → 0` 时 `v_θ → 0` 但 `dv_θ/dr` 最大；核心也给 `turbulence`。

#### WX-DD-02 【P2】高度用绝对 y，`heightBlocks` 与地形无关（旧 `DD-02`，未修）

**位置**：`DustDevilSystem.java:108`（`y > d.heightBlocks + 60`）、`:110`（`hDecay` 用 `y` 而非 AGL）。

**分析**：`heightBlocks` 是 20–40 格的"尘卷风高度"，但 `y` 是绝对坐标。地表 63 时 `y=63` 就在"底部"，地表 200 时 `y=200` 已经超过 `heightBlocks+60 = 100` → **高山上的尘卷风永远采样不到**。同时 `hDecay = 1 - (y - heightBlocks)/60` 在 `y < heightBlocks` 时恒 1，即"从世界底层到 40 格全是满强度"。

**后果**：尘卷风只在低地可见；与 `WX-TH-01` 完全同构的 AGL 错误。

**修复**：`DustDevil` 记录 `groundY`（生成时 `terrain.heightAt`），`agl = y - groundY`。

---

### 7.8 MountainWaveSystem（山地波）

> `MountainWaveSystem.java`，72 行。无状态，每次采样现场算。

#### WX-MW-01 【P2】波长公式被 clamp 到 40–220 格，物理上应是 0.5–5 km（旧 `MW-01`，未修）

**位置**：`MountainWaveSystem.java:48`（`wavelength = clamp(2π·speed/N, 40, 220)`，`N = 0.012`）。

**分析**：`2π·U/N` 在 `U = 5` 时是 `2,618 m`，`U = 15` 时 `7,854 m`。代码 clamp 到 220 格（220 m）——比物理值小 1–2 个数量级。为了"在 MC 尺度上看得见"而压波长，这在游戏里可以接受，但必须**显式承认是艺术压缩**，否则所有相位/振幅推导都不成立。

**后果**：`sin(2π·crestDist/λ)` 的相位在 220 格内转好几圈，山脊后的上升/下沉交替出现"多个波纹"，而真实背风波只有一个主波。

**修复**：保留 clamp，但用一个显式常量 `WAVE_SCALE_COMPRESSION = 0.08`，`λ = clamp(2π·U/N · 0.08, 40, 400)`；文档写明"1:12 压缩"。

#### WX-MW-02 【P2】5 个固定探测距离 → 相位量化（旧 `MW-02`，未修）

**位置**：`MountainWaveSystem.java:37-44`（`dists = {80, 160, 320, 560, 800}`）。

**分析**：`crestDist` 只能取这 5 个值之一（加上 0），`phase = 2π·crestDist/λ` 因此只有 6 个可能值 → `w` 只有 6 档。相邻格子的 `crestDist` 跳变时，`w` 阶跃。

**后果**：背风波的垂直速度场呈"条带"，不是连续波。

**修复**：对 `heightAt` 沿上风向做 12–16 点采样 + 抛物线插值求 `crestDist`，或直接对地形做 FFT（成本高，但可离线）。

#### WX-MW-03 【P2】`terrain` 来自 `SeedTerrainProvider`，不是真实地形（旧 `MW-03`，未修）

**位置**：`MountainWaveSystem.java:19,23`（`this.terrain = ctx.terrain()`），`WeatherContext.java:15`（`SeedTerrainProvider terrain`）。

**分析**：`WeatherContext` 的 `terrain()` 是 `HashedSeedTerrainProvider`（`ZenithServerRuntime.java:148`），**与 `McTerrainAdapter` 无关**。所以：

- 已加载区的真实山脊（玩家挖/建的）不影响山地波；
- 哈希地形是 `fbm` 噪声，玩家看到的山与哈希山不一致；
- 平原上可能算出"虚拟山脊"→ 凭空出现背风波。

**后果**：`MW-03` 是 `SYS-12` 的第三个同源实例（地形两套语义）。玩家在真实山顶上可能完全没波，在平地上却有波。

**修复**：同 `WX-TH-03` —— `WeatherContext` 只暴露一个 `TerrainProbe`，由运行时按"已加载 → MC，未加载 → 哈希"路由。

---

### 7.9 MonsoonSystem / SeasonSystem

#### WX-MS-01 【P2】季风全维度均匀，方向由单值温度偏差决定（旧报告 §8.8 季风段落，当时未编号；本条首次给号，未修）

> **编号说明**：`Hark/01` 的季风只以散文形式出现在 §8.8，没有 `MS-01` 这个编号（全库 `grep MS-01` 在 `01/02/03` 中 0 命中）。`WX-MS-01` 是本报告为它新分配的编号，不属于"旧编号迁移"。

**位置**：`MonsoonSystem.java:16-35`（`targetX/Z` 只由 `season.tempBiasKelvin()` 决定，`sample` 不依赖 `x/z`）。

**分析**：`sample` 返回的 `(monsoonX·hFactor, 0, monsoonZ·hFactor)` 与水平位置**完全无关** → 整个维度同一个季风矢量。真实季风是"海陆热力差驱动的区域环流"，有空间结构（海岸最强、内陆弱）。

**后果**：季风像一个"全局恒定风偏置"，玩家走到哪里都一样；与 `WX-SB`（海陆风）在物理上是同一个机制的两个尺度，但代码里互不相关。

**修复**：季风作为 L0 的**基础流偏置**（`baseFlowX/Z`），而不是一个 `WeatherPhenomenon`；强度按 `海岸距离` 加权。

---

### 7.10 FogModel

#### WX-FOG-01 【P3】`density` 的分段硬切换与 `overWater` 的常数 0.6（未修）

**位置**：`FogModel.java:15-33`。

```java
if (overWater && humidity01 > 0.7 && windSpeedMps > 1.5 && windSpeedMps < 9)
    d = Math.max(d, 0.6);
```

**分析**：平流雾只要满足三个条件就**直接给 0.6**，与湿度、风速的具体值无关；`windSpeed > 9` 时**直接归零**（没有过渡）。辐射雾的分支是连续的（`0.8·(h-0.6)/0.4·(1-w/3)`），平流雾却是一个阶跃。

**后果**：`FogController` 的 `currentDensity` 在 `windSpeed` 跨过 9 m/s 时从 0.6 跳到 0（虽然 `k = 1-e^(-1/60)` 会抹平，但目标值是阶跃）。

**修复**：`d = max(d, 0.6 · smoothstep(humidity, 0.7, 0.95) · smoothstep(9 - windSpeed, 0, 3))`；并让 `overWater` 的湿度门限与 `raining` 分支共享一个 `saturationDeficit`。

---

## 8. 运行时编排（`ZenithServerRuntime`）

> 本章只谈"调度与数据流"，公式本身的问题分散在 §3–§7。0.1.4 在编排层做了两处实质改进：`onServerStopped` 清理维度状态（`ZenithServerRuntime.java:182-188`），以及快照每 100 tick **只构建一次**再分发给所有玩家（`:490-505`）。**其余六项仍是 0.1.1 的形态。**

### 8.1 采样链路（先给结论）

```
API.sample()
  └─ sampleInternal()                     ← 唯一实现，policy 到此为止
       ├─ L1.sample(x,y,z)                ← 最近邻取格，64 格一块，8 层×40 格
       ├─ 9 个 WeatherPhenomenon.sample() ← 逐个加矢量，无质量守恒
       ├─ typhoons.eyeDampening(x,z)      ← 只按 x/z，无限高
       ├─ L2.solve(x,y,z, 合成后的 v)      ← 每次 729 次 isSolid + 热源 + 射流
       └─ builder.source(L1_COARSE).confidence(0.9f)   ← 固定值
```

**这张图就是本报告对"风不像风"的第二个总解释**：L1 是 64 格分辨率的**阶梯常数场**，现象层是**无散度约束的加法**，L2 只输出一个被直接相加的 `localModifier`。三者叠加后既没有连续性，也没有任何一层负责"把质量找回来"。

---

### RT-01 【P1】粗风场只发 L1，客户端却以为拿到了全部（未修，`RT-01`）

**位置**：`ZenithServerRuntime.java:467-488`。

```java
MesoscaleGrid.Sample smp = s.l1.sample(bx, py, bz);
vx[k] = (float) smp.windX();  vy[k] = (float) smp.windY();  vz[k] = (float) smp.windZ();
```

**分析**：广播的 81 个格点**只取 L1**，不含现象层（台风眼墙、飑线阵风锋、尘卷风、热泡）也不含 L2 的绕流与尾流。而客户端 `ClientWindState.sample()` 把这份数据标成 `Source.L1_COARSE`、`confidence(0.6f)`（`ClientWindState.java:126-128`），**与"完整风场"无法区分**。

**后果（量化）**：
- 台风：`TyphoonSystem` 的切向风峰值 ≈ 30 m/s 只在服务端 `sample()` 里存在；客户端粒子/风声/雾/风向标看到的仍是 L1 的背景风（典型 5–8 m/s）。玩家在台风眼里**看不到旋转**，只能感受到"雨变大了"。
- 飑线：`WX-SQ-01` 的阵风锋是 `SquallLineSystem` 的现象贡献，同样不进包。
- 尘卷风/热泡：完全不可见。`ZenithVisualizer` 因此只能画"水平均匀的风丝"。

**修复**：包里加一条"现象层压缩通道"——每格点额外发 3 个 `int16`（现象合成矢量，0.01 m/s 精度），或退一步：单独发 `TyphoonInfo/TornadoInfo/SquallInfo` 的**速度剖面参数**，客户端用同一套解析式重建。后者带宽更省，且能保证与服务端一致。

---

### RT-02 【P1】热源缓存按"焦点 + 海平面"取盒，且每 200 tick 重建（未修，`RT-02`，合并 `L2-03`）

**位置**：`ZenithServerRuntime.java:431-449`。

```java
int cx = (int) s.focusX, cz = (int) s.focusZ;
int cy = level.getSeaLevel();
int R = 48;
for (int dx = -R; dx <= R; dx += 2)
  for (int dz = -R; dz <= R; dz += 2)
    for (int dy = -16; dy <= 24; dy += 2) { ... }
```

**分析**：采样盒是 **49 × 49 × 21 = 50,421 次 `getBlockState`**（每 2 格取一次），全部集中在以**所有玩家坐标均值**为中心、`海平面 −16 ~ +24` 的 96×96×40 盒子里。

四个问题叠在一起：
1. **玩家分散即互相失效**：`focusX/Z` 是算术平均（`:250-265`），两名玩家相距 2000 格时焦点落在中间无人区，双方的热源都扫不到。
2. **高度带写死**：`cy = getSeaLevel()`。山地（y=120）、深洞（y=−40）、下界（无海平面语义）全部错位；`dy` 只用 21 个采样层，热源在垂直方向的分辨率是 2 格。
3. **每 200 tick 全量重建**：缓存没有增量，玩家走 20 格就整盒作废；50,421/200 ≈ **252 次 `getBlockState`/tick 的常驻成本**（旧报告 §15 的估算被实测证实）。
4. **`heatCache.size() > 256` 直接 `return`**：一旦盒内热源超过 256 个（岩浆湖、下界、大型营火阵列），扫描**从 dx=−48 处提前中断**，盒子的另一半永远没有热源——这是一个静默的各向异性 bug。

**后果**：L2 的上升气流只在"焦点附近、海平面高度"可用。玩家在山上或洞穴里用 `/zenith thermal` 生成的上升气流**在 L2 里根本不存在**（热源没进缓存），只有 `ThermalSystem` 自己的解析式在起作用。

**修复**：
- 热源改为**事件驱动注册**（`BlockEvent`/方块实体 `setChanged` 时登记），不是周期性网格扫描；
- 或者按玩家分桶（每玩家一个 32 格盒），而不是全局均值；
- `cy` 用 `level.getMinBuildHeight()..getMaxBuildHeight()` 分段，或直接跟随玩家 y；
- 去掉 256 上限，改为按距离裁剪。

---

### RT-03 【P2】固定 `dt = 0.05`，与 `/tick rate` 和睡眠/时间跳变全部脱钩（未修，`RT-03`）

**位置**：`ZenithServerRuntime.java:88`（`SOLVER_STEP_SECONDS = 0.05`）、`:201`（`double dt = SOLVER_STEP_SECONDS;`）。

**分析**：`WorldScaleDriver.advance(dt, ...)` 与所有 `WeatherPhenomenon.tick(level, dt, ctx)` 每 tick 都拿 **50 ms**，与实际 tick 间隔无关。

- `/tick rate 5` → 真实 200 ms/tick，模拟时间只走 1/4；
- `/tick rate 100` → 模拟时间走 5 倍，风暴 5 倍速生成；
- 服务器卡顿（20→5 TPS）→ 天气**跟着变慢**，玩家感觉"卡的时候风也停了"；
- 睡眠跳过夜晚：`level.getDayTime()` 一次跳 12000，但 `driver.advance` 只推进了 50 ms，L0 的日变化（`0.65 + 0.35·solar`）会**瞬间跳变**——`WX-MW/WX-SB` 的相位因此错位。

**后果**：天气的"物理时间"与玩家的"墙钟时间"是两条独立的时间轴。单人正常 20 TPS 时不明显，一旦装性能模组、开加速火把、或用 `/tick`，全部失真。

**修复**：`dt = min(0.25, (nowNs - lastNs)/1e9)` 并 clamp；`/tick rate` 改变时按比例补偿；`dayTime` 跳变 > 1000 时对 L0 做 `n` 次子步（`n = ceil(jump/1200)`）。

---

### RT-04 【P2】粗风场与快照按玩家全量重发，无脏标记、无压缩（未修，`RT-04`）

**位置**：`ZenithServerRuntime.java:467-488`（每玩家 81 次 L1 采样 + 1 包）、`:490-505`（每玩家 1 包）。

**分析**：0.1.4 把快照"每玩家重建"改成了"每维度重建一次"，这是真改进。但：
- 粗风场仍是**每玩家独立采样**：N 个玩家 → 81N 次 `l1.sample` + N 个包，每 40 tick 一次。20 人服 ≈ 1620 次采样/2 s，包体 81×3×4 + 8×3 + 12 ≈ **1.0 KB/人/2 s ≈ 10 KB/s/人**（未压缩）。
- 数据内容 40 tick 内几乎不变（L1 是 64 格/64 tick 刷新的阶梯场），却每次全量重发。
- 无 `int16` 量化（`NET-06`）、无 `Zstd`/`deflate`（MC 的 `Connection` 只在阈值以上压缩，默认 256 字节，1 KB 包会压缩但压缩率差）。

**修复**：改"变化量"协议——只有 `tick % 40 == 0` 且格点值变化 > 0.1 m/s 的格子才发；或改用 **区域订阅**（玩家只订阅自己周围 3×3 块区域，用 chunk 加载事件驱动）。

---

### RT-05 【P2】维度查找用 `toString().equals()` 线性遍历（未修，`RT-05`）

**位置**：`ZenithServerRuntime.java:295-302`（`sampleInternal`）、`:509-518`（`stateFor(world)`）、`:576-584`（`fogDensityAt`）、`:601-608`（`season`）。

**分析**：每次 API 采样都要 `server.getAllLevels()` 遍历 + `dimension().location().toString()` **新建字符串** + `equals`。`sampleInternal` 在**每个实体 tick**（帆船）、每 10 tick（涡轮）、每次右击（风速计）都会走一遍。

**量化**：主世界+下界+末地 = 3 个 `ServerLevel`，每次采样 3 次 `ResourceLocation.toString()`（每次分配 ~40 字节）。20 人服帆船 10 艘 → 200 次采样/s → 600 次字符串分配/s。不是瓶颈，但它是**纯浪费**，且 `toString()` 比较把 `ResourceLocation` 的命名空间语义丢了（`minecraft:overworld` vs 自定义 `overworld` 不等）。

**修复**：`Map<ResourceKey<Level>, DimensionState>` + `stateFor(ServerLevel)` 已有的 `computeIfAbsent` 直接复用；`ZenithWorldRef` 里存 `ResourceKey` 而不是字符串。

---

## 9. 公开 API（`api/`）

> 0.1.4 的 API 层**只做了一件事**：把 0.1.1 的 `ZenithTerrainApi` 从"无实现"变成"注册了 provider 但返回 unknown"（`ZenithServerRuntime.java:281-284`）。`API-08`（`GameplayWindSample.empty()` 温度 NaN）确认为**已修**——`ZenithWindSample.Builder` 的 `hasTemperature=false` 与 `airTemperatureKelvin=NaN` 组合现在有 Javadoc 说明（`ZenithWindSample.java:71`），且 `GameplayWindSample` 暴露 `hasTemperature()`（`:35`）。

### API-01 【P1】`SamplePolicy` 是死参数：不改变层级、不改变精度、不改变来源（未修，`API-01`）

**位置**：`ZenithServerRuntime.java:270-278`（policy 传入后**未使用**）、`:286-347`（`sampleInternal` 的 `policy` 形参在方法体内一次都没出现）。

```java
public ZenithWindSample sample(ZenithWorldRef world, ZenithVec3 position, SamplePolicy policy) {
    return sampleInternal(world, position.x(), position.y(), position.z(), policy, false);
}
// sampleInternal(..., SamplePolicy policy, boolean fromClient) {
//     ... policy 从未被读取 ...
// }
```

**分析**：`SamplePolicy` 定义了 6 个枚举值（`SamplePolicy.java:9-15`），带 `allowClientLocal` / `allowAggregated` 两个开关，文档写"决定采样时可以触达哪些数据源"。但实际上：
- `SERVER_COARSE_ONLY` 也会跑 L2（729 次方块查询）——**最贵的路径被标成了最便宜的语义**；
- `DIAGNOSTIC_ALL_SOURCES` 和 `GAMEPLAY_SERVER_ONLY` 返回**逐位相同**的结果；
- 客户端侧 `ClientWindState.java:117` 是唯一真正读 `policy.allowClientLocal()` 的地方，而它读出来的结果只是**加不加正弦抖动**。

**后果**：第三方模组无法用 policy 控制成本。想"只要个大概风向"的调用方（比如 HUD 每帧查询）付的是 L2 全价；想"要精确力"的调用方拿到的是 L1 + 加法现象。

**修复**：把 policy 变成**真正的分层门控**：
```java
if (policy == SERVER_COARSE_ONLY) return coarseOnly(...);   // 只 L1
if (policy == GAMEPLAY_SERVER_ONLY) return gameplay(...);    // L1 + 现象，跳过 L2
return full(...);                                            // L1 + 现象 + L2
```
并且 `Source` 必须反映实际参与合成的最高层。

---

### API-02 【P1】`runL2()` 返回 `supported=true` + 零力零矩（未修，`API-02`）

**位置**：`ZenithServerRuntime.java:349-353`。

```java
public ZenithL2Result runL2(ZenithL2Request request) {
    // L2-lite 组合接口，先给出零力占位
    return ZenithL2Result.of(net.atomos.zenith.api.ZenithL2ForceMoment.zero());
}
```

**分析**：`ZenithL2Result.of(...)` 的唯一语义是 `supported = true`（`ZenithL2Result.java:17-19`）。所以任何调用方——包括未来的空气动力学模组、风帆、风力发电机——都会得到"**支持，力 = 0 N，矩 = 0 N·m**"的答复。

**后果**：这是本报告里**最危险的 API 语义**。零力在物理上是"无风"，而调用方无法区分"这里真的没风"和"接口没实现"。旧报告 §10.2 已经点名，0.1.4 未动。

**修复**：立即改为 `return ZenithL2Result.unsupported();`。等真正实现后再改回 `of(...)`，并且 `ZenithL2Request` 要能表达"参考点/特征长度/攻角"。

---

### API-03 【P1】`runPolar()` 的六个物理缺陷全部保留（未修，`API-03`）

**位置**：`ZenithServerRuntime.java:360-400`。

| # | 缺陷 | 代码 | 数值后果 |
|---|------|------|----------|
| 1 | 零升迎角写成 `α₀ = −2 − 8t` | `:369` | NACA 0012（t=0.12）→ α₀ = **−2.96°**；对称翼型本应 **0°**。`Cl(0°) = 2π·0.0517·0.714 = **0.232**` |
| 2 | `maxThickness()` 用 `2·max|y|` | `ZenithAirfoilDefinition.java:25-29` | NACA 2412 的弯度叠加后 `max|y| ≈ 0.095` → 厚度报 **0.19**（真实 0.12）。α₀ 与失速角随之偏 |
| 3 | 失速角线性外推 | `:370` | `stall = 14 − 10t`；2412 → **12.1°**，真实约 15–16° |
| 4 | 失速后阻力用 `(α/15)^4` | `:391` | α=45° → `cd = 1.63`；**α=90° → cd = 25.9**（平板实测 ≈ 1.98，差 **13 倍**） |
| 5 | `cm` 当常数 | `:392` | 对称翼型给出 **−0.098**，应为 0 |
| 6 | `stepDegrees ≤ 0` 不校验 | `ZenithPolarRequest.java:33-35` | `step=0` → `(max−min)/0 = ∞` → `(int)∞ = Integer.MAX_VALUE` → `angleSampleCount()` 返回 21 亿 → `runPolar` **OOM** |

**分析**：这不是"模型粗糙"，是**符号与量纲错误**（#1）加**无界循环**（#6）。而且 `request.reynoldsNumbers()`（`:31`）在整个 `runPolar` 里一次都没被读——`cd0` 写死 `0.006 + 0.05t`，与 Re 无关。

**后果**：`/zenith polar 1 5` 的输出不可用于任何工程判断；`step=0` 可直接把服务端打挂（虽然是 OP 命令，但 API 是公开的，第三方可传任意 `ZenithPolarRequest`）。

**修复**：
- `α₀ = −(1/π)∫ (dyc/dx)(cos θ − 1) dθ`，NACA 2412 ≈ −2.1°、4412 ≈ −4.2°，对称翼型严格 0；
- 厚度改用标准公式 `t_max = 5t(0.2969√x − ...)` 的最大值 ×2，或直接 `t`；
- 失速后用 **Viterna–Corrigan**：`CD_max ≈ 1.11 + 0.018·AR`，`CL` 向 `±1.1·sin2α` 过渡；
- `stepDegrees` 在 `Builder.angleSweep` 里 `if (step <= 0) throw`；
- `cd0 = 0.074/Re^0.2`（湍流平板摩阻）。

---

### API-04 【P2】地形 API 双入口，公开的那个永远返回 `unknown`（未修，`API-04`，与 `SYS-12` 叠加）

**位置**：`ZenithServerRuntime.java:280-284` 与 `:402-416`。

```java
@Override
public ZenithTerrainSample sample(ZenithWorldRef world, ZenithBlockPos pos) {
    // 地形采样：没有真实高度查询，先返回未知
    return ZenithTerrainSample.unknown();          // ← 接口方法，恒 unknown
}

@Override
public ZenithTerrainSample sampleTerrain(ZenithWorldRef world, ZenithBlockPos pos) {
    ... s.terrainAdapter.terrainHeightBlocks(pos.x(), pos.z()) ...   // ← 有实现，无人调用
}
```

**分析**：`ZenithTerrainApi.sample()`（`ZenithTerrainApi.java:12-15`）调的是前者；后者是 `ZenithWindRuntimeProvider` 上的另一个方法，**全代码库零调用**（grep 无命中）。

**后果**：任何外部模组问"这里地形多高"，得到的永远是 `UNKNOWN`。而且**即使接对了也是错的**——`terrainHeightBlocks` 返回的是地表上方第一格空气的 y（`SYS-12`），比真实地表高 1，`surfaceAt` 恒 `GRASS`。

**修复**：删掉 `sample()` 的空实现，转发到 `sampleTerrain()`；并先修 `SYS-12`（`h - 1`）。

---

### API-05 【P2】无版本号、无事件、无批量接口（未修，`API-05`）

**位置**：`api/` 目录全量（21 个文件），无 `VERSION` 常量；`ZenithWeatherApi`（`ZenithWeatherApi.java:11-45`）只有 pull 式查询。

**分析**：
- **无版本**：第三方无法在编译期/运行期判断"这个 Zenith 支持 `runL2` 吗"。`ZenithWindApi.isAvailable()` 只告诉你 provider 非空（`:19-21`），不告诉你能力集。
- **无事件**：台风生成/消散、风暴进入成熟期、锋面过境——全部只能轮询。轮询 `fronts()` 每 tick 会创建 `List` + `stream().map().toList()`（`ZenithServerRuntime.java:525-528`）。
- **无批量**：`sample(world, pos)` 一次一个点。`WeatherSnapshotPacket.build` 内部已经证明会需要"一次算一片"（81 点），但那是 private。
- `TornadoVortex`（`WorldScaleDriver` 内部类）字段是 `public` 可变——旧报告点名过，0.1.4 未改。

**修复**：加 `public static final String API_VERSION = "0.1"`；加 `ZenithWeatherEvent.TyphoonSpawned/SquallLineFormed`（NeoForge `EventBus`）；加 `sampleBatch(world, double[] xyz)`。

---

### API-06 【P2】客户端 `Level` 走服务端 runtime，纯客户端返回空样本（未修，`API-06`）

**位置**：`ZenithMinecraftWindApi.java:23-43`。

```java
public static ZenithWorldRef worldRefOf(Level level) {
    return level.isClientSide
            ? ZenithWorldRef.client(...)      // client ref
            : ZenithWorldRef.server(...);
}
public static ZenithWindSample sample(Level level, Vec3 pos) {
    return ZenithWindApi.sample(worldRefOf(level), ...);   // ← 永远走 ZenithWindApi
}
```

**分析**：`ZenithWindApi.provider` 在 `ZenithMod` 构造里被绑到 `ZenithServerRuntime`（`ZenithMod.java:44`），**客户端也执行这段**。`ZenithClientWindApi` 是**另一个类**（`api/client/`），有自己的 provider，只有 `ZenithClientSetup.java:23` 会绑。

所以 `ZenithMinecraftWindApi.sample(clientLevel, pos)`：
- 单人/集成服：`ServerLifecycleHooks.getCurrentServer()` 非空 → 返回**服务端**数据（此时客户端和后端同 JVM，碰巧能用）；
- 专用服客户端：`getCurrentServer()` 返回 `null` → `sampleInternal` 直接 `return b.build()`（`ZenithServerRuntime.java:293`）→ `Source.NONE`，**静默的空风**。

**后果**：同一个 API 在单人和多人下行为不同。第三方模组在客户端渲染里调 `ZenithMinecraftWindApi.sample(level, pos)` 会得到"单人正常、多人无风"的诡异现象——这正是 0.1.1 `CLI-03`（风声）踩过的坑。

**修复**：`level.isClientSide` 时转发到 `ZenithMinecraftClientWindApi`，不要落到 `ZenithWindApi`。

---

### API-07 【P2】`pressureAnomalyPa` 恒 0，`humidity01` 是单点值（未修，`API-07`，`L0-07` 未修）

**位置**：`ZenithWindSample.Builder:99`（默认 0）、`ZenithServerRuntime.java:335-346`（组装时**不设** `pressureAnomalyPa`）。

**分析**：L0 内部有完整的 `pressurePa` 场（`BackgroundMetGrid` 的 `CellState`），`computeGeostrophicWind` 天天在读它（`:204-213`），但**从不外泄**。同时 `humidity01` 虽然设了（`:341`），来源是 `l1.sample(x,y,z)` 的**同一格点**——而快照里的湿度是 `l1.sample(focusX, 80, focusZ)`（`:494`），两者可以差 0.3。

**后果**：任何"用气压梯度算风"或"用气压做天气 UI"的第三方代码拿到的永远是 0 Pa，只能自己重算——那等于把 L0 复制一遍。

**修复**：`Builder.pressureAnomalyPa((float)(l1.pressurePa() - 101325))`；`MesoscaleGrid.Sample` 加 `pressureAnomalyPa` 字段。

---

### API-09 【P3】`ZenithAirfoilJson` 手写 JSON 解析（未修，`API-09`）

**位置**：`ZenithAirfoilJson.java:10-36`。

```java
int nameIdx = json.indexOf("\"name\"");
int c = json.indexOf(':', nameIdx), q1 = json.indexOf('"', c), q2 = json.indexOf('"', q1 + 1);
...
for (String pair : body.split("\\],\\s*\\[")) {
    String clean = pair.replaceAll("[\\[\\]\\s]", "");
```

**具体缺陷**：
1. `indexOf('"', c)` 在 `c == -1`（无冒号）时从 0 开始搜，会把**第一个引号**当名字起点；
2. `q2 = indexOf('"', q1+1)` 遇到 `\"` 转义直接截断；
3. `findMatching` 未闭合时返回 `s.length()`，随后 `substring(arr+1, end)` 抛 `StringIndexOutOfBoundsException`；
4. `split("\\],\\s*\\[")` 依赖"两个数组之间恰好是 `],[`"，`[[0,0],[1,0]]` 可以，但 `[[0, 0], [1, 0]]` 的空格被 `\\s*` 吃掉后**首尾**仍带 `[`/`]`，靠 `replaceAll` 兜底——遇到负号科学计数 `1.0e-3` 没问题，但遇到 `NaN`/`Infinity` 会静默 `ignored`（`:30`）丢点。

**后果**：目前只有测试/未来功能会用，但这是**公开 API**，错误输入直接抛未包装异常。

**修复**：用 MC 自带的 Gson（`com.google.gson`）或 `JsonParser`，三行代码。

---

### API-10 【P3】NACA 生成器把上下表面对调，且前/后缘各重复一个点（未修，`API-10`）

**位置**：`ZenithAirfoilPresets.java:37` 与 `:52`。

```java
// 第一圈（i = 0 → n）
pts.add(new ZenithAirfoilCoordinate(x + yt*sinθ, yc - yt*cosθ));   // ← 这是下表面
// 第二圈（i = n → 0）
pts.add(new ZenithAirfoilCoordinate(x - yt*sinθ, yc + yt*cosθ));   // ← 这是上表面
```

**分析**：`ZenithAirfoilDefinition` 的注释（`ZenithAirfoilDefinition.java:7`）明写"**上表面**前缘→后缘，**下表面**后缘→前缘"。生成器却是"下表面前→后，上表面后→前"——**整体反了**，且：
- `i = n` 在第二圈的第一步被加入，与第一圈最后一个点**重合**（后缘重复）；
- 第二圈最后 `i = 0` 与第一圈第一个点**重合**（前缘重复）。

**后果**：如果未来有代码按"点序"做面积分/周长/法向（绕流、翼型阻力），符号会整体反；多边形闭合会多两个退化边。

**修复**：交换 `yc ± yt*cosθ` 的符号，第二圈从 `i = n-1` 到 `i = 1`。

---

### API-11 【P3】`Source` / `Authority` 的组合语义不完备（新，`API-11`，合并旧 `RT-06`）

**位置**：`ZenithWindSample.java:10-12`（枚举）、`:76`（`hasFlow()`）、`:89-91`（`isTrustedForGameplay()`）、`ZenithServerRuntime.java:343-344`。

```java
public boolean hasFlow() { return source != Source.NONE; }
...
.source(ZenithWindSample.Source.L1_COARSE)   // 实际是 L1 + 9 个现象 + L2
.confidence(0.9f)                            // 常数，与任何误差估计无关
```

**分析**：
1. `Source` 只有 `NONE/L0_BACKGROUND/L1_COARSE/L2_LOCAL` 四档，**没有"现象层"这一档**，也没有"多层合成"。返回值实际是四层合成，却只能标一个。
2. `hasFlow()` 只看 `source != NONE`。`ClientWindState.sample()` 在**没有收到任何包**时（`coarseWind == null`）仍然 `source(L1_COARSE)`（`ClientWindState.java:126`），于是 `hasFlow() == true` 而风速严格为 0 → `WindVaneBlockEntity.tick()` 的 `if (!s.hasFlow()) return;`（`WindVaneBlockEntity.java:30`）**永远不为真**，箭头会朝 `atan2(0,0)=0`（正南）死锁。
3. `confidence = 0.9f` 是常数。`isTrustedForGameplay()` 只检查 `> 0`，所以 0.9 与 0.01 等价。这个字段目前**零信息量**。

**后果**：调用方无法用 `Source` 判断"我拿到的是粗风还是全风"，也无法用 `confidence` 做插值/降级。

**修复**：`Source` 改为位标记 `L0 | L1 | PHENOMENA | L2`；`hasFlow()` 改为 `meanVelocity().length() > 1e-6 || source != NONE && (gust||modifier)`；`confidence` 按"实际参与层 + 数据新鲜度"计算（如 L1 新鲜 → 0.9，跨焦点边界 → 0.5，客户端插值 → 0.6）。

---

## 10. 网络（`network/`）

> 0.1.4 的网络层**没有结构变化**：两个包、同一个注册器、`PayloadRegistrar("1")`（`ZenithNetwork.java:17`）。已修的是 `NET-02`（单位，随 `DRV-04` 一起改为 m/s）。**新增确认：两个包都没有任何长度/参数上界，恶意客户端可打崩服务端，恶意服务端可打崩客户端。**

### NET-01 【P1】粗风场只含 L1，且分辨率比气象图小一个数量级（未修，`NET-01` → 见 `RT-01`）

**位置**：`CoarseWindPacket.java:15-19`、`ZenithServerRuntime.java:91-94`。

```
COARSE_WIND_SYNC_TICKS            = 40      （2 s）
COARSE_WIND_SYNC_CELL_SIZE_BLOCKS = 32
COARSE_WIND_SYNC_RADIUS_CELLS     = 4       （9×9 = ±128 格）
```

**分析**：三个数字放在一起就是一个**三重错配**：

| 数据 | 实际覆盖范围 | 分辨率 | 刷新 |
|------|--------------|--------|------|
| `CoarseWindPacket` | **±128 格** | 32 格 | 2 s |
| L1 源网格 | **±1024 格**（33×33@64） | 64 格 | 3.2 s |
| 气象图绘制范围 | **±1200 格** | 屏幕 220 px / 81 箭头 | 每帧 |
| 玩家视距（默认 12 区块） | **±192 格** | — | — |

**后果**：
1. **气象图 90% 的区域是空白**：`MeteorologicalMapScreen.java:44` 用 `sampleCoarse` 画 ±1200 格的箭头，但 `sampleCoarse` 在包外会 `clamp` 到边缘格（`ClientWindState.java:93-95`），于是图上的风全部"边缘复制"，形成一个**假的均匀场**。用户看到的台风位置在图上，风向箭头却和它无关。
2. **视距 12 区块 ≈ 192 格 > 128 格**：玩家看向远处时，粒子/风丝的风是**最近边缘格的外推值**，与近处风不连续。
3. **32 格 vs 64 格**：客户端把 32 格当作 L1 的插值步长，实际 L1 是 64 格——客户端在做"对阶梯场再采样"，只会把台阶磨圆，不会增加信息。

**修复**：`radiusCells` 至少 12（25×25@32 = ±384 格）覆盖视距；或改成 **"L1 原网格 + 客户端 2D 采样"**，包只发 `centerX/Z` 和 `l1.focusBlockX/Z`，让客户端按 33×33@64 自己重建——带宽反而更低。

---

### NET-03 【P2】包内无维度标识，靠客户端 `LoggingIn` 猜（未修，`NET-03`）

**位置**：`CoarseWindPacket.java:15-19`（无 `ResourceKey`）、`WeatherSnapshotPacket.java:25-36`（无 `ResourceKey`）、`ClientWindState.java:55-77`。

**分析**：两个包都不带维度。客户端只能靠 `ClientPlayerNetworkEvent.LoggingIn` 记 `lastDimension`（`:59`），并在 `onPlayerClone` 里比较（`:69-77`）。

**失败场景**（0.1.4 已修一部分）：
- 同维度内传送（`/tp`）→ `Clone` 事件**不触发** → 不 `clear()` → 旧包继续用（正确，因为同维度）。
- 跨维度传送 → `Clone` 触发，`dim != lastDimension` → `clear()`。**这条 0.1.4 修对了**（`N-17`）。
- **但**：`clear()` 后到下一个 40 tick 包到达之间，客户端 `coarseWind == null`，而 `ClientWindState.sample()` **仍返回 `source(L1_COARSE)` 的零矢量**（`:122-130`）——见 `API-11`。这段时间（最多 2 s）里风向标指南、帆船没风、风声静音。
- 更隐蔽：如果服务端把玩家传送到**另一个维度但同名**（数据包世界 vs 主世界都有 `minecraft:overworld`），字符串比较会认为"同维度"。

**修复**：两个包都加 `ResourceKey<Level>`（`ResourceLocation` 编码，~20 字节/包）；客户端丢弃不匹配的包。

---

### NET-05 【P3】`FLOAT_ARRAY_CODEC` 无长度上限，可被构造为 OOM（未修，`NET-05`）

**位置**：`CoarseWindPacket.java:24-31`。

```java
public float[] decode(ByteBuf buf) {
    int n = ByteBufCodecs.VAR_INT.decode(buf);   // ← 攻击者可控，最大 2^31-1
    float[] arr = new float[n];                  // ← 立即分配 4n 字节
    for (int i = 0; i < n; i++) arr[i] = buf.readFloat();
    return arr;
}
```

**分析**：`VAR_INT` 最大 5 字节，可编码到 `Integer.MAX_VALUE`。`new float[2_147_483_647]` → 请求 **8 GiB**，`OutOfMemoryError`。

**攻击面**：
- 恶意**客户端** → 服务端：`CoarseWindPacket` 是 `playToClient`（`ZenithNetwork.java:18-21`），NeoForge 默认**不允许客户端发这个包**。所以这条路被框架挡住了。
- 恶意**服务端** → 客户端：服务端可以发任意 byte，客户端 `decode` 在**网络线程**上跑 → OOM → 客户端崩溃。这在"连接陌生服务器"场景下是真的。
- 中间人/损坏的包：TCP 校验和下概率极低，但 `n` 与实际字节数不匹配会先抛 `IndexOutOfBoundsException`（不是 OOM）。

旧报告标 `P3` 并注"风险低"，**本次维持 P3**，但要写清"服务端 → 客户端方向成立"。

**修复**：`if (n < 0 || n > 1024) throw new DecoderException(...)`；且 `n` 必须等于 `(2·radiusCells+1)²`。

---

### NET-06 【P3】浮点全精度传输，未做量化（未修，`NET-06`）

**位置**：`CoarseWindPacket.java:36`（`buf.writeFloat`）、`WeatherSnapshotPacket` 的所有 `DOUBLE`。

**量化**：粗风场单包 = 6×4/8（头）+ 3×(4 + 81×4) = **~1.0 KB**。若改 `int16`（0.01 m/s 精度，范围 ±327 m/s 足够）：`3×(4 + 81×2)` = **~0.5 KB**，减半。坐标 `double`（8 字节）改 `int`（4 字节，方块坐标）再减 24 字节。

**分析**：1 KB/2 s/人 不是大问题，但**每个玩家独立采样**（`RT-04`）放大了它。20 人服 = 10 KB/s 出站，配合 `NET-01` 的"内容几乎不变"就是纯浪费。

**修复**：`int16` 定点 + `int` 坐标 + 差量编码。

---

### NET-07 【P2】`WeatherSnapshotPacket` 参数无校验：`cellSizeBlocks=0` 除零、`radiusCells` 非法 AIOOBE、`listOf` 无上限（新，`NET-07`）

**位置**：`ClientWindState.java:88-108`（采样）、`WeatherSnapshotPacket.java:115-131`（`listOf`）。

**三个独立缺陷**：

**(a) `cellSizeBlocks == 0` 除零**
```java
// ClientWindState.java:91
double fi = (x - pkt.centerX()) / pkt.cellSizeBlocks() + pkt.radiusCells();
```
`cellSizeBlocks` 来自包（`CoarseWindPacket.java:45`）。若为 0 → `fi = ±Infinity` → `(int) Math.floor(Infinity)` = `Integer.MAX_VALUE` → `clamp(..., 0, n-2)` 兜住 → **不崩，但全部采样都钉在最右/下格**。若为负 → 采样反转。当前服务端恒发 32，但**包是公开 API 的一部分**，第三方/未来版本可以发别的值。

**(b) `radiusCells` 非法 → `n = radiusCells*2+1` 为负或 0 → `bilerp` AIOOBE**
```java
// ClientWindState.java:90
int n = pkt.gridSize();              // = radiusCells*2 + 1
int i0 = clamp((int)Math.floor(fi), 0, n - 2);   // ← n=0 时 hi = -2，clamp 返回 -2
double a = g[j * n + i];             // ← 索引 -2 → ArrayIndexOutOfBoundsException
```
`radiusCells = -1` → `n = -1`；`radiusCells = 0` → `n = 1` → `n-2 = -1`。两种情况都在 `bilerp` 里越界，**异常抛在客户端 tick 线程**（`ClientWindState.sample` 由粒子/风声/雾调用）。

**(c) `listOf` 无上限**
```java
// WeatherSnapshotPacket.java:118-122
int n = ByteBufCodecs.VAR_INT.decode(buf);
java.util.List<T> list = new ArrayList<>(n);   // ← 直接按 n 预分配
for (int i = 0; i < n; i++) list.add(element.decode(buf));
```
服务端能发 `n = 2^31-1` → `new ArrayList<>(2147483647)` → **立即 OOM**。比 `NET-05` 更直接（`ArrayList` 的数组是 `Object[]`，4/8 字节/槽）。

**修复**：
- `CoarseWindPacket` 构造时校验 `cellSizeBlocks > 0 && radiusCells in [1, 64]`，`decode` 时同样校验；
- `gridSize()` 加断言，`bilerp` 前 `if (n < 2) return ZERO`；
- `listOf` 加 `if (n < 0 || n > 64) throw`。

---

## 11. 客户端（`client/` + `mixin/`）

### 11.0 先说结论：0.1.4 的视觉修复是真的，但"客户端看到的风"仍是 L1 的 32 格外推

`docs/02` 列的 V0.6 九项（雾 tick 平滑、上风向水、雪地海面、粒子阈值、水沫、龙卷形状、半透明、落叶、风丝）**在代码里都能找到对应实现**，本报告确认其真实性。但所有这些改善都建立在 `ClientWindState.sampleCoarse()` 之上，而它：

- 只有 ±128 格数据（`NET-01`）；
- 只有 L1（`RT-01`）；
- `centerY` 被完全忽略（只做 x/z 双线性，`ClientWindState.java:87-101`）——**所以垂直风（上升气流、下击暴流、山波）在客户端恒为"最近格点的同一值"**。

**换句话说：0.1.4 把"怎么画"修好了，但"画什么"还是错的。**

---

### CLI-01 【P1】已修（关闭）：粒子单位与一阶松弛

**位置**：`ZenithParticleProviders.java:36-62`。

`applyWind(w, τ)` 现在写 `this.xd += (v.x()*w/20.0 - this.xd) * (1-exp(-1/τ))`——**m/s → block/tick 的 `/20` 在正确的位置，且是指数松弛而非每 tick 直接加**。旧报告的"稳态 50×factor 倍"问题消失。`WindStreakProvider` 的初速也改成 `/20`（`:87-89`）。**关闭。**

---

### CLI-02 【P0】已修（关闭）：`RenderFog` 现在会检查 `FogType` 并 `setCanceled`

**位置**：`FogController.java:30-45`。

```java
if (event.getType() != FogType.NONE) return;      // ← 不再覆盖水/岩浆/细雪
if (cam instanceof LivingEntity le && (le.hasEffect(BLINDNESS) || ...)) return;
float far = Mth.lerp((float) currentDensity, event.getFarPlaneDistance(), 24f);
event.setFarPlaneDistance(far);
event.setNearPlaneDistance(...);
event.setCanceled(true);
```

三项都对：类型门控、失明/黑暗让路、`cancel` + `setFar/Near`。**关闭。**

---

### CLI-03 【P0】已修（关闭）：`canStartSilent`

**位置**：`WindSoundManager.java:66-70`。

```java
@Override
public boolean canStartSilent() { return true; }
```

`volume = 0` 初始化 + `canStartSilent = true` → `SoundEngine.play` 会真正把实例放进 `playingSounds`，`isActive` 从第一 tick 起为真，不再每 tick 新建。**关闭。**

---

### CLI-04 【P0】已修（关闭）：客户端指令注册在 GAME bus

**位置**：`ZenithClientCommands.java:15`。

```java
@EventBusSubscriber(value = Dist.CLIENT, bus = EventBusSubscriber.Bus.GAME)
```

`RegisterClientCommandsEvent` 是 game bus 事件，0.1.4 改对了（旧版是 `Bus.MOD`）。`/zenithc map` 与 `/zenithc visualizer` 可用。**关闭。**

---

### CLI-05 【P1】已修（关闭）：风丝/落叶不再靠 `biome.toString()`

**位置**：`ParticleWindController.java:73-82`。

```java
var biome = level.getBiome(bp);
if (biome.is(BiomeTags.IS_FOREST) || biome.is(BiomeTags.IS_JUNGLE) || biome.is(BiomeTags.IS_TAIGA)) { ... }
```

改为标签判定。**关闭。**（注意：`CLI-13` 是同一处的新问题，见下。）

---

### CLI-06 【P1】已修（关闭）：落叶 biome 标签

与 `CLI-05` 同一处修复，合并关闭。

---

### CLI-07 【P1】部分修：排序 + `endBatch` 已做，T-4 双 pass 未做

**位置**：`TornadoRenderer.java:59-60`（排序）、`:88`（`endBatch`）、`:67`（单一 `FLAT_TRANSLUCENT`）。

**已修**：漏斗按"由远到近"排序（`:60`），漏斗内部 quad 也按深度插入排序（`:150-159`），最后 `buffers.endBatch(FLAT_TRANSLUCENT)` 强制刷新。这三个都是 `CLI-07` 的核心诉求。

**未修**：`docs/02` 的 T-4 要求"漏斗先画背面、再画正面"的**两趟 pass**（双面半透明在单 pass 里无论怎么排序都会有自遮挡伪影）。当前是单 `VertexConsumer` + `NO_CULL` + `COLOR_WRITE`（`ClientRenderHelper.java:32-45`），漏斗内壁和外壁在同一 draw 里按 quad 质心排序——**对于凹形圆柱，质心排序不是正确顺序**。

**状态**：`CLI-07` 由 P1 降为 **P2**，并拆出新条目 `CLI-14`（见下）。旧 `T-4` 仍为未修。

---

### CLI-08 【P2】已修（关闭）：`180 - yaw` 与 `WindVaneRenderer` 的 `rotLerp`

**位置**：`SailboatRenderer.java:26`（`180 - yaw`）、`WindVaneRenderer.java:39`（`Mth.rotLerp(partialTick, prev, cur)`）。

`SailboatRenderer` 用 `180 - yaw` 把 MC 的 `-sin yaw` 前向映射到模型的 `+Z` 前向；`WindVaneRenderer` 用 `rotLerp` 做跨 tick 插值，不再每 tick 跳 180°。旧报告的"船头反了"与"抖动"都已解决。**关闭。**

---

### CLI-09 【P2】部分修：光照/雾已接，但仍是 `POSITION_COLOR` 平面色

**位置**：`ClientRenderHelper.java:19-45`。

**已修**：`TornadoRenderer.vert()` 手动实现了**着色器雾**（`:221-240`，含 `FogShape.CYLINDER` 分支），等价于 `N-19` 的修复。

**未修**：
- `SailboatRenderer.java:34` 与 `WindVaneRenderer.java:25` 仍用 `FLAT_COLOR`（`POSITION_COLOR` + `NO_LIGHTMAP`）——**船和风向标在夜里自发光**，且不受光源/阴影影响。
- `FLAT_TRANSLUCENT` 的 `NO_LIGHTMAP`（`:40`）同样让龙卷风不受光照。

**状态**：`CLI-09` 保留 P2，范围收窄为"玩法实体渲染器无光照"。

---

### CLI-10 【P2】未修：气象图 ±1200 格 vs 风场 ±128 格，且用 Emoji

**位置**：`MeteorologicalMapScreen.java:14`（`MAP_RANGE_BLOCKS = 1200.0`）、`:42-44`、`:74`、`:88`、`:90`。

**分析**（在 `NET-01` 之外的新增证据）：
1. **箭头网格 9×9 = 81 个，全部走 `sampleCoarse`**，超出 ±128 格后全是 clamp 的边缘值。图上 ±1200 格的 90% 显示的是"最外圈那个格子的风"，方向完全错误。
2. **Emoji**：`"?? "`（`:74`）、`"?"`（`:88`）、`"??"`（`:90`）——源文件里就是替换字符（编码损坏），MC 默认字体没有这些码位，**实际渲染为方框/空白**。
3. **`thermalCount` / `tornadoCount` 显示了但没有对应的图形**：`:120-122` 只在底部文字里出现。
4. **`player.getY()` 被当作采样高度传进去**（`:44`），但 `sampleCoarse` 忽略 y，所以图上的风永远是"玩家所在高度那一层"的 L1——飞起来看图和站在地上看图一样。

**修复**：范围改成跟随包半径或加缩放；Emoji 换成 ASCII + 颜色；用 `tornadoCount` 画龙卷图标。

---

### CLI-11 【P2】已修（关闭）：风速计方位角

**位置**：`WindMeterItem.java:31-36`。

```java
double dirDeg = Math.toDegrees(Math.atan2(v.x(), v.z()));
double toBearing = (180 - dirDeg + 360) % 360;
double fromBearing = (toBearing + 180) % 360;
```

`dirDeg` 是"去向"（0=南，90=东）；`toBearing` 转成"北为 0、顺时针"；`fromBearing` 是"来向"。数值验证：风朝东（`v=(1,0,0)`）→ `dirDeg=90` → `toBearing=90`（东）→ `fromBearing=270`（西）——**"东风"从西边吹来，正确**。旧报告的 `(dirDeg+180)` 错误已修。**关闭。**

---

### CLI-12 【P3】未修：静态 `Random`、静态 `tickCounter`、`(int)` 截断

**位置**：`ParticleWindController.java:25-26`、`WeatherWindController.java:20-21`、`ZenithVisualizer.java:17`、`ParticleWindController.java:63`/`:74`。

- 三个客户端控制器各有一个 `static final Random`（无种子）+ `static int tickCounter`——客户端重连/换世界不清零，`tickCounter % 20` 的相位会漂。
- `new BlockPos((int) x, (int) py, (int) z)`（`:63`、`:74`）对负坐标截断向 0，与 `Mth.floor` 差 1 格。
- 影响很小（P3），但**三处重复**说明没有共用工具。

**修复**：`Mth.floor`；`tickCounter` 移到 `ClientWindState` 或在 `LoggingOut` 清零。

---

### CLI-13 【P3】分桶风暴判定只认 `isRaining()`，局部降水/雷暴无法触发（新，`CLI-13`，`N-13` 的残留）

**位置**：`WeatherWindController.java:25-37`。

```java
if (!level.isRaining()) return;
if (++tickCounter % 2 != 0) return;
...
double stormBoost = snap.typhoons().isEmpty() ? 0 : 0.8;
```

**分析**：`docs/01` 的 `N-13` 声称"`!= RAIN` 门控"已改，代码确实不再区分 `RAIN/SNOW`。但新的门控是 `level.isRaining()`——这是**全局天气**，不是"玩家头顶是否在下雨"。

**后果**：
- 局部雷暴（`StormCellSystem` 生成的单格风暴）在 `level.isRaining() == false` 时不产生任何水沫/风丝，即使玩家正站在风暴下。
- 反过来，全局下雨但玩家在沙漠/洞穴里，仍然会生成水沫（`ParticleWindController.java:85-105` 有水面检测，所以水沫本身没错，但 `WeatherWindController` 的"降水丝"会出现在没下雨的地方）。
- `snap.typhoons().isEmpty() ? 0 : 0.8`：任意台风存在就把 `stormBoost` 拉到 0.8，**不管台风在 5000 格外**。

**修复**：用 `level.getRainLevel()` 或 `Biome.Precipitation` + 玩家所在 `StormCell` 的距离判定；`stormBoost` 按台风/风暴到玩家的距离衰减。

---

### CLI-14 【P3】`TornadoRenderer` 自注册事件、未在 `ZenithClientSetup` 注册、无 LOD（新，`CLI-14`）

**位置**：`TornadoRenderer.java:27-49`（`@EventBusSubscriber` + `@SubscribeEvent` 直接监听 `RenderLevelStageEvent`）、`ZenithClientSetup.java:26-32`（只注册风向标与帆船）。

**分析**：
1. `TornadoRenderer` **不走 `EntityRenderersEvent`**，而是在 `AFTER_TRANSLUCENT_BLOCKS` 阶段自己 `bufferSource()` + `endBatch`。这本身可行，但它与 `SailboatRenderer`/`WindVaneRenderer` 用的是**两套渲染生命周期**：前者在 level 阶段手动 flush，后者由实体渲染器自动管理。
2. `ZenithClientSetup` 里**没有台风/龙卷的任何注册**——因为不需要，但也意味着"想改用实体渲染器"时要改两处。
3. **无 LOD**：`HEIGHT_SEGS=20 × RADIAL_SEGS=32 = 640 quad/tornado`，`CULL_DIST=256` 内**全部**提交。`MAX_TYPHOONS=2`，每帧最多 1280 quad——不算多，但每个 quad 都要 6 次 `vert()`（`:165-170`），每次做雾计算 + `addVertex`。**没有按距离降采样**（`docs/02` 的 P-7 未做）。

**修复**：`CULL_DIST` 分三档（64 格 20×32、128 格 12×20、256 格 8×12）；或把漏斗改成实体 + `EntityRenderer`，让 MC 的视锥裁剪接管。

---

### CLI-15 【P3】`ZenithVisualizer` 每 tick 固定 4 个粒子，且用 `sampleCoarse` 判空（新，`CLI-15`）

**位置**：`ZenithVisualizer.java:26-41`。

```java
for (int i = 0; i < 4; i++) {
    double x = px + (RANDOM.nextDouble()-0.5)*48; ...
    var v = ClientWindState.get().sampleCoarse(x, y, z);
    if (v.horizontalLength() < 0.5) continue;
    level.addParticle(WIND_STREAK.get(), x, y, z, 0, 0, 0);
}
```

**分析**：
1. **固定 4 个**，与风速无关。风速 20 m/s 和 2 m/s 画出来一样密。
2. 采样点分布在 `48×16×48` 的盒子里，但 `sampleCoarse` 只做 x/z 插值 → 垂直方向 4 个粒子拿的是同一层的风。
3. `v.horizontalLength() < 0.5` 判据与 `ParticleWindController.java:48` 的 `speed > 3.0` 不一致——两个可视化通道的"有风"阈值差 6 倍。
4. 只在 `/zenithc visualizer` 开启时跑，默认关闭——所以这是**调试工具**，不是主视觉。

**修复**：`count = clamp(1, 12, (int)(speed))`；阈值统一；或直接删掉（`ParticleWindController` 已覆盖）。

---

### CLI-16 【P3】Mixin 覆盖面极窄：只有火焰与营火烟（新，`CLI-16`）

**位置**：`ParticleMixin.java:26-37`（`instanceof FlameParticle`）、`CampfireSmokeParticleMixin.java:11-28`、`zenith.mixins.json`（3 个类）。

**分析**：`ParticleMixin` 注入 `Particle.tick` 的 `TAIL`，但第一行就 `if (!(((Object) this) instanceof FlameParticle)) return;`（`:28-30`）。**全 Minecraft 的粒子（烟、云、雨、雪、爆炸、岩浆、末地烛……）都不受风影响**，只有火焰和营火烟。

**后果**：玩家在 20 m/s 的风里看篝火，烟被吹走；但看**普通火把的烟、熔炉的烟、营火的余烬**——只有 `CampfireSmokeParticle` 被单独 mixin 覆盖了。视觉上"风只对两种粒子生效"，与 `docs/02` 的"风场可视化"目标不符。

**修复**：把 `instanceof FlameParticle` 换成"白名单 + 排除列表"，或用 `ParticleRenderType` 判定；至少覆盖 `SmokeParticle`、`CampfireSmokeParticle`、`LavaParticle`、`AshParticle`、`WhiteAshParticle`、`SnowflakeParticle`。

---

## 12. 玩法（`block/` + `entity/` + `item/` + 指令）

### 12.1 帆船（`SailboatEntity` / `SailingPhysics`）

**0.1.4 修好了什么**：`isPickable() = true`（`SailboatEntity.java:152`）与完整的 `hurt()`（`:156-171`，含 `getHurtDir`/`getHurtTime`/`getDamage` 与破坏掉落），这两个是 `SAIL-01` 的全部内容。**关闭 `SAIL-01`。**

### SAIL-02 【P1】朝向约定仍与 MC 不一致，靠渲染器 `180-yaw` 硬掰（未修，`SAIL-02`）

**位置**：`SailboatEntity.java:127`（`setYRot(toDegrees(headingRadians))`）、`SailingPhysics.java:63`（`fwd = (sin h, cos h)`）、`SailboatRenderer.java:26`（`180 - yaw`）。

**分析**：三处约定互不相同：

| 位置 | 前向矢量 | 备注 |
|------|----------|------|
| MC 实体 `yaw` | `(-sin yaw, cos yaw)` | yaw=0 → +Z |
| `SailingPhysics` | `(sin h, cos h)` | h=0 → +Z，但与 MC 差一个 x 符号 |
| `SailboatRenderer` | `180 - yaw` | 用旋转补偿 |

`h = 90°` 时：物理前向是 `(+1, 0)`（东），`setYRot(90)` 让 MC 前向变成 `(-1, 0)`（西），渲染器再 `180-90 = 90` 转回来……**结果是"物理往东、实体碰撞箱朝向标西、模型朝东"**。三者里有两个对、一个错，视觉上碰巧对了。

**后果**：
- `getBoundingBox()` 与视觉不总一致（`Entity` 的 AABB 不旋转，但 `getViewVector`/`getLookAngle` 会）；
- 任何第三方按 `boat.getYRot()` 推断船头方向的代码（拖拽、海盗 AI、hooking）会得到反的；
- `player.xxa` 的控制到 `rudder = -xxa * 0.9`（`:105`）之间还夹了一个符号翻转，A/D 是否"左打左转"需要实测，但代码里没有任何注释解释这个负号。

**修复**：统一到 MC 约定——`fwd = (-sin h, cos h)`，`headingRadians` 直接等于 `yaw` 弧度，删掉渲染器的 `180 - yaw`，`rudder` 符号重算一遍。

---

### SAIL-03 【P1】`setPos` 直接位移，无碰撞、无 `move()`（未修，`SAIL-03`）

**位置**：`SailboatEntity.java:89`（`setPos(getX(), targetY, getZ())`）、`:126`（`setPos(getX() + velX*dt, getY(), getZ() + velZ*dt)`）。

**分析**：每 tick 两次 `setPos`，完全绕过 `move(MoverType.SELF, ...)`：
- **可以穿墙**：14 m/s × 0.05 s = 0.7 格/tick，比 1 格薄墙还快，直接穿过去；
- **可以上岸**：`findWaterSurface` 只在 `[y-3, y+2]` 找水（`:131`），船头一离开水面就 `return NaN` → 走 `:82-87` 的"下落分支"，用 `move()` 掉下去，但**水平速度 `velX/velZ` 不清零**，落水后继续带着旧速度；
- **不推实体**：MC 的船会推开玩家/掉落物，这里不会；
- **不触发 `Entity.move` 的 `onGround`/`verticalCollision` 等状态**，`isInWater()` 之类的判定全是错的。

**修复**：用 `move(MoverType.SELF, new Vec3(velX*dt, 0, velZ*dt))`，并把 `setPos(y)` 的"吸附水面"改成 `setDeltaMovement(getDeltaMovement().add(0, (targetY-y)*0.5, 0))` 的软约束。

---

### SAIL-04 【P1】横向阻力系数与质量把衰减时间常数拉长到约 50 s（未修，`SAIL-04`）

**位置**：`SailingPhysics.java:63-70`。

```java
double lateralVel = velX*fwdZ - velZ*fwdX;
fx -= fwdZ * lateralVel * LATERAL_RESISTANCE_K * 0.05;
fz += fwdX * lateralVel * LATERAL_RESISTANCE_K * 0.05;
double mass = 800.0;
```

**分析**：`LATERAL_RESISTANCE_K` 的实际值需读常量区（下同），但结构上：横向力 ∝ `lateralVel`（线性），除以 `mass = 800` 后得到加速度。旧报告算过 `320 × 0.05 = 16 N/(m/s)` → `τ = m/c = 800/16 = 50 s`。

**后果**：松开舵后船会持续侧滑近一分钟才停。真实小帆船的横向衰减时间常数是 **1–2 s**（龙骨/稳向板的阻尼极大）。玩家感觉"船像在冰上"。

**修复**：横向阻尼提到 `c ≈ 400–800 N/(m/s)`（τ ≈ 1–2 s），并把 `0.05` 这个魔数从力公式里拿掉（它看起来是"每 tick 的 dt"，但 `step()` 返回的是**加速度**，dt 在外面乘——所以这 0.05 被乘了两次）。

---

### SAIL-05 【P2】顺风时推进系数趋近 0.1，帆船几乎无法顺风航行（未修，`SAIL-05`）

**位置**：`SailingPhysics.java:37-45`。

```java
double t = (absPoint - NO_GO_ZONE) / (PI - NO_GO_ZONE);
driveCoeff = Math.sin(PI * min(1, 0.15 + t*0.85)) * 0.9 + 0.1;
driveCoeff *= 0.55 + 0.45 * t;
```

**分析**：`t=1`（正顺风）时 `sin(π·1) = 0` → `driveCoeff = 0.1`，再乘 `1.0` → **0.1**。`t=0.5`（横风）时 `sin(π·0.575) = 0.976` → `≈ 0.98`。所以**横风比顺风快 10 倍**。

这在真实帆船里是对的方向（横风最快），但 0.1 太极端：真实帆船顺风仍有 40–60% 的横风速度（因为有球帆/蝴蝶帆）。另外 `driveCoeff` 在上式里**永远不为 0**（最小值 0.1），只有 `absPoint < 30°` 的死区里才归零（`:38-39`）——即"正顶风停、偏 1° 就突然有 10% 推力"，这是一个**阶跃**。

**修复**：`driveCoeff = max(0, sin(π·t)^0.7) * 0.85 + 0.15*cos(...)`；死区边界做 `smoothstep` 过渡；顺风分支单独给 `0.45`。

---

### SAIL-06 【P2】权威在服务端，但客户端预测缺失；`setPos` 与 `EntityData` 双写（未修，`SAIL-06`）

**位置**：`SailboatEntity.java:72-76`（客户端只读 `DATA_HEADING` 然后 `return`）、`:124`（服务端写 `DATA_HEADING`）、`:126`（服务端 `setPos`）。

**分析**：客户端 `tick()` 在 `:72-76` **直接 return**，只把 `DATA_HEADING` 同步到 `setYRot`。位置的同步靠 MC 内置的 `ClientboundSetEntityMotionPacket`/位置包（每 tick 最多一次 + 插值）。

**后果**：在 100 ms 延迟下，船的位置比服务端晚 2 tick，转向晚 2 tick。视觉上是"船在漂移然后突然对齐"。更严重的是**输入延迟**：`player.zza` 在服务端读（`:107`），玩家按 W 到帆收满要 `1/0.008 = 125 tick = 6.25 s`。

**修复**：客户端做本地预测（同样的 `SailingPhysics.step`），服务端权威校正；或至少把 `trim` 的变化率提到 `0.05/tick`。

---

### SAIL-07 【P2】用 `meanVelocity`（不含阵风）且采样点在 `y+1`（未修，`SAIL-07`）

**位置**：`SailboatEntity.java:92-99`。

```java
GameplayWindSample wind = ZenithMinecraftWindApi.sampleGameplay(
        serverLevel, position().add(0, 1, 0));
...
ZenithVec3 v = wind.meanVelocityVector();
```

**分析**：两点：
1. `meanVelocityVector()` 是 **L1 均值**，不含 `gustVelocity` 也不含 `localModifier`。帆船完全感受不到阵风——而阵风恰恰是帆船最需要的（`gustVelocity` 在 `MesoscaleGrid` 里由噪声生成，幅度可达均值的 30%）。
2. `position().add(0,1,0)` 在 `SYS-03` 的坐标系下是"船体上方 1 格"，但 `MesoscaleGrid.sample` 用 `blockY - c.terrainHeight` 算 AGL（`MesoscaleGrid.java:218`），而 `terrainHeight` 由 `McTerrainAdapter` 提供（**高 1 格**，`SYS-12`）→ 实际 AGL 少了 1 格。
3. 船体高度约 0.5 格，帆高 2.6 格，但采样点固定在 `y+1`——**帆船的风应该取帆的中心高度（约 y+1.8）**。

**修复**：`effectiveVelocityVector()`（含阵风）或至少 `velocityWithGustVector()`；采样点 `y + 1.8`。

---

### SAIL-08 【P3】放置时 `setYRot(playerYaw)` 但 `headingRadians = 0`（未修，`SAIL-08`）

**位置**：`SailboatItem.java:35`（`boat.setYRot(player.getYRot())`）、`SailboatEntity.java:44`（`headingRadians = 0` 未初始化其它值）。

**分析**：服务端第一次 `tick()` 走到 `:127` 时 `setYRot(toDegrees(headingRadians))`，`headingRadians` 初始 0 → **船瞬间转到正南**。玩家放下船后看到它自己转 180°。

**修复**：`SailboatItem` 里同时设 `boat.setHeading((float) Math.toRadians(player.getYRot()))`，或在 `SailboatEntity` 构造/`readAdditionalSaveData` 后从 `getYRot()` 初始化 `headingRadians`。

---

### SAIL-09 【P1】水线公式对流动水错 0.11 格，且 `findWaterSurface` 的搜索窗只有 6 格（新，`SAIL-09`）

**位置**：`SailboatEntity.java:130-139`。

```java
for (int dy = 2; dy >= -3; dy--) {
    BlockPos p = pos.offset(0, dy, 0);
    if (level().getFluidState(p).is(Fluids.WATER)) {
        while (level().getFluidState(p.above()).is(Fluids.WATER)) p = p.above();
        return p.getY() + 1 - level().getFluidState(p).getOwnHeight();
    }
}
```

**分析**：
1. **`getOwnHeight()` 对流动水返回 `0.8 - flow/…`**，源水为 `0.875`，流动水最低约 `0.1`。所以 `p.getY() + 1 - ownHeight` 在**源水**上给出 `y + 0.125`（水面在 `y + 0.875`，即 `+1 - 0.875 = +0.125`？错——MC 的水面高度是 `y + ownHeight`，不是 `y + 1 - ownHeight`）。**正确公式是 `p.getY() + ownHeight`**。当前公式给出 `y + 0.125`，而真实水面是 `y + 0.875`，**差 0.75 格**（船沉在水下 3/4 格）。若 `ownHeight = 0.875`，`1 - 0.875 = 0.125`。这是明确的公式错误。
2. 加上 `targetY = waterY + 0.15`（`:88`），最终船体在 `y + 0.275`，而水面在 `y + 0.875` → **船体 60% 在水下**。
3. **搜索窗 `dy ∈ [-3, +2]`**：船在 1 格深的浅水、或被推上 2 格高的浪（不可能）时找不到水 → `NaN` → 走下落分支。更重要的是**船在流动水（河流/海洋边缘）里 `ownHeight` 变化**，`targetY` 每 tick 抖 0.1–0.7 格。

**后果**：帆船在所有水域都偏沉，且在水流边缘上下抖动。这是"新"问题（0.1.4 没有改动这段），但旧报告的 `SAIL-*` 系列没覆盖公式本身，只在 §13.1 的表格里说"水线偏移"。

**修复**：`return p.getY() + level().getFluidState(p).getOwnHeight();`；搜索窗改 `[-5, +5]`；`targetY` 偏移改成 `+0.05`（船体吃水 0.3 格）。

---

### SAIL-10 【P2】`SailboatItem` 用 `SOURCE_ONLY` 却在任意方块命中点放置（新，`SAIL-10`）

**位置**：`SailboatItem.java:29-35`。

```java
BlockHitResult hit = getPlayerPOVHitResult(level, player, ClipContext.Fluid.SOURCE_ONLY);
if (hit.getType() == HitResult.Type.BLOCK) {
    Vec3 pos = hit.getLocation();
    boat.setPos(pos.x, pos.y + 0.2, pos.z);
```

**分析**：`SOURCE_ONLY` 让射线**穿过流动水**只停在源水方块上，但**也能停在任意固体方块上**（`ClipContext.Fluid` 只影响流体，不影响方块）。所以：
- 对着**草地**右键 → `hit` 是草地方块 → 在草地表面放一条船 → 船 tick 时 `findWaterSurface` 返回 `NaN` → 掉下去，卡在地里；
- 对着**树叶/半砖**右键 → 同样放船；
- 对着**流动水**右键 → 射线穿过，可能命中水下的沙 → 船放在沙上。

只有 `nearby.stream().noneMatch(...)`（`:37-38`）这一层去重，没有"必须在水中"的检查。

**修复**：`if (!level.getFluidState(BlockPos.containing(pos)).is(Fluids.WATER)) return pass(stack);`；或改用 `ClipContext.Fluid.ANY` 并对命中点做水位校验。

---

### 12.2 方块（`FanBlock` / `DuctBlock` / `WindVaneBlock` / `WindTurbineProbeBlock`）

### BLK-01 【P1】已修（关闭）：风扇射流注册在 `speed == 0` 与 `setRemoved` 时都会注销

**位置**：`FanBlockEntity.java:59-75`（`speed <= 0` → `unregisterAt`）、`:77-85`（`setRemoved` → `unregisterAt`）。

`outletPos()` 被抽成方法（`:43-50`），注册与注销用同一个计算，不再有"0.6 格偏差"导致的注销失败。旧报告的"关不掉/泄漏"已修。**关闭。**

### BLK-02 【P2】已修（关闭）：红石覆盖与用户档位分离

**位置**：`FanBlockEntity.java:19`（`redstoneOverride`）、`:27-32`（`setRedstoneOverride`）、`:61`（`redstoneOverride ? 3 : state.getValue(SPEED)`）。

红石只覆盖**有效档位**，不改 `SPEED` blockstate；断电后 `redstoneOverride = false` → 回到用户档位。旧报告的"断电后回不到原档位"已修。**关闭。**

### BLK-03 【P2】风道不接力：射流只在 `localModifier` 里，`meanVelocity` 不含它（未修，`BLK-03` → `BLK-07`）

见 `BLK-07`，合并。

### BLK-04 【P2】涡轮探针读 `effectiveSpeed`，受 L2 伪上升与现象层污染（未修，`BLK-04`）

**位置**：`WindTurbineProbeBlockEntity.java:22-33`。

```java
power = (int) Math.min(15, Math.round(wind.effectiveSpeedMetersPerSecond() / 18.0 * 15));
```

**分析**：`effectiveSpeed = |mean + gust + localModifier|`。它把**三个不同物理量**加在一起：
- `mean`：真正的风；
- `gust`：阵风（应当是"峰值"，不是"持续功率"）；
- `localModifier`：**L2 的伪上升**（`L2-01` 在 `base=5` 时眼高 `+11.2 m/s`）。

**后果**：一个 12 m/s 的风扇（`SPEED_MPS[3]`）对着探针吹，`localModifier` 会让 `effectiveSpeed` 报 23 m/s → 输出 15（满格）。而探针周围**实际平均风可能只有 3 m/s**。风力发电机的功率 ∝ v³，用 `effectiveSpeed` 会让输出极度不稳（阵风每 tick 抖动）。

**修复**：用 `meanSpeedMetersPerSecond()`（或 10 分钟滑动平均），不要 `effective`；`gust` 单独作为"过载"信号；把 `L2` 的 `localModifier` 从"功率输入"里排除（它是遮蔽/绕流修正，不是新增能量）。

---

### BLK-05 【P1】已修（关闭）：`data/` 现已有 7 个配方 + 4 个战利品表 + `mineable/pickaxe` 标签

**位置**：`data/zenith/recipe/`（7 个）、`data/zenith/loot_table/blocks/`（4 个）、`data/minecraft/tags/block/mineable/pickaxe.json`（4 个方块）。

旧报告的"破坏无掉落、无配方、无标签"全部解决。**关闭。**

### BLK-06 【P3】已修（关闭）：`WindVaneBlock.getTicker` 仅客户端返回 ticker

**位置**：`WindVaneBlock.java:55-61`。

```java
return level.isClientSide ? (lvl, pos, st, be) -> { ... } : null;
```

服务端不再为纯视觉的 yaw 付 tick 成本。**关闭。**

### BLK-07 【P2】风道/风扇串联不接力：射流只进 `localModifier`，而风道取 `meanVelocity`（新编号，`BLK-07`，合并旧 `L2-04`/`BLK-03`）

**位置**：`DuctBlockEntity.java:27-46`、`FanBlockEntity.java:71-74`、`LocalFlowSolver.java:117-130`、`ZenithServerRuntime.java:337`。

**分析**：这是本模组**玩法层最核心的断裂**，值得完整走一遍数据流：

```
FanBlockEntity.tick()
   → JetSourceRegistry.register(jet)                    ← 射流进了全局注册表
LocalFlowSolver.solve()
   → jets.jetsNear(...) → modX/modY/modZ += ...         ← 射流只加到 localModifier
ZenithWindSample
   → .meanVelocity(L1) .localModifier(L2)               ← 两者分开存
DuctBlockEntity.tick()
   → wind.meanVelocityVector()                          ← 只取 meanVelocity！
   → speed = v.length()                                 ← 射流贡献 = 0
   → if (speed < 0.8) unregisterAt(...)                 ← 所以风道永远注销自己
```

**结论**：**风道永远读不到风扇的风**。风扇的射流在 `meanVelocity` 里不存在，风道看到的是"环境风"，环境风 < 0.8 m/s 时（洞穴、室内、无风天）风道直接不注册射流。即使环境风够大，风道注册的射流强度也是 `min(14, ambient×1.15)`——**与风扇无关**。

同理，**风扇串联**（风扇 A 吹风扇 B）：B 的 `outletPos()` 处的 `localModifier` 里有 A 的射流，但 B 的 `FanBlockEntity` 用的是 `state.getValue(SPEED)`（用户档位），**根本不读风**。所以风扇也不接力。

**只有一种情况能"看起来工作"**：风扇对着**玩家**吹——玩家的移动由 `ZenithMinecraftWindApi.gameplayWindAt`? 不，`Player` 的移动是原版。实际上 `localModifier` 只影响：`/zenith wind` 的显示、涡轮探针（`effectiveSpeed`）、风速计（`effectiveVelocity`）、粒子（客户端不含 L2）。**风扇对玩家和实体没有任何物理作用。**

**修复**：
1. `DuctBlockEntity` 改用 `effectiveVelocityVector()`（或专门的"管道风"采样：`mean + localModifier`，排除 gust）；
2. 或者更正确：给 `LocalFlowSolver` 加"射流上游采样"，让风道能感知"我处在别人的射流里"；
3. 风扇/风道的射流应当同时写入 `meanVelocity`（作为"强制对流"），而不只是 `localModifier`——否则任何用 `mean` 的消费者都看不到。

---

### BLK-08 【P3】`FanBlock.SPEED_MPS` 与 `Jet` 的 `length` 不成比例，且 `lightLevel(s -> 0)` 是空操作（新，`BLK-08`）

**位置**：`FanBlock.java:29-30`（`SPEED_MPS = {0, 4, 8, 12}`）、`FanBlockEntity.java:70`（`length = 10 + speedLevel*8`）、`ZenithBlocks.java:44`（`.lightLevel(s -> 0)`）。

**分析**：
1. `length = 10 + level*8` → 档位 1/2/3 = 18/26/34 格。而 `SPEED_MPS` 是 4/8/12。射流长度与速度**线性相关但不成立**（真实射流的射程 ∝ `v·D`，D 固定时应 ∝ v）。4 m/s 吹 18 格、12 m/s 吹 34 格，比例是 4.5 vs 2.8。
2. `.lightLevel(s -> 0)` 是 MC 的默认值，**显式写成 0 没有任何效果**，反而让读者以为"风扇会发光但被关掉了"。
3. `SPEED_MPS` 是 `public static final float[]`——**可变数组暴露为 public**，任何代码都能改它。

**修复**：`length = 8 + 6*level`（或按 `v` 算）；删掉 `lightLevel`；改 `List<Float>` 或 `private static final` + getter。

---

### 12.3 指令

### CMD-01 【P0】已修（关闭）：`storm`/`typhoon`/`thermal` 已加 `requires(hasPermission(2))`

**位置**：`ZenithCommands.java:80`、`:90`、`:100`。

```java
.then(Commands.literal("storm").requires(src -> src.hasPermission(2)).executes(...))
.then(Commands.literal("typhoon").requires(src -> src.hasPermission(2)).executes(...))
.then(Commands.literal("thermal").requires(src -> src.hasPermission(2)).executes(...))
```

三个破坏性命令都已加权限门。**关闭。**

### CMD-02 【P3】部分修：`status`/`wind`/`seabreeze`/`polar` 已用 `translatable`，但 `wind_meter`/`fan_speed` 仍是 `String.format`

**位置**：`ZenithCommands.java:52-60`、`:73-78`（`Component.translatable` + `f()` 格式化）、`WindMeterItem.java:37-40`（`String.format` 后塞进 `translatable` 的参数）、`FanBlock.java:53-54`（同理）。

**分析**：
- 指令侧已改好：`Component.translatable("zenith.command.status", ...)`，参数用 `f()` 保留小数。
- **但风速计与风扇档位仍是"服务端 `String.format` 成字符串 → 当参数传给 `translatable`"**：
  ```java
  Component.translatable("message.zenith.wind_meter",
      String.format("%.1f", speed), compass, String.format("%.0f", fromBearing), ...)
  ```
  这在功能上能显示，但 `%.1f` 是**硬编码的英文小数点格式**，且 `compass16()` 返回 `"N"/"NNE"` 等**英文字面量**（`WindMeterItem.java:51-52`），中文客户端看到的是 "风 12.3 m/s，NNE 22°"。`zh_cn.json` 里 `message.zenith.wind_meter` 的译文是 `"风 %s m/s，%s %s°）湍流 %s"`——**括号不配对**（只有右括号）。

**后果**：中文玩家的风速计永远显示 "风 12.3 m/s，NNE 22°）湍流 15%"（多一个右括号），且方位是英文缩写。

**修复**：把 16 个方位做成 `zenith.compass.n/nne/...` 的 `translatable`；小数位数用 `NumberFormat` 按 locale 或直接用 `%.1f` 但走 `Component.literal`；修正 `zh_cn.json` 的括号。

---

## 13. 资源与构建

### 13.0 盘点（实测）

| 类别 | 数量 | 说明 |
|------|------|------|
| `src/main/resources` 总文件 | **57** | 含 38 个 JSON |
| `assets/zenith` | **42** | blockstates 4 / models 11 / lang 2 / particles 7 / textures 15 / sounds 1 / `sounds.json` |
| `data/zenith` | **12** | recipe 7 / loot_table 4（+1 空目录占位） |
| `data/minecraft` | **1** | `tags/block/mineable/pickaxe.json` |
| `META-INF` | **1** | `neoforge.mods.toml` |
| 根 | **2** | `pack.mcmeta`、`zenith.mixins.json` |
| Java | **106 文件 / 8,348 行** | `src/main/java`，无 `src/test` |

**模型/贴图引用完整性**：11 个 block/item 模型 + 4 个 blockstate 的全部 `parent`/`textures` 引用均能在 `textures/` 找到对应 PNG（逐项核对 48 条引用，**0 断链**）。7 个 `particles/*.json` 的 `textures` 字段都指向存在的 `particle/*.png`。**这一块质量很好。**

**语言文件**：`en_us.json` 与 `zh_cn.json` **各 26 键，差集为空**（脚本核对）。但存在内容缺陷，见 `ENG-08`。

---

### ENG-01 【P1】部分修：README 与 LICENSE 仍然互相矛盾

**位置**：`README.md:68`、`LICENSE:19-22`。

**README:68（原文）**：
> 本模组**禁止修改**、禁止二次分发。

**LICENSE:19-22（原文）**：
> 3. 允许在**不二次分发**的前提下修改
>    你可以改代码、做魔改版自用，前提是：保留原作者署名（2025STELLU），并在显著位置注明"基于 Zenith 修改"，修改的文件要在文件头注明说明了什么。

**分析**：`LICENSE` 第 3 条**明确允许修改**（只要不二次分发），README 第 68 行说"禁止修改"。二者直接冲突。而 `neoforge.mods.toml` 写的是 `license="All Rights Reserved"`——**第三种说法**（保留所有权利，通常理解为不允许任何派生）。

**后果**：这不是"文档笔误"，是**发布合规问题**。用户/整合包作者无法判断自己能不能改；如果发生纠纷，三份文件互相矛盾会让权利主张变弱。

**修复**：三处统一到一份 `LICENSE`。若真的要"禁止修改"，删掉 `LICENSE` 第 3 条；若要"允许自用修改"，改 README:68 与 `mods.toml` 的 `license`（`All Rights Reserved` 应改为自定义 SPDX 或 `LicenseRef-Zenith`）。

---

### ENG-02 【P1】已修（关闭）：wrapper jar 已提交，`.gitignore` 有 `!gradle/wrapper/gradle-wrapper.jar`

**位置**：`gradle/wrapper/gradle-wrapper.jar` 存在（实测）、`.gitignore` 末段 `!gradle/wrapper/gradle-wrapper.jar`。

旧报告的"仓库缺 wrapper jar，`./gradlew build` 直接失败"已解决。**关闭。**（本次构建失败是另一原因，与 wrapper 无关，见 §17。）

---

### ENG-03 【P1】仍然没有任何配置系统（未修，`ENG-03`）

**位置**：全代码库 grep `ModConfigSpec|ConfigTracker|registerConfig` → **0 命中**；`ZenithMod.java:31-48` 无 `modContainer.registerConfig(...)`。

**分析**：所有可调参数都是 `public static final` 常量：
- 网格尺寸（`MesoscaleGrid.RADIUS_CELLS = 16`、`LAYER_HEIGHT_BLOCKS = 40`）
- 刷新周期（`COARSE_WIND_SYNC_TICKS = 40`、`MESOSCALE_REFRESH_TICKS = 64`）
- 物理系数（`GEOSTROPHIC_WIND_SCALE = 12.0`、`FLOW_RELAXATION_PER_SECOND = 1/90`）
- 玩法参数（`SPEED_MPS`、`SAIL_AREA_M2`）

**后果**：
- 服务器管理员无法为低配服务器降网格（33×33×8 是固定成本）；
- 整合包作者无法关掉台风（有些整合包不想要）；
- 玩家无法调雾浓度/粒子密度；
- **这是"服务端性能问题"的唯一无代码修复路径**，但不存在。

**修复**：`ModConfigSpec.Builder` + `ModConfig.Type.SERVER`（网格/刷新/天气开关）+ `CLIENT`（粒子/雾/音效）；至少暴露 `gridRadius`、`refreshTicks`、`enableTyphoon`、`particleDensity`。

---

### ENG-04 【P2】仍然没有任何单元测试（未修，`ENG-04`）

**位置**：`src/test` 不存在（实测）。

**分析**：`build.gradle` 里也没有 `testImplementation`（`dependencies {}` 是空的，`:44-46`）。而本报告 §4–§7 的**每一条数值结论都是手工算的**——`L2-01` 的 `+11.2 m/s`、`L0` 的地转风符号、`WX-TY` 的 13.09 m/s、`runPolar` 的 `cd(90°) = 25.9`。这些**全部可以用纯 JUnit 复现**（`LocalFlowSolver`、`BackgroundMetGrid`、`MesoscaleGrid`、`SailingPhysics` 都不依赖 MC 类）。

**后果**：没有回归网。`docs/03` 的 0.2 计划第 1 周就是"写测试"，说明作者自己也知道——但 0.1.4 没做。

**修复**：见 `docs/03` §2 第 1 周清单，本报告完全同意其优先级。

---

### ENG-05 【P2】`tools/gen_textures.py` 写死 `~/workspace/zenith`（未修，`ENG-05`）

**位置**：`tools/gen_textures.py:5`。

```python
ROOT = os.path.expanduser("~/workspace/zenith/src/main/resources/assets/zenith/textures")
```

**分析**：作者本地路径。任何其他人（或 CI）跑这个脚本都会在 `$HOME/workspace/zenith/...` 下创建一棵新树，**不会更新仓库里的贴图**。且脚本用 `random.seed(20261006)` + 纯 Python 写 PNG（`:8-19`），无 Pillow 依赖——这点很好，但路径让它不可移植。

**修复**：`ROOT = Path(__file__).resolve().parent.parent / "src/main/resources/assets/zenith/textures"`。

---

### ENG-06 【P3】注释里的口语化表达与不成立的断言（未修，`ENG-06`）

**位置（抽样）**：
- `LocalFlowSolver.java:8-11`：把原实现的 D3Q27 cumulant LBM 描述为"其实是个玩具/只能糊弄"，但没有给出任何引用或对比；
- `ZenithServerRuntime.java:178-181`：一段**已被注释掉的**崩溃分析（"维度状态会 pin 住 ServerLevel"），内容与当前 `dimensions.clear()` 实现重复；
- `MesoscaleGrid.java:9`："迎风坡加速、背风坡减速"——但 `:114-160` 的分层循环里**没有地形坡度对风的加速项**（只有 `terrainFormDrag`），注释与代码不符；
- `SailboatEntity.java:69`："原版船就是这么做出来的"——原版 `Boat` 用的是 `float waterLevel` + `move()`，不是 `setPos`。

**后果**：P3，但对"独立审计"而言，**误导性注释比没有注释更糟**——它会让后续维护者以为某功能已实现。

**修复**：删掉/改写这四处；对"与原实现的差异"给出具体引用。

---

### ENG-07 【P3】发布元数据仍缺 `displayURL` / `logoFile` / `issueTrackerURL`（未修，`ENG-07`）

**位置**：`src/main/resources/META-INF/neoforge.mods.toml` 的 `[[mods]]` 段。

**分析**：缺少 `displayURL`（项目主页）、`logoFile`（ModMenu/模组列表图标）、`issueTrackerURL`（问题反馈地址）。`license="All Rights Reserved"` 与 `README`/`LICENSE` 的三方冲突见 `ENG-01`。

**后果**：玩家在模组列表里看到的是空白图标与无链接条目；整合包作者无从确认来源；问题反馈没有入口。发布合规层面这是**元数据残缺**，不影响运行。

**修复**：补三段字段，`license` 按 `ENG-01` 的统一结论回填。

> 旧报告把本条与"构建能不能过"混在同一个编号里。本次审计的构建失败**不是代码问题**（依赖解析阶段网络不可达），已从本条剥离，单独记录在 **§17 构建未验证**。因此本条由 P1 降为 P3。

---

### ENG-08 【P3】语言文件内容缺陷：中文括号不配对、方位硬编码英文（新，`ENG-08`，并入 `CMD-02` 的客户端侧）

**位置**：`assets/zenith/lang/zh_cn.json`（`message.zenith.wind_meter`）、`assets/zenith/lang/en_us.json`（同键）。

**分析**：
- `zh_cn` 的 `message.zenith.wind_meter` 值形如 `"风 %s m/s，%s %s°）湍流 %s"`——**开头没有 `（`，结尾有 `）`**；
- 16 方位（`WindMeterItem.compass16`）是英文字面量，两语言共用；
- `en_us` 与 `zh_cn` 键集合一致（26 个），**这是好的**，但值的质量不一致。

**后果**：中文客户端的风速计显示 `风 12.3 m/s，NNE 22°）湍流 15%`。

**修复**：改 `zh_cn` 的该键为 `"风 %s m/s，%s %s°，湍流 %s"`；方位改 `translatable`。

---

### 13.1 资源层小结

**做得好**：
- 引用完整性 100%（48 条模型/贴图引用零断链）；
- 双语言键集合严格对齐（26/26，差集 0）；
- 配方/战利品表/挖掘标签齐全（`BLK-05` 已修）；
- `pack.mcmeta` 的 `pack_format: 48` 与 1.21.1 匹配；
- `zenith.mixins.json` 的 `compatibilityLevel: JAVA_21`、`minVersion: 0.8`、`injectors.defaultRequire: 1` 均正确。

**待补**：
- `neoforge.mods.toml` 缺 `displayURL`、`logoFile`、`issueTrackerURL`（`ENG-07`，P3）；
- `sounds.json` 只有 1 条 `wind_loop`，`ZenithSounds` 也只注册 1 个 SoundEvent——**没有雷声、雨声、冰雹声、龙卷风声**，`docs/02` 的"风声分层"（S-3/S-4）无从实现；
- `textures/` 全部为 `gen_textures.py` 生成的程序化占位图（16×16 块、8×8 物品），无模型实体贴图（帆船/龙卷风是纯色三角）；
- 无 `data/zenith/tags/`（如 `zenith:wind_permeable`），未来做"可透风方块"时才有用。

---

## 14. 旧问题状态矩阵（对照 `Hark/01` 的 126 个编号）

### 14.0 口径与阅读方式

- **基准台账**：`Hark/01_Zenith_全模组代码审计报告.md`。脚本枚举实测 **126** 个编号，家族分布：SYS 11、DRV 7、L0 8、L1 6、L2 5、TH 5、SB 6、SC 5、TY 7、FR 1（仅 `FR-02`）、SQ 3、DD 2、MW 3、RT 6、API 10、NET 6、CLI 12、SAIL 8、BLK 6、ENG 7、CMD 2。
- **三态分离**：已关闭 **24** + 部分修 **5** + 未修 **97** = **126**（三重核验自洽，见 §1.3）。
- **`MS-01` 不存在**：旧报告的季风只有 §8.8 的散文，没有编号（`Hark/01`、`02`、`03` 中 `grep MS-01` 均 0 命中）。本次首次给号 `WX-MS-01`，不计入 126，也不在 §14.3。
- **级别列一律用旧报告的级别**（当时判定）。级别调整（如 `DRV-03` P0→P1、`SYS-08` P1→P2、`ENG-07` P1→P3）在"本版编号"列后注明。
- 三处"旧报告有描述但未单列成行"的条目（`TH-04`、`SB-01`、`NET-04`）在 §14.3 中标注「并入」，其旧描述确实存在，故计入 126。
- 同一旧编号可能对应多个本版标题（拆分），也可能多个旧编号对应一个本版标题（合并），因此**总数不能简单配平**；逐编号对照才是唯一可靠口径。

### 14.1 已关闭：24 条

| 旧编号 | 旧级别 | 关闭证据（`文件:行`） |
|--------|--------|------------------------|
| `SYS-02` | P1 | 7 处日相位全部收敛到 `FogModel.solarFactor()`；实测 13 个引用点：`FogModel.java:41`、`BackgroundMetGrid.java:173-174`、`MesoscaleGrid.java:142`、`WorldScaleDriver.java:98-99`、`ThermalSystem.java:61-62`、`SeaBreezeSystem.java:42-43`、`DustDevilSystem.java:47-48`、`FogController.java:81`、`ZenithServerRuntime.java:590`。`grep "PI / 2"` 无残留 |
| `SYS-04` | P1 | 单位统一到 m/s：公共 API 全部字段带单位后缀（`airTemperatureKelvin`、`vmaxMps`、`tangentialMps`、`tempBiasKelvin`）；m/s → block/tick 的换算只在 `ZenithParticleProviders.java:39-61,87-89` 显式 `/ 20.0`，无隐式混用 |
| `SYS-07` | P0 | `ZenithServerRuntime.java:182-188`（`ServerStoppedEvent` 清 `dimensions` + `JetSourceRegistry`）、`JetSourceRegistry.java:20-27`（按维度分桶）、`ClientWindState.java:55-77`（`LoggingOut`/`Clone` 清理） |
| `DRV-04` | P0 | 龙卷坐标先乘 `CELL_SIZE_BLOCKS` 再进包（`WeatherSnapshotPacket.java:199`），客户端不再画到原点 |
| `TH-01` | P1 | 热泡日相位改走 `solarFactor`（`ThermalSystem.java:61-62`），随 `SYS-02` 一并关闭 |
| `SQ-01` | P1 | 即旧报告的组合编号 `FR/SQ-01`：锋面线走向与法向符号一致（`FrontSystem.java:72-87`），"线方向与法向互相矛盾"消失 |
| `SQ-02` | P1 | 阵风锋端点不再无限延伸：沿线投影参与判定（`SquallLineSystem.java:203-208`） |
| `NET-02` | P1 | 快照内风速单位随 `DRV-04` 改为 m/s（`WeatherSnapshotPacket.java:199`） |
| `API-08` | P3 | `ZenithWindSample.Builder` 的 `hasTemperature=false` + `airTemperatureKelvin=NaN` 组合有 Javadoc 契约（`ZenithWindSample.java:71`），`GameplayWindSample.hasTemperature()` 暴露（`GameplayWindSample.java:35`） |
| `CLI-01` | P1 | `applyWind` 改为指数松弛，`/20` 在正确位置（`ZenithParticleProviders.java:36-62`），旧"稳态 50×factor"消失；`WindStreakProvider` 初速同样 `/20`（`:87-89`） |
| `CLI-02` | P0 | `RenderFog` 检查 `FogType.NONE` + 失明/黑暗守卫 + `setCanceled(true)` + `setFar/Near`（`FogController.java:30-45`） |
| `CLI-03` | P0 | `canStartSilent()` 已实现（`WindSoundManager.java:66-70`） |
| `CLI-04` | P0 | 客户端指令注册在 `Bus.GAME`（`ZenithClientCommands.java:15`） |
| `CLI-05` | P1 | 服务端粗糙度/群系判定改用 biome 标签（`ZenithServerRuntime.java:670-674`），不再 `biome.toString().contains("forest")` |
| `CLI-06` | P1 | 落叶判定用 `BiomeTags.IS_FOREST/IS_JUNGLE/IS_TAIGA`（`ParticleWindController.java:76-77`） |
| `CLI-08` | P2 | 风向标 yaw 去掉 `180 -` 并加 `partialTick` 插值（`WindVaneRenderer.java:39-40`） |
| `CLI-11` | P2 | 罗盘换算 `(180 - dir + 360) % 360`（`WindMeterItem.java:33`） |
| `SAIL-01` | P0 | `isPickable() = true`、`hurt()` 完整（`SailboatEntity.java:152-161`），上船与拆船可用 |
| `BLK-01` | P1 | `outletPos()` 抽成方法、注册与注销同源（`FanBlockEntity.java:43-50`），`speed<=0` 与 `setRemoved` 双路径注销（`:59-85`） |
| `BLK-02` | P2 | 红石只覆盖有效档位、不改 blockstate（`FanBlockEntity.java:19,27-32,61`） |
| `BLK-05` | P1 | `data/zenith/recipe/` 7 个、`loot_table/blocks/` 4 个、`mineable/pickaxe` 4 方块，全部齐全 |
| `BLK-06` | P3 | `WindVaneBlock.getTicker` 仅客户端返回 ticker（`WindVaneBlock.java:55-61`） |
| `CMD-01` | P0 | `storm`/`typhoon`/`thermal` 均带 `requires(hasPermission(2))`（`ZenithCommands.java:80,90,100`） |
| `ENG-02` | P1 | `gradle/wrapper/gradle-wrapper.jar` 已提交；`.gitignore` 末段 `!gradle/wrapper/gradle-wrapper.jar` |

### 14.2 部分修复：5 条

| 旧编号 | 旧级别 | 现级别 | 已修部分 | 未修部分 / 现编号 |
|--------|--------|--------|----------|-------------------|
| `FR-02` | P2 | P2 | 线走向与法向符号（与 `SQ-01` 同源）已正确，锋面不再"横着走" | 热力学仍为零：无锋后 `ΔT`、无 `Δp`、无风向顺转；`WindContribution` 结构里根本没有温度/气压字段 → `WX-FR-02` |
| `CLI-07` | P1 | P2 | 漏斗三角形已按视距深度排序 + `endBatch`（原 P1 的"排序错乱/闪烁"消失） | T-4 双 pass（先背面后正面）未做，半透明自叠加产生的"脏边"仍在 → `CLI-07`（范围收窄） |
| `CLI-09` | P2 | P2 | 龙卷风着色器雾已接（`TornadoRenderer.vert():221-240`，含 `FogShape.CYLINDER`），等价于 `N-19` | 帆船/风向标仍是 `POSITION_COLOR + NO_LIGHTMAP`（`SailboatRenderer.java:34`、`WindVaneRenderer.java:25`），夜里自发光；`FLAT_TRANSLUCENT` 同样不带光照 → `CLI-09`（范围收窄为"玩法实体渲染器无光照"） |
| `CMD-02` | P3 | P3 | `status`/`wind`/`seabreeze`/`polar` 已改 `translatable` | `wind_meter`/`fan_speed` 仍用 `String.format` 直拼；客户端语言侧缺陷另立 `ENG-08`（中文括号不配对、方位硬编码英文） |
| `ENG-01` | P1 | P1 | `LICENSE:19-22` 第 3 条已给出可执行的修改条件（保留署名 + 注明"基于 Zenith 修改"），比旧报告的"完全未定义"前进了一步 | 三方仍互相矛盾：`README.md:68` 写"禁止修改"、`LICENSE:19-22` 允许修改、`mods.toml` 写 `All Rights Reserved` |

### 14.3 未修：97 条

#### 14.3.1 横切系统 SYS（8 条）

| 旧编号 | 旧级别 | 本版编号 | 迁移 / 现状 |
|--------|--------|----------|-------------|
| `SYS-01` | P1 | `SYS-01` | 手性错误整体未动；同根条目 `L0-01`、`WX-TY-01`、`DRV-08`（新发现的第三处符号错误） |
| `SYS-03` | P1 | `SYS-03` | 主条目仍在，另拆出 `WX-TH-01`（`groundY=60`）、`WX-SB-05`（`y-64`）、`WX-TY-05`（高度衰减用绝对 y）、`WX-DD-02`（高度用绝对 y） |
| `SYS-05` | P1 | `SYS-05` | 主条目仍在，另拆出 `L1-02`（水平最近邻）、`WX-TY-02`（外缘硬截断）、`SB-02` 的夹边外推部分 |
| `SYS-06` | P1 | `SYS-06` | 主条目仍在，并入 `SB-01`（焦点/场不同步 + 半格偏移）；拆出 `L0-02`（平移不迁移状态）、`WX-SB-01`（锚点取第 0 个玩家） |
| `SYS-08` | P1 | `SYS-08`（降 P2） | 仍无 `SavedData`、现象系统全用 `new Random()`；并入 `TH-04`（热泡不回收/不持久化/锚点随机） |
| `SYS-09` | P1 | `SYS-09` | 仍无维度门控，下界/末地照常生成全部天气 |
| `SYS-10` | P1 | `SYS-10` | 仍为同步区块访问；拆出 `WX-SC-02` / `WX-SQ-02`（闪电）、`WX-SB-04`（13k 次地形查询）、`RT-02`（热源重建） |
| `SYS-11` | P2 | `SYS-11` | 线程安全仍未定义 |

#### 14.3.2 驱动器 DRV（6 条）

| 旧编号 | 旧级别 | 本版编号 | 迁移 / 现状 |
|--------|--------|----------|-------------|
| `DRV-01` | P1 | `DRV-01` | 驱动网格仍未周期化 |
| `DRV-02` | P1 | `DRV-02` | 对流团仍每约 100 秒重选玩家 → 瞬移 |
| `DRV-03` | P0 | `DRV-03`（降 P1） | 龙卷仍被 L0 网格混叠；功能不再"完全失效"（渲染与快照已修），故降级 |
| `DRV-05` | P2 | `DRV-05` | 气旋旋向与气压仍独立随机 |
| `DRV-06` | P2 | `DRV-06` | 分量仍独立钳制 |
| `DRV-07` | P3 | `DRV-07` | 随机游走时间尺度未改 |

#### 14.3.3 L0 BackgroundMetGrid（8 条）

| 旧编号 | 旧级别 | 本版编号 | 迁移 / 现状 |
|--------|--------|----------|-------------|
| `L0-01` | P1 | `L0-01`（归 `SYS-01`） | 地转风两分量符号仍反（`BackgroundMetGrid.java:210-211`） |
| `L0-02` | P1 | `L0-02`（归 `SYS-06`） | 焦点平移仍不迁移状态 |
| `L0-03` | P2 | `L0-03` | 刷新步长仍写死，扩散/气压混合与 `dt` 无关 |
| `L0-04` | P2 | `L0-04` | 地形形阻仍顺序依赖 |
| `L0-05` | P2 | `L0-05` | 群系温度映射与递减率未改，且被 `SYS-13`（固定 y=64）污染 |
| `L0-06` | P2 | `L0-06` | 粗糙度拖曳与松弛仍重复计算地表摩擦 |
| `L0-07` | P2 | `L0-07` | 气压异常仍不进 API（`pressureAnomalyPa` 恒 0），与 `API-07` 同源 |
| `L0-08` | P3 | `L0-08` | 每格刷新仍产生临时对象 |

#### 14.3.4 L1 MesoscaleGrid（6 条）

| 旧编号 | 旧级别 | 本版编号 | 迁移 / 现状 |
|--------|--------|----------|-------------|
| `L1-01` | P0 | `L1-01` | 垂直层仍用绝对高度构建、按 AGL 读取（`MesoscaleGrid.java:114-133` vs `:218-220`） |
| `L1-02` | P1 | `L1-02`（归 `SYS-05`） | 水平采样仍是最近邻 + `clamp` |
| `L1-03` | P2 | `L1-03` | "切变"语义仍为方向应变 |
| `L1-04` | P2 | `L1-04` | Ekman 螺旋参考系仍未对齐 L0 背景风 |
| `L1-05` | P2 | `L1-05` | 阵风模型的时间/空间结构仍不可调 |
| `L1-06` | P2 | `L1-06` | 强度仍只作用在第 0 层 |

#### 14.3.5 L2 LocalFlowSolver（5 条）

| 旧编号 | 旧级别 | 本版编号 | 迁移 / 现状 |
|--------|--------|----------|-------------|
| `L2-01` | P0 | `L2-01` | 地面仍被当障碍物，平地伪上升 `+11.2 m/s`（基流 5）原样保留 |
| `L2-02` | P1 | `L2-02` | 每次采样 729 次 `isSolid` + 线性扫描未变 |
| `L2-03` | P2 | `L2-03` | 热源缓存漏洞与范围未改，另见 `RT-02` |
| `L2-04` | P2 | `BLK-07`（合并） | 射流只在 `localModifier`，与 `BLK-03` 同根，合并为 `BLK-07` |
| `L2-05` | P3 | `L2-05` | 置信度、未使用模块、越界判断未变 |

#### 14.3.6 天气 TH / SB（10 条）

| 旧编号 | 旧级别 | 本版编号 | 迁移 / 现状 |
|--------|--------|----------|-------------|
| `TH-02` | P1 | `WX-TH-01` | `groundY = 60` 仍硬编码（`ThermalSystem.java:114`） |
| `TH-03` | P2 | `WX-TH-02` | 下沉环仍固定 −0.5 m/s，质量通量不守恒 |
| `TH-04` | P2 | `SYS-08` + `SYS-06`（**并入，未单列**） | 热泡不随玩家离开回收、不持久化、生成锚点随机选玩家导致多人密度不均；分别归入持久化（`SYS-08`）与焦点模型（`SYS-06`） |
| `TH-05` | P3 | `WX-TH-04` | `topY` 仍用种子地形 + 120…240，采样不检查地表 |
| `SB-01` | P1 | `SYS-06`（**并入，未单列**） | 焦点/场不同步 + 半格偏移（构建用 `GRID/2=16`、采样用 `GRID/2.0=16.5`，系统性偏 32 格） |
| `SB-02` | P1 | `SYS-05` + `WX-SB-05` | 拆两半：域外夹边外推归 `SYS-05`；`agl = y - 64` 归 `WX-SB-05` |
| `SB-03` | P1 | `WX-SB-01` | `players.get(0)` 仍为锚点 |
| `SB-04` | P2 | `WX-SB-03` | `isOceanAt` 语义仍错（旧：任意水面为真 → 湖/水池也产海风；现：因 `SYS-12` 读空气恒 `false`，两个方向的错都存在，修 `SYS-12` 后会回到"任意水面为真"） |
| `SB-05` | P2 | `WX-SB-04` | 每 100 tick 约 13k 次地形查询仍在 |
| `SB-06` | P2 | `WX-SB-02` | 8 方向 × 32 格量化的海岸法向未改 |

#### 14.3.7 天气 SC / TY / SQ / DD / MW（18 条）

| 旧编号 | 旧级别 | 本版编号 | 迁移 / 现状 |
|--------|--------|----------|-------------|
| `SC-01` | P1 | `WX-SC-02` + `WX-SQ-02` | **拆分**：风暴单体与飑线是两条独立闪电路径，都要各自加维度/加载/权限门控 |
| `SC-02` | P2 | `WX-SC-03` | 电场仍只有"中心下沉 + 环状上升"，无高度结构 |
| `SC-03` | P2 | `WX-SC-04` | 仍用原版降雨、不写回 L0/L1 |
| `SC-04` | P2 | `WX-SC-01`（重新编号） | 冰雹伤害语义：仍只看 `hail01 ≥ 0.5` 与 `canSeeSky` |
| `SC-05` | P3 | `WX-SC-05` | 单体移动速度恒定的问题未改 |
| `TY-01` | P1 | `WX-TY-01` | `spin=1` 在 MC 坐标下仍为顺时针（归 `SYS-01`） |
| `TY-02` | P1 | `WX-TY-02` | 影响半径 `r=R` 处仍硬截断归零（归 `SYS-05`） |
| `TY-03` | P1 | `WX-TY-03` | 眼壁 `r=18` 处仍 13.09 m/s 突变（`continue` 与 `eyeDampening` 双重作用） |
| `TY-04` | P2 | `WX-TY-04` | Rankine 与 Holland 的边界参数仍固定 |
| `TY-05` | P2 | `WX-TY-05` | 高度衰减仍用绝对 y，登陆判定仍靠 `isOceanAt` |
| `TY-06` | P2 | `WX-TY-06` | 台风仍不写回 L0 气压场，也不改造原版降雨 |
| `TY-07` | P3 | `WX-TY-07` | 半径/`spin` 仍可能按概率随机，`nameCounter` 无持久化 |
| `SQ-03` | P2 | `WX-SQ-01` | 阵风仍整柱同速、线后无衰减 |
| `DD-01` | P2 | `WX-DD-01` | 核心仍只上升不旋转 |
| `DD-02` | P2 | `WX-DD-02` | 高度仍用绝对 y（归 `SYS-03`） |
| `MW-01` | P2 | `WX-MW-01` | 波长仍 clamp 到 40–220 格（真实山坡应 0.5–5 km） |
| `MW-02` | P2 | `WX-MW-02` | 仍只在上风 5 点固定探测，风向抖动 |
| `MW-03` | P2 | `WX-MW-03` | 仍用 `SeedTerrainProvider` 而非真实地形（`SYS-12` 同源第三例） |

#### 14.3.8 运行时与 API（15 条）

| 旧编号 | 旧级别 | 本版编号 | 迁移 / 现状 |
|--------|--------|----------|-------------|
| `RT-01` | P1 | `RT-01` | 粗风场仍只采 L1、不含现象；`NET-01` 同源 |
| `RT-02` | P1 | `RT-02` | 热源仍按"焦点 + 点平均"取中，每 200 tick 重建；含 `L2-03` |
| `RT-03` | P2 | `RT-03` | 固定 `dt = 0.05`，仍与 `/tick rate`、睡眠跳夜脱钩 |
| `RT-04` | P2 | `RT-04` | 快照仍全维度全现象广播；并入 `NET-04` |
| `RT-05` | P2 | `RT-05` | 维度查找仍 `toString().equals()` 线性遍历 |
| `RT-06` | P3 | `API-11`（重新编号） | `Source` 标 `L1_COARSE` 而实际含 L2；`confidence` 恒 0.9 |
| `API-01` | P1 | `API-01` | `SamplePolicy` 仍是死参数 |
| `API-02` | P1 | `API-02` | `runL2()` 仍恒 `supported=true` + 零力 |
| `API-03` | P1 | `API-03` | `runPolar()` 仍缺参数校验（`stepDegrees` 可致死循环/large alloc） |
| `API-04` | P2 | `API-04` | 地形 API 仍恒 `unknown`；`sampleTerrain` 零调用（`SYS-12` 放大） |
| `API-05` | P2 | `API-05` | 仍无版本号与兼容策略 |
| `API-06` | P2 | `API-06` | 客户端仍拿到 `Level` 强引用 |
| `API-07` | P2 | `API-07` | `pressureAnomalyPa` 仍恒 0；`humidity01` 仍是单点值 |
| `API-09` | P3 | `API-09` | `ZenithAirfoilJson` 仍只写 JSON |
| `API-10` | P3 | `API-10` | NACA 仍在前/后缘各重复一点 |

#### 14.3.9 网络 NET（5 条）

| 旧编号 | 旧级别 | 本版编号 | 迁移 / 现状 |
|--------|--------|----------|-------------|
| `NET-01` | P1 | `NET-01` | 粗风场 9×9@32 不变，见 `RT-01` |
| `NET-03` | P2 | `NET-03` | 包内仍无维度标识，靠客户端 `LoggingIn` 猜 |
| `NET-04` | P2 | `RT-04`（**并入，未单列**） | 全量广播问题与 `RT-04` 完全同根，合并 |
| `NET-05` | P3 | `NET-05` | `FLOAT_ARRAY_CODEC` 仍无长度上界 |
| `NET-06` | P3 | `NET-06` | 仍用全精度浮点，未改 int16 |

#### 14.3.10 客户端 CLI（2 条）

| 旧编号 | 旧级别 | 本版编号 | 迁移 / 现状 |
|--------|--------|----------|-------------|
| `CLI-10` | P2 | `CLI-10` | 气象图仍 ±1200 格 vs 风场 ±128 格，且用 Emoji |
| `CLI-12` | P3 | `CLI-12` | 静态 `Random`、静态 `tickCounter`、`(int)` 重载 |

#### 14.3.11 玩法 SAIL（7 条）

| 旧编号 | 旧级别 | 本版编号 | 迁移 / 现状 |
|--------|--------|----------|-------------|
| `SAIL-02` | P1 | `SAIL-02` | 航向约定与 MC 不一致，渲染仍 `180-yaw` 硬补偿 |
| `SAIL-03` | P1 | `SAIL-03` | 仍用 `setPos` 直接位移，无碰撞 |
| `SAIL-04` | P1 | `SAIL-04` | 仍无侧向阻力，衰减时间常数约 50 s |
| `SAIL-05` | P2 | `SAIL-05` | 顺风推进系数仍约 0.1 |
| `SAIL-06` | P2 | `SAIL-06` | 仍无客户端预测，`setPos` 与 `EntityData` 双写 |
| `SAIL-07` | P2 | `SAIL-07` | 仍取 `meanVelocity`、采样点仍为 `y+1` |
| `SAIL-08` | P3 | `SAIL-08` | 生成时仍 `setYRot(playerYaw)` 而 `headingRadians = 0` |

#### 14.3.12 方块 BLK（2 条）

| 旧编号 | 旧级别 | 本版编号 | 迁移 / 现状 |
|--------|--------|----------|-------------|
| `BLK-03` | P2 | `BLK-07`（合并） | 风道不接力，与 `L2-04` 同根 |
| `BLK-04` | P2 | `BLK-04` | 涡轮探针仍读 `effectiveSpeed`，受 L2 伪上升污染 |

#### 14.3.13 工程 ENG（5 条）

| 旧编号 | 旧级别 | 本版编号 | 迁移 / 现状 |
|--------|--------|----------|-------------|
| `ENG-03` | P1 | `ENG-03` | 仍无任何配置系统 |
| `ENG-04` | P2 | `ENG-04` | 仍无任何单元测试（`src/test` 不存在） |
| `ENG-05` | P2 | `ENG-05` | `tools/gen_textures.py:5` 仍写死 `~/workspace/zenith` |
| `ENG-06` | P3 | `ENG-06` | 四处误导性注释未改 |
| `ENG-07` | P3 | `ENG-07` | `neoforge.mods.toml` 仍缺 `displayURL`/`logoFile`/`issueTrackerURL` |

### 14.4 合并与拆分清单（10 组）

| # | 类型 | 旧编号 | 本版编号 | 理由 |
|---|------|--------|----------|------|
| 1 | 合并 | `BLK-03` + `L2-04` | `BLK-07` | 同一数据流断裂（射流只进 `localModifier`，风道读 `meanVelocity`），拆成两条会让读者以为有两个独立 bug |
| 2 | 合并 | `NET-04` | `RT-04` | 全量广播的登记位置不同，根因完全一致 |
| 3 | 合并 | `SB-01` | `SYS-06` | 是焦点模型的天气层实例，单独成条会与 `SYS-06` 重复表述 |
| 4 | 合并 | `TH-04` | `SYS-08`（持久化部分）+ `SYS-06`（锚点部分） | 旧条目混合了两件事，按根因归位 |
| 5 | 重新编号 | `SC-04` | `WX-SC-01` | 编号前缀与内部序号冲突（`SC` 与 `WX-SC` 顺序错位），且需要与 §7.3 的排序一致 |
| 6 | 重新编号 | `RT-06` | `API-11` | 问题实体在 `api/` 的 `Source`/`Authority` 语义，不在网络层 |
| 7 | 拆分 | `SC-01` | `WX-SC-02` + `WX-SQ-02` | 单体与飑线是两条独立闪电路径，修复点不同（间隔 4–18 s vs 2–8 s） |
| 8 | 拆分 | `SYS-03` | `SYS-03` + `WX-TH-01`/`WX-SB-05`/`WX-TY-05`/`WX-DD-02` | 每个天气系统各有一处绝对 y 与 AGL 混用，需逐个改 |
| 9 | 拆分 | `SYS-05` | `SYS-05` + `L1-02` + `WX-TY-02` + `SB-02`（夹边部分） | 同属"最近邻 + 夹边外推 + 硬截断"，但分布在三个尺度 |
| 10 | 拆分 | `SYS-10` | `SYS-10` + `WX-SC-02`/`WX-SQ-02`/`WX-SB-04` + `RT-02` | 同步区块访问在四个调用点各自可修，合并成一条无法跟踪进度 |

### 14.5 18 个新编号溯源

| 新编号 | 级别 | 类型 | 溯源 |
|--------|------|------|------|
| `SYS-12` | **P0** | 全新 | `McTerrainAdapter` 把 `getHeight(WORLD_SURFACE)` 的返回值（空气格）当方块用。旧报告只审了"两套地形语义"（`API-04`、`WX-SB-03`、`WX-MW-03` 各提一句），没有定位到这个 P0 |
| `SYS-13` | P3 | 全新 | 群系固定按 `block y = 64` 采样（`ZenithServerRuntime.java:676-690`），高海拔群系判错 |
| `DRV-08` | P1 | 全新 | 低压径向分量符号反（`WorldScaleDriver.java:161-164`），与 `SYS-01` 是**两个独立的符号错误** |
| `L0-09` | P2 | 全新 | 对流/龙卷的潜热与湿量写进 `CellState` 后无人读取（死数据） |
| `SAIL-09` | P1 | 全新 | `findWaterSurface` 源水偏差 0.75 格（`SailboatEntity.java:135`），旧报告的 `SAIL-02..08` 都没覆盖水线 |
| `SAIL-10` | P2 | 全新 | `SailboatItem` 用 `SOURCE_ONLY` 只保证"命中点不是水"，任意方块命中都会放置（`:29-35`） |
| `NET-07` | P2 | 全新 | 两个包都没有长度/参数上界：`cellSizeBlocks=0` 可除零、`radiusCells` 非法致 AIOOBE |
| `BLK-07` | P2 | 旧缺陷归并 | = `BLK-03` + `L2-04` |
| `BLK-08` | P3 | 全新 | `FanBlock.SPEED_MPS` 与 `Jet.length` 语义不一致；`lightLevel(s -> 0)` 是空操作 |
| `WX-FRONT-01` | P2 | 全新 | 锋面生成位置纯随机，与驱动器低压/温度场无关（旧 `FR-02` 只谈热力学） |
| `WX-SC-01` | P2 | 旧缺陷重新编号 | = 旧 `SC-04`（冰雹伤害语义） |
| `WX-FOG-01` | P3 | 全新 | `FogModel` 平流雾分支是阶跃（`d = max(d, 0.6)`），与辐射雾的连续式不一致 |
| `API-11` | P3 | 旧缺陷重新编号 | = 旧 `RT-06` |
| `CLI-13` | P3 | 全新 | 雷暴判断只看 `isRaining()`，局部强降水/冰雹无法点亮视觉 |
| `CLI-14` | P3 | 全新 | `TornadoRenderer` 存在但 `ZenithClientSetup` 未注册（`:27-31`） |
| `CLI-15` | P3 | 全新 | `ZenithVisualizer` 每 tick 固定 4 个标签，不随 `sampleCoarse` 空而退化 |
| `CLI-16` | P3 | 全新 | Mixin 覆盖面过窄，只覆盖粒子与营火烟雾 |
| `ENG-08` | P3 | 全新 | 语言文件内容缺陷（中文括号不配对、方位硬编码英文），旧报告只审了键集合一致性 |

**合计 18**：P0 1、P1 2、P2 6、P3 9。其中 **3 个**是旧缺陷的重新编号/归并（`WX-SC-01`、`BLK-07`、`API-11`），**15 个**是本次新发现。

> `WX-MS-01` 不在上表 18 之列：它对应旧报告 §8.8 的季风散文（没有编号），属于"旧描述首次给号"的第 19 个新编号（P2）。若按"新编号总数"统计则为 19，按"新发现"统计则为 15。

---

## 15. 与 `Workspace/docs/` 自述的逐项差异

只列**自述与实测不一致**的条目。一致的条目已在 §0.4（值得肯定）与 §14.1（关闭证据）中确认，不重复。

### 15.1 `docs/01_Zenith_0.1.4_全模组复审报告.md`（142 行）

| 位置 | 自述 | 独立复核 | 影响 |
|------|------|----------|------|
| `:11` | 0.1.3 的 10 项新增问题"修了 9 项半" | 净修复 **8 项**（`N-10/12/13/14/15/16/17/19`），`N-11` 部分（只修客户端），`N-18` 修了 ticker 但引入 `N-20` 回归。把"引入回归"计为已修，等于把 `N-20` 的代价藏进分母 | 报喜不报忧；建议改为"8 完整 + 1 部分 + 1 回归" |
| `:19-24` 修复计分表 | `N-10…N-19` 一栏：10 项 / 8 修 / 2 部分 / 0 未修 | 数字本身自洽，但**表中没有"回归"这一列**，`N-20` 只能从正文读到 | 计分表应加一行"0.1.4 新增回归 = 1" |
| `:15` | "物理核心除日相位外仍然没动" | 同意，但**遗漏 `SYS-12`**：这是本次唯一的 P0 新发现，且比 `L1-01`/`L2-01` 更靠上游（读错地形 → 海风/台风/尘卷风/雾/山地波全部失真） | 未修清单不完整 |
| `:40`（§2.2） | `N-11` 剩余只是"两套口径" | 实际更重：服务端 `fogDensityAt` 的 `overWater` 依赖 `surfaceAt`，而 `surfaceAt` 在已加载区恒 `GRASS` → **服务端 API 的平流雾永远不触发**（并入 `SYS-12`） | 低估了影响面 |
| `:93-107` | `N-20` 风声室内遮蔽回归（含 `skyOpenAt` 分析） | **完全同意**，且本次复核证实性能/逻辑链一致（`getHeight(WORLD_SURFACE)+1` 必然露天） | 唯一与本次结论完全一致的回归条目 |
| `:109-120` | `N-20` 修法：只在埋进方块时抬升，最多 `+4` 格 | 同意，是本报告认可的方案（与 `docs/03:27` 第 1 项一致） | — |
| `:122` 起 | `N-21`（水沫/水柱顶面）、`N-22`（雾粒子高度用 `MOTION_BLOCKING_NO_LEAVES`） | 同意，均 P3；补充：`N-21` 的改动区域与 `N-18` 的水面检测重叠，修完需回归"潜水无水沫 + 水面有水沫"两态 | 建议与 `N-20` 同批提交 |

### 15.2 `docs/02_Zenith_0.1.4_特效与渲染复审及建议.md`（136 行）

| 位置 | 自述 | 独立复核 | 影响 |
|------|------|----------|------|
| `:10` | 视觉补丁清单"9 项都做了，其中 8 项完全正确" | 同意：8 项正确，第 9 项是 `S-2` 回归（与 `docs/01` 同一事件） | — |
| `:14` | "0.1.1 文档中所有不需要改数据通路就能修的视觉问题已经**全部清零**" | **不成立**，且与本文件自己的表 `:24-36` 冲突：`F-5/F-6`、`S-3/S-4`、`P-6`、`P-7`、`T-4` 共 7 项标 ❌，其中 `F-5/F-6`（雾色/高度雾）、`P-6`（拉伸）、`P-7`（视锥/LOD）、`T-4`（双 pass）**都不依赖数据通路** | 结论性陈述与事实相反，会让人跳过 §15.3 的工期 |
| `:26` | `S-3/S-4` 声音分层、事件音 ❌ | 与资源侧吻合：`sounds.json` 只有 1 条 `wind_loop`，`ZenithSounds` 只注册 1 个 `SoundEvent`，没有雷/雨/雹/龙卷风声 | 确认是"未做"，不是"做了没写文档" |
| `:36` | `T-4` 漏斗三角形未排序 ❌ | 与代码吻合：`TornadoRenderer` 已按深度排序 + `endBatch`，但**无双 pass**，因此"排序"这一子项已修、"背面再正面"未做 → 本报告记为 `CLI-07`（部分修，P1→P2） | 口径差异：docs 判 ❌，本报告判 🟡 |
| `:37` | `R-1` 风向标 ✅（服务端不再挂 ticker） | ✅ 只覆盖"挂 ticker"这一子项（`WindVaneBlock.java:55-61` 已改）。原始 `R-1` 的光照/雾/光影兼容未做——`ClientRenderHelper.java:32-45` 仍 `NO_LIGHTMAP`，船与风向标夜里自发光 | 标 ✅ 会让读者以为渲染侧全清 |
| `:39` | `V-DATA-1…8` ❌ 不变 | 与本次 `RT-01`/`NET-01`/`API-01`/`NET-07` 一致 | — |
| `:120` | V0.7 清单（0.2 首个提交） | 4 项中 2 项（`P-5` 渲染类型、水沫）已完成；雾色/高度雾与高度图雾未做。清单应标"部分完成" | 进度表述偏乐观 |

### 15.3 `docs/03_Zenith_开发路线图_v0.1.4更新版.md`（151 行）

| 位置 | 自述 | 独立复核 | 影响 |
|------|------|----------|------|
| `:14` | "0.1.4 收尾 8 项**全部完成**" | 8 项都已提交，但其中 1 项（风声）引入回归 `N-20`；同一行括号里自己写了"引入 1 个回归"，前后矛盾 | 建议改"8 项交付、1 项回归" |
| `:17` | "0.1.x 热修线可以正式收尾" | 同意热修线收尾；但 `SYS-12` 是 **P0 且非表现层**，不属于"热修线"，必须进 0.2 第 1 周而不是第 2 周 | 排期需前移 |
| `:23-33` §1 小修 5 项 | 半天工作量，并入 0.2 首个提交 | 5 项**全部未开始**；其中第 5 项 `T-4` 就是本报告 `CLI-07` 的剩余部分，第 4 项是 `N-11` 剩余（`SYS-12` 修完才谈得上） | 清单可用，但需加 `SYS-12` |
| `:41-49` 第 1 周"测试先行" | JUnit 纯物理测试 + GitHub Actions | **未开始**：`src/test` 不存在，`build.gradle` 的 `dependencies {}` 为空（`:44-46`），无 `testImplementation` | 0.2 的验收基础不存在 |
| `:43-47` 测试阈值 | L1 AGL 10 格 ∈ [0.6,0.9]（当前 0.28）；L2 平地 1.6 格 |w|<0.5（当前 11.2）；`cd(90°)` ∈ [1.6,2.1] | 与本次复算**完全吻合**（§17.2），可直接作 gold values | 说明作者已掌握严重程度，只差动手 |
| `:55` | L1 修好后同周把 `CoarseWindPacket` 换 v2 | 同意；建议把 `NET-07` 的入参校验一并提交（同一个包的同一处解码路径，分两次改等于两次回归风险） | 合并提交 |
| `:74` 0.2 验收 | "P0/P1 物理问题清零" | 需把 `SYS-12`、`DRV-08`、`SAIL-09` 纳入清单，否则按现清单验收"清零"时这三条仍在 | 验收不可达 |
| `:102-112` KPI | 物理 P0 = 2（`L1-01`、`L2-01`） | 实际 **3**（+`SYS-12`）；"回归 = 1（`N-20`）"准确；"日相位统一 7/7"准确（实测 13 个引用点覆盖 7 个系统） | KPI 表需 +1 |
| `:129-151` issue 清单 | 0.2 的 issue 列表 | 未包含本报告 18 个新编号中的 15 个，尤其 `SYS-12`(P0)、`DRV-08`(P1)、`SAIL-09`(P1) | 建议按 §14.5 补齐 |

### 15.4 `docs/WEATHER_SYSTEMS.md`（124 行）：四处过期 + 一处把缺陷写成特性

| 位置 | 文档内容 | 实际代码 |
|------|----------|----------|
| `:11-13` | 边界用"海绵层"（`spongeBlend`）吸收 | `NestedBoundaryCoupler.java:15` 确实实现了 `spongeBlend(dist, domainRadius, spongeLayers)`，但 **L0/L1 的实际边界路径走的是 `clamp`**（`BackgroundMetGrid.java:287-291`、`MesoscaleGrid.java:214-215`）。文档描述的那条路径不生效 |
| `:25` | 太阳因子 `sin(2πt − π/2)` | 代码已全部改为 `FogModel.solarFactor()`（13 个引用点，见 §14.1 `SYS-02`）。文档保留旧公式会误导后续"按文档对齐物理"的人 |
| `:88` | "冷锋：窄降水带 + 强阵风 + **锋后风向顺转**" | 风向顺转未实现，`WindContribution` 连温度/气压字段都没有（`WX-FR-02`）。文档把设计目标写成了已实现 |
| `:119` | "程序化合成棕色噪声循环（**12 秒 ogg**）" | `sounds.json` 只有 1 条 `wind_loop`，`ZenithSounds` 只注册 1 个 `SoundEvent`；无 12 秒 ogg 资源，也没有分层/事件音（`S-3/S-4` 未做） |
| `:116` | 季风"夏季东南→西北、冬季反向，3–5 m/s，**全维度均匀**" | "全维度均匀"是**缺陷**（`WX-MS-01`）却被写成特性描述。真实季风应有空间结构 |

---

## 16. 优先级路线图

排序依据：**（崩溃/数据风险 × 影响面）÷ 改动量**。与 `docs/03` 的计划大体一致，差异集中在"`SYS-12` 必须前移到第 1 周"和"`WX-MS-01` 建议删除而非修复"。

### 16.1 第一批：0.1.5 或 0.2 首个提交（约 1 周，6 项）

| # | 任务 | 编号 | 级别 | 改动量 | 为什么现在做 |
|---|------|------|------|--------|-------------|
| 1 | `McTerrainAdapter` 取 `height - 1` 作为地表方块 | `SYS-12` | **P0** | 1 行 + 3 处调用调整 | 一行把海风/台风登陆/尘卷风热地表/服务端平流雾/山地波五个系统从"完全失效"拉回可用；也是 0.2 所有地形相关工作的前置 |
| 2 | 风声遮蔽采样点按 `docs/01:109-120` 改 | `N-20` | P2 | 2 行 | 已发生的回归，与其他热修同批发布成本最低 |
| 3 | `findWaterSurface` 取真实水面、水线统一 | `SAIL-09` | P1 | 3 行 | 0.75 格偏差肉眼可见，且影响 `SAIL-07` 的采样高度 |
| 4 | 两个包的解码加长度/参数上界 | `NET-07` | P2 | ~20 行 | 崩溃/OOM 风险；恶意服务端可打崩客户端，属于安全面 |
| 5 | `runPolar` 校验 `stepDegrees ∈ (0, 360]` 且整除 360 | `API-03` | P1 | ~10 行 | 当前可直接死循环或巨量分配 |
| 6 | 锋面生成时播种 `ageSeconds = 0.2·lifetime` | `WX-FR-01` | P1 | 1 行 | 让"锋面存在但无感"在一天内变成可感知，收益/成本比最高 |

### 16.2 0.2 第 1 周：测试网（`docs/03:41-49`，本报告完全同意）

- 建 `src/test`，`build.gradle` 加 `testImplementation`；
- 先写**会失败**的测试：§17.2 的 gold values 可直接搬（`L1` 0.2775→应 0.41、`L2` +11.2→应 <0.05、`runPolar` `cd(90°)=25.9`→应 1.6–2.1、`WX-TY` `r=18` 13.09→应连续）；
- GitHub Actions：`./gradlew build` + `test`（顺带解决 §17.1 的"构建未验证"）。

### 16.3 0.2 第 2 周：两个 P0（+ 数据通路一次改完）

1. `L1-01`：层定义改 AGL 拉伸网格（2/6/14/30/60/110/190/300），采样改三线性；
2. `L2-01`：先求局地地面、忽略地下方块，排挤与亏损归一化限幅，9³ 边缘加 `smoothstep`；
3. 同周换 `CoarseWindPacket` v2（含 `NET-07` 校验）。

### 16.4 0.2 第 3 周：方向与边界（一次改完三处符号错误）

4. `SYS-01` + `DRV-08` + `WX-TY-01`：地转风 `ug/vg` 符号、低压径向 `inflowSign`、台风 `spin` 语义，**必须在同一次提交里改**（三处互相抵消，分开改会让中间态更难判断）；
5. `SYS-05` + `L1-02` + `WX-TY-02`：双线性插值、域外不外推、台风外缘平滑包络；
6. `SYS-03` + `WX-TH-01` + `WX-SB-05` + `WX-TY-05` + `WX-DD-02`：AGL 统一（同一批改完，否则每个系统各错一次）。

### 16.5 0.2 第 4 周：服务端友好

7. `SYS-09` 维度门控；8. `SYS-10` + `WX-SC-02` + `WX-SQ-02` + `RT-02`（闪电只落已加载区块、热源事件化）；9. `WX-SB-04` 海陆距离场缓存；10. `ENG-03` 配置系统（`gridRadius`/`refreshTicks`/`enableTyphoon`/`particleDensity`）。

### 16.6 0.2 第 5–6 周：玩法与持久化

11. `SAIL-02…08`（`move()` 代替 `setPos`、横向阻力、航向约定、视风、客户端预测）；12. `SYS-08` `SavedData` + 种子派生随机源；13. `DRV-01/02/03`（周期化、固定宿主、龙卷改逐点 Burgers–Rott 涡）。

### 16.7 0.3：表现层（`docs/03` §3，本报告补充两条）

- `CLI-14` 台风渲染器 + `CLI-07` T-4 双 pass + `CLI-15` Visualizer 自适应（`F-5/F-6` 雾色/高度雾、`P-6/P-7` 拉伸/视锥同批）；
- `ENG-08` 语言文件（顺手，1 小时）；
- `S-3/S-4` 声音分层需要新增 4 类 `SoundEvent` 与对应 ogg（当前只有 1 条 `wind_loop`），是**资源工作量**而非代码工作量，应单独排期。

### 16.8 建议删除而非修复

| 编号 | 建议 | 理由 |
|------|------|------|
| `WX-MS-01` | 删掉 `MonsoonSystem` 这个 `WeatherPhenomenon`，把季风作为 L0 的 `baseFlow` 偏置（按海岸距离加权） | 当前实现是"全维度恒定风偏置"，没有任何空间结构；留着它只会让 `WX-SB`（海陆风）与它互相矛盾 |
| `WX-SQ-03` | 实现或删除 `Microburst` | 现在 `microbursts.add(...)` 根本不存在，整个列表恒空，`-10 m/s` 分支是死代码 |

### 16.9 验收标准（建议写进 `docs/03`）

| 指标 | 当前实测 | 0.2 目标 |
|------|----------|----------|
| 平地眼高（1.6 格）风速 / L0 | 约 0.17–0.28 | ∈ [0.35, 0.45] |
| 平地 10 格高风速 / L0 | 0.2775 | ∈ [0.5, 0.65] |
| 平地眼高 \|w\| | 11.2 m/s（基流 5） | < 0.5 m/s |
| 北半球低压中心东侧风向 | 指向 +Z（南，错） | 指向 −Z（北） |
| `runPolar` `cd(90°)` | 25.9 | ∈ [1.6, 2.1] |
| 台风 `r=18` 与 `r=19` 的风速差 | 13.09 → 突变 | < 1 m/s |
| 物理 P0 数量 | **3** | 0 |
| 回归数（相对上一版） | 1（`N-20`） | 0 |

---

## 17. 构建验证与复算附录

### 17.1 构建状态：**未验证**

**实测命令**：`./gradlew build --no-daemon`（工作目录 `Workspace`）。

**失败输出（原文）**：

```
Could not resolve net.neoforged:neoform-runtime:2.0.31
Connect to maven.neoforged.net:443 ... failed: Connection timed out
BUILD FAILED in 2m 42s
```

**判定**：失败发生在**依赖解析阶段**——`net.neoforged.moddev` 插件需要下载 `neoform-runtime`，而本次审计环境无法访问 `maven.neoforged.net:443`。这不是代码错误。

**因此本报告必须声明**：

- **构建状态 = 未验证**。不能据此判断 0.1.4 能否编译通过；
- 本报告全部结论来自**静态阅读 + 离线数值复算 + 资源清单核验**，不依赖编译产物；
- 反向风险仍然存在：若存在编译错误（例如 `ZenithWindSample.Builder` 缺方法、`MesoscaleGrid.Sample` 字段名不匹配、`WeatherPhenomenon` 接口签名漂移），本报告**无法发现**；
- 唯一已验证的是 `gradle/wrapper/gradle-wrapper.jar` 存在（`ENG-02` 关闭依据），以及 `pack.mcmeta` / `zenith.mixins.json` 的结构性字段正确。

**建议**：在 CI 上加 `./gradlew build`（`docs/03:49` 已列入 0.2 第 1 周），并在 `settings.gradle` 里配置 `maven.neoforged.net` 的镜像与超时，避免本地/CI 因网络不可达而误判"代码有问题"。

### 17.2 离线复算 gold values（可直接作为 JUnit 断言）

#### (1) `L2-01` 平地伪上升（`LocalFlowSolver`，`r=4` 的 9³ 扫描）

条件：平地，地面方块 `y ≤ 63`，采样点 `(0.5, 64+h, 0.5)`，基流 5 m/s 向东，`baseY = 0`。

| 离地 h | modX (m/s) | modY (m/s) | 有效水平风 (m/s) | `sheltered` |
|---|---|---|---|---|
| 0.5 | −14.3 | **+17.8** | −9.3（反向） | true |
| 1.0 | −8.5 | +13.1 | −3.5（反向） | true |
| 1.6（眼高） | −3.7 | **+11.2** | +1.3 | false |
| 2.5 | −0.7 | +6.7 | +4.3 | false |
| 4.0 | 0 | 0 | +5.0 | false |

基流 10 m/s 时 `h=1.6` 的 `modY = +22.5`；基流 2.775 m/s（≈ L1 平地的实际值）时 `modY ≈ +6.2`。**伪上升与基流同阶**。

#### (2) `L1-01` 近地风廓线（`MesoscaleGrid`，`z0 = 0.5`、`ABL = 280`）

平地（地表方块 `y=63`，`terrainHeight ≈ 64/65`）时，第 0 层中心绝对 `y = 20` → `hAGL = −44` → 命中 `hAboveGround ≤ z0` 分支 → `prof = 0.15` → 系数恒为 `0.15 + 0.85×0.15 = 0.2775`。

| 高度 | `prof` | 实测系数 | §5 记录的应有效系数 |
|------|--------|----------|----------------------|
| 1.6 格 | 0.3062 | **0.2775** | 0.42 |
| 10 格 | 0.5524 | **0.2775** | 0.62 |
| 50 格 | 0.7686 | **0.2775** | 0.77 |

> **口径提醒**：按 `0.15 + 0.85·prof` 复算得 0.41 / 0.62 / 0.80，与 §5 记录的 0.42 / 0.77 有 0.01–0.03 的差异（第 50 格最明显）。差异不影响结论（三者实测完全相同，恒为 0.2775），但**写 JUnit 断言时应以代码里的实际公式为准**，不要照抄本表，也不要照抄 `docs/03:43` 的区间而不复核。

地表以上 0–80 格，风速恒为 L0 的 0.2775 倍，与粗糙度、稳定度、坡度完全无关。眼高再叠加近地附加衰减，`/zenith wind` 报出的总系数约 **0.17**（推导见 §5 `L1-01`）。

#### (3) `API-03` 极线阻力（`runPolar`）

| 迎角 | 计算值 | 物理期望 |
|------|--------|----------|
| `cd(90°)` | **25.9** | 1.6–2.1（平板法向） |
| `Cl(0°)` | 0（正确） | 0 |

`cd(90°)` 偏高约 13 倍，直接导致极线/滑翔机的失速后阻力不可用。

#### (4) `WX-TY-03` 台风眼壁突变

| 半径 | 切向风速 |
|------|----------|
| `r = 18`（`eyeRadiusBlocks`） | **13.09 m/s** |
| `r = 19` | 显著不同（`continue` 与 `eyeDampening` 双重作用） |
| `r = R = 600` | 12.11 m/s，随后**硬截断归零** |

眼壁处没有平滑过渡，外缘从 12.11 m/s 直接跳到 0。

### 17.3 盘点数据（实测，供复核）

| 类别 | 数量 |
|------|------|
| Java 文件 / 行数 | **106 / 8,348**（`src/main/java`，无 `src/test`） |
| `src/main/resources` 总文件 | **57**（含 38 个 JSON） |
| `assets/zenith` | **42** |
| 模型 / blockstate / 贴图 / 粒子定义 | 11 / 4 / 15 / 7 |
| `data/zenith` 配方 / 战利品表 | 7 / 4 |
| 语言键 | `en_us` 26 / `zh_cn` 26，差集 0 |
| `sounds.json` 条目 | **1**（`wind_loop`） |
| 模型与贴图引用断链 | **0**（48 条引用逐项核对） |
| 旧台账编号总数 | **126**（`Hark/01`，脚本枚举） |
| 本报告标题总数 | **136**（现存 122 + 已关闭 14） |

### 17.4 局限声明

1. **未运行游戏**：所有"观感"结论来自代码路径与数值推演，不含实机截图/视频证据；
2. **未编译**：见 §17.1；
3. **未做随机统计**：现象系统大量使用 `new Random()` 与无种子随机，本报告只审单次生成的逻辑，未做长时间蒙特卡洛；
4. **未审未实现功能**：`ClientAtmosphere`、局地降水、积雨云、雾色/高度雾、声音分层等 `docs/02`/`docs/03` 中的规划项，本报告只在"文档与代码不一致"的层面提及，不评价其设计；
5. **API 语义假设**：`getHeight(WORLD_SURFACE)` 返回"地表上方第一格空气"、`canSeeSky` 按天空光照满 15 判定、`FogType.NONE` 等均按 MC 1.21.1 官方语义，若该版本有行为差异，相关结论需重验（相关条目：`SYS-12`、`N-20`、`CLI-02`）。

---

## 附：报告编号索引

| 章节 | 内容 | 标题数（含已关闭） | 其中现存 |
|------|------|--------------------|----------|
| §2 | 横切系统 SYS | 10 | 10 |
| §3 | 驱动器 DRV | 7 | 7 |
| §4–§6 | L0 / L1 / L2 | 9 / 6 / 5 | 9 / 6 / 5 |
| §7 | 天气现象 `WX-*` | 36 | 36 |
| §8–§10 | 运行时 RT / API / 网络 NET | 5 / 10 / 5 | 5 / 10 / 5 |
| §11 | 客户端 CLI | 16 | 8（8 条已关闭） |
| §12 | 玩法 SAIL / BLK / CMD | 9 / 8 / 2 | 9 / 4 / 1（BLK 4 条、CMD 1 条已关闭） |
| §13 | 资源与构建 ENG | 8 | 7（`ENG-02` 已关闭） |
| **合计** | — | **136** | **122** |
| §14 | 旧台账 126 编号状态矩阵 | 24 关闭 / 5 部分 / 97 未修 | — |
| §15–§17 | 文档差异 / 路线图 / 构建与复算 | — | — |
