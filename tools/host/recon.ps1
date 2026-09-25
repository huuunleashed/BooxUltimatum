#requires -Version 5.1
<#
.SYNOPSIS
    Read-only inventory of a connected Boox tablet. Changes nothing on the device.
.EXAMPLE
    .\tools\host\recon.ps1 -Label factory
#>
[CmdletBinding()]
param(
    [string]$Label = 'recon',
    [string]$Serial,
    [switch]$Quick
)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'common.ps1')

$adb = Get-Adb
$Serial = Select-Device -Adb $adb -Serial $Serial
$out = New-CaptureDir -Serial $Serial -Label $Label
Write-Host "Capturing into $out"

# name => shell command. Failures are recorded, not fatal (many paths need root).
$cmds = [ordered]@{
    'getprop'                 = 'getprop'
    'uname'                   = 'uname -a; cat /proc/version'
    'cpuinfo'                 = 'cat /proc/cpuinfo'
    'meminfo'                 = 'cat /proc/meminfo'
    'soc0'                    = 'for f in /sys/devices/soc0/*; do [ -f "$f" ] && echo "$f=$(cat $f 2>&1)"; done'
    'cpufreq'                 = 'for p in /sys/devices/system/cpu/cpufreq/policy*; do echo "== $p"; for f in related_cpus scaling_governor scaling_available_governors cpuinfo_min_freq cpuinfo_max_freq scaling_min_freq scaling_max_freq scaling_available_frequencies; do echo "$f: $(cat $p/$f 2>&1)"; done; done'
    'cpuidle'                 = 'for s in /sys/devices/system/cpu/cpu0/cpuidle/state*; do echo "$s $(cat $s/name 2>&1) usage=$(cat $s/usage 2>&1) time=$(cat $s/time 2>&1)"; done'
    'thermal'                 = 'for z in /sys/class/thermal/thermal_zone*; do echo "$z $(cat $z/type 2>&1) $(cat $z/temp 2>&1)"; done'
    'power_supply'            = 'for d in /sys/class/power_supply/*; do echo "== $d"; cat $d/uevent 2>&1; done'
    'suspend_stats'           = 'for f in /sys/power/suspend_stats/*; do echo "$f=$(cat $f 2>&1)"; done; echo "mem_sleep=$(cat /sys/power/mem_sleep 2>&1)"; echo "last_resume_reason=$(cat /sys/kernel/wakeup_reasons/last_resume_reason 2>&1)"'
    'wakeup_sources'          = 'cat /sys/kernel/debug/wakeup_sources 2>&1 || cat /d/wakeup_sources 2>&1'
    'backlight'               = 'for d in /sys/class/backlight/* /sys/class/leds/*; do echo "$d brightness=$(cat $d/brightness 2>&1) max=$(cat $d/max_brightness 2>&1)"; done'
    'display'                 = 'wm size; wm density; dumpsys display'
    'input'                   = 'getevent -pl 2>&1 | head -c 200000'
    'selinux'                 = 'getenforce; id'
    'mounts'                  = 'mount; echo; df -h'
    'partitions'              = 'ls -l /dev/block/by-name 2>&1; ls -l /dev/block/bootdevice/by-name 2>&1; cat /proc/partitions 2>&1'
    'system_dirs'             = 'for d in /system/app /system/priv-app /system/framework /system_ext/app /system_ext/priv-app /system_ext/framework /product/app /product/priv-app /vendor/app /odm/app; do echo "== $d"; ls $d 2>&1; done'
    'onyxconfig'              = 'ls -laR /onyxconfig 2>&1 | head -n 500'
    'packages_all'            = 'pm list packages -f -U -i --show-versioncode'
    'packages_disabled'       = 'pm list packages -d'
    'packages_system'         = 'pm list packages -s'
    'packages_3p'             = 'pm list packages -3'
    'features'                = 'pm list features'
    'services_list'           = 'service list'
    'dumpsys_list'            = 'dumpsys -l'
    'settings_global'         = 'settings list global'
    'settings_secure'         = 'settings list secure'
    'settings_system'         = 'settings list system'
    'dumpsys_battery'         = 'dumpsys battery'
    'dumpsys_batterystats'    = 'dumpsys batterystats'
    'dumpsys_batterystats_ci' = 'dumpsys batterystats --checkin'
    'dumpsys_power'           = 'dumpsys power'
    'dumpsys_deviceidle'      = 'dumpsys deviceidle'
    'dumpsys_alarm'           = 'dumpsys alarm'
    'dumpsys_jobscheduler'    = 'dumpsys jobscheduler'
    'dumpsys_services'        = 'dumpsys activity services'
    'dumpsys_processes'       = 'dumpsys activity processes'
    'dumpsys_appops'          = 'dumpsys appops'
    'dumpsys_usagestats'      = 'dumpsys usagestats'
    'dumpsys_procstats'       = 'dumpsys procstats --hours 24'
    'dumpsys_cpuinfo'         = 'dumpsys cpuinfo'
    'dumpsys_thermal'         = 'dumpsys thermalservice'
    'dumpsys_suspend'         = 'dumpsys suspend_control_internal 2>&1; dumpsys android.system.suspend.ISuspendControlService/default 2>&1'
    'dumpsys_wifi'            = 'dumpsys wifi | head -n 400'
    'dumpsys_connectivity'    = 'dumpsys connectivity'
    'dumpsys_netstats'        = 'dumpsys netstats detail'
    'dumpsys_package'         = 'dumpsys package'
    'ps'                      = 'ps -A -o USER,PID,PPID,VSZ,RSS,PRI,NI,S,TIME,NAME'
    'top'                     = 'top -b -n 1 -m 40'
    'logcat'                  = 'logcat -d -b all -v threadtime'
}
if ($Quick) {
    $keep = 'getprop','uname','cpuinfo','soc0','power_supply','suspend_stats','selinux','partitions','onyxconfig','packages_all','dumpsys_battery','dumpsys_power','dumpsys_deviceidle'
    foreach ($k in @($cmds.Keys)) { if ($keep -notcontains $k) { $cmds.Remove($k) } }
}

