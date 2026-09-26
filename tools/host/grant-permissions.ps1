#requires -Version 5.1
<#
.SYNOPSIS
    One-time setup: grants BooxUltimatum the permissions of tier T1 over adb. They survive reboots.
.DESCRIPTION
    WRITE_SECURE_SETTINGS lets the app change system settings (status bar icons, most setting-based tweaks) without
    Shizuku. DUMP lets it read battery statistics for the battery log. READ_LOGS and usage access help the battery and
    app pages. Revoke any of them at any time with `adb shell pm revoke app.booxultimatum <permission>`.
#>
[CmdletBinding()]
param([string]$Serial)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'common.ps1')
$adb = Get-Adb
$Serial = Select-Device -Adb $adb -Serial $Serial

$pkg = 'app.booxultimatum'
if (-not (& $adb -s $Serial shell pm path $pkg)) { throw "BooxUltimatum ($pkg) is not installed on the tablet." }
foreach ($p in 'WRITE_SECURE_SETTINGS', 'DUMP', 'READ_LOGS') {
    & $adb -s $Serial shell pm grant $pkg "android.permission.$p"
    Write-Host "Granted $p"
}
& $adb -s $Serial shell appops set $pkg GET_USAGE_STATS allow
Write-Host 'Allowed usage access'
Write-Host 'Done. These grants stay after a reboot; open BooxUltimatum > Access to check.'
