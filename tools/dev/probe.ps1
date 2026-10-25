# Compile and run one ad-hoc probe against the built main classes.
#
# Probes are throwaway diagnostic mains (not JUnit tests) used to read off
# numbers that end up in docs/04 §17.2 / §18.
#
# Usage:  powershell -NoProfile -File tools/dev/probe.ps1 SailingProbe
#         powershell -NoProfile -File tools/dev/probe.ps1 L1Probe -List

param(
    [string]$Name = "",
    [switch]$List
)

. "$PSScriptRoot\common.ps1"

$probeSrc = Join-Path $PSScriptRoot "probes"
$available = @(Get-ChildItem -Path $probeSrc -Filter *.java -File | ForEach-Object { $_.BaseName })

if ($List -or -not $Name) {
    Write-Output "available probes:"
    $available | ForEach-Object { "  $_" }
    exit 0
}

$file = Join-Path $probeSrc "$Name.java"
if (-not (Test-Path $file)) {
    Write-Output "no such probe: $Name"
    Write-Output "available: $($available -join ', ')"
    exit 1
}
if (-not (Test-Path $MainOut)) { throw "run tools/dev/build.ps1 first ($MainOut missing)" }

$probeOut = Join-Path $BuildDir "classes\probes"
if (-not (Test-Path $probeOut)) { New-Item -ItemType Directory -Force -Path $probeOut | Out-Null }

$cp = Join-Classpath (@($MainOut, $probeOut, $MergedJar) + $JUnitJars)
& $Javac -proc:none -nowarn -encoding UTF-8 -cp $cp -d $probeOut $file 2>&1 | Out-String | Write-Output
if ($LASTEXITCODE -ne 0) { Write-Output "probe javac failed"; exit 1 }

& $Java "-Dfile.encoding=UTF-8" "-cp" $cp $Name 2>&1 | Write-Output
exit $LASTEXITCODE
