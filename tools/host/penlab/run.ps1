# Runs a penlab script on the tablet and brings back its output and bitmaps.
#
#   .\tools\host\penlab\run.ps1 -Script <file> -Out <local folder> [-Serial <adb serial>]
#
# Streams files with exec-out (adb pull fails silently on the maintainer's PC). Output stays out of the repository.
param(
    [Parameter(Mandatory = $true)][string]$Script,
    [Parameter(Mandatory = $true)][string]$Out,
    [string]$Serial = ''
)
$ErrorActionPreference = 'Stop'
$s = if ($Serial) { "-s $Serial" } else { '' }
New-Item -ItemType Directory -Force $Out | Out-Null
$remote = '/data/local/tmp/penlab'
cmd /c "adb $s shell `"rm -rf $remote/out; mkdir -p $remote/out`"" | Out-Null
cmd /c "adb $s push `"$Script`" $remote/script.txt" | Out-Null
$cmd = "CLASSPATH=$remote/penlab.dex app_process /system/bin app.booxultimatum.penlab.Main $remote/script.txt $remote/out"
cmd /c "adb $s shell `"$cmd`" > `"$Out\result.txt`" 2>&1"
$files = (cmd /c "adb $s shell ls $remote/out") -split "`r?`n" | Where-Object { $_ -match '\.png$' }
foreach ($f in $files) { cmd /c "adb $s exec-out cat $remote/out/$f > `"$Out\$f`"" }
cmd /c "adb $s shell rm -rf $remote/out $remote/script.txt" | Out-Null
Write-Host "penlab: $($files.Count) bitmaps; output in $Out\result.txt"
