# tools/dev/libs

`neoforge-merged.jar` — NeoForge 21.1.x + Minecraft 1.21.1 的合并 jar（约 31 MB）。

`tools/dev/*.ps1` 靠它拿到 `net.minecraft.*` 与 `net.neoforged.*` 的符号。它来自某个
Gradle 构建的 `build/moddev/artifacts/`，本机 Gradle 无法重新生成（离线 + 缺
`neoform-runtime` descriptor），所以只能随仓库带着。

若换机器后这里缺失，`common.ps1` 会按顺序退让：

1. `tools/dev/libs/neoforge-merged.jar`（首选）；
2. `$GRADLE_USER_HOME/caches/modules-2/files-2.1` 下最大的 `*neoforge*merged*.jar`；
3. 都没有就报错并提示放置路径。

放别的版本也可以，只要改掉文件名或让第 2 步找到它。**不要提交 `*.jar` 以外的构建产物**。
