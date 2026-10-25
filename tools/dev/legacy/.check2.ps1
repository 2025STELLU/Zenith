$root = "D:\Stellux\MCDev\Zenith\Workspace\src\main\java"
$out = "D:\Stellux\MCDev\Zenith\.javac-out2"
if (Test-Path $out) { Remove-Item -Recurse -Force $out }
New-Item -ItemType Directory -Path $out | Out-Null
$files = Get-ChildItem -Path $root -Recurse -Filter *.java | ForEach-Object { $_.FullName }
$listFile = "D:\Stellux\MCDev\Zenith\.javac-srcs.txt"
$enc = New-Object System.Text.UTF8Encoding($false)
[System.IO.File]::WriteAllLines($listFile, $files, $enc)

$cp = @(
  "D:\Stellux\MCDev\Light With Sable\build\moddev\artifacts\neoforge-21.1.236-minecraft-merged.jar"
) -join ";"

cmd /c "javac -J-Duser.language=en -J-Duser.country=US -proc:none -nowarn -Xmaxerrs 100000 -encoding UTF-8 -cp `"$cp`" -d $out @$listFile > D:\Stellux\MCDev\Zenith\.javac-log2.txt 2>&1"
Write-Output ("exit=" + $LASTEXITCODE)
$log = Get-Content "D:\Stellux\MCDev\Zenith\.javac-log2.txt"
Write-Output ("lines=" + $log.Count)
$kinds = @{}
foreach ($l in $log) { if ($l -match 'error: (.*)$') { $m = $matches[1]; if ($m.Length -gt 70) { $m = $m.Substring(0,70) }; if ($kinds.ContainsKey($m)) { $kinds[$m]++ } else { $kinds[$m] = 1 } } }
$kinds.GetEnumerator() | Sort-Object -Property Value -Descending | Select-Object -First 40 | ForEach-Object { "{0,5}  {1}" -f $_.Value, $_.Key }
