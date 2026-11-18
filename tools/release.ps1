# Builds and signs a Windows direct-download installer, then puts it in your Programs folder.
#
#   .\tools\release.ps1 -Version 1.0.89
#   .\tools\release.ps1 -Version 1.0.89 -PfxPath D:\keys\ember\ember-signing.pfx
#
# Needs the tools named in AGENTS.md (Gradle, JDK 21, Inno Setup, cargo). Runs locally only; nothing is published.
param(
    [Parameter(Mandatory = $true)][string]$Version,
    [string]$PfxPath,
    [string]$CertThumbprint,
    [string]$TimestampServer = 'http://timestamp.digicert.com',
    [string]$OutDir = (Join-Path $env:USERPROFILE 'Downloads\Programs')
)
$ErrorActionPreference = 'Stop'
if ($Version -notmatch '^\d+\.\d+\.\d+$') { throw 'The version looks like 1.0.89.' }

# Build tools write progress to stderr, which Windows PowerShell 5.1 would treat as an error: judge them by exit code.
function Invoke-Tool {
    param([string]$Name, [string]$Exe, [string[]]$Arguments)
    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    & $Exe @Arguments
    $code = $LASTEXITCODE
    $ErrorActionPreference = $previous
    if ($code -ne 0) { throw "$Name failed (exit code $code)." }
}
$root = Split-Path $PSScriptRoot -Parent
$gradle = 'D:\Programming\tools\gradle\gradle-8.10.2\bin\gradle.bat'
$iscc = 'D:\Programming\tools\InnoSetup\ISCC.exe'
if (-not $env:JAVA_HOME) { $env:JAVA_HOME = 'C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot' }
$env:Path += ";$env:USERPROFILE\.cargo\bin"
$sign = [bool]($PfxPath -or $CertThumbprint)
if (-not $sign) { throw 'A direct-download release needs an Ember code-signing certificate. Build unsigned locally for development.' }
$signArgs = @{
    PfxPath = $PfxPath
    CertThumbprint = $CertThumbprint
    TimestampServer = $TimestampServer
    ExpectedPublisher = 'Ember'
    RequireTrustedCertificate = $true
}

Write-Host "== Building the app folder ($Version)"
$env:EMBER_VERSION = $Version
Invoke-Tool 'The build or its tests' $gradle @('-p', "$root\desktop", 'test', 'createDistributable', '--console=plain', '-q')
$app = "$root\desktop\build\compose\binaries\main\app\Ember"

if ($sign) {
    Write-Host '== Signing the programs Ember starts itself'
    & "$PSScriptRoot\Sign-Files.ps1" -Files "$app\Ember.exe", "$app\app\resources\ember-helper.exe" @signArgs
}

Write-Host '== Making the installer'
$out = "$root\desktop\build\installer"
New-Item -ItemType Directory -Force -Path $out | Out-Null
Invoke-Tool 'Inno Setup' $iscc @('/Q', "/DVersion=$Version", "/DSourceDir=$app", "/DOutputDir=$out", "$root\desktop\packaging\ember.iss")
$installer = "$out\Ember-$Version.exe"

if ($sign) {
    Write-Host '== Signing the installer'
    & "$PSScriptRoot\Sign-Files.ps1" -Files $installer @signArgs
}

New-Item -ItemType Directory -Force -Path $OutDir | Out-Null
$final = Join-Path $OutDir "Ember-$Version-servers.exe"
Copy-Item $installer $final -Force
Get-ChildItem $OutDir -Filter 'Ember-*-servers.exe' | Where-Object { $_.FullName -ne $final } | Remove-Item -Force
Write-Host "== Done: $final"
Get-FileHash $final -Algorithm SHA256 | ForEach-Object { "SHA-256 $($_.Hash)" }
