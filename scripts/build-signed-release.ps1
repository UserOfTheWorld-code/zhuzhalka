# Local release builds reuse the permanent key; this helper never generates keys.
[CmdletBinding()]
param(
    [string]$SigningDirectory = (Join-Path $env:LOCALAPPDATA 'Zhuzhalka\signing'),
    [string]$JavaHome = $env:JAVA_HOME,
    [string]$AndroidHome = $env:ANDROID_HOME,
    [string[]]$Tasks = @(':app:testPlaystoreDebugUnitTest', ':app:compilePlaystoreDebugKotlin', ':app:assemblePlaystoreRelease', ':app:assembleFdroidRelease')
)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$keyPath = Join-Path $SigningDirectory 'release.jks'
$passwordPath = Join-Path $SigningDirectory 'password.dpapi'
if (!(Test-Path -LiteralPath $keyPath) -or !(Test-Path -LiteralPath $passwordPath)) {
    throw 'Permanent signing files are missing. Restore the existing key; do not create a replacement for a published app.'
}
if (!$JavaHome) { $JavaHome = Join-Path $env:ProgramFiles 'Android\Android Studio\jbr' }
if (!(Test-Path -LiteralPath (Join-Path $JavaHome 'bin\java.exe'))) { throw 'Set -JavaHome to JDK 21.' }
if (!$AndroidHome) { $AndroidHome = $env:ANDROID_SDK_ROOT }
if (!$AndroidHome) { $AndroidHome = Join-Path $env:LOCALAPPDATA 'Android\Sdk' }
if (!(Test-Path -LiteralPath $AndroidHome -PathType Container)) { throw 'Set -AndroidHome to the Android SDK directory.' }
$secure = ConvertTo-SecureString (Get-Content -LiteralPath $passwordPath -Raw).Trim()
$prior = @{}
foreach ($name in @('JAVA_HOME', 'ANDROID_HOME', 'ANDROID_SDK_ROOT', 'PATH', 'ZHUZHALKA_KEYSTORE_PATH', 'ZHUZHALKA_KEYSTORE_PASSWORD')) {
    $prior[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
}
try {
    $env:JAVA_HOME = $JavaHome
    $env:ANDROID_HOME = $AndroidHome
    $env:ANDROID_SDK_ROOT = $AndroidHome
    $env:PATH = (Join-Path $JavaHome 'bin') + [IO.Path]::PathSeparator + $env:PATH
    $env:ZHUZHALKA_KEYSTORE_PATH = (Resolve-Path -LiteralPath $keyPath).Path
    $env:ZHUZHALKA_KEYSTORE_PASSWORD = [System.Net.NetworkCredential]::new('', $secure).Password
    Push-Location (Join-Path $projectRoot 'V2rayNG')
    try {
        # No daemon retains the signing environment after this build process exits.
        & .\gradlew.bat --no-daemon --console=plain @Tasks
        $buildExitCode = $LASTEXITCODE
    } finally { Pop-Location }
    if ($buildExitCode -ne 0) { throw "Gradle failed with exit code $buildExitCode." }
} finally {
    foreach ($name in $prior.Keys) { [Environment]::SetEnvironmentVariable($name, $prior[$name], 'Process') }
    $secure.Dispose()
}
