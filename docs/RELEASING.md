# Releasing Ember (local only)

`tools/release.ps1` does everything from the source folder to the installer in `Downloads\Programs`:

```powershell
.\tools\release.ps1 -Version 1.0.89                                   # unsigned
.\tools\release.ps1 -Version 1.0.89 -PfxPath D:\keys\ember\ember-signing.pfx   # signed
```

It runs the tests, builds the app folder, signs `Ember.exe` and `ember-helper.exe` (when a certificate is given), makes
the installer with Inno Setup, signs it, copies it to `Ember-<version>-servers.exe` (removing the older ones) and prints
its SHA-256. Nothing is published. Add the version's entry to `ui/WhatsNewPage.kt` first.

## Signing, and what it can and cannot do

Windows shows "Unknown publisher" and SmartScreen warns for programs without a signature from a certificate authority.

| Certificate | Cost | Effect |
|---|---|---|
| None | free | Warning on every computer. |
| Self-signed (`tools/New-SigningCertificate.ps1`) | free | Removes the warning only on computers where you trust the certificate (commands in the script's header). Fine for your own PCs; useless for other people's. |
| Code-signing certificate from a certificate authority (organisation or individual validation) | paid, yearly; identity checks | Shows your name as publisher. SmartScreen still builds trust over time. |
| Extended-validation certificate or a signing service | higher | SmartScreen trusts it at once. |

`tools/Sign-Files.ps1` works with any of them: a `.pfx` file (password from `EMBER_PFX_PASSWORD` or asked) or a certificate
in the Windows store (`-CertThumbprint`). Keep the `.pfx` outside the repository; `*.pfx` is ignored by git. Signatures are
timestamped (`-TimestampServer`, default DigiCert's) so they stay valid after the certificate expires.
