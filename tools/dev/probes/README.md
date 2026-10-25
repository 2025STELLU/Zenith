# tools/dev/probes

一次性诊断程序：直接 `main()` 打印数值，不是单元测试。

用途是把写进 `docs/04 §17.2` / `§18.6` 的那些 gold values 现算一遍。它们依赖的类在
`Workspace/src/main/java`，跑之前要先执行 `tools/dev/build.ps1`。

```powershell
powershell -NoProfile -File tools/dev/probe.ps1 -List
powershell -NoProfile -File tools/dev/probe.ps1 L1Probe
```

| 探针 | 输出 |
|------|------|
| `L1Probe` | 平地 `y=64`、L0 基流 3.2022 m/s 下各 AGL 的风速比（1.62 → 0.4078、10 → 0.6054、300 → 1.0000）与 Ekman 偏转（`turnNear` / `turnTop`） |
| `SailingProbe` | `SailingPhysics.driveCoefficient()` 在 0..180° 的取值与一次 `step()` 的分量 |
| `AirfoilProbe` | NACA 预设的上下表面配对数与 `maxThickness()`（0012 / 2412 / 4412） |

加新探针就是往这个目录扔一个 `public class Xxx { public static void main }`，
文件名 = 类名，然后用 `probe.ps1 Xxx` 跑。
