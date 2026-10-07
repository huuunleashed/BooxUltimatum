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
if ($files.Count -gt 0) {
    # One tar over the link: one exec-out per bitmap takes minutes over a relayed connection such as Tailscale.
    cmd /c "adb $s shell `"cd $remote && tar -cf out.tar out`"" | Out-Null
    cmd /c "adb $s exec-out cat $remote/out.tar > `"$Out\out.tar`""
    tar -xf "$Out\out.tar" -C $Out
    Get-ChildItem "$Out\out" -Filter *.png | Move-Item -Destination $Out -Force
    Remove-Item -Recurse -Force "$Out\out", "$Out\out.tar"
}
cmd /c "adb $s shell rm -rf $remote/out $remote/out.tar $remote/script.txt" | Out-Null
Write-Host "penlab: $($files.Count) bitmaps; output in $Out\result.txt"
