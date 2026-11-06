# Makes a self-signed code-signing certificate for your own computers. A self-signed certificate is NOT trusted by
# Windows or SmartScreen by itself: it only removes the warning on computers where you trust it (below). For other
# people's computers you need a certificate from a certificate authority.
#
#   .\New-SigningCertificate.ps1 -OutDir D:\keys\ember          (asks for a password for the .pfx)
#
# It writes ember-signing.pfx (private: keep it out of the repository) and ember-signing.cer (public).
# To trust it on a computer, run once as administrator:
#   Import-Certificate -FilePath ember-signing.cer -CertStoreLocation Cert:\LocalMachine\Root
#   Import-Certificate -FilePath ember-signing.cer -CertStoreLocation Cert:\LocalMachine\TrustedPublisher
param(
    [Parameter(Mandatory = $true)][string]$OutDir,
    [string]$Subject = 'CN=Ember, O=Ember',
    [int]$Years = 3
)
$ErrorActionPreference = 'Stop'
New-Item -ItemType Directory -Force -Path $OutDir | Out-Null
$password = $env:EMBER_PFX_PASSWORD
if (-not $password) {
    $secure = Read-Host -AsSecureString 'Password for the .pfx file'
    $password = [System.Net.NetworkCredential]::new('', $secure).Password
}

# Built in memory and written to files: nothing is added to the Windows certificate store.
$rsa = [System.Security.Cryptography.RSA]::Create(3072)
$request = [System.Security.Cryptography.X509Certificates.CertificateRequest]::new(
    $Subject, $rsa, [System.Security.Cryptography.HashAlgorithmName]::SHA256, [System.Security.Cryptography.RSASignaturePadding]::Pkcs1)
$request.CertificateExtensions.Add([System.Security.Cryptography.X509Certificates.X509BasicConstraintsExtension]::new($false, $false, 0, $true))
$request.CertificateExtensions.Add([System.Security.Cryptography.X509Certificates.X509KeyUsageExtension]::new(
    [System.Security.Cryptography.X509Certificates.X509KeyUsageFlags]::DigitalSignature, $true))
$usages = [System.Security.Cryptography.OidCollection]::new()
[void]$usages.Add([System.Security.Cryptography.Oid]::new('1.3.6.1.5.5.7.3.3'))
$request.CertificateExtensions.Add([System.Security.Cryptography.X509Certificates.X509EnhancedKeyUsageExtension]::new($usages, $true))
$cert = $request.CreateSelfSigned([DateTimeOffset]::UtcNow.AddMinutes(-5), [DateTimeOffset]::UtcNow.AddYears($Years))

$pfx = Join-Path $OutDir 'ember-signing.pfx'
$cer = Join-Path $OutDir 'ember-signing.cer'
[System.IO.File]::WriteAllBytes($pfx, $cert.Export([System.Security.Cryptography.X509Certificates.X509ContentType]::Pfx, $password))
[System.IO.File]::WriteAllBytes($cer, $cert.Export([System.Security.Cryptography.X509Certificates.X509ContentType]::Cert))
Write-Host "Wrote $pfx (keep it private) and $cer."
Write-Host "Thumbprint: $($cert.Thumbprint)"
