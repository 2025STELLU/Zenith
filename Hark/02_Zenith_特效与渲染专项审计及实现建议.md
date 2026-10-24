# Zenith 特效与渲染：专项审计与未来实现建议

> 适用：NeoForge 21.1.x / Minecraft 1.21.1（OpenGL 3.2 Core，原版 `ShaderInstance` + `RenderType` 管线）
> 关联：文档 01（全模组审计）中的 CLI-*/DRV-04/RT-01 等编号在此展开
> 目标读者：负责客户端视觉、粒子、着色器与声音的开发者

---

## 0. 结论先行

1. **当前视觉层最大的问题不是"画得不够好"，而是"拿到的数据不对"**。客户端只收到每 2 秒一次、只覆盖 ±128 格、只含 L1 背景风（且近地层只有真实值的约 28%）的粗风场；台风、风暴外流、锋面、季风、龙卷的风在客户端**根本不存在**。所以第一优先级是建立一个**客户端大气场（ClientAtmosphere）**：粗风场时间插值 + 根据天气快照在客户端**解析重建**各天气现象。
2. **有 4 个视觉/听觉功能当前实际上不生效**：雾（未 cancel 事件）、风声（零音量起播被引擎跳过）、`/zenithc` 可视化指令（注册在错误总线）、落叶粒子（`biome.toString()` 永不匹配）。另有龙卷画在错误位置（坐标少乘 256）。这些是"一行修复、立刻可见"的收益。
3. **粒子物理单位错误**导致所有风驱动粒子的速度是真实风速的 25–50 倍，这是"风看起来很假"的主因之一。
4. 渲染路线建议**三档分级**：
   - **T1 兼容档**（默认，任何光影/性能模组下都能用）：粒子 + 原版雨雪/雾/天空状态驱动 + 简单网格；
   - **T2 增强档**：自定义核心着色器（龙卷、云墙、雨幕倾斜）+ 深度软化；
   - **T3 电影档**（可选）：半分辨率光线步进体积云/风暴云、屏幕空间效果，检测到 Iris/Oculus 时自动降级。
5. 为将来跨版本（1.21.2 的着色器定义变更、1.21.5 的 `RenderPipeline` 重写、1.21.9 的提取/提交两段式渲染）做准备：现在就引入一个薄的**渲染后端抽象层**。

---

## 1. 现有视觉资产盘点

| 组件 | 文件 | 技术 | 状态 |
|------|------|------|------|
| 风迹 / 落叶 / 扬尘 / 水沫 / 热浪 / 冰雹粒子 | `ZenithParticleProviders`, `ParticleWindController` | `TextureSheetParticle`，8×8 贴图 | 能显示，物理单位错误；落叶不生成 |
| 雨丝（随风倾斜） | `WeatherWindController` | 用 SPRAY 粒子冒充雨滴 | 能显示，无遮挡/生物群系判断，速度过大 |
| 原版粒子风响应 | `ParticleMixin`, `CampfireSmokeParticleMixin` | Mixin `Particle.tick` TAIL | 生效，但累加式加速度，会饱和到上限 |
| 雾 | `FogController` | `ViewportEvent.RenderFog` | **不生效**（未 cancel） |
| 雾气粒子 | `FogController.clientTick` | 复用 SPRAY 粒子，固定斜向速度 | 看起来像飞溅的水滴，不像雾 |
| 风声 | `WindSoundManager` | `AbstractTickableSoundInstance` | **不响** |
| 龙卷 | `TornadoRenderer` | `RenderLevelStageEvent` + `POSITION_COLOR` 三角网格，CPU 排序，顶点色螺旋带 | 位置错误；缺 flush；写深度；无雾无光照 |
| 风向标 | `WindVaneRenderer` (BER) | `POSITION_COLOR` 盒子 | 东西镜像；每秒跳一次；夜间发光 |
| 帆船 | `SailboatRenderer` | `POSITION_COLOR` 盒子 + 三角帆 | 朝向约定可能反；夜间发光；无布料 |
| 气象图 | `MeteorologicalMapScreen` | `GuiGraphics.fill` 逐像素 Bresenham | 外圈数据是外推；Emoji 不显示 |
| 风场可视化 | `ZenithVisualizer` | 随机撒风迹粒子 | 指令注册失败导致无法开启 |

**缺失**（与"天气模拟模组"的预期差距最大的部分）：风暴/台风/飑线在世界中的**云体**；局地降水（只有原版全局雨）；台风的天空/海面表现；闪电的云内闪光与雷声延迟；植被随风摆动；屏幕空间效果（雨滴、热浪扭曲、沙尘色调）。

---

## 2. 问题逐项审计

### 2.1 数据通路（根因）

| 编号 | 问题 | 影响 |
|------|------|------|
| V-DATA-1 | 粗风场只含 L1，不含 9 个天气现象 | 台风中心附近粒子仍按背景微风飘 |
| V-DATA-2 | 粗风场 9×9@32，仅 ±128 格；域外夹边外推 | 远景粒子、气象图外圈全是边缘值 |
| V-DATA-3 | 每 40 tick 更新一次，客户端直接替换、无插值 | 每 2 秒风向"跳变"，粒子、风声、风向标同步抽搐 |
| V-DATA-4 | 服务端 L1 是最近邻（64 格台阶），再被 32 格网格采样 | 风场呈块状 |
| V-DATA-5 | 快照中的现象位置每 5 秒更新一次，客户端不外推 | 龙卷、风暴图标每 5 秒瞬移 |
| V-DATA-6 | 龙卷坐标单位为"驱动格"（文档 01 DRV-04） | 渲染在原点附近 |
| V-DATA-7 | `ClientWindState` 登出/换维度不清空 | 新世界里出现旧世界的台风和龙卷 |
| V-DATA-8 | 降水强度、雾浓度等标量无空间场，只有焦点处单值湿度 | 无法做局地降水/局地雾 |

