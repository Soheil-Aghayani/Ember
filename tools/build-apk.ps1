# Builds and verifies a signed Android release APK using the Flutter port's root Gradle wrapper.
# The Android keystore and its DPAPI-protected password file stay outside the repository.
#
#   .\tools\build-apk.ps1 -KeystoreDir D:\Programming\Ember-releases\signing

[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$KeystoreDir,
    [string]$OutDir = (Join-Path $env:USERPROFILE 'Downloads\Programs')
)

$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
$flutterRoot = Join-Path $root 'flutter'
$gradle = Join-Path $root 'gradlew.bat'
$originalJavaHome = $env:JAVA_HOME
$javaHome = if ($env:JAVA_HOME) { $env:JAVA_HOME } else { 'C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot' }
if (-not (Test-Path -LiteralPath (Join-Path $javaHome 'bin\java.exe') -PathType Leaf)) { throw "JDK 21 was not found at $javaHome; set JAVA_HOME to a JDK 21 installation." }
$originalAndroidHome = $env:ANDROID_HOME
$originalAndroidSdkRoot = $env:ANDROID_SDK_ROOT
$sdkRoot = if ($env:ANDROID_SDK_ROOT) { $env:ANDROID_SDK_ROOT } elseif ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { 'D:\Programming\tools\android-sdk' }
if (-not (Test-Path -LiteralPath $sdkRoot -PathType Container)) { throw "Android SDK was not found at $sdkRoot." }

$flutterCommand = Get-Command flutter -ErrorAction SilentlyContinue
$flutter = if ($flutterCommand) { $flutterCommand.Source } else { 'D:\Programming\tools\flutter\bin\flutter.bat' }
if (-not (Test-Path -LiteralPath $flutter -PathType Leaf)) { throw "Flutter was not found at $flutter." }
if (-not (Test-Path -LiteralPath $gradle -PathType Leaf)) { throw "Gradle wrapper was not found at $gradle." }

$store = Join-Path $KeystoreDir 'ember-android.jks'
$saved = Join-Path $KeystoreDir 'ember-android.pwd'
if (-not (Test-Path -LiteralPath $store -PathType Leaf) -or -not (Test-Path -LiteralPath $saved -PathType Leaf)) {
    throw "No Android key in $KeystoreDir; run tools\New-AndroidKeystore.ps1 first."
}

$versionMatch = [regex]::Match((Get-Content -LiteralPath (Join-Path $root 'android\build.gradle.kts') -Raw), '(?m)^\s*versionName\s*=\s*"([^"]+)"')
if (-not $versionMatch.Success) { throw 'Could not read Android versionName from android/build.gradle.kts.' }
$version = $versionMatch.Groups[1].Value
$pubLock = Join-Path $flutterRoot 'pubspec.lock'
$pubHostMatch = [regex]::Match((Get-Content -LiteralPath $pubLock -Raw), '(?m)^\s*url:\s*"([^"]+)"')
if (-not $pubHostMatch.Success) { throw "Could not read the package host from $pubLock." }
$lockedPubHost = $pubHostMatch.Groups[1].Value
$password = [System.Net.NetworkCredential]::new('', (Get-Content -LiteralPath $saved | ConvertTo-SecureString)).Password
$signingNames = 'SIGNING_KEYSTORE', 'SIGNING_KEY_ALIAS', 'SIGNING_KEY_PASSWORD', 'SIGNING_STORE_PASSWORD'
$originalSigningValues = @{}
foreach ($name in $signingNames) { $originalSigningValues[$name] = [Environment]::GetEnvironmentVariable($name, 'Process') }
$originalPubHostedUrl = $env:PUB_HOSTED_URL

try {
    if (-not $env:JAVA_HOME) { $env:JAVA_HOME = $javaHome }
    if (-not $env:ANDROID_HOME) { $env:ANDROID_HOME = $sdkRoot }
    if (-not $env:ANDROID_SDK_ROOT) { $env:ANDROID_SDK_ROOT = $sdkRoot }
    $env:PUB_HOSTED_URL = $lockedPubHost
    Push-Location -LiteralPath $flutterRoot
    try {
        & $flutter pub get --offline --enforce-lockfile
        if ($LASTEXITCODE -ne 0) { throw 'Flutter dependency resolution failed.' }
    } finally {
        Pop-Location
    }

    $env:SIGNING_KEYSTORE = $store
    $env:SIGNING_KEY_ALIAS = 'ember'
    $env:SIGNING_KEY_PASSWORD = $password
    $env:SIGNING_STORE_PASSWORD = $password
    Push-Location -LiteralPath $root
    try {
        & $gradle :android:assembleRelease --console=plain -q
        if ($LASTEXITCODE -ne 0) { throw 'The Android release build failed.' }
    } finally {
        Pop-Location
    }

    $apk = Join-Path $root 'android\build\outputs\apk\release\android-release.apk'
    if (-not (Test-Path -LiteralPath $apk -PathType Leaf)) { throw "Signed Android APK was not produced: $apk" }

    $buildToolsRoot = Join-Path $sdkRoot 'build-tools'
    $buildTools = Get-ChildItem -LiteralPath $buildToolsRoot -Directory -ErrorAction SilentlyContinue |
        Sort-Object { try { [version]$_.Name } catch { [version]'0.0' } } -Descending |
        Select-Object -First 1
    if (-not $buildTools) { throw "Android Build Tools were not found in $buildToolsRoot." }
    $apksigner = Join-Path $buildTools.FullName 'apksigner.bat'
    if (-not (Test-Path -LiteralPath $apksigner -PathType Leaf)) { throw "apksigner was not found in $($buildTools.FullName)." }
    & $apksigner verify --print-certs $apk
    if ($LASTEXITCODE -ne 0) { throw 'Android APK signature verification failed.' }

    New-Item -ItemType Directory -Force -Path $OutDir | Out-Null
    $final = Join-Path $OutDir "Ember-$version.apk"
    Copy-Item -LiteralPath $apk -Destination $final -Force
    Write-Host "Signed Android APK ready: $final"
    Get-FileHash -LiteralPath $final -Algorithm SHA256 | ForEach-Object { "SHA-256 $($_.Hash)" }
} finally {
    foreach ($name in $signingNames) {
        $value = $originalSigningValues[$name]
        if ($null -eq $value) { Remove-Item "Env:$name" -ErrorAction SilentlyContinue }
        else { Set-Item "Env:$name" $value }
    }
    if ($originalJavaHome) { $env:JAVA_HOME = $originalJavaHome }
    else { Remove-Item Env:JAVA_HOME -ErrorAction SilentlyContinue }
    if ($originalAndroidHome) { $env:ANDROID_HOME = $originalAndroidHome }
    else { Remove-Item Env:ANDROID_HOME -ErrorAction SilentlyContinue }
    if ($originalAndroidSdkRoot) { $env:ANDROID_SDK_ROOT = $originalAndroidSdkRoot }
    else { Remove-Item Env:ANDROID_SDK_ROOT -ErrorAction SilentlyContinue }
    if ($originalPubHostedUrl) { $env:PUB_HOSTED_URL = $originalPubHostedUrl }
    else { Remove-Item Env:PUB_HOSTED_URL -ErrorAction SilentlyContinue }
    $password = $null
}
