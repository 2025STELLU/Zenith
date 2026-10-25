# Zenith

Minecraft 多尺度实时风与天气模拟（NeoForge 1.21.1）。

风场/天气系统的设计思路受 [Aerodynamics4MC-Core](https://github.com/MozillaFiredoge/Aerodynamics4MC-Core)
启发，但全部代码为独立编写的 cleanroom 实现，未复制其源码（致谢见 `ACKNOWLEDGMENT_Aerodynamics4MC.md`）。
包名为 `net.atomos.zenith`，在此感谢 Firedoge 的灵感和技术分享。
我把原生的JNI LBM求解器替换为了纯Java版的实现，并新增了热对流、海风、风暴、台风系统。

## 架构

```
WorldScaleDriver（行星尺度：气旋/对流团/龙卷/台风/行星波）
        │  target wind, ΔP, T bias, humidity, storm activity
        ▼
L0 BackgroundMetGrid（41×41 @256格：半拉格朗日平流-扩散-地转调整-地形阻力）
        │  wind, pressure, T, humidity
        ▼
L1 MesoscaleGrid（33×33×8 @64×64×40：ABL风切变/Ekman偏转/地形反弹/阵风诊断）
        │  3D wind, turbulence, gust, shear, ABL diagnostics
        ▼
L2 LocalFlowSolver（纯Java：障碍物绕流+浮力，客户端本地高分辨率细节）
```

- 服务端权威：L0/L1/驱动器；游戏玩法一律走 `ZenithWindApi.sampleGameplay()` 并检查
  `isTrustedForGameplay()`。
- 客户端：服务端会每2秒广播粗风场，客户端内置的本地求解器会提供可视化细节。

## 新增天气系统

| 系统 | 说明 |
|------|------|
| 热对流 Thermal | 日照加热地表→热泡上升气流（2–6 m/s），地表类型决定加热速率 |
| 海风 Sea breeze | 海陆温差→白天向岸风/夜间离岸风，海风锋向内陆推进 |
| 风暴单体 Storm cell | 积雨云生命史（发展→成熟→消散）、阵风锋外流、闪电 |
| 台风 Typhoon | 暖心涡旋、眼墙、螺旋雨带、路径移动、登陆减弱 |

## 内容

- 方块：风扇（定向风源）、风道、风向标、风力涡轮探针（红石输出）、气象图物品、风速计
- 粒子：风迹、落叶/碎屑、雨丝随风偏斜、热浪、台风雨带水沫
- 帆船：风力驱动的帆船实体（`SailingPhysics`）
- 指令：`/zenith status|wind|storm|typhoon|thermal|seabreeze …`

## 公共 API（供其他模组调用）

```java
ZenithWorldRef world = ZenithWorldRef.server(ZenithId.of("minecraft", "overworld"));
GameplayWindSample wind = ZenithWindApi.sampleGameplay(world, ZenithBlockPos.of(x, y, z));
if (wind.isTrustedForGameplay()) {
    ZenithVec3 v = wind.effectiveVelocityVector();
    float turb = wind.turbulenceIntensity();
}
```

客户端可视化使用 `ZenithClientWindApi.sample(world, pos, SamplePolicy.CLIENT_LOCAL_PREFERRED)`。

## 构建

```bash
./gradlew build
```

需要JDK 21（Gradle toolchain 自动下载）。编译后的产物在 `build/libs/`中。

## 许可证

**允许修改，也允许二次分发，但必须署名原作者。** 具体是：保留 "Zenith by 2025STELLU"、附上官方发布链接、并在显眼位置注明"基于 Zenith 修改"；不得移除或弱化作者信息后当成自己的作品发布。商用与同赛道竞品仍需作者事先书面许可。以 `LICENSE` 为准（四条）。

本模组使用Muse Spark 1.3补充了源码注释并完善了某些我自己的语法错误（懒得去写注释了）。

By Stellux

## 仓库结构

| 路径 | 内容 |
|------|------|
| `build.gradle` / `settings.gradle` / `gradle.properties` | 标准 Gradle 工程（ModDevGradle 2.0.148，NeoForge 21.1.256） |
| `src/main/java/net/atomos/zenith/` | 源码：`wind/` `weather/` `api/` `client/` `block/` `entity/` `item/` `network/` `mixin/` |
| `src/main/resources/` | `neoforge.mods.toml`、语言文件、数据包（`damage_type/hail.json` 等） |
| `src/test/java/` | JUnit 5 单元测试（80 例，纯函数，不依赖 MC 运行时） |
| `docs/` | 审计报告 `01`–`04`（`04` 含 §18 修复记录）、`WEATHER_SYSTEMS.md`、模组自述在 `docs/mod/` |
| `tools/` | `gen_textures.py`（贴图生成）与 `dev/`（离线编译 / 测试 / 复算脚本） |
| `releases/` | 历史发布包 |

### 离线构建

本机 Gradle 无法解析 `net.neoforged:neoform-runtime`（离线且缺 descriptor），所以 `tools/dev/` 下有一套等价的
javac + JUnit 脚本：

```powershell
powershell -NoProfile -File tools/dev/check.ps1   # 编译 + 编译测试 + 跑测试 + 复算 gold values
```

产物落在 `.build/`（已忽略）。详见 `tools/dev/libs/README.md` 关于合并 jar 的说明。

### 修复状态

`docs/04_Zenith_0.1.4_全模组独立审计报告.md` 的 §2–§13 保留审计时的原始判断，**实际修复状态看 §18**：
122 条现存问题中 121 条关闭、1 条部分关闭（`L2-02`）。
