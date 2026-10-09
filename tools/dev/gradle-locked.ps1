<#
.SYNOPSIS
    Runs gradlew in the current folder while holding a machine-wide lock, so several worktrees can build one after another.
.DESCRIPTION
    Parallel Gradle builds of this project fight over CPU, memory and the shared caches. Every build started through this
    script waits for the lock first (up to 40 minutes). Redirect long output to a file and read that.
.EXAMPLE
    .\tools\dev\gradle-locked.ps1 :hub:compileDebugKotlin -q *> build.log; Get-Content build.log -Tail 40
#>
param([Parameter(ValueFromRemainingArguments = $true)][string[]]$GradleArgs)
$env:JAVA_HOME = 'C:\Program Files\Eclipse Adoptium\jdk-21.0.8.9-hotspot'
$mutex = New-Object System.Threading.Mutex($false, 'Global\BooxUltimatumGradle')
$held = $false
$code = 1
try {
    try { $held = $mutex.WaitOne([TimeSpan]::FromMinutes(40)) } catch [System.Threading.AbandonedMutexException] { $held = $true }
    if (-not $held) { Write-Host 'Timed out waiting for the Gradle lock.'; $code = 2 }
    else {
        Write-Host "Gradle lock held in $((Get-Location).Path)"
        & .\gradlew.bat @GradleArgs
        $code = $LASTEXITCODE
    }
} finally {
    if ($held) { $mutex.ReleaseMutex() }
}
exit $code