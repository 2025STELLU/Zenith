# Zenith 天气系统设计

> 移植自 Aerodynamics4MC-Core（MIT）的部分见各 Java 文件头注释。
> 本文档描述 Zenith 新增的四个天气系统与纯 Java L2 求解器。

## 1. 纯 Java L2 本地流场求解器（`wind.LocalFlowSolver`）

原版 L2 是 D3Q27 cumulant LBM 原生求解器（JNI + OpenCL），Zenith 改为无状态
逐点诊断模型，零 native 依赖：

- **障碍物绕流**：以采样点为中心 9³ 扫描固体方块；每块产生径向排挤
  `push ∝ baseSpeed·0.35/min(1.2, 1/d²)`；下风方向 14 格内锥形尾流亏损
  `deficit = exp(-across²/coneR²)·exp(-along/9)·0.55`，亏损 >0.45 标记 sheltered。
- **热浮力**：热源缓存（熔岩 9000W / 营火 2000W / 火把 80W …），
  上升 `w = min(6, power/1500·4)·1/(1+d²/r²)`，附带向内辐合。
- **风扇射流**：`Jet` 记录（位置/方向/速度/半径/长度），锥形扩散 + 指数衰减。
- **海绵层**：`NestedBoundaryCoupler.spongeBlend`，局部域（48 格）边缘 16 格
  二次混合回 L1 基流，防止截断伪影。

服务端 gameplay 采样同样走 L2（遮蔽/绕流对玩法可见），客户端只做
coarse 插值 + 视觉抖动。

## 2. 热对流（`weather.ThermalSystem`）

- 日照 `solar = max(0, sin(tod·2π − π/2))`，雨天 ×0.25。
- 地表加热速率：沙漠 1.0 / 岩石 0.9 / 草地 0.55 / 森林 0.35 / 海洋 0.1 …
- 白天在玩家 320 格内生成热泡（≤24 个）：半径 8–20 格，
  上升 2–6 m/s × (0.5+0.7·rate) × (0.4+0.8·solar)，寿命 5–15 min，
  随低层风漂移。
- 采样：柱内 `w = updraft·(1−(r/R)²)·envelope` + 向内辐合；
  柱外 1–2.2R 环形下沉补偿。

## 3. 海风（`weather.SeaBreezeSystem`）

- 每 100 tick 在焦点周围重算 33×33 @64 格海风场（海岸线不动，重算贵）。
- `findCoast`：8 方向放射搜索 32–480 格找海陆分界，得离岸距离与指海向量。
- 白天海风（海→陆）峰值 5 m/s，夜间陆风（陆→海）2 m/s；
  衰减 `exp(−dist/170)`，高度衰减 `exp(−agl/150)`。
- 海风锋：白天以 1.6 m/s 向内陆推进（上限 320 格），锋面 ±60 格高斯带
  产生辐合上升 1.2 m/s + 湍流 + 积云降水倾向。

## 4. 风暴单体（`weather.StormCellSystem`）

生命史 50 min：发展 15 min（上升 6 m/s）→ 成熟 20 min →
消散 15 min。成熟期：

- 降水核心（<R）：下沉 −4.5 m/s，降水强度 0.9；
- 阵风锋（R–2.2R）：径向外流 `12·sin(ring·π)` m/s，前缘（移动方向）×1.5；
- 闪电：每 4–18 s 在单体范围内打一次真闪电（`LightningBolt` 实体）。

生成条件：`stormActivity > 0.45`，湿度/不稳定加权，≤8 个/维度。

## 5. 台风（`weather.TyphoonSystem`）

Rankine 组合涡旋：

- 切向风 `V(r) = Vmax·r/Rmax (r<Rmax)`，`Vmax·(Rmax/r)^0.5 (r>Rmax)`；
  Vmax 25–60 m/s，Rmax 30–80 格，眼半径 12–25 格，影响半径 400–800 格。