$i = 0
foreach ($name in $cmds.Keys) {
    $i++
    Write-Progress -Activity 'recon' -Status $name -PercentComplete (100 * $i / $cmds.Count)
    Invoke-AdbShell -Adb $adb -Serial $Serial -Command $cmds[$name] -OutFile (Join-Path $out "$name.txt")
}
Write-Progress -Activity 'recon' -Completed

# Summary
$props = @{}
Get-Content (Join-Path $out 'getprop.txt') | ForEach-Object { if ($_ -match '^\[(.+?)\]: \[(.*)\]$') { $props[$Matches[1]] = $Matches[2] } }
$pk = Get-Content (Join-Path $out 'packages_all.txt') | Where-Object { $_ -like 'package:*' }
$onyx = $pk | ForEach-Object { if ($_ -match '=([\w\.]+) ') { $Matches[1] } } | Where-Object { $_ -match 'onyx|boox' } | Sort-Object
$keys = 'ro.product.manufacturer','ro.product.model','ro.product.device','ro.product.board','ro.board.platform','ro.soc.manufacturer','ro.soc.model','ro.hardware','ro.build.display.id','ro.build.fingerprint','ro.build.version.release','ro.build.version.sdk','ro.build.version.security_patch','ro.boot.verifiedbootstate','ro.boot.flash.locked','ro.boot.vbmeta.device_state','ro.oem_unlock_supported','ro.boot.slot_suffix','ro.boot.dynamic_partitions','ro.virtual_ab.enabled','ro.kernel.version','persist.sys.usb.config'
$lines = @("# Recon summary: $Label", '', "Captured $(Get-Date -Format s) from ``$Serial``.", '', '| Property | Value |', '|---|---|')
foreach ($k in $keys) { $lines += "| ``$k`` | ``$($props[$k])`` |" }
$lines += '', "Packages: $($pk.Count) total, $($onyx.Count) Onyx/Boox.", '', '## Onyx/Boox packages', ''
$lines += $onyx | ForEach-Object { "- ``$_``" }
$lines | Set-Content (Join-Path $out 'SUMMARY.md') -Encoding utf8
Write-Host "Done. See $(Join-Path $out 'SUMMARY.md')"
