$env:JAVA_HOME = "C:\Program Files\Java\jdk-21"
$javap = "C:\Program Files\Java\jdk-21\bin\javap.exe"
$cp = "D:\Stellux\MCDev\Zenith\.libs\neoforge-merged.jar"
$out = & $javap -p -cp $cp "net.minecraft.server.MinecraftServer" 2>&1
$out | Select-String -Pattern 'saveAllChunks|saveEverything|saveLevelData' | ForEach-Object { $_.Line.Trim() }
Write-Output ""
Write-Output "=== ServerLevel.save signature ==="
& $javap -p -cp $cp "net.minecraft.server.level.ServerLevel" 2>&1 | Select-String -Pattern 'public void save|saveLevelData' | ForEach-Object { $_.Line.Trim() }
Write-Output ""
Write-Output "=== saveAllChunks body (dirty check) ==="
$i = ($out | Select-String -Pattern 'public boolean saveAllChunks' | Select-Object -First 1).LineNumber
$out | Select-Object -Skip ([Math]::Max($i-1,0)) -First 60
