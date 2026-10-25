$env:JAVA_HOME = "C:\Program Files\Java\jdk-21"
$out = "D:\Stellux\MCDev\Zenith\.javac-out3"
$vout = "D:\Stellux\MCDev\Zenith\.verify\out"
if (!(Test-Path $vout)) { New-Item -ItemType Directory -Path $vout | Out-Null }
# gson 不在合并 jar 里，离线运行时必须显式加进 classpath（AirfoilJson 用到了）
$gson = (Get-ChildItem "D:\User\.gradle\caches\modules-2\files-2.1\com.google.code.gson" -Recurse -Filter "gson-2.10.1.jar" -File | Select-Object -First 1).FullName
if (-not $gson) { $gson = (Get-ChildItem "D:\User\.gradle\caches\modules-2\files-2.1\com.google.code.gson" -Recurse -Filter "gson-*.jar" -File | Select-Object -First 1).FullName }
$runCp = $out + ";" + $vout + ";" + "D:\Stellux\MCDev\Zenith\.libs\neoforge-merged.jar" + ";" + $gson
& "C:\Program Files\Java\jdk-21\bin\javac.exe" -proc:none -nowarn -encoding UTF-8 -cp $runCp -d $vout "D:\Stellux\MCDev\Zenith\.verify\Verify.java" 2>&1 | Select-Object -First 20
$lines = & "C:\Program Files\Java\jdk-21\bin\java.exe" "-Dfile.encoding=UTF-8" "-cp" $runCp Verify 2>&1
$lines | Out-File -FilePath "D:\Stellux\MCDev\Zenith\.verify\result.txt" -Encoding UTF8
Get-Content "D:\Stellux\MCDev\Zenith\.verify\result.txt" -Encoding UTF8
