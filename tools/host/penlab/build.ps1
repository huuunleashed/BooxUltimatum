# Builds penlab (a developer tool that runs the firmware's pen-ink library on the tablet) and pushes it.
# Run from the repository root:
#
#   .\tools\host\penlab\build.ps1 [-Serial <adb serial>]
#
# Then run a script on the tablet with .\tools\host\penlab\run.ps1. Nothing here is bundled in an app.
param([string]$Serial = '')
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$jdk = if ($env:JAVA_HOME) { $env:JAVA_HOME } else { 'C:\Program Files\Eclipse Adoptium\jdk-21.0.8.9-hotspot' }
$sdk = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { "$env:LOCALAPPDATA\Android\Sdk" }
$androidJar = "$sdk\platforms\android-36\android.jar"
$bt = Get-ChildItem "$sdk\build-tools" -Directory | Sort-Object { [version]($_.Name -replace '[^0-9.]', '') } | Select-Object -Last 1
$build = Join-Path $root 'build'
if (Test-Path $build) { Remove-Item $build -Recurse -Force }
New-Item -ItemType Directory -Force "$build\classes" | Out-Null
$sources = Get-ChildItem "$root\src" -Recurse -Filter *.java | ForEach-Object { $_.FullName }
& "$jdk\bin\javac.exe" --release 11 -nowarn -cp $androidJar -d "$build\classes" @sources
if ($LASTEXITCODE -ne 0) { throw 'javac failed' }
$classes = Get-ChildItem "$build\classes" -Recurse -Filter *.class | ForEach-Object { $_.FullName }
& "$($bt.FullName)\d8.bat" --min-api 30 --lib $androidJar --output $build @classes
if ($LASTEXITCODE -ne 0) { throw 'd8 failed' }
$adb = @('adb'); if ($Serial) { $adb += @('-s', $Serial) }
& $adb[0] $adb[1..($adb.Length - 1)] shell mkdir -p /data/local/tmp/penlab | Out-Null
& $adb[0] $adb[1..($adb.Length - 1)] push "$build\classes.dex" /data/local/tmp/penlab/penlab.dex | Out-Null
Write-Host "penlab built and pushed to /data/local/tmp/penlab/penlab.dex"
