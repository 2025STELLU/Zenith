$ErrorActionPreference = "Stop"
$root = "D:\Stellux\MCDev\Zenith"
$out = "$root\.javac-test-out"
if (Test-Path $out) { Remove-Item -Recurse -Force $out }
New-Item -ItemType Directory -Path $out | Out-Null

# 主源码已由 .check3.ps1 编到 .javac-out3
$mainOut = "$root\.javac-out3"
if (-not (Test-Path $mainOut)) { Write-Output "MISSING $mainOut"; exit 1 }

$junitJars = @(
    "D:\User\.gradle\caches\modules-2\files-2.1\org.junit.jupiter\junit-jupiter-api\5.11.4\308315b28e667db4091b2ba1f7aa220d1ddadb97\junit-jupiter-api-5.11.4.jar",
    "D:\User\.gradle\caches\modules-2\files-2.1\org.junit.jupiter\junit-jupiter-engine\5.11.4\dc10ec209623986a68ea07f67cdc7d2a65a60355\junit-jupiter-engine-5.11.4.jar",
    "D:\User\.gradle\caches\modules-2\files-2.1\org.junit.jupiter\junit-jupiter-params\5.11.4\e4c86fbe2a39c60c6b87260ef7f7e7c1a1906481\junit-jupiter-params-5.11.4.jar",
    "D:\User\.gradle\caches\modules-2\files-2.1\org.junit.platform\junit-platform-commons\1.11.4\8898eea3ed0da2641548d602c3e308804f166303\junit-platform-commons-1.11.4.jar",
    "D:\User\.gradle\caches\modules-2\files-2.1\org.junit.platform\junit-platform-engine\1.11.4\21f61b123ad6ac8f7e73971bff3a096c8d8e1cd0\junit-platform-engine-1.11.4.jar",
    "D:\User\.gradle\caches\modules-2\files-2.1\org.junit.platform\junit-platform-launcher\1.11.4\3d83c201899d8c5e74e1a5d628eab900342a0e48\junit-platform-launcher-1.11.4.jar",
    "D:\User\.gradle\caches\modules-2\files-2.1\org.opentest4j\opentest4j\1.3.0\152ea56b3a72f655d4fd677fc0ef2596c3dd5e6e\opentest4j-1.3.0.jar",
    "D:\User\.gradle\caches\modules-2\files-2.1\org.apiguardian\apiguardian-api\1.1.2\a231e0d844d2721b0fa1b238006d15c6ded6842a\apiguardian-api-1.1.2.jar",
    "D:\User\.gradle\caches\modules-2\files-2.1\com.google.code.gson\gson\2.10.1\b3add478d4382b78ea20b1671390a858002feb6c\gson-2.10.1.jar"
)
foreach ($j in $junitJars) { if (-not (Test-Path $j)) { Write-Output "MISSING JAR $j"; exit 1 } }

$cpParts = @($mainOut, "$root\.libs\neoforge-merged.jar") + $junitJars
$cpParts = $cpParts | ForEach-Object { $_.Replace('\', '/') }
$cp = [string]::Join(";", $cpParts)

$srcList = @()
foreach ($s in (Get-ChildItem -Path "$root\Workspace\src\test\java" -Recurse -Filter *.java)) {
    $srcList += $s.FullName.Replace('\', '/')
}
Write-Output ("testSrcs=" + $srcList.Count)

$argFile = "$root\.javac-test-args.txt"
$lines = @("-proc:none", "-nowarn", "-encoding", "UTF-8", "-cp", $cp, "-d", $out.Replace('\','/'))
$lines += $srcList
# 必须无 BOM：javac 会把 BOM 当成参数的一部分
[System.IO.File]::WriteAllLines($argFile, $lines, (New-Object System.Text.UTF8Encoding($false)))

$javac = "C:\Program Files\Java\jdk-21\bin\javac.exe"
& $javac "@$argFile" 2>&1 | Out-String | Write-Output
Write-Output ("javacExit=" + $LASTEXITCODE)
