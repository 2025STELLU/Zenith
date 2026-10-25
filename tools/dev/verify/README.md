# tools/dev/verify

| 文件 | 用途 |
|------|------|
| `Verify.java` | gold values 独立复算。每个公式按规格重新实现，**不调用生产代码**，所以重构时改了常量会在这里暴露。对照 `docs/04 §17.2`。 |
| `RunTests.java` | JUnit 5 离线启动器（`junit-platform-launcher`）。Gradle 的 `test` 任务在本机被依赖解析卡住，只能绕开。 |

由 `tools/dev/verify.ps1` 与 `tools/dev/test-run.ps1` 调用，不需要手动编译。
