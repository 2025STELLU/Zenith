# Inspect a class from the merged NeoForge/Minecraft jar.
#
# Usage:  powershell -NoProfile -File tools/dev/javap.ps1 net.minecraft.server.MinecraftServer
#         powershell -NoProfile -File tools/dev/javap.ps1 <class> -Filter 'save.*'

param(
    [Parameter(Mandatory = $true)][string]$Class,
    [string]$Filter = "",
    [switch]$NoPrivate
)

. "$PSScriptRoot\common.ps1"

$flag = if ($NoPrivate) { "-public" } else { "-p" }
$out = & $Javap $flag -cp $MergedJar $Class 2>&1
if ($LASTEXITCODE -ne 0) { $out | Write-Output; exit 1 }

if ($Filter) {
    $out | Select-String -Pattern $Filter | ForEach-Object { $_.Line }
} else {
    $out
}
