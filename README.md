# Zenith

Minecraft 多尺度实时风与天气模拟（NeoForge 1.21.1）。

模组本体在 `Workspace/`，设计文档与审计报告在 `docs/`。

## 目录

| 路径 | 内容 |
|------|------|
| `Workspace/` | 模组工程（Gradle 项目，`build.gradle` / `src` / `gradle.properties`） |
| `Workspace/src/main/java/net/atomos/zenith/` | 源码：`wind/` `weather/` `api/` `client/` `block/` `entity/` `item/` `network/` `mixin/` |
| `Workspace/docs/` | 模组自述文档（0.1.4 版） |
| `docs/` | 独立审计报告与修复记录 |
| `releases/` | 历史构建产物 |
| `tools/dev/` | 离线编译 / 测试 / 复算脚本，以及 `javap` 查询工具 |

## 审计与修复记录

| 文件 | 内容 |
|------|------|
| `docs/01_Zenith_全模组审计报告.md` | 0.1.1 基线审计 |
| `docs/02_Zenith_特效渲染专题审计及建议.md` | 渲染专题 |
| `docs/03_Zenith_开发路线图.md` | 路线图 |
| `docs/04_Zenith_0.1.4_全模组独立审计报告.md` | 0.1.4 全模组审计（136 条）＋ **§18 修复记录（0.1.5-alpha）** |

`docs/04` 的 §2–§13 保留审计时的原始判断（含"（未修）"标记）；**实际修复状态看 §18**：122 条现存问题中 121 条关闭、1 条部分关闭（`L2-02`）。

## 构建

标准方式（需要能联网解析 NeoForge 依赖）：

```powershell
cd Workspace
.\gradlew build
```

本机 Gradle 无法解析 `net.neoforged:neoform-runtime`（离线且缺 descriptor），因此提供了一套等价的离线脚本：

```powershell
# 全量：编译 + 编译测试 + 跑测试 + 复算 gold values
powershell -NoProfile -File tools/dev/check.ps1

# 也可以单独跑
powershell -NoProfile -File tools/dev/build.ps1         # javac 全部源码
powershell -NoProfile -File tools/dev/test-compile.ps1  # 编译 src/test/java
powershell -NoProfile -File tools/dev/test-run.ps1      # JUnit 5（80 用例）
powershell -NoProfile -File tools/dev/verify.ps1        # docs/04 §17.2 的 gold values

# 查一个类的签名
powershell -NoProfile -File tools/dev/javap.ps1 net.minecraft.server.MinecraftServer -Filter 'save.*'

# 跑一个临时探针（读数值用，不是单元测试）
powershell -NoProfile -File tools/dev/probe.ps1 -List
powershell -NoProfile -File tools/dev/probe.ps1 L1Probe
```

产物全部落在 `.build/`（已忽略）。脚本通过 `$PSScriptRoot` 定位仓库，依赖 JDK 21（可用 `ZENITH_JAVA_HOME` 覆盖）与 `GRADLE_USER_HOME`（默认 `~/.gradle`）。

`tools/dev/libs/neoforge-merged.jar` 是编译所需的合并 jar（NeoForge + Minecraft，约 31 MB）。它是可再生的编译输入，**没有入库**；缺失时按 `tools/dev/libs/README.md` 的三级退让规则查找。

## 许可

见 `Workspace/LICENSE`：禁止非授权商用与同赛道竞品，其余在**署名原作者**前提下允许二次分发。