### 2.2 粒子

**P-1 单位与积分（核心问题）**

原版 `Particle` 的 `xd/yd/zd` 单位是 **block/tick**，每 tick `move()` 后乘以 `friction`（默认 0.98）。Zenith 的做法：

| 粒子 | 代码 | 稳态速度 / 真实风速 |
|------|------|------|
| 扬尘 (factor 1.0) | 每 tick `xd += 0.05·w` | `0.05/(1−0.98) = 2.5` block/tick 每 m/s → **50×** |
| 落叶 (0.8) | 同上 | **40×** |
| 水沫/雨丝 (1.2) | 初速 `0.35·w` + 每 tick 累加 | 初速 7×，之后趋向 60× |
| 风迹 | 初速 `0.9·w`，上限 3 block/tick | 18× → 被截断在 60 m/s |
| 冰雹 (0.25) | 累加 | 12.5× |
| 原版营火烟 Mixin | `+0.035·w`/tick，上限 0.18 | 很快饱和，只剩方向 |

**修正**：用一阶松弛（对应粒子的空气动力学响应时间 τ）：
```java
// k = 1 - exp(-dtTick / τTick)；落叶 τ≈10 tick，扬尘 τ≈4，雨滴 τ≈2，烟 τ≈6
Vec3 wTick = windMps.scale(1.0 / 20.0);
xd += (wTick.x - xd) * k;
zd += (wTick.z - zd) * k;
yd += (wTick.y - yd) * k * verticalCoupling - gravityTick;
```
并让 `friction = 1.0`（阻力已由松弛项表达），避免双重阻尼。

**P-2 生物群系判断**：`biome.toString()` → 改为 `level.getBiome(pos).is(BiomeTags.IS_FOREST)`（以及 `IS_JUNGLE`、`IS_TAIGA`）。更好的做法：直接看玩家周围是否有树叶方块（`BlockTags.LEAVES`）并从叶子方块下方生成，颜色取该方块的 `BlockColors`（随生物群系着色）。

**P-3 雨丝**：
- 应检查 `biome.getPrecipitationAt(pos)`（RAIN/SNOW/NONE）与 `level.getHeightmapPos(MOTION_BLOCKING, pos).getY() > y`（头顶有遮挡则不生成）；
- 下落速度 9 m/s → 0.45 block/tick；
- 更推荐的路线见第 4.2 节：直接**倾斜原版雨**而不是叠加粒子雨。

**P-4 雾气粒子**：复用 SPRAY 且固定速度 `(0.15, 0.02, 0.15)` → 看起来像飞溅水珠斜飞。雾应该是大尺寸（2–6 格）、极低 alpha（0.03–0.08）、慢速随风、贴近地表/水面、带渐入渐出的软粒子，使用专门贴图（径向渐变噪声）。

**P-5 渲染类型**：落叶/冰雹使用 `PARTICLE_SHEET_OPAQUE` 但设置了 alpha；`setSpriteFromAge` 只在构造时调用（单帧贴图时无所谓，但未来做动画要在 tick 中调用）。

**P-6 风迹的形态**：方形 billboard 无法表达"速度"。应**沿速度方向拉伸**：覆写 `render(VertexConsumer, Camera, float)`，构造一条以速度方向为长轴、面向相机的四边形（类似原版 `TrailParticle` / 拖尾），长度 ∝ |v|，两端 alpha 渐隐。

**P-7 生成策略**：所有粒子在玩家周围固定立方体内均匀随机生成，不考虑视锥、遮挡、地形。建议：
- 视锥内优先（`Frustum` 可从 `LevelRenderer` 拿到，或简单按相机朝向半球采样）；
- 风迹沿**流线**生成：在上风方向边界处生成、随风流入视野，存活时间 = 穿越可视区域所需时间；
- 粒子预算：按设置档位与 FPS 动态调节（见第 7 节）。

### 2.3 雾

| 编号 | 问题 |
|------|------|
| F-1 | 未 `setCanceled(true)`，修改不生效（NeoForge 1.21.1 Javadoc 明确要求） |
| F-2 | 未区分 `FogMode`（天空 / 地形）与 `FogType`（水下、岩浆、细雪）；未排除失明/黑暗效果 → 修好 F-1 后会覆盖这些原版雾 |
| F-3 | 无时间平滑：浓度由瞬时湿度/风速/是否在水上决定，走到水边一步雾就突变 |
| F-4 | `isOverWater` 只看脚下一格；"平流雾"语义是"暖湿空气流经冷水面"，应看上风向一段距离的水面占比 |
| F-5 | 雾颜色未调整（`ViewportEvent.ComputeFogColor`）：辐射雾偏冷白、沙尘暴偏土黄、台风偏灰蓝 |
| F-6 | 雾只有距离雾，无高度：辐射雾应贴地（谷底浓、山顶清）。原版距离雾做不到，需要 T2 着色器方案或"贴地雾粒子 + 适度距离雾"组合 |

### 2.4 风声

