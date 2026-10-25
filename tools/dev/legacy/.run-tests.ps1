$ErrorActionPreference = "Stop"
$root = "D:\Stellux\MCDev\Zenith"
$testOut = "$root\.javac-test-out"

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
# 最后一个是 Gson：ZenithAirfoilJson 用它，api/ 包唯一的外部依赖，运行时必须有

$cpParts = @($testOut, "$root\.javac-out3", "$root\.libs\neoforge-merged.jar", "$root\.verify\runner") + $junitJars
$cpParts = $cpParts | ForEach-Object { $_.Replace('\', '/') }
$cp = [string]::Join(";", $cpParts)

New-Item -ItemType Directory -Force -Path "$root\.verify\runner" | Out-Null
& "C:\Program Files\Java\jdk-21\bin\javac.exe" -proc:none -nowarn -encoding UTF-8 -cp $cp -d "$root\.verify\runner" "$root\.verify\RunTests.java" 2>&1 | Out-String | Write-Output
if ($LASTEXITCODE -ne 0) { Write-Output "runner javac failed"; exit 1 }

$javaArgs = "-Dfile.encoding=UTF-8 -cp `"$cp`" RunTests"
$p = Start-Process -FilePath "C:\Program Files\Java\jdk-21\bin\java.exe" -ArgumentList $javaArgs -NoNewWindow -Wait -PassThru -RedirectStandardOutput "$root\.test-stdout.txt" -RedirectStandardError "$root\.test-stderr.txt"
Get-Content "$root\.test-stdout.txt" -Encoding UTF8 | Write-Output
Get-Content "$root\.test-stderr.txt" -Encoding UTF8 | Write-Output
Write-Output ("javaExit=" + $p.ExitCode)
