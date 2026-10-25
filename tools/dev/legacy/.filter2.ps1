$log = Get-Content "D:\Stellux\MCDev\Zenith\.javac-log2.txt"
$n = $log.Count
$out = @()
for ($i = 0; $i -lt $n; $i++) {
  $l = $log[$i]
  if ($l -match 'error: ') {
    $blk = @()
    for ($j = $i; $j -lt [Math]::Min($i+6, $n); $j++) {
      if ($j -gt $i -and $log[$j] -match '^D:\\') { break }
      $blk += $log[$j]
      if ($log[$j] -match '^\s+location:') { break }
    }
    $joined = $blk -join "  ||  "
    # 只保留：cannot find symbol 或 does not override —— 其余（package does not exist / cannot access）都是缺依赖
    if ($l -match 'cannot find symbol' -or $l -match 'does not override') {
      $out += $joined
    }
  }
}
$out | ForEach-Object { $_ }
Write-Output ("total=" + $out.Count)
