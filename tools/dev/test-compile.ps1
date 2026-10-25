# Compile Workspace/src/test/java against the already-built main classes.
#
# Usage:  powershell -NoProfile -File tools/dev/test-compile.ps1
#
# Requires build.ps1 to have run (classes under .build/classes/main).

. "$PSScriptRoot\common.ps1"

if (-not (Test-Path $MainOut)) { throw "run tools/dev/build.ps1 first ($MainOut missing)" }

Reset-Dir $TestOut

$cp = Join-Classpath (@($MainOut, $MergedJar) + $JUnitJars)
$srcs = @(Get-ChildItem -Path $TestSrc -Recurse -Filter *.java -File | ForEach-Object { $_.FullName -replace '\\', '/' })
if ($srcs.Count -eq 0) { throw "no test sources under $TestSrc" }

$argFile = Join-Path $ArgDir "test.args"
Write-ArgFile $argFile (@(
    "-proc:none", "-nowarn", "-encoding", "UTF-8",
    "-cp", $cp, "-d", ($TestOut -replace '\\', '/')
) + $srcs)

$log = Join-Path $LogDir "test-javac.log"
cmd /c "`"$Javac`" -J-Duser.language=en -J-Duser.country=US @`"$argFile`" > `"$log`" 2>&1"
$exit = $LASTEXITCODE

Write-Output ("testsources = {0}" -f $srcs.Count)
if ($exit -ne 0) {
    Get-Content $log | Select-Object -First 60
}
Write-Output ("exit={0}" -f $exit)
if ($exit -ne 0) { exit $exit }
