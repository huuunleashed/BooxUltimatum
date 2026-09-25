# Shared helpers for host scripts. Dot-source: . (Join-Path $PSScriptRoot 'common.ps1')

$script:RepoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path

function Get-Adb {
    $candidates = @()
    if ($env:ANDROID_HOME) { $candidates += Join-Path $env:ANDROID_HOME 'platform-tools\adb.exe' }
    $candidates += Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe'
    $onPath = Get-Command adb -ErrorAction SilentlyContinue
    if ($onPath) { $candidates += $onPath.Source }
    foreach ($c in $candidates) { if ($c -and (Test-Path $c)) { return $c } }
    throw 'adb not found. Install platform-tools or set ANDROID_HOME.'
}

function Select-Device {
    param([string]$Adb, [string]$Serial)
    $lines = & $Adb devices | Select-Object -Skip 1 | Where-Object { $_.Trim() }
    $devices = foreach ($l in $lines) { $p = $l -split '\s+'; [pscustomobject]@{ Serial = $p[0]; State = $p[1] } }
    if ($Serial) { $devices = $devices | Where-Object Serial -eq $Serial }
    if (-not $devices) { throw 'No device connected. Enable USB debugging and plug the tablet in.' }
    $unauth = $devices | Where-Object State -ne 'device'
    if ($unauth) { throw "Device $($unauth[0].Serial) is '$($unauth[0].State)'. Accept the RSA prompt on the tablet." }
    if (@($devices).Count -gt 1) { throw "Multiple devices: $((@($devices).Serial) -join ', '). Pass -Serial." }
    return @($devices)[0].Serial
}

function New-CaptureDir {
    param([string]$Serial, [string]$Label)
    $safe = ($Label -replace '[^\w\-]', '_')
    $dir = Join-Path $script:RepoRoot ("captures\{0}-{1}-{2}" -f (Get-Date -Format 'yyyyMMdd-HHmm'), $Serial, $safe)
    New-Item -ItemType Directory -Force -Path $dir | Out-Null
    return $dir
}

function Invoke-AdbShell {
    param([string]$Adb, [string]$Serial, [string]$Command, [string]$OutFile)
    # Base64 transport: Windows PowerShell 5.1 mangles embedded quotes when calling native exes.
    $b64 = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($Command.Replace("`r`n", "`n")))
    $result = & $Adb -s $Serial shell "echo $b64 | base64 -d | sh" 2>&1 | ForEach-Object { "$_" }
    if ($OutFile) { $result | Set-Content -Path $OutFile -Encoding utf8 } else { $result }
}
