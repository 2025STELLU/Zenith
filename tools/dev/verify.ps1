# Recompute the audit's "gold values" independently and diff them against the
# numbers recorded in docs/04 §17.2 / §18.6.
#
# Usage:  powershell -NoProfile -File tools/dev/verify.ps1
#
# Verify.java deliberately re-derives each formula from the spec instead of
# calling the production code, so a refactor that silently changes a constant
# shows up here.

. "$PSScriptRoot\common.ps1"

if (-not (Test-Path $MainOut)) { throw "run tools/dev/build.ps1 first ($MainOut missing)" }

$verifyOut = Join-Path $BuildDir "classes\verify"
Reset-Dir $verifyOut

$gson = $JUnitJars[-1]
$cp = Join-Classpath (@($MainOut, $verifyOut, $MergedJar, $gson))

& $Javac -proc:none -nowarn -encoding UTF-8 -cp $cp -d $verifyOut (Join-Path $PSScriptRoot "verify\Verify.java") 2>&1 |
    Select-Object -First 20
if ($LASTEXITCODE -ne 0) { Write-Output "verify javac failed"; exit 1 }

$result = Join-Path $LogDir "verify-result.txt"
& $Java "-Dfile.encoding=UTF-8" "-cp" $cp Verify 2>&1 | Out-File -FilePath $result -Encoding UTF8
Get-Content $result -Encoding UTF8
