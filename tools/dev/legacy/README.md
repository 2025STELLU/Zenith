# tools/dev/legacy

这些是 0.1.5-alpha 审计期间临时写的脚本，**已不再使用**，保留只为追溯当时怎么跑的。

它们有两个问题：

1. 路径全部写死成 `D:\Stellux\MCDev\Zenith\...`，换机器或换目录就跑不了；
2. 输出目录是 `.javac-out` / `.javac-out2` / `.javac-out3` / `.javac-test-out` 四个并存的临时目录，互相覆盖。

现行脚本在上一层（`tools/dev/`）：路径通过 `$PSScriptRoot` 推导，产物统一进 `.build/`。

| 旧脚本 | 现行替代 |
|--------|----------|
| `.check.ps1` | `build.ps1`（无 classpath 的早期版本，已废弃） |
| `.check2.ps1` | `build.ps1`（指向外部 mod 构建的合并 jar，已废弃） |
| `.check3.ps1` | `build.ps1` |
| `.check-tests.ps1` | `test-compile.ps1` |
| `.run-tests.ps1` | `test-run.ps1` |
| `.verify.ps1` | `verify.ps1` |
| `.filter.ps1` / `.filter2.ps1` | 已并入 `build.ps1` 的错误分组输出 |
| `.javap.ps1` | `javap.ps1`（改为接受参数） |
