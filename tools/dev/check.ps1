# Full offline gate: compile main, compile tests, run tests, recompute gold values.
#
# Usage:  powershell -NoProfile -File tools/dev/check.ps1
#
# This is the replacement for `gradlew build` on a machine that cannot resolve
# the NeoForge/Minecraft artifacts.  It is what the 0.1.5-alpha §18.6 numbers
# were produced with.

$ErrorActionPreference = "Stop"
$here = $PSScriptRoot

& (Join-Path $here "build.ps1")
if ($LASTEXITCODE -ne 0) { Write-Output "FAILED at build.ps1"; exit 1 }

& (Join-Path $here "test-compile.ps1")
if ($LASTEXITCODE -ne 0) { Write-Output "FAILED at test-compile.ps1"; exit 1 }

& (Join-Path $here "test-run.ps1")
if ($LASTEXITCODE -ne 0) { Write-Output "FAILED at test-run.ps1"; exit 1 }

& (Join-Path $here "verify.ps1")
if ($LASTEXITCODE -ne 0) { Write-Output "FAILED at verify.ps1"; exit 1 }

Write-Output ""
Write-Output "ALL CHECKS PASSED"