| 编号 | 问题 |
|------|------|
| S-1 | 零音量起播被 `SoundEngine` 跳过（需 `canStartSilent()` 返回 true），并且每 tick 新建实例 |
| S-2 | 无遮蔽：室内、洞穴里风声和野外一样大 |
| S-3 | 只有一个循环层，音高随风速线性变化（0.82–1.25），高风速时显得"电子味" |
| S-4 | 无阵风事件、无树叶沙沙、无雨打屋顶、无远雷延迟 |

### 2.5 龙卷渲染

| 编号 | 问题 |
|------|------|
| T-1 | 坐标单位错误（少乘 256） |
| T-2 | `mc.renderBuffers().bufferSource().getBuffer(...)` 后没有 `endBatch(FLAT_TRANSLUCENT)`：依赖后续某处的全局 flush，可能在手部渲染阶段以手部投影矩阵绘制、或延迟到下一帧 |
| T-3 | 半透明 RenderType 默认写深度（`createCompositeState(false)` 时 write mask = COLOR_DEPTH）：CPU 排序只能保证单个龙卷内部顺序，与水、玻璃、云、其他半透明物交叉时出错 |
| T-4 | 顶点色恒定，没有雾（`position_color` 着色器无雾计算）→ 雾天里龙卷是一根清晰的发光灰柱 |
| T-5 | 尺寸固定（底 4 / 顶 16 / 高 60–100），不使用快照里的 `coreRadiusBlocks`、`maxWindMps` |
| T-6 | 每帧每个龙卷分配 7 个 21×33 float 数组 + 插入排序 640 个 quad：可接受，但没必要（几何可缓存到 `VertexBuffer`，变化交给着色器） |
| T-7 | 地面尘环是平面圆环，不贴地形、不受遮挡，在坡地上会悬空或插地 |
| T-8 | 漏斗形状是"倒锥"，真实凝结漏斗与气压亏损相关：越接近地面越细，常有弯曲（随高度的风切变导致倾斜） |

### 2.6 方块/实体渲染（风向标、帆船）

| 编号 | 问题 |
|------|------|
| R-1 | `POSITION_COLOR` + `NO_LIGHTMAP` → 不受光照、夜间自发光、无环境光遮蔽，也不受雾影响 |
| R-2 | 风向标 `YP.rotationDegrees(-yaw)` 东西镜像；角度每 20 tick 更新且不插值 |
| R-3 | 帆船：帆是两片三角形，没有布料形变、没有横倾；`trim` 只是改一点"鼓度"；朝向约定需统一（文档 01 SAIL-02） |
| R-4 | 与光影包兼容性：自定义 `POSITION_COLOR` RenderType 在 Iris/Oculus 下可能以无光照的 fallback 程序渲染，或完全不可见，需实测 |

### 2.7 气象图

| 编号 | 问题 |
|------|------|
| M-1 | 视野 ±1200 格，数据只有 ±128 格 → 外圈箭头全部为外推值，误导玩家 |
| M-2 | Emoji（🌀⛈🧊）在 MC 默认字体中没有字形 |
| M-3 | 逐像素 `fill` 画线不裁剪，长线段越界绘制 |
| M-4 | 缺少：风速色阶底图、等压线、图例、比例尺、指北针、缩放、时间轴（预报） |

---

## 3. 目标架构：客户端大气场 ClientAtmosphere

```
              ┌──────────── 服务端 ────────────┐
              │ L0/L1/现象 (权威)               │
              │  ├ CoarseWindPacket v2 (含现象、int16 量化, 两个时间层)
              │  └ WeatherSnapshot v2 (现象参数 + 速度 + 种子 + 维度)
              └───────────────┬────────────────┘
                              ▼
┌──────────────────────── 客户端 ClientAtmosphere ────────────────────────┐
│ 1. CoarseField：环形缓冲保存最近两帧粗场 → 按 (t − t0)/(t1 − t0) 时间插值     │
│ 2. PhenomenaMirror：快照中的台风/风暴/龙卷/锋面/飑线/尘卷风，                 │
│    按速度做航位推算（dead reckoning），并用与服务端**同一份**解析模型求值        │
│    （把各现象的 sample() 抽到 common 模块的纯函数类中，两端共享）               │
│ 3. GustField：与服务端同种子的冻结湍流噪声（随平均风平移）                      │
│ 4. ScalarFields：降水强度 P(x,z)、云量 C(x,z)、雾浓度 F(x,y,z)、沙尘 D(x,z)   │
│ 5. CameraCache：以相机为中心 32×12×32 @4 格的缓存体，每帧增量更新 1/8          │
│    → 粒子、声音、着色器 uniform、天气纹理全部从这里 O(1) 读取                   │
└──────────────────────────────────────────────────────────────────────────┘
```

要点：
- **共享解析模型**：天气现象本身就是解析式（台风剖面、风暴环、下击暴流……），完全可以在客户端用同样的参数重算，网络只需传几十个参数而不是场。这同时解决 V-DATA-1/2/5。
- **时间插值**：粗场保留两帧，`lerp`；现象位置按速度外推，收到新快照时用 0.5 秒平滑校正。
- **天气纹理**：每 0.5 秒把 ScalarFields 栅格化到一张 128×128 的 `DynamicTexture`（以相机为中心，覆盖 ±1024 格，R=降水、G=云量、B=沙尘、A=雷暴强度），供 T2/T3 着色器采样。

---

## 4. 各效果的实现建议

### 4.1 降水：从"全局雨"到"局地降水"

**方案 A（T1，强烈推荐先做）：驱动原版雨的强度**

