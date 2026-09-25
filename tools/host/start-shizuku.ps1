#requires -Version 5.1
<#
.SYNOPSIS
    Start (or restart) the Shizuku server over adb. Needed after every tablet reboot.
.DESCRIPTION
    Runs the libshizuku.so starter shipped inside the installed Shizuku APK, so it always matches the installed version.
    The server runs as the shell uid (2000); this is tier T2 in BooxUltimatum terms.
#>
[CmdletBinding()]
param([string]$Serial)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'common.ps1')
$adb = Get-Adb
$Serial = Select-Device -Adb $adb -Serial $Serial

$apkPath = (& $adb -s $Serial shell pm path moe.shizuku.privileged.api | Select-Object -First 1)
if (-not $apkPath) { throw 'Shizuku is not installed (moe.shizuku.privileged.api).' }
$dir = ($apkPath -replace '^package:', '' -replace 'base\.apk\s*$', '').Trim()
$abi = (& $adb -s $Serial shell "ls ${dir}lib/" | Select-Object -First 1).Trim()
& $adb -s $Serial shell "${dir}lib/$abi/libshizuku.so"
Start-Sleep -Seconds 2
$ps = & $adb -s $Serial shell 'ps -A -o USER,PID,NAME' | Select-String 'shizuku_server'
if ($ps) { Write-Host "Shizuku server running: $($ps.Line.Trim())" } else { throw 'Shizuku server did not start.' }
