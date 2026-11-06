# Signs files (exe, dll, msi) with an Authenticode certificate. Used by release.ps1; can be run on its own.
#
#   .\Sign-Files.ps1 -Files Ember-1.0.88.exe -PfxPath C:\keys\ember.pfx
#   .\Sign-Files.ps1 -Files Ember.exe -CertThumbprint 0123ABCD...        (certificate in the Windows store)
#
# The certificate password is read from the EMBER_PFX_PASSWORD environment variable, or asked for. It is never written
# anywhere, and the .pfx file must stay outside the repository (the .gitignore excludes *.pfx).
param(
    [Parameter(Mandatory = $true)][string[]]$Files,
    [string]$PfxPath,
    [string]$CertThumbprint,
    # A timestamp keeps the signature valid after the certificate expires. Pass '' to sign without one (offline).
    [string]$TimestampServer = 'http://timestamp.digicert.com'
)
$ErrorActionPreference = 'Stop'

function Get-SigningCertificate {
    param([string]$PfxPath, [string]$CertThumbprint)
    if ($PfxPath) {
        if (-not (Test-Path $PfxPath)) { throw "The certificate file was not found: $PfxPath" }
        $password = $env:EMBER_PFX_PASSWORD
        if (-not $password) {
            $secure = Read-Host -AsSecureString 'Certificate password'
            $password = [System.Net.NetworkCredential]::new('', $secure).Password
        }
        # Loaded into memory only: nothing is added to the Windows certificate store.
        return [System.Security.Cryptography.X509Certificates.X509Certificate2]::new(
            (Resolve-Path $PfxPath).Path, $password, [System.Security.Cryptography.X509Certificates.X509KeyStorageFlags]::EphemeralKeySet)
    }
    if ($CertThumbprint) {
        $found = Get-ChildItem Cert:\CurrentUser\My, Cert:\LocalMachine\My -ErrorAction SilentlyContinue |
            Where-Object { $_.Thumbprint -eq $CertThumbprint -and $_.HasPrivateKey } | Select-Object -First 1
        if (-not $found) { throw "No certificate with that thumbprint (and a private key) is in the Windows store." }
        return $found
    }
    throw 'Give -PfxPath or -CertThumbprint.'
}

$cert = Get-SigningCertificate -PfxPath $PfxPath -CertThumbprint $CertThumbprint
if ($cert.NotAfter -lt (Get-Date)) { throw "The certificate expired on $($cert.NotAfter)." }
if (-not ($cert.EnhancedKeyUsageList | Where-Object { $_.ObjectId -eq '1.3.6.1.5.5.7.3.3' })) {
    throw 'That certificate is not meant for code signing.'
}
foreach ($file in $Files) {
    $args = @{ FilePath = $file; Certificate = $cert; HashAlgorithm = 'SHA256' }
    if ($TimestampServer) { $args.TimestampServer = $TimestampServer }
    $result = Set-AuthenticodeSignature @args
    # A certificate that Windows does not trust yet (a self-signed one) reports UnknownError; the signature is still there.
    $signature = Get-AuthenticodeSignature -FilePath $file
    if (-not $signature.SignerCertificate -or $signature.SignerCertificate.Thumbprint -ne $cert.Thumbprint) {
        throw "Signing failed for ${file}: $($result.StatusMessage)"
    }
    Write-Host ("signed {0} ({1})" -f (Split-Path $file -Leaf), $signature.Status)
}