原版客户端的雨幕密度、天空变暗、雨声、地面溅射、以及**光影包的 `rainStrength`/`wetness`**，都读取 `ClientLevel` 的 `rainLevel` / `thunderLevel`。只要每个客户端 tick 根据相机处的局地降水强度 `P` 设置：
```java
// 客户端 tick（ClientTickEvent.Pre）
float p = atmosphere.precipAt(cam);           // 0..1，已做时间平滑
float t = atmosphere.thunderAt(cam);
level.setRainLevel(Math.max(serverRain, p));  // 与原版全局雨取大值
level.setThunderLevel(Math.max(serverThunder, t));
```
就能得到：风暴单体经过时局地下雨、台风雨带扫过时雨势起伏、晴天里远处的雷暴不影响玩家头顶。生物群系降水类型（沙漠无雨、雪原下雪）由原版自动处理。代价几乎为零，且对 Iris/Oculus 光影包**天然兼容**。

注意：原版用 `isRaining()` 判断某些客户端逻辑（如雨声），这正是我们想要的；服务端的真实天气不受影响（仅客户端视觉）。需要保存服务端同步来的原始值（`ClientboundGameEventPacket` 的 RAIN_LEVEL_CHANGE），在其基础上合成。

**方案 B（T2）：雨幕随风倾斜**

原版 `LevelRenderer.renderSnowAndRain` 以相机为中心逐列绘制竖直四边形。实现倾斜有两条路：
1. NeoForge 的 `IDimensionSpecialEffectsExtension#renderSnowAndRain(...)` 返回 true 可接管雨雪渲染。但要对主世界生效需替换其 `DimensionSpecialEffects`，与其他也这么做的模组冲突；
2. **Mixin 进 `renderSnowAndRain`**（推荐，配置开关 + 冲突检测）：复刻原版循环，对每个雨柱的上沿顶点加水平偏移
   `Δ = w_h · (H / v_fall)`，其中 `w_h` 取 CameraCache 中该列的水平风，`v_fall` 雨 9 m/s、雪 1 m/s；并把 UV 的垂直滚动速度按 `|v_rel|` 调整，使大风下雨丝看起来更斜更快。雪花额外加入 `sin` 摆动与湍流。

**方案 C（T2）：雨带与降水边缘**
相机附近的雨柱按 `P(x,z)` 做逐列的 alpha/密度调制（原版是统一的），这样站在雷暴边缘能看到"雨墙"——一侧倾盆、一侧干燥。远处（>64 格）的降水用**雨幕广告牌**（竖直半透明长条带，带噪声纹理和向下滚动 UV）在雷暴核心位置绘制，形成"远处在下雨"的灰色雨帘（风暴单体的降水核心、飑线主体、台风雨带）。

**冰雹**：冰雹粒子落地时生成小反弹（`TerrainParticle` 风格的白色碎片）+ 敲击音效（复用原版 `block.glass.hit` 变调或自制），屋顶上方块被击中时播放更响的声音。

### 4.2 云：风暴必须"看得见"

这是 Zenith 当前最大的视觉缺口——风暴单体、飑线、台风在世界里**没有任何云**。

| 档位 | 方案 | 成本 | 兼容性 |
|------|------|------|------|
| T1 | **冒充体积的广告牌云团**：每个风暴单体用 30–80 个大尺寸（16–48 格）软边精灵组成积雨云（底部平、上部塔状、成熟期顶部砧状外扩），按太阳方向做两面光照（向阳亮、背阳暗、底部灰蓝）；整体按深度排序；随现象生命周期增长/消散 | 低：只是粒子/四边形 | 好 |
| T2 | **网格云**：在客户端用 3D 噪声 + 现象形状函数生成低分辨率体素（如 32×24×32），Marching Cubes 生成网格，缓存到 `VertexBuffer`，自定义着色器做边缘 alpha、Fresnel 软边、伪散射（`exp(−厚度)`）；云墙、台风眼墙（环状）用同样方法 | 中 | 一般 |
| T3 | **光线步进体积云**：在 `AFTER_TRANSLUCENT_BLOCKS` 之后的全屏 pass，半分辨率 + 时间重投影；密度 = 天气纹理（覆盖度/云类型）× Perlin-Worley 3D 噪声（离线烘焙为 128³，GL 3.2 支持 3D 纹理，需直接用 `GL12.glTexImage3D`）；光照用 Beer–Powder 近似 + Henyey–Greenstein 相函数（参考 Schneider 2015/2017 Guerrilla "Nubis"、Hillaire 2016 Frostbite）；读取主帧缓冲深度做遮挡 | 高（1.5–4 ms@1080p 半分辨率） | 差：与光影包冲突，检测到时自动关闭 |

建议路径：**先做 T1**，它能让 90% 的玩家看到"那边有一团雷暴云正在靠近"；T3 作为可选的"电影档"。

原版云层：可通过 `IDimensionSpecialEffectsExtension#renderClouds` 或 Mixin 调整原版云的颜色（雷暴区域变暗）与覆盖度（读取天气纹理，在雷暴区让原版方块云变厚变暗），这是 T1 中最便宜的"整体天空变化"。

### 4.3 天空、光照与闪电

