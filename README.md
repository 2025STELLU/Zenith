# Zenith

Minecraft 多尺度实时风与天气模拟（NeoForge 1.21.1）。

风场/天气系统架构移植并改写自 [Aerodynamics4MC-Core](https://github.com/MozillaFiredoge/Aerodynamics4MC-Core)
（MIT 许可，详见 `LICENSE` 与 `THIRD_PARTY_LICENSES/`），包名已改为 `net.atomos.zenith`，
原生 JNI LBM 求解器替换为纯 Java 实现，并新增了热对流、海风、风暴、台风系统。

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
- 客户端：服务端每 2 秒广播粗风场，客户端本地求解器提供可视化细节。

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

客户端可视化用 `ZenithClientWindApi.sample(world, pos, SamplePolicy.CLIENT_LOCAL_PREFERRED)`。

## 构建

```bash
./gradlew build
```

需要 JDK 21（Gradle toolchain 自动下载）。产物在 `build/libs/`。
