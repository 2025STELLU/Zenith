# Compile all of src/main/java with javac.
#
# Usage:  powershell -NoProfile -File tools/dev/build.ps1 [-Quiet]
#
# Exit code 0 = clean compile.  Deprecation notes are printed as a count only;
# real errors are grouped by message so a 500 KB javac log stays readable.

param([switch]$Quiet)

. "$PSScriptRoot\common.ps1"

Reset-Dir $MainOut

$cp = Join-Classpath (Get-CompileClasspath)
$srcs = @(Get-ChildItem -Path $MainSrc -Recurse -Filter *.java -File | ForEach-Object { $_.FullName -replace '\\', '/' })

$argFile = Join-Path $ArgDir "main.args"
Write-ArgFile $argFile (@(
    "-proc:none", "-nowarn", "-Xmaxerrs", "100000",
    "-encoding", "UTF-8", "-d", ($MainOut -replace '\\', '/'),
    "-cp", $cp
) + $srcs)

$log = Join-Path $LogDir "main-javac.log"
cmd /c "`"$Javac`" -J-Duser.language=en -J-Duser.country=US @`"$argFile`" > `"$log`" 2>&1"
$exit = $LASTEXITCODE

$lines = @(Get-Content $log)
$errors = @{}
$notes = 0
foreach ($l in $lines) {
    if ($l -match 'error: (.*)$') {
        $m = $matches[1]
        if ($m.Length -gt 80) { $m = $m.Substring(0, 80) }
        if ($errors.ContainsKey($m)) { $errors[$m]++ } else { $errors[$m] = 1 }
    } elseif ($l -match 'note:') { $notes++ }
}

if (-not $Quiet) {
    Write-Output ("sources = {0}" -f $srcs.Count)
    Write-Output ("classes = {0}" -f (Get-ChildItem $MainOut -Recurse -Filter *.class -File | Measure-Object).Count)
    Write-Output ("note    = {0} line(s)" -f $notes)
    if ($errors.Count -gt 0) {
        Write-Output "--- errors by message ---"
        $errors.GetEnumerator() | Sort-Object Value -Descending |
            ForEach-Object { "{0,5}  {1}" -f $_.Value, $_.Key }
    }
}
Write-Output ("exit={0}" -f $exit)
if ($exit -ne 0) { exit $exit }