- **天空/雾色**：`ViewportEvent.ComputeFogColor` 按局地天气调色：雷暴下偏暗蓝灰、沙尘偏黄、台风外围偏绿灰（真实的"风暴前绿天"现象可做彩蛋）、辐射雾偏冷白。
- **闪电**：
  - 云内闪电（不落地）：客户端根据快照中雷暴强度随机触发，云团内部短暂提亮（T1 云精灵的颜色瞬时加亮 + 天空亮度闪烁，原版 `ClientLevel.setSkyFlashTime` 可用）；
  - 雷声延迟：`delay = distance / 343 m/s`，远雷低通（音高降低、音量衰减）；
  - 落地闪电保持原版实体，但服务端只在已加载区块生成（文档 01 SYS-10），远处闪电改为纯客户端视觉。

### 4.4 龙卷 2.0

**物理驱动的形状**
- 风场：Burgers–Rott 涡（与服务端同一份模型）。
- 凝结漏斗半径：在 Rankine 外区，气压亏损 `Δp(r) = ρΓ²/(8π²r²)`；当亏损等于把空气抬升到抬升凝结高度所需的压降 `ρ g (z_LCL − z)` 时凝结，可得
  `r_f(z) = Γ / (2π · √(2 g (z_LCL − z)))`
  → 漏斗越接近云底越粗、越接近地面越细，强龙卷（大 Γ）能"触地"，弱龙卷只是悬空的漏斗云。这样漏斗形状直接由服务端的强度参数决定。
- 倾斜：漏斗中心线按 0–1 km 风切变偏移 `x_c(z) = ∫ (V(z) − V_storm)/w_up dz`，产生真实的弯曲"绳状"形态（消散期尤其明显）。

**渲染（T2）**
- 几何：一次性生成单位圆柱网格（48×32），存入 `VertexBuffer`；每帧只传 uniform（中心线控制点、半径函数参数、旋转相位、强度、时间）。
- 顶点着色器：按高度计算半径与中心偏移；沿法向用 3D 噪声位移（`noise(θ·k − ωt, z·k2 − vt)`）制造翻滚的"云絮"感。
- 片元着色器：fbm 噪声做 alpha、条纹沿螺旋方向（`θ + z·twist − ωt`）、边缘按视线与法线夹角软化（侧视边缘更透明）；`#moj_import <fog.glsl>` 使用原版 `linear_fog`，使龙卷正确融入雾中；用原版光照方向做简单兰伯特 + 环境项。
- 软接触：复制一份深度缓冲（`RenderTarget.copyDepthFrom`），在片元中比较场景深度做"软粒子"淡出，消除漏斗与地面/山体的硬交线。
- 多层壳：内层（浓、窄）+ 外层（稀、宽，碎屑云）两个 draw；关闭深度写入，按距离排序两层。
- 碎屑：客户端粒子在漏斗底部生成，速度由 Burgers–Rott 场积分（切向 + 径向入流 + 上升），使用 `TerrainParticle` 取地面方块纹理（草、泥土、沙）——在什么地面上就卷起什么碎屑。
- 地面尘环：改为贴地的粒子环或投影贴花（decal），而不是悬空平面。

**渲染管线注意事项（1.21.1）**
```java
@SubscribeEvent
static void onStage(RenderLevelStageEvent e) {
    if (e.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) return;
    var buffers = Minecraft.getInstance().renderBuffers().bufferSource();
    PoseStack ps = e.getPoseStack();
    ps.pushPose();
    Vec3 cam = e.getCamera().getPosition();
    ps.translate(-cam.x, -cam.y, -cam.z);
    // ... 写入顶点
    ps.popPose();
    buffers.endBatch(ZenithRenderTypes.TORNADO);   // 立即提交，避免被后续阶段以其他矩阵 flush
}
```
半透明 RenderType 使用 `setWriteMaskState(COLOR_WRITE)`（不写深度），`setDepthTestState(LEQUAL_DEPTH_TEST)`。

### 4.5 台风的世界表现

| 元素 | T1 实现 | T2/T3 增强 |
|------|------|------|
| 外围 | 天空/雾色渐暗；原版云变厚；阵风驱动的雨量起伏（4.1 方案 A） | 高层卷云（cirrus）条纹沿出流方向辐射 |
| 雨带 | 雨带位置的降水强度按螺旋函数调制；远处雨帘广告牌 | 网格/体积云沿螺旋臂 |
| 眼墙 | 极强雨、强风迹、海面水沫 | 环形云墙（网格），眼区可见蓝天（"体育场效应"） |
| 眼区 | 风骤停、雨停、天空转亮（局地 `rainLevel` 降为 0） | — |
| 海面 | 水面附近大量水沫粒子顺风贴水飞行，浪尖白沫粒子 | 若要真正的浪，需要水体着色器，与 Sodium/光影冲突大，不建议 |
| 陆地 | 树叶粒子剧增、扬尘、风声强、方块被吹落（可选玩法） | — |

### 4.6 雾 2.0

- 修复 F-1/F-2（必须 cancel + 只在 `FogType.NONE` 下工作 + 排除失明/黑暗）。
- 浓度做一阶平滑（τ≈3 s），并在相机移动跨越雾区边界时平滑过渡。
- **辐射雾**：贴地，取 `max(0, 1 − (y − ground)/H_fog)` 高度权重（H_fog≈8–20 格），谷底更浓（可用 L1 地形曲率判断洼地）。原版距离雾无法表达高度，因此 T1 组合为：中等距离雾 + 低空大尺寸雾粒子（4.6 软雾精灵）；T2 用自定义着色器在后处理中加**高度雾**（根据深度重建世界坐标，按高度指数衰减积分，公式 `∫ρ0·e^{−(y−y0)/H} ds` 有闭式解）。
- **平流雾**：海面/湖面上方 + 上风向水面占比高 + 风速 1.5–9 m/s；雾粒子随风推进上岸。
- **沙尘暴**（新）：风速 >10 m/s 且地表为沙/红沙 → 黄色距离雾 + 扬尘粒子 + 能见度降低。

