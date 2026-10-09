# Builds, checks and publishes one suite app's release-signed APK to GitHub Releases, named the way the in-app
# updater (kit/update) expects, with its .sha256 and the zipped R8 mapping file. Run from the repository root:
#
#   .\tools\dev\publish.ps1 -App hub -Notes notes.md            # tag v0.6.0,        asset BooxUltimatum-0.6.0.apk
#   .\tools\dev\publish.ps1 -App nib -Notes notes.md            # tag nib-v0.1.0,    asset Nib-0.1.0.apk
#   .\tools\dev\publish.ps1 -App nib -Test -Notes notes.md      # tag test-nib-0.1.0-test.1, asset test-Nib-0.1.0-test.1.apk
#   add -DryRun to build and check everything without publishing.
#
# Test builds need a pre-release versionName such as 0.1.0-test.1, and are only offered to tablets whose owner turned
# on test builds. Hubs from before the suite (0.5.x) never see them. The commit being released must already be pushed and
# the tree clean: the release is tagged on that exact commit (from any branch), so the tag always matches what was built.
param(
    [Parameter(Mandatory = $true)][ValidateSet('hub', 'nib')][string]$App,
    [Parameter(Mandatory = $true)][string]$Notes,
    [switch]$Test,
    [switch]$DryRun,
    [string]$Signing = "$env:USERPROFILE\.booxultimatum\signing.properties"
)
$ErrorActionPreference = 'Stop'
$ReleaseCert = '75dbdea9807374ce0a269432528187798d9f662e4fbf8953129f2993d99a2121'
$Repo = 'huuunleashed/BooxUltimatum'

$apps = @{
    hub = @{ Module = 'hub'; Asset = 'BooxUltimatum'; TagPrefix = 'v'; Title = 'BooxUltimatum' }
    nib = @{ Module = 'nib'; Asset = 'Nib'; TagPrefix = 'nib-v'; Title = 'Nib' }
}
$a = $apps[$App]
if (-not (Test-Path $Notes)) { throw "Notes file not found: $Notes" }
if (-not (Test-Path $Signing)) { throw "Signing properties not found: $Signing" }

$commit = (git rev-parse HEAD).Trim()
if (git status --porcelain) { throw 'The tree has uncommitted changes; commit them first, so the tag is what gets built' }
if (-not (git branch -r --contains $commit)) { throw "Commit $commit isn't pushed; push it first" }

$gradleFile = Get-Content "$($a.Module)\build.gradle.kts" -Raw
$version = [regex]::Match($gradleFile, 'versionName\s*=\s*"([^"]+)"').Groups[1].Value
$code = [regex]::Match($gradleFile, 'versionCode\s*=\s*(\d+)').Groups[1].Value
if (-not $version) { throw "No versionName in $($a.Module)\build.gradle.kts" }
$isPre = $version -match '-'
if ($Test -and -not $isPre) { throw "A test build needs a pre-release versionName such as $version-test.1" }
if (-not $Test -and $isPre) { throw "Version $version has a pre-release suffix; publish it with -Test, or drop the suffix" }

$tag = if ($Test) { "test-$App-$version" } else { "$($a.TagPrefix)$version" }
$assetBase = if ($Test) { "test-$($a.Asset)-$version" } else { "$($a.Asset)-$version" }
Write-Host "$($a.Title) $version ($code) -> tag $tag, asset $assetBase.apk"

if (-not $env:JAVA_HOME) { $env:JAVA_HOME = 'C:\Program Files\Eclipse Adoptium\jdk-21.0.8.9-hotspot' }
& .\gradlew.bat ":$($a.Module):assembleRelease" "-Pbu.signing=$Signing" --console=plain -q
if ($LASTEXITCODE -ne 0) { throw 'Build failed' }

$apk = "$($a.Module)\build\outputs\apk\release\$($a.Module)-release.apk"
$mapping = "$($a.Module)\build\outputs\mapping\release\mapping.txt"
$sdk = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { "$env:LOCALAPPDATA\Android\Sdk" }
$apksigner = Get-ChildItem "$sdk\build-tools" -Directory | Sort-Object { [version]($_.Name -replace '[^0-9.]', '') } | Select-Object -Last 1 | ForEach-Object { Join-Path $_.FullName 'apksigner.bat' }
$certs = & $apksigner verify --print-certs $apk
if ($LASTEXITCODE -ne 0) { throw 'apksigner rejected the APK' }
$sha = ($certs | Select-String 'SHA-256 digest: ([0-9a-f]{64})').Matches | Select-Object -First 1 | ForEach-Object { $_.Groups[1].Value }
if ($sha -ne $ReleaseCert) { throw "Signed with $sha, not the release key" }

New-Item -ItemType Directory -Force dist | Out-Null
$outApk = "dist\$assetBase.apk"
Copy-Item $apk $outApk -Force
$files = @($outApk)
$hash = (Get-FileHash $outApk -Algorithm SHA256).Hash.ToLower()
[IO.File]::WriteAllText("$PWD\dist\$assetBase.apk.sha256", "$hash  $assetBase.apk`n", (New-Object Text.UTF8Encoding $false))
$files += "dist\$assetBase.apk.sha256"
if (Test-Path $mapping) {
    # Zipped: the mapping is tens of megabytes of text and shrinks about tenfold.
    $zip = "dist\$assetBase-mapping.zip"
    if (Test-Path $zip) { Remove-Item $zip }
    Compress-Archive -Path $mapping -DestinationPath $zip
    $files += $zip
}
Write-Host "SHA-256 $hash"

$title = if ($Test) { "$($a.Title) $version (test build)" } else { "$($a.Title) $version" }
if ($DryRun) { Write-Host "Dry run: would publish $($files -join ', ') as $tag, '$title'"; return }
& gh release create $tag @files --repo $Repo --target $commit --title $title --notes-file $Notes --prerelease
if ($LASTEXITCODE -ne 0) { throw 'gh release create failed' }

# Check what anyone would download matches what was built.
$tmp = Join-Path $env:TEMP "$assetBase.apk"
& gh release download $tag --repo $Repo --pattern "$assetBase.apk" --output $tmp --clobber
$remote = (Get-FileHash $tmp -Algorithm SHA256).Hash.ToLower()
Remove-Item $tmp
if ($remote -ne $hash) { throw "Published APK hash $remote doesn't match $hash" }
Write-Host "Published $tag; the download matches."
