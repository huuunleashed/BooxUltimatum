#requires -Version 5.1
<#
.SYNOPSIS
    On-device power logger that keeps running after USB is unplugged (no app needed).
.DESCRIPTION
    Start: pushes a tiny sh loop to /data/local/tmp and launches it detached (setsid + nohup).
    Each sample writes: epoch, boottime_s, capacity, current_uA, voltage_uV, temp_dC, status, wakefulness, suspend_success.
    While the SoC is in deep sleep the loop is frozen too, so gaps between samples show suspended time.
    Stop: kills the loop. Pull: copies the CSV into a capture folder.
.EXAMPLE
    .\tools\host\power-logger.ps1 -Action Start -Interval 60 -Label B0-standby
    # unplug, wait, replug
    .\tools\host\power-logger.ps1 -Action Pull -Label B0-standby
    .\tools\host\power-logger.ps1 -Action Stop
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory)][ValidateSet('Start', 'Stop', 'Status', 'Pull')][string]$Action,
    [int]$Interval = 60,
    [string]$Label = 'power',
    [string]$Serial
)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'common.ps1')
$adb = Get-Adb
$Serial = Select-Device -Adb $adb -Serial $Serial
$remoteDir = '/data/local/tmp/bu'
$csv = "$remoteDir/power.csv"

switch ($Action) {
    'Start' {
        $script = @'
#!/system/bin/sh
# BooxUltimatum power logger. Low overhead: a few sysfs reads per sample.
D=/data/local/tmp/bu
PS=/sys/class/power_supply/battery
[ -d "$PS" ] || PS=$(ls -d /sys/class/power_supply/* | head -n 1)
echo $$ > $D/pid
[ -f $D/power.csv ] || echo "epoch,boottime_s,capacity,current_uA,voltage_uV,temp_dC,status,wakefulness,suspend_success" > $D/power.csv
while true; do
  W=$(dumpsys power 2>/dev/null | grep -m1 'mWakefulness=' | cut -d= -f2)
  echo "$(date +%s),$(cut -d' ' -f1 /proc/uptime),$(cat $PS/capacity 2>/dev/null),$(cat $PS/current_now 2>/dev/null),$(cat $PS/voltage_now 2>/dev/null),$(cat $PS/temp 2>/dev/null),$(cat $PS/status 2>/dev/null),$W,$(cat /sys/power/suspend_stats/success 2>/dev/null)" >> $D/power.csv
  sleep __INTERVAL__
done
'@
        # Note: /proc/uptime on Android counts CLOCK_BOOTTIME (includes suspend); compare with epoch deltas and sample gaps.
        $script = $script.Replace('__INTERVAL__', "$Interval").Replace("`r`n", "`n")
        $tmp = New-TemporaryFile
        [IO.File]::WriteAllText($tmp.FullName, $script, (New-Object Text.UTF8Encoding $false))
        Invoke-AdbShell -Adb $adb -Serial $Serial -Command "mkdir -p $remoteDir" | Out-Null
        & $adb -s $Serial push $tmp.FullName "$remoteDir/logger.sh" | Out-Null
        Remove-Item $tmp.FullName
        $launch = 'D=__D__; [ -f $D/pid ] && kill $(cat $D/pid) 2>/dev/null; rm -f $D/power.csv; chmod 755 $D/logger.sh; setsid nohup sh $D/logger.sh > /dev/null 2>&1 < /dev/null &'
        Invoke-AdbShell -Adb $adb -Serial $Serial -Command $launch.Replace('__D__', $remoteDir) | Out-Null
        Start-Sleep -Seconds 2
        Invoke-AdbShell -Adb $adb -Serial $Serial -Command "cat $remoteDir/pid; tail -n 2 $csv"
        Write-Host "Logger started (interval ${Interval}s). You can unplug now."
    }
    'Status' {
        $status = 'D=__D__; if [ -f $D/pid ]; then p=$(cat $D/pid); echo pid=$p; [ -d /proc/$p ] && echo running || echo stopped; else echo "not started"; fi; wc -l $D/power.csv 2>/dev/null; tail -n 3 $D/power.csv 2>/dev/null'
        Invoke-AdbShell -Adb $adb -Serial $Serial -Command $status.Replace('__D__', $remoteDir)
    }
    'Stop' {
        $stop = 'D=__D__; [ -f $D/pid ] && kill $(cat $D/pid) 2>/dev/null; rm -f $D/pid; echo stopped'
        Invoke-AdbShell -Adb $adb -Serial $Serial -Command $stop.Replace('__D__', $remoteDir)
    }
    'Pull' {
        $out = New-CaptureDir -Serial $Serial -Label $Label
        & $adb -s $Serial pull $csv (Join-Path $out 'power.csv') | Out-Null
        Invoke-AdbShell -Adb $adb -Serial $Serial -Command 'dumpsys batterystats --checkin' -OutFile (Join-Path $out 'batterystats_checkin.txt')
        Invoke-AdbShell -Adb $adb -Serial $Serial -Command 'dumpsys batterystats' -OutFile (Join-Path $out 'batterystats.txt')
        Invoke-AdbShell -Adb $adb -Serial $Serial -Command 'dumpsys power' -OutFile (Join-Path $out 'power.txt')
        Invoke-AdbShell -Adb $adb -Serial $Serial -Command 'dumpsys alarm' -OutFile (Join-Path $out 'alarm.txt')
        Invoke-AdbShell -Adb $adb -Serial $Serial -Command 'dumpsys jobscheduler' -OutFile (Join-Path $out 'jobscheduler.txt')
        Invoke-AdbShell -Adb $adb -Serial $Serial -Command 'dumpsys deviceidle' -OutFile (Join-Path $out 'deviceidle.txt')
        Write-Host "Pulled into $out. For Battery Historian also run: adb bugreport `"$out\bugreport.zip`""
    }
}
