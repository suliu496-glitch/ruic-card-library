param(
    [ValidateSet('Debug', 'Release')][string]$Configuration = 'Release',
    [switch]$Offline
)
$ErrorActionPreference = 'Stop'
if (-not $env:ANDROID_HOME -or -not (Test-Path -LiteralPath $env:ANDROID_HOME)) {
    throw 'Set ANDROID_HOME to your Android SDK directory.'
}
if ($Configuration -eq 'Release') {
    $signingFile = if ($env:RUIC_SIGNING_PROPERTIES) { $env:RUIC_SIGNING_PROPERTIES }
        else { Join-Path ([Environment]::GetFolderPath('UserProfile')) '.codex/signing/ruic-card-library/signing.properties' }
    if (-not (Test-Path -LiteralPath $signingFile)) {
        throw 'Release requires your private signing.properties. Use -Configuration Debug for a local test APK.'
    }
}
$cacheRoot = if ($env:GRADLE_USER_HOME) { $env:GRADLE_USER_HOME }
    else { Join-Path ([Environment]::GetFolderPath('UserProfile')) '.gradle' }
$distributionRoot = Join-Path $cacheRoot 'wrapper/dists/gradle-9.1.0-bin'
$cachedGradle = if (Test-Path -LiteralPath $distributionRoot) {
    Get-ChildItem -LiteralPath $distributionRoot -Filter 'gradle.bat' -Recurse | Select-Object -First 1
}
$gradleCommand = if ($cachedGradle) { $cachedGradle.FullName } else { Join-Path $PSScriptRoot 'gradlew.bat' }
if ($Offline -and -not $cachedGradle) { throw 'No cached Gradle 9.1.0 distribution. Run once without -Offline.' }
$buildArguments = @('--no-daemon', (':app:assemble' + $Configuration), (':app:lint' + $Configuration))
if ($Offline) { $buildArguments = @('--offline') + $buildArguments }
Push-Location $PSScriptRoot
try {
    & $gradleCommand @buildArguments
    if ($LASTEXITCODE -ne 0) { throw 'Android build or lint failed.' }
} finally { Pop-Location }
