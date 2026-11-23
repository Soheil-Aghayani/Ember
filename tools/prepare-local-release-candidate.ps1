# Builds a local Windows installer and an Android phone/TV APK, then writes a versioned manifest and SHA-256 list.
# This prepares local review artifacts only: it never publishes, uploads, signs Windows files, or runs an installer.
# Supply the Windows resources folder used by ember-rs/installer/stage.ps1. Android signing is optional.
#
#   .\tools\prepare-local-release-candidate.ps1 `
#     -WindowsResources 'C:\Program Files\Ember\app\resources' `
#     -AndroidKeystoreDir 'D:\Programming\Ember-releases\signing'

[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidateScript({ Test-Path -LiteralPath $_ -PathType Container })]
    [string]$WindowsResources,
    [string]$OutputRoot = 'D:\Programming\Ember-releases\local-candidates',
    [string]$AndroidKeystoreDir
)

$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path $PSScriptRoot -Parent
$rustRoot = Join-Path $repoRoot 'ember-rs'
$androidRoot = Join-Path $repoRoot 'android'
$windowsStageScript = Join-Path $rustRoot 'installer\stage.ps1'
$windowsInstallerScript = Join-Path $rustRoot 'installer\ember-rs.iss'
$androidSigningScript = Join-Path $repoRoot 'tools\build-apk.ps1'
$gradleWrapper = Join-Path $repoRoot 'gradlew.bat'

function Read-Version([string]$Path, [string]$Pattern, [string]$Label) {
    $match = [regex]::Match((Get-Content -LiteralPath $Path -Raw), $Pattern)
    if (-not $match.Success) { throw "Could not read the $Label from $Path." }
    return $match.Groups[1].Value
}

function Invoke-Checked([string]$Executable, [string[]]$Arguments, [string]$WorkingDirectory) {
    Push-Location -LiteralPath $WorkingDirectory
    try {
        & $Executable @Arguments
        if ($LASTEXITCODE -ne 0) { throw "$([IO.Path]::GetFileName($Executable)) failed with exit code $LASTEXITCODE." }
    } finally {
        Pop-Location
    }
}

$windowsVersion = Read-Version (Join-Path $rustRoot 'ember-ui\Cargo.toml') '(?m)^version\s*=\s*"([^"]+)"' 'Windows app version'
$androidBuildFile = Join-Path $androidRoot 'build.gradle.kts'
$androidVersion = Read-Version $androidBuildFile '(?m)^\s*versionName\s*=\s*"([^"]+)"' 'Android app version'
$androidVersionCode = [int](Read-Version $androidBuildFile '(?m)^\s*versionCode\s*=\s*(\d+)' 'Android version code')
$pubLock = Join-Path $repoRoot 'flutter\pubspec.lock'
$pubHostMatch = [regex]::Match((Get-Content -LiteralPath $pubLock -Raw), '(?m)^\s*url:\s*"([^"]+)"')
if (-not $pubHostMatch.Success) { throw "Could not read the package host from $pubLock." }
$lockedPubHost = $pubHostMatch.Groups[1].Value
$originalPubHostedUrl = $env:PUB_HOSTED_URL

$cargo = (Get-Command cargo -ErrorAction SilentlyContinue).Source
if (-not $cargo) { throw 'Cargo is not available on PATH.' }
$flutterCommand = Get-Command flutter -ErrorAction SilentlyContinue
$flutter = if ($flutterCommand) { $flutterCommand.Source } else { 'D:\Programming\tools\flutter\bin\flutter.bat' }
if (-not (Test-Path -LiteralPath $flutter -PathType Leaf)) { throw "Flutter was not found at $flutter." }
$isccCommand = Get-Command ISCC.exe -ErrorAction SilentlyContinue
$iscc = if ($isccCommand) { $isccCommand.Source } else {
    @('C:\Program Files (x86)\Inno Setup 6\ISCC.exe', 'C:\Program Files\Inno Setup 6\ISCC.exe') |
        Where-Object { Test-Path -LiteralPath $_ -PathType Leaf } |
        Select-Object -First 1
}
if (-not $iscc) { throw 'Inno Setup 6 ISCC.exe was not found.' }
if (-not (Test-Path -LiteralPath $gradleWrapper -PathType Leaf)) { throw "Gradle wrapper is missing: $gradleWrapper" }

