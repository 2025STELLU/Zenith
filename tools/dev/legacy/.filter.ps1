$log = Get-Content "D:\Stellux\MCDev\Zenith\.javac-log.txt"
$n = $log.Count
$out = @()
for ($i = 0; $i -lt $n; $i++) {
  $l = $log[$i]
  if ($l -match 'error: ') {
    # 只关心 "symbol: method/variable" —— 这类错误最可能是我写错 API，而不是缺 classpath
    $blk = @()
    for ($j = $i; $j -lt [Math]::Min($i+6, $n); $j++) {
      if ($j -gt $i -and $log[$j] -match '^D:\\') { break }
      $blk += $log[$j]
      if ($log[$j] -match '^\s+location:') { break }
    }
    $joined = $blk -join "`n"
    if ($joined -match 'symbol:\s+(method|variable) ') {
      $out += $joined
    }
  }
}
$out | Select-Object -First 60
Write-Output ("total=" + $out.Count)
