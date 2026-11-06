# Makes the key that signs Ember's Android release APKs. Keep it (and its password file) safe and outside the repository:
# every update of the app must be signed with this same key, or phones will refuse to install it over the old one.
#
#   .\New-AndroidKeystore.ps1 -OutDir D:\Programming\Ember-releases\signing
#
# Writes ember-android.jks and ember-android.pwd (the password, readable only by this Windows user).
param(
    [Parameter(Mandatory = $true)][string]$OutDir,
    [string]$Alias = 'ember',
    [string]$Subject = 'CN=Ember, O=Ember'
)
$ErrorActionPreference = 'Stop'
if (-not $env:JAVA_HOME) { $env:JAVA_HOME = 'C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot' }
$keytool = Join-Path $env:JAVA_HOME 'bin\keytool.exe'
New-Item -ItemType Directory -Force -Path $OutDir | Out-Null
$store = Join-Path $OutDir 'ember-android.jks'
if (Test-Path $store) { throw "$store already exists; refusing to replace the key that signed earlier releases." }

$bytes = New-Object byte[] 24
[System.Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($bytes)
$password = ([Convert]::ToBase64String($bytes)) -replace '[^A-Za-z0-9]', 'x'
$previous = $ErrorActionPreference
$ErrorActionPreference = 'Continue'
& $keytool -genkeypair -keystore $store -storetype PKCS12 -alias $Alias -keyalg RSA -keysize 4096 -validity 10000 `
    -dname $Subject -storepass $password -keypass $password | Out-Null
$code = $LASTEXITCODE
$ErrorActionPreference = $previous
if ($code -ne 0) { throw 'keytool failed.' }
ConvertTo-SecureString $password -AsPlainText -Force | ConvertFrom-SecureString | Set-Content (Join-Path $OutDir 'ember-android.pwd')
Write-Host "Wrote $store. Back it up together with ember-android.pwd (the password only works for this Windows user, so also keep the password somewhere safe)."