- 眼区（r < 眼半径）：`eyeDampening` 对总风做 ×(1−0.92·(1−r/眼半径)²) 衰减。
- 眼墙（r≈Rmax）：上升 12 m/s + 暴雨 0.95 + 湍流。
- 螺旋雨带：3 条对数螺旋臂
  `phase = θ − ln(r/Rmax)·1.8 + t·0.004`，臂宽高斯 σ²=0.18，
  环带中心 3.2·Rmax。
- 边界层径向内流 = 15% 切向风。
- 移动：L0 基流 ×0.8 + β 漂移（−1.0, −1.1 m/s，向极向西）；
  中心上陆后强度指数衰减（τ=6h），回洋面缓慢恢复（上限 0.85）。
- 生成：洋面 + `stormActivity > 0.6`，≤2 个/维度，中文命名（天鹅/海燕/……）。

## 6. 特效与粒子

| 粒子 | 触发 |
|------|------|
| `wind_streak` | 风速 >3 m/s；可视化模式流线 |
| `leaf` | 森林生物群系 + 有风 |
| `dust` | 风速 >7 m/s 或风暴 |
| `spray` | 风暴/台风（雨带水沫）；雨天随风倾斜的雨丝 |
| `heat_shimmer` | 熔岩/火焰/营火上方 |

气象图（`MeteorologicalMapScreen`）：coarse 风场箭头（9×9）、台风螺旋图标 +
名称/风速、风暴单体（颜色表相位）、风暴活动度/热泡数/海风锋距离。
指令：`/zenith status|wind|storm|typhoon|thermal|seabreeze|polar`，
客户端 `/zenithc visualizer|map`。

## 新增：第二轮天气系统（0.1.0-alpha）

### 锋面系统（FrontSystem）
- 冷锋/暖锋线段，≤4 条/维度；由强气旋催生，随引导气流移动
- 冷锋：窄降水带 + 强阵风 + 锋后风向顺转；暖锋：宽层状降水
- 气象图绘制蓝/红锋线

### 尘卷风系统（DustDevilSystem）
- 晴热 + 弱风 + 沙漠/岩石/沙滩地表生成；≤12 个/维度
- 半径 2–6 格、高 20–60 格、切向风 5–12 m/s、寿命 1–5 分钟
- 核心强上升 + 涡旋切向风；气象图棕色点标记

### 飑线系统（SquallLineSystem）
- 线状强对流，≤2 条/维度，长 200–600 格
- 线上有强降水核心 + 频繁闪电；线前阵风锋外流 15–20 m/s
- 下击暴流：嵌入强下沉核（−10 m/s，半径 30 格）+ 触地外流

### 冰雹
- 风暴单体成熟期按单体强度计算雹强；核心区露天玩家每 2 秒 1 点伤害
- 冰雹粒子（hail）+ 快照标记 + 气象图 🧊 标记 + API 查询

### 地形背风波（MountainWaveSystem）
- 解析诊断：上风 800 格搜山脊；波长 λ=2πU/N；垂直速度 ±6 m/s
- 山后近地面转子强湍流；滑翔机可用波峰上升气流

### 雾系统（FogModel + FogController）
- 辐射雾（晴夜弱风高湿）/ 平流雾（暖湿气流过冷水面）/ 雨雾
- 客户端 ViewportEvent.ComputeFog 动态调整能见度 + 雾气粒子
- 服务端 API 可查询任意点雾浓度

### 季节 + 季风（SeasonSystem + MonsoonSystem）
- 120 天一年；季节影响气温偏置（±7K）→ L0 背景场，日照乘子 → 热对流
- 季风：夏季东南→西北、冬季反向，3–5 m/s，全维度均匀

### 风声音效
- 程序化合成棕色噪声循环（12 秒 ogg），随玩家处风速调制音量/音高

### 开放天气 API（ZenithWeatherApi）
- fronts / dustDevils / squallLines / typhoons / hailAt / fogDensityAt
- season / monsoonVector / mountainWaveLift
