# Builds the signed Android release APK and copies it to your Programs folder.
#
#   .\tools\build-apk.ps1 -KeystoreDir D:\Programming\Ember-releases\signing
#
# The key comes from tools\New-AndroidKeystore.ps1. Needs the Android SDK named in local.properties. Local only.
param(
    [Parameter(Mandatory = $true)][string]$KeystoreDir,
    [string]$OutDir = (Join-Path $env:USERPROFILE 'Downloads\Programs')
)
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
$gradle = 'D:\Programming\tools\gradle\gradle-8.10.2\bin\gradle.bat'
if (-not $env:JAVA_HOME) { $env:JAVA_HOME = 'C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot' }
$env:Path += ";$env:USERPROFILE\.cargo\bin"

$store = Join-Path $KeystoreDir 'ember-android.jks'
$saved = Join-Path $KeystoreDir 'ember-android.pwd'
if (-not (Test-Path $store) -or -not (Test-Path $saved)) { throw "No Android key in $KeystoreDir; run tools\New-AndroidKeystore.ps1 first." }
$password = [System.Net.NetworkCredential]::new('', (Get-Content $saved | ConvertTo-SecureString)).Password
$env:SIGNING_KEYSTORE = $store
$env:SIGNING_KEY_ALIAS = 'ember'
$env:SIGNING_KEY_PASSWORD = $password
$env:SIGNING_STORE_PASSWORD = $password
try {
    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    & $gradle -p $root :android:assembleRelease --console=plain -q
    $code = $LASTEXITCODE
    $ErrorActionPreference = $previous
    if ($code -ne 0) { throw 'The Android build failed.' }
} finally {
    Remove-Item Env:\SIGNING_KEY_PASSWORD, Env:\SIGNING_STORE_PASSWORD, Env:\SIGNING_KEYSTORE, Env:\SIGNING_KEY_ALIAS -ErrorAction SilentlyContinue
}

$apk = Get-ChildItem "$root\android\build\outputs\apk\release" -Filter '*.apk' | Select-Object -First 1
if (-not $apk) { throw 'No release APK was produced (is the signing key found by the build?).' }
$version = (Select-String -Path "$root\android\build.gradle.kts" -Pattern 'versionName = "([^"]+)"').Matches[0].Groups[1].Value
$apksigner = Get-ChildItem (Join-Path (Split-Path $root -Qualifier) 'Programming\tools\android-sdk\build-tools') -Recurse -Filter apksigner.bat | Sort-Object FullName | Select-Object -Last 1
if ($apksigner) {
    $previous = $ErrorActionPreference; $ErrorActionPreference = 'Continue'
    & $apksigner.FullName verify --print-certs $apk.FullName | Select-Object -First 3
    $ErrorActionPreference = $previous
}
New-Item -ItemType Directory -Force -Path $OutDir | Out-Null
$final = Join-Path $OutDir "Ember-$version.apk"
Copy-Item $apk.FullName $final -Force
Write-Host "== Done: $final"
Get-FileHash $final -Algorithm SHA256 | ForEach-Object { "SHA-256 $($_.Hash)" }