### 4.7 植被与环境响应

| 效果 | 方式 | 兼容性说明 |
|------|------|------|
| 树叶/草随风摆动 | 覆盖原版核心着色器 `rendertype_cutout_mipped.vsh`，按顶点世界坐标 + 风场 uniform 摆动 | Sodium/Embeddium 使用自有地形着色器，原版核心着色器覆盖对其**无效**；光影包同样接管。建议仅作为"原版渲染器下"的可选项，并检测 Sodium 时禁用 |
| 光影包联动 | 光影包读取 `rainStrength`、`wetness`、`thunderStrength`；通过 4.1 方案 A 驱动这些值即可让光影包的湿地面、雨雾自动生效 | 最佳兼容路线 |
| 落叶 | 从玩家附近真实树叶方块下方生成，颜色取 `BlockColors`（随生物群系着色） | 纯粒子 |
| 风车/旗帜/绳索类模组 | 通过 API 暴露客户端风（`ZenithClientWindApi`），提供"推荐的视觉响应函数"与示例代码 | 生态建设 |
| 原版烟与火焰 | 改为松弛模型（2.2 P-1），营火烟在强风中贴地拖长 | 已有 Mixin，改公式即可 |

### 4.8 屏幕空间效果（T3，可选）

- 镜头雨滴：玩家面朝来雨方向、头顶无遮挡时，在屏幕上绘制水滴折射（后处理 PostChain，`assets/zenith/shaders/post/*.json`）。
- 热浪扭曲：熔岩/沙漠正午地表上方，用法线噪声扰动 UV（仅局部区域，按深度限制在近处地面附近）。
- 沙尘/暴雨色调与暗角。
- 所有后处理在 Iris 激活时禁用（`IrisApi.getInstance().isShaderPackInUse()`，软依赖）。

### 4.9 风向标、帆船与可动模型

- 改用**带贴图的模型**：静态部分写成 JSON 方块模型（享受原版光照、AO、资源包可替换），旋转部分用 `ModelPart`/`LayerDefinition` 在 BER 中绘制，`RenderType.entityCutout(texture)`，传入 `packedLight` 与 `OverlayTexture.NO_OVERLAY`。
- 角度插值：方块实体保存 `prevYaw/yaw`，客户端每 tick 更新（而不是每 20 tick），渲染时 `Mth.rotLerp(partialTick, prev, cur)`；角度本身用带阻尼的二阶系统（风向标有惯性和过冲，会轻微摆动，非常生动）：
  `θ'' = −k(θ − θ_wind) − c·θ' + 湍流扰动`
- 修正镜像：`Axis.YP.rotationDegrees(yaw)`（若 yaw = atan2(x, z)），并决定箭头指向"来向"还是"去向"（气象惯例：风向标箭头指向风的**来向**）。
- 帆船：
  - 帆用 8×6 细分网格，顶点按"帆鼓"函数位移：`belly(u,v) = depth · sin(πu) · (1 − v·0.4)`，`depth` 随视风动压与 trim；
  - 当攻角过小（帆"抖动/luffing"）时叠加高频噪声位移，配合布料拍打音效；
  - 船体横倾角由帆侧力力矩与扶正力矩平衡给出，渲染时绕船艏轴旋转；
  - 船尾尾迹：水面上的白沫粒子 + 原版气泡。

### 4.10 声音设计

| 层 | 触发 | 实现 |
|------|------|------|
| 风底噪（已有） | 风速 >1.5 m/s | 修 `canStartSilent`；音量按"天空可见度"衰减（在玩家周围 5–9 个点做 `canSeeSky` 采样，平滑） |
| 呼啸/哨音 | 风速 >12 m/s 或阵风峰值 | 随机触发的一次性音效，音高随风速 |
| 树叶沙沙 | 附近树叶方块数 × 风速 | 位置音源放在最近的树冠 |
| 雨打屋顶 | 下雨 & 头顶有方块 | 原版有 `weather.rain.above`，可根据局地降水调节 |
| 远雷 | 云内/落地闪电 | 延迟 `d/343`、远距离低音量低音高 |
| 台风眼 | 进入眼区 | 风声骤降至几乎无声，形成强烈对比 |
| 冰雹 | 冰雹区 | 密集的"敲击"粒子音，屋内更响 |

建议与 Sound Physics Remastered 兼容（它提供混响/遮蔽），检测到时减少 Zenith 自己的遮蔽衰减，避免重复。

### 4.11 气象图 2.0

- 底图：把风速栅格化为 `DynamicTexture`（如 128×128），用感知均匀的色阶（viridis/turbo）着色，一次性 `blit`，取代逐像素 fill；
- 流线：用 **LIC（线积分卷积）** 或短流线簇绘制，比箭头更直观；
- 等压线：L0 气压场 marching squares；
- 天气符号：锋面用标准气象符号（冷锋三角、暖锋半圆，沿线等距绘制），台风用自制图标贴图；
- 数据范围：只绘制有真实数据的区域（服务端为地图单独发送一份更大范围、更低分辨率的 L0 摘要，比如 41×41@256），域外显示"无数据"阴影；
- 交互：滚轮缩放、拖动、悬停显示读数、时间轴（未来做预报时展示 +1h/+3h）。

