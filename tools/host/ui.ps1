# Helpers for driving the tablet UI over adb, for tests and screenshots. Dot-source it in each call:
#   . .\tools\host\ui.ps1; Go-Rail 'Sleep'; Save-Shot 'sleep'
# Read-only apart from taps and swipes; never use it to change settings on a tablet that isn't yours.
$script:adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
# Screenshots go to captures\shots (git-ignored: they can show personal data) unless $env:BU_SHOTS says otherwise.
$script:shots = if ($env:BU_SHOTS) { $env:BU_SHOTS } else { Join-Path (Split-Path (Split-Path $PSScriptRoot)) "captures\shots" }
New-Item -ItemType Directory -Force $script:shots | Out-Null

function Get-UiNodes {
    $xml = & $script:adb exec-out uiautomator dump /dev/tty 2>$null | Out-String
    $xml = $xml.Substring(0, [Math]::Max(0, $xml.LastIndexOf('>') + 1))
    $matches2 = [regex]::Matches($xml, '<node [^>]*?text="([^"]*)"[^>]*?content-desc="([^"]*)"[^>]*?bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')
    foreach ($m in $matches2) {
        [pscustomobject]@{
            Text = [System.Net.WebUtility]::HtmlDecode($m.Groups[1].Value)
            Desc = [System.Net.WebUtility]::HtmlDecode($m.Groups[2].Value)
            X = ([int]$m.Groups[3].Value + [int]$m.Groups[5].Value) / 2
            Y = ([int]$m.Groups[4].Value + [int]$m.Groups[6].Value) / 2
        }
    }
}

function Tap-Text([string]$text, [int]$index = 0, [switch]$Contains, [int]$wait = 3) {
    $nodes = Get-UiNodes | Where-Object { if ($Contains) { $_.Text -like "*$text*" -or $_.Desc -like "*$text*" } else { $_.Text -eq $text -or $_.Desc -eq $text } }
    $n = @($nodes)[$index]
    if (-not $n) { Write-Host "NOT FOUND: $text"; return $false }
    & $script:adb shell input tap ([int]$n.X) ([int]$n.Y)
    Start-Sleep -Seconds $wait
    return $true
}

function Has-Text([string]$text, [switch]$Contains) {
    $nodes = Get-UiNodes | Where-Object { if ($Contains) { $_.Text -like "*$text*" } else { $_.Text -eq $text } }
    return [bool]@($nodes).Count
}

function Save-Shot([string]$name) {
    & $script:adb exec-out screencap -p > "$script:shots\$name.png"
    "{0} {1}KB" -f $name, [math]::Round((Get-Item "$script:shots\$name.png").Length / 1KB)
}

function Sh([string]$cmd) { & $script:adb shell $cmd }

function Open-Main { Sh 'am force-stop app.booxultimatum' | Out-Null; Sh 'am start -n app.booxultimatum/.MainActivity' | Out-Null; Start-Sleep 5 }

function Go-Rail([string]$label) { Tap-Text $label -wait 6 | Out-Null }

# Taps the button labelled $action that sits on the same row as $title (nearest below-or-level within 220 px).
function Tap-RowAction([string]$title, [string]$action, [int]$wait = 5) {
    $nodes = @(Get-UiNodes)
    $t = $nodes | Where-Object { $_.Text -eq $title } | Select-Object -First 1
    if (-not $t) { Write-Host "ROW NOT FOUND: $title"; return $false }
    $b = $nodes | Where-Object { $_.Text -eq $action -and $_.Y -ge ($t.Y - 60) -and $_.Y -le ($t.Y + 220) } | Sort-Object { [math]::Abs($_.Y - $t.Y) } | Select-Object -First 1
    if (-not $b) { Write-Host "ACTION NOT FOUND: $action near $title"; return $false }
    & $script:adb shell input tap ([int]$b.X) ([int]$b.Y)
    Start-Sleep -Seconds $wait
    return $true
}

function Swipe-Up([int]$times = 1) { for ($i = 0; $i -lt $times; $i++) { & $script:adb shell input swipe 900 1500 900 450 400; Start-Sleep 2 } }
function Swipe-Down([int]$times = 1) { for ($i = 0; $i -lt $times; $i++) { & $script:adb shell input swipe 900 450 900 1500 400; Start-Sleep 2 } }