foreach ($required in 'ember-helper.exe', 'ember-tunnel.ps1', 'tunnel') {
    if (-not (Test-Path -LiteralPath (Join-Path $WindowsResources $required))) {
        throw "Required Windows resource '$required' is missing from $WindowsResources."
    }
}
if ($AndroidKeystoreDir -and -not (Test-Path -LiteralPath $AndroidKeystoreDir -PathType Container)) {
    throw "Android keystore directory was not found: $AndroidKeystoreDir"
}

$candidateId = "Ember-$windowsVersion-$androidVersion-$((Get-Date).ToUniversalTime().ToString('yyyyMMdd-HHmmss-fffZ'))"
New-Item -ItemType Directory -Force -Path $OutputRoot | Out-Null
$candidateRoot = Join-Path $OutputRoot $candidateId
if (Test-Path -LiteralPath $candidateRoot) { throw "Candidate folder already exists; choose a new output root: $candidateRoot" }
$windowsStage = Join-Path $candidateRoot 'windows-stage'
$windowsOutput = Join-Path $candidateRoot 'windows'
$androidOutput = Join-Path $candidateRoot 'android'
New-Item -ItemType Directory -Path $candidateRoot | Out-Null
New-Item -ItemType Directory -Path $windowsOutput, $androidOutput | Out-Null

$originalJavaHome = $env:JAVA_HOME
$javaHome = if ($env:JAVA_HOME) { $env:JAVA_HOME } else { 'C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot' }
if (-not (Test-Path -LiteralPath (Join-Path $javaHome 'bin\java.exe') -PathType Leaf)) { throw "JDK 21 was not found at $javaHome; set JAVA_HOME to a JDK 21 installation." }
$originalAndroidHome = $env:ANDROID_HOME
$originalAndroidSdkRoot = $env:ANDROID_SDK_ROOT
$androidSdk = if ($env:ANDROID_SDK_ROOT) { $env:ANDROID_SDK_ROOT } elseif ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { 'D:\Programming\tools\android-sdk' }
if (-not (Test-Path -LiteralPath $androidSdk -PathType Container)) { throw "Android SDK was not found at $androidSdk." }