### 4.12 调试可视化（开发者档）

- `/zenithc debug vectors`：以 `RenderType.lines()` 在玩家周围绘制 3D 风矢量网格（颜色=风速，长度=对数风速），并可切换图层（L1 / 现象 / L2 / 总和）；
- `/zenithc debug profile`：屏幕 HUD 显示当前列的垂直风廓线、温度、湿度、稳定度；
- 现象轮廓：台风 Rmax/眼区/影响半径、风暴阵风锋环、飑线阵风带，用线框显示；
- F3 调试文本：`CustomizeGuiOverlayEvent.DebugText` 加入"Zenith: 风 7.2 m/s ENE, 湍流 18%, 来源 L1+TY+L2"。
这些调试工具会极大加速文档 01 中物理问题的修复与回归。

---

## 5. 着色器与 RenderType 工程模板（1.21.1）

**5.1 注册核心着色器**
```java
@EventBusSubscriber(modid = "zenith", value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
public final class ZenithShaders {
    public static ShaderInstance TORNADO;
    @SubscribeEvent
    static void register(RegisterShadersEvent e) throws IOException {
        e.registerShader(new ShaderInstance(e.getResourceProvider(),
                ResourceLocation.fromNamespaceAndPath("zenith", "tornado"),
                DefaultVertexFormat.POSITION_TEX_COLOR), s -> TORNADO = s);
    }
}
```
`assets/zenith/shaders/core/tornado.json`：
```json
{
  "vertex": "zenith:tornado",
  "fragment": "zenith:tornado",
  "samplers": [ { "name": "Sampler0" } ],
  "uniforms": [
    { "name": "ModelViewMat", "type": "matrix4x4", "count": 16, "values": [1,0,0,0, 0,1,0,0, 0,0,1,0, 0,0,0,1] },
    { "name": "ProjMat",      "type": "matrix4x4", "count": 16, "values": [1,0,0,0, 0,1,0,0, 0,0,1,0, 0,0,0,1] },
    { "name": "ColorModulator","type": "float", "count": 4, "values": [1,1,1,1] },
    { "name": "FogStart", "type": "float", "count": 1, "values": [0] },
    { "name": "FogEnd",   "type": "float", "count": 1, "values": [1] },
    { "name": "FogColor", "type": "float", "count": 4, "values": [0,0,0,0] },
    { "name": "FogShape", "type": "int",   "count": 1, "values": [0] },
    { "name": "GameTime", "type": "float", "count": 1, "values": [0] },
    { "name": "ZenithVortex", "type": "float", "count": 4, "values": [0,0,0,0] }
  ]
}
```
片元着色器中 `#moj_import <fog.glsl>` 并调用 `linear_fog(color, vertexDistance, FogStart, FogEnd, FogColor)`，即可与原版雾一致。`GameTime` 由原版自动设置；自定义 uniform（如 `ZenithVortex`）在自定义 `RenderStateShard` 的 setup 回调中 `TORNADO.safeGetUniform("ZenithVortex").set(...)`。

**5.2 半透明不写深度的 RenderType**
```java
public static final RenderType TORNADO = RenderType.create("zenith_tornado",
    DefaultVertexFormat.POSITION_TEX_COLOR, VertexFormat.Mode.QUADS, 1 << 16, false, true,
    RenderType.CompositeState.builder()
        .setShaderState(new RenderStateShard.ShaderStateShard(() -> ZenithShaders.TORNADO))
        .setTextureState(new RenderStateShard.TextureStateShard(NOISE_TEX, true, false))
        .setTransparencyState(RenderStateShard.TRANSLUCENT_TRANSPARENCY)
        .setWriteMaskState(RenderStateShard.COLOR_WRITE)
        .setCullState(RenderStateShard.NO_CULL)
        .setDepthTestState(RenderStateShard.LEQUAL_DEPTH_TEST)
        .createCompositeState(false));
```

**5.3 帆船/风向标改用受光照的实体 RenderType**
```java
VertexConsumer vc = buffers.getBuffer(RenderType.entityCutoutNoCull(TEXTURE));
model.renderToBuffer(poseStack, vc, packedLight, OverlayTexture.NO_OVERLAY, 0xFFFFFFFF);
```

**5.4 速度拉伸的风迹粒子**
```java
@Override
public void render(VertexConsumer vc, Camera cam, float pt) {
    Vec3 p = new Vec3(Mth.lerp(pt, xo, x), Mth.lerp(pt, yo, y), Mth.lerp(pt, zo, z)).subtract(cam.getPosition());
    Vec3 v = new Vec3(xd, yd, zd);
    double len = Math.min(3.0, v.length() * 6.0);              // 拉伸长度 ∝ 速度
    Vec3 dir = v.lengthSqr() < 1e-8 ? new Vec3(1, 0, 0) : v.normalize();
    Vec3 side = dir.cross(p.normalize()).normalize().scale(quadSize);
    Vec3 head = p.add(dir.scale(len * 0.5)), tail = p.subtract(dir.scale(len * 0.5));
    // tail 端 alpha=0，head 端 alpha=a，四个顶点：tail±side, head±side
}
```

---

## 6. 兼容性矩阵

