#requires -Version 5.1
<#
.SYNOPSIS
    Pull APKs (and optionally framework jars) for static analysis with jadx. Read-only on the device.
.EXAMPLE
    .\tools\host\pull-apks.ps1 -Filter 'onyx|boox' -Framework
#>
[CmdletBinding()]
param(
    [string]$Filter = 'onyx|boox',
    [switch]$Framework,
    [string]$Serial
)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'common.ps1')
$adb = Get-Adb
$Serial = Select-Device -Adb $adb -Serial $Serial
$out = New-CaptureDir -Serial $Serial -Label 'apks'
$apkDir = New-Item -ItemType Directory -Force -Path (Join-Path $out 'apk')

$pkgs = & $adb -s $Serial shell 'pm list packages -f' | ForEach-Object { if ($_ -match '^package:(.+\.apk)=(.+)$') { [pscustomobject]@{ Path = $Matches[1]; Name = $Matches[2].Trim() } } } | Where-Object { $_.Name -match $Filter }
$manifest = @()
foreach ($p in $pkgs) {
    $paths = & $adb -s $Serial shell "pm path $($p.Name)" | ForEach-Object { $_ -replace '^package:', '' } | Where-Object { $_ }
    $target = New-Item -ItemType Directory -Force -Path (Join-Path $apkDir $p.Name)
    foreach ($ap in $paths) {
        & $adb -s $Serial pull $ap.Trim() $target.FullName | Out-Null
        $manifest += [pscustomobject]@{ Package = $p.Name; DevicePath = $ap.Trim() }
    }
    Write-Host "pulled $($p.Name)"
}
$manifest | Export-Csv (Join-Path $out 'apks.csv') -NoTypeInformation

if ($Framework) {
    $fw = New-Item -ItemType Directory -Force -Path (Join-Path $out 'framework')
    foreach ($d in '/system/framework', '/system_ext/framework', '/product/framework') {
        $files = & $adb -s $Serial shell "ls $d 2>/dev/null" | Where-Object { $_ -match '\.jar$' }
        foreach ($f in $files) { & $adb -s $Serial pull "$d/$($f.Trim())" $fw.FullName | Out-Null }
    }
    Write-Host "framework jars pulled"
}
Write-Host "Done: $out ($($manifest.Count) files). Decompile with: jadx -d <outdir> <apk>"