try {
    if (-not $env:JAVA_HOME) { $env:JAVA_HOME = $javaHome }
    if (-not $env:ANDROID_HOME) { $env:ANDROID_HOME = $androidSdk }
    if (-not $env:ANDROID_SDK_ROOT) { $env:ANDROID_SDK_ROOT = $androidSdk }
    $env:PUB_HOSTED_URL = $lockedPubHost
    Write-Host "Building Windows PC candidate $windowsVersion..."
    Invoke-Checked $cargo @('build', '--release', '--manifest-path', (Join-Path $rustRoot 'Cargo.toml'), '-p', 'ember-ui') $repoRoot
    & $windowsStageScript -Resources $WindowsResources -Out $windowsStage
    if (-not (Test-Path -LiteralPath (Join-Path $windowsStage 'Ember.exe') -PathType Leaf)) { throw 'Windows staging did not produce Ember.exe.' }
    Invoke-Checked $iscc @(
        "/DVersion=$windowsVersion",
        "/DSourceDir=$windowsStage",
        "/DOutputDir=$windowsOutput",
        $windowsInstallerScript
    ) $repoRoot
    $windowsArtifact = Join-Path $windowsOutput "Ember-Rust-$windowsVersion.exe"
    if (-not (Test-Path -LiteralPath $windowsArtifact -PathType Leaf)) { throw "Windows installer was not produced: $windowsArtifact" }

    Write-Host "Fetching Flutter packages and building Android release $androidVersion..."
    Invoke-Checked $flutter @('pub', 'get', '--offline', '--enforce-lockfile') (Join-Path $repoRoot 'flutter')
    if ($AndroidKeystoreDir) {
        & $androidSigningScript -KeystoreDir $AndroidKeystoreDir -OutDir $androidOutput
        if ($LASTEXITCODE -and $LASTEXITCODE -ne 0) { throw 'Signed Android build failed.' }
        $androidArtifact = Join-Path $androidOutput "Ember-$androidVersion.apk"
        $androidSigned = $true
    } else {
        $signingNames = 'SIGNING_KEYSTORE', 'SIGNING_KEY_ALIAS', 'SIGNING_KEY_PASSWORD', 'SIGNING_STORE_PASSWORD'
        $originalSigningValues = @{}
        foreach ($name in $signingNames) {
            $originalSigningValues[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
            Remove-Item "Env:$name" -ErrorAction SilentlyContinue
        }
        try {
            Invoke-Checked $gradleWrapper @(':android:assembleRelease', '--console=plain') $repoRoot
        } finally {
            foreach ($name in $signingNames) {
                $value = $originalSigningValues[$name]
                if ($null -eq $value) { Remove-Item "Env:$name" -ErrorAction SilentlyContinue }
                else { Set-Item "Env:$name" $value }
            }
        }
        $releaseDir = Join-Path $androidRoot 'build\outputs\apk\release'
        $unsignedApks = @(Get-ChildItem -LiteralPath $releaseDir -Filter '*unsigned*.apk' -File)
        if ($unsignedApks.Count -ne 1) { throw "Expected one unsigned Android release APK in $releaseDir; found $($unsignedApks.Count)." }
        $androidArtifact = Join-Path $androidOutput "Ember-$androidVersion-unsigned.apk"
        Copy-Item -LiteralPath $unsignedApks[0].FullName -Destination $androidArtifact
        $androidSigned = $false
    }
    if (-not (Test-Path -LiteralPath $androidArtifact -PathType Leaf)) { throw "Android APK was not produced: $androidArtifact" }

    $artifactFiles = @($windowsArtifact, $androidArtifact)
    $artifacts = foreach ($path in $artifactFiles) {
        $file = Get-Item -LiteralPath $path
        $hash = (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash.ToLowerInvariant()
        $relative = $path.Substring($candidateRoot.TrimEnd('\').Length + 1).Replace('\', '/')
        [ordered]@{
            file = $relative
            bytes = $file.Length
            sha256 = $hash
            platform = if ($path -eq $windowsArtifact) { 'Windows PC x64' } else { 'Android phone and Android TV arm64' }
            version = if ($path -eq $windowsArtifact) { $windowsVersion } else { $androidVersion }
            signed = if ($path -eq $windowsArtifact) { $false } else { $androidSigned }
        }
    }
    $checksumLines = foreach ($artifact in $artifacts) { "$($artifact.sha256) *$($artifact.file)" }
    $utf8NoBom = [System.Text.UTF8Encoding]::new($false)
    [System.IO.File]::WriteAllLines((Join-Path $candidateRoot 'SHA256SUMS.txt'), [string[]]$checksumLines, $utf8NoBom)

    $commit = (& git -C $repoRoot rev-parse HEAD 2>$null | Select-Object -First 1)
    $branch = (& git -C $repoRoot branch --show-current 2>$null | Select-Object -First 1)
    $manifest = [ordered]@{
        schemaVersion = 1
        app = 'Ember'
        candidateId = $candidateId
        builtAtUtc = (Get-Date).ToUniversalTime().ToString('o')
        source = [ordered]@{ commit = "$commit".Trim(); branch = "$branch".Trim() }
        targets = @('Windows PC x64', 'Android phone arm64', 'Android TV arm64')
        android = [ordered]@{
            version = $androidVersion
            versionCode = $androidVersionCode
            phoneAndTvShareApk = $true
            signing = if ($androidSigned) { 'signed with local keystore' } else { 'unsigned local candidate' }
        }
        windows = [ordered]@{ version = $windowsVersion; signing = 'unsigned local candidate' }
        published = $false
        checksumFile = 'SHA256SUMS.txt'
        artifacts = @($artifacts)
        releaseNotes = 'Do not publish before signing/release review and the required real-device checks.'
    }
    $manifestJson = ($manifest | ConvertTo-Json -Depth 6) + [Environment]::NewLine
    [System.IO.File]::WriteAllText((Join-Path $candidateRoot 'release-manifest.json'), $manifestJson, $utf8NoBom)
    Write-Host "Local candidate ready: $candidateRoot"
    Write-Host 'This script did not publish the files or run the installer.'
} finally {
    if ($originalJavaHome) { $env:JAVA_HOME = $originalJavaHome }
    else { Remove-Item Env:JAVA_HOME -ErrorAction SilentlyContinue }
    if ($originalAndroidHome) { $env:ANDROID_HOME = $originalAndroidHome }
    else { Remove-Item Env:ANDROID_HOME -ErrorAction SilentlyContinue }
    if ($originalAndroidSdkRoot) { $env:ANDROID_SDK_ROOT = $originalAndroidSdkRoot }
    else { Remove-Item Env:ANDROID_SDK_ROOT -ErrorAction SilentlyContinue }
    if ($originalPubHostedUrl) { $env:PUB_HOSTED_URL = $originalPubHostedUrl }
    else { Remove-Item Env:PUB_HOSTED_URL -ErrorAction SilentlyContinue }
}