| 模组 / 环境 | 影响 | 策略 |
|------|------|------|
| Sodium / Embeddium | 接管地形渲染；原版核心着色器覆盖（植被摆动）失效；粒子、BER、实体不受影响 | 植被摆动在检测到时关闭 |
| Iris / Oculus（光影） | 自定义 RenderType/着色器可能不被光影管线识别（不可见或无光照）；后处理冲突 | 软依赖 `IrisApi`：光影激活时使用 T1 档（粒子 + 原版雨/雷强度驱动），关闭 T2/T3 着色器 |
| Distant Horizons | 远景 LOD 有自己的雾；Zenith 的雾距修改可能与 DH 的雾混合不一致 | 读取 DH API 判断，必要时仅调颜色不调距离 |
| 其他天气模组（Weather2、Pretty Rain、Particle Rain 等） | 雨雪渲染 Mixin 冲突、粒子重复 | 冲突检测 + 配置开关；文档列出已知冲突 |
| Serene Seasons / Fabric Seasons 等 | 季节冲突 | 提供"跟随外部季节模组"选项 |
| Sound Physics Remastered | 遮蔽/混响重复 | 检测到时关闭 Zenith 自有遮蔽 |

---

## 7. 性能预算（目标值，1080p，中端显卡）

| 子系统 | T1 | T2 | T3 |
|------|------|------|------|
| ClientAtmosphere 更新（CPU） | ≤0.15 ms/帧 | 同 | 同 |
| 粒子（总数上限） | 1,500 | 3,000 | 4,000 |
| 粒子 CPU | ≤0.4 ms | ≤0.8 ms | ≤1.0 ms |
| 云 | 广告牌 ≤400 片 | 网格 ≤60k 三角形 | 半分辨率光线步进 ≤3 ms GPU |
| 龙卷 | — | ≤0.3 ms GPU / 个 | 同 |
| 雨幕倾斜（Mixin） | 0 | ≈原版成本 | 同 |
| 后处理 | — | 高度雾 ≤0.4 ms | 镜头雨/热浪 ≤0.5 ms |

动态调节：根据最近 60 帧平均帧时间自动降低粒子密度与云片数量（"自适应质量"），并在设置界面暴露档位。

---

## 8. 面向后续版本的渲染抽象

Minecraft 渲染接口在 1.21.x 内多次大改：
- 1.21.2：着色器定义与 `RenderType` 相关接口调整；
- 1.21.5：引入 `RenderPipeline` / `GpuDevice`，`ShaderInstance` 被移除；
- 1.21.9：两段式"提取—提交"渲染，NeoForge 的 `RenderLevelStageEvent` 改为子事件，并要求在 `ExtractLevelRenderStateEvent` 中提取状态。

建议现在就引入：
```
client/render/backend/
   ZenithRenderBackend        // 接口：drawTornado(state), drawCloudImpostors(list), drawRainOverlay(...)
   ZenithRenderState          // 纯数据：每帧从 ClientAtmosphere 提取（天然契合 1.21.9 的 extract 阶段）
   v1211/Backend1211          // 当前实现：ShaderInstance + RenderType
```
所有效果先"提取状态"再"绘制"，跨版本时只需重写后端。

---

## 9. 分阶段实施计划（视觉侧）

| 阶段 | 内容 | 验收标准 |
|------|------|------|
| V0 热修（1–2 天） | 雾 cancel + 模式判断；风声 `canStartSilent`；客户端指令换总线；落叶生物群系标签；龙卷 ×256 与 `endBatch`；风向标镜像；粒子松弛模型 | 四项"失效功能"恢复；粒子速度与风速同量级（误差 <20%） |
| V1 数据通路（1–2 周） | ClientAtmosphere：粗场时间插值、现象解析共享、航位推算、相机缓存、清理逻辑 | 台风附近粒子方向与服务端 `/zenith wind` 读数一致；无 2 秒跳变 |
| V2 T1 天气可见化（2–3 周） | 局地降水驱动原版雨/雷；雨幕倾斜；雨帘广告牌；广告牌积雨云；天空/雾色；云内闪电与雷声延迟；软雾粒子；声音分层 | 站在雷暴边缘能看到"雨墙"；台风眼内雨停天晴 |
| V3 T2 着色器（3–4 周） | 龙卷 2.0；网格云墙/眼墙；高度雾后处理；带贴图模型与帆布形变 | 龙卷与雾正确融合、与地面软接触；光影激活时自动降级无报错 |
| V4 T3 可选（按需） | 光线步进体积云、屏幕空间效果、植被摆动（原版渲染器） | 帧时间预算达标；可在设置中完全关闭 |

---

## 附：参考资料（供实现时查阅）

- NeoForge 1.21.1 Javadoc：`ViewportEvent.RenderFog`（"The event must be cancelled for any changes to the plane distances to take effect"）、`RenderLevelStageEvent`、`RegisterClientCommandsEvent`（游戏总线）、`RegisterShadersEvent`、`IDimensionSpecialEffectsExtension`。
- Minecraft `SoundEngine.play`：音量为 0 且 `canStartSilent()==false` 时跳过播放。
- A. Schneider, "The Real-time Volumetric Cloudscapes of Horizon: Zero Dawn", SIGGRAPH 2015；"Nubis: Authoring Real-Time Volumetric Cloudscapes", SIGGRAPH 2017。
- S. Hillaire, "Physically Based Sky, Atmosphere and Cloud Rendering in Frostbite", SIGGRAPH 2016。
- Burgers–Rott 涡与凝结漏斗：Rotunno (2013), "The Fluid Dynamics of Tornadoes", *Annual Review of Fluid Mechanics*。
- Cabral & Leedom, "Imaging Vector Fields Using Line Integral Convolution", SIGGRAPH 1993（气象图流线）。
