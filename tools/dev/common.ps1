# Common paths and helpers for the offline dev scripts.
#
# The Gradle build cannot resolve its Minecraft artifacts on this machine
# (offline, no descriptor for neoform-runtime), so compile / test / verify
# are driven straight through javac + java here.  Everything is resolved
# relative to the repository root, so the scripts survive a clone.
#
# Dot-sourced by build.ps1 / test-compile.ps1 / test-run.ps1 / verify.ps1.

$ErrorActionPreference = "Stop"

$RepoRoot    = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$JavaHome    = if ($env:ZENITH_JAVA_HOME) { $env:ZENITH_JAVA_HOME } else { "C:\Program Files\Java\jdk-21" }
$Javac       = Join-Path $JavaHome "bin\javac.exe"
$Java        = Join-Path $JavaHome "bin\java.exe"
$Javap       = Join-Path $JavaHome "bin\javap.exe"
$GradleHome  = if ($env:GRADLE_USER_HOME) { $env:GRADLE_USER_HOME } else { Join-Path $env:USERPROFILE ".gradle" }
$GradleJars  = Join-Path $GradleHome "caches\modules-2\files-2.1"

$MainSrc     = Join-Path $RepoRoot "src\main\java"
$TestSrc     = Join-Path $RepoRoot "src\test\java"
$BuildDir    = Join-Path $RepoRoot ".build"
$MainOut     = Join-Path $BuildDir "classes\main"
$TestOut     = Join-Path $BuildDir "classes\test"
$RunnerOut   = Join-Path $BuildDir "classes\runner"
$LogDir      = Join-Path $BuildDir "logs"
$ArgDir      = Join-Path $BuildDir "args"

# The merged NeoForge + Minecraft jar.  Preferred location is the vendored
# copy under tools/dev/libs; if that is absent we fall back to whatever the
# Gradle cache happens to have, and finally to a sibling mod build.
$MergedJar = Join-Path $PSScriptRoot "libs\neoforge-merged.jar"
if (-not (Test-Path $MergedJar)) {
    $candidate = Get-ChildItem -Path $GradleJars -Recurse -Filter "*neoforge*merged*.jar" -File -ErrorAction SilentlyContinue |
        Sort-Object Length -Descending | Select-Object -First 1
    if ($candidate) { $MergedJar = $candidate.FullName }
}

if (-not (Test-Path $Javac)) { throw "javac not found at $Javac (set ZENITH_JAVA_HOME)" }
if (-not (Test-Path $MergedJar)) {
    throw "NeoForge merged jar not found. Put it at tools/dev/libs/neoforge-merged.jar"
}

# JUnit 5.11.4 + Gson 2.10.1, resolved from the Gradle module cache by
# version-less glob so a cache relocation does not break the scripts.
function Find-CacheJar([string]$groupPath, [string]$namePattern) {
    $root = Join-Path $GradleJars $groupPath
    if (-not (Test-Path $root)) { throw "missing cache group: $root" }
    $jar = Get-ChildItem -Path $root -Recurse -Filter $namePattern -File -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -notlike "*-sources.jar" -and $_.Name -notlike "*-javadoc.jar" } |
        Sort-Object FullName -Descending | Select-Object -First 1
    if (-not $jar) { throw "missing jar $namePattern under $root" }
    return $jar.FullName
}

$JUnitJars = @(
    (Find-CacheJar "org.junit.jupiter\junit-jupiter-api"      "junit-jupiter-api-5.11.4.jar"),
    (Find-CacheJar "org.junit.jupiter\junit-jupiter-engine"   "junit-jupiter-engine-5.11.4.jar"),
    (Find-CacheJar "org.junit.jupiter\junit-jupiter-params"   "junit-jupiter-params-5.11.4.jar"),
    (Find-CacheJar "org.junit.platform\junit-platform-commons"  "junit-platform-commons-1.11.4.jar"),
    (Find-CacheJar "org.junit.platform\junit-platform-engine"   "junit-platform-engine-1.11.4.jar"),
    (Find-CacheJar "org.junit.platform\junit-platform-launcher" "junit-platform-launcher-1.11.4.jar"),
    (Find-CacheJar "org.opentest4j\opentest4j"                "opentest4j-1.3.0.jar"),
    (Find-CacheJar "org.apiguardian\apiguardian-api"          "apiguardian-api-1.1.2.jar"),
    (Find-CacheJar "com.google.code.gson\gson"                "gson-2.10.1.jar")
)

# Everything on the Gradle module cache except sources/javadoc and the
# Fabric/Loom ecosystem, which would shadow the NeoForge classes.  Fabric's
# sponge-mixin is kept because it provides org.spongepowered.asm.mixin.
function Get-CompileClasspath {
    $parts = @($MergedJar)
    Get-ChildItem -Path $GradleJars -Recurse -Filter *.jar -File -ErrorAction SilentlyContinue | ForEach-Object {
        if ($_.Name -like "*-sources.jar") { return }
        if ($_.Name -like "*-javadoc.jar") { return }
        if ($_.FullName -match '\\fabric-loom|\\unimined|\\paperweight|\\mercurymixin|mixinextras-fabric') { return }
        if ($_.FullName -match '\\net\.fabricmc' -and $_.Name -notlike "sponge-mixin*") { return }
        $parts += $_.FullName
    }
    return $parts
}

function Join-Classpath([string[]]$parts) {
    return (@($parts) | ForEach-Object { $_ -replace '\\', '/' }) -join ";"
}

# javac argument files must be UTF-8 **without** BOM, otherwise the BOM is
# parsed as part of the first argument.
function Write-ArgFile([string]$path, [string[]]$lines) {
    $dir = Split-Path -Parent $path
    if (-not (Test-Path $dir)) { New-Item -ItemType Directory -Force -Path $dir | Out-Null }
    [System.IO.File]::WriteAllLines($path, $lines, (New-Object System.Text.UTF8Encoding($false)))
}

function Reset-Dir([string]$path) {
    if (Test-Path $path) { Remove-Item -Recurse -Force $path }
    New-Item -ItemType Directory -Force -Path $path | Out-Null
}

foreach ($d in @($BuildDir, $LogDir, $ArgDir)) {
    if (-not (Test-Path $d)) { New-Item -ItemType Directory -Force -Path $d | Out-Null }
}
