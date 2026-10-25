$root = "D:\Stellux\MCDev\Zenith\Workspace\src\main\java"
$out = "D:\Stellux\MCDev\Zenith\.javac-out3"
if (Test-Path $out) { Remove-Item -Recurse -Force $out }
New-Item -ItemType Directory -Path $out | Out-Null

$merged = "D:\Stellux\MCDev\Zenith\.libs\neoforge-merged.jar"
$jars = New-Object System.Collections.Generic.List[string]
$jars.Add($merged)
foreach ($f in (Get-ChildItem "D:\User\.gradle\caches\modules-2\files-2.1" -Recurse -Filter *.jar -File)) {
    if ($f.Name -like "*-sources.jar") { continue }
    if ($f.Name -like "*-javadoc.jar") { continue }
    if ($f.FullName -match '\\fabric-loom|\\unimined|\\paperweight|\\mercurymixin|mixinextras-fabric') { continue }
    # 只要 fabric 的 sponge-mixin（它提供 org.spongepowered.asm.mixin），其余 fabric 依赖不要
    if ($f.FullName -match '\\net\.fabricmc' -and $f.Name -notlike "sponge-mixin*") { continue }
    $jars.Add($f.FullName)
}
Write-Output ("jars=" + $jars.Count)

$cpParts = @()
foreach ($j in $jars) { $cpParts += $j.Replace('\', '/') }
$cp = [string]::Join(";", $cpParts)
Write-Output ("cpLen=" + $cp.Length)

$srcList = @()
foreach ($s in (Get-ChildItem -Path $root -Recurse -Filter *.java)) { $srcList += $s.FullName.Replace('\', '/') }
Write-Output ("srcs=" + $srcList.Count)

$argFile = "D:\Stellux\MCDev\Zenith\.javac-args.txt"
$lines = @("-proc:none", "-nowarn", "-Xmaxerrs", "100000", "-encoding", "UTF-8", "-d", $out.Replace('\','/'))
$lines += "-cp"
$lines += $cp
$lines += $srcList
$enc = New-Object System.Text.UTF8Encoding($false)
[System.IO.File]::WriteAllLines($argFile, $lines, $enc)

cmd /c "set PATH=C:\Program Files\Java\jdk-21\bin;%PATH% && javac -J-Duser.language=en -J-Duser.country=US @$argFile > D:\Stellux\MCDev\Zenith\.javac-log3.txt 2>&1"
Write-Output ("exit=" + $LASTEXITCODE)
$log = Get-Content "D:\Stellux\MCDev\Zenith\.javac-log3.txt"
Write-Output ("lines=" + $log.Count)
$kinds = @{}
foreach ($l in $log) { if ($l -match 'error: (.*)$') { $m = $matches[1]; if ($m.Length -gt 70) { $m = $m.Substring(0,70) }; if ($kinds.ContainsKey($m)) { $kinds[$m]++ } else { $kinds[$m] = 1 } } }
$kinds.GetEnumerator() | Sort-Object -Property Value -Descending | Select-Object -First 30 | ForEach-Object { "{0,5}  {1}" -f $_.Value, $_.Key }
