$root = "D:\Stellux\MCDev\Zenith\Workspace\src\main\java"
$out = "D:\Stellux\MCDev\Zenith\.javac-out"
if (Test-Path $out) { Remove-Item -Recurse -Force $out }
New-Item -ItemType Directory -Path $out | Out-Null
$files = Get-ChildItem -Path $root -Recurse -Filter *.java | ForEach-Object { $_.FullName }
$listFile = "D:\Stellux\MCDev\Zenith\.javac-srcs.txt"
$enc = New-Object System.Text.UTF8Encoding($false)
[System.IO.File]::WriteAllLines($listFile, $files, $enc)
cmd /c "javac -J-Duser.language=en -J-Duser.country=US -proc:none -nowarn -Xmaxerrs 100000 -encoding UTF-8 -d $out @$listFile > D:\Stellux\MCDev\Zenith\.javac-log.txt 2>&1"
Write-Output ("exit=" + $LASTEXITCODE)
$log = Get-Content "D:\Stellux\MCDev\Zenith\.javac-log.txt"
Write-Output ("lines=" + $log.Count)
Write-Output "--- error kinds ---"
$kinds = @{}
foreach ($l in $log) { if ($l -match 'error: (.*)$') { $m = $matches[1]; if ($m.Length -gt 60) { $m = $m.Substring(0,60) }; if ($kinds.ContainsKey($m)) { $kinds[$m]++ } else { $kinds[$m] = 1 } } }
$kinds.GetEnumerator() | Sort-Object -Property Value -Descending | Select-Object -First 50 | ForEach-Object { "{0,5}  {1}" -f $_.Value, $_.Key }
