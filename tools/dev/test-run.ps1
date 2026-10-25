# Run the JUnit 5 suite offline through the platform launcher.
#
# Usage:  powershell -NoProfile -File tools/dev/test-run.ps1
#
# Gradle's `test` task is blocked by dependency resolution on this machine, so
# this drives junit-platform-launcher directly.  Run test-compile.ps1 first.

. "$PSScriptRoot\common.ps1"

if (-not (Test-Path $TestOut)) { throw "run tools/dev/test-compile.ps1 first ($TestOut missing)" }

Reset-Dir $RunnerOut

$cp = Join-Classpath (@($TestOut, $MainOut, $MergedJar, $RunnerOut) + $JUnitJars)
$runnerSrc = Join-Path $PSScriptRoot "verify\RunTests.java"

& $Javac -proc:none -nowarn -encoding UTF-8 -cp $cp -d $RunnerOut $runnerSrc 2>&1 | Out-String | Write-Output
if ($LASTEXITCODE -ne 0) { Write-Output "runner javac failed"; exit 1 }

$stdout = Join-Path $LogDir "test-stdout.txt"
$stderr = Join-Path $LogDir "test-stderr.txt"
$p = Start-Process -FilePath $Java `
    -ArgumentList "-Dfile.encoding=UTF-8 -cp `"$cp`" RunTests" `
    -NoNewWindow -Wait -PassThru `
    -RedirectStandardOutput $stdout -RedirectStandardError $stderr

Get-Content $stdout -Encoding UTF8 | Write-Output
if ((Get-Item $stderr).Length -gt 0) { Get-Content $stderr -Encoding UTF8 | Write-Output }
Write-Output ("javaExit={0}" -f $p.ExitCode)
if ($p.ExitCode -ne 0) { exit $p.ExitCode }
