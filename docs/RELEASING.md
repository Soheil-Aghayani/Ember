# Releasing Ember (local build instructions)

## Local multi-target candidate bundle

`tools/prepare-local-release-candidate.ps1` builds a Windows x64 installer from the Rust app and one arm64 Android release APK for both phones and Android TV. It writes a unique local folder containing `release-manifest.json` (source commit, versions, targets and signing state) plus `SHA256SUMS.txt` for the installer and APK.

Pass the Windows runtime resources explicitly so the candidate uses the files you reviewed. Add `-AndroidKeystoreDir` to sign the Android APK with the local keystore; without it, the script creates an unsigned Android candidate. The Windows installer remains unsigned. The Android phone and TV versions share one release APK; `-PtvPreview` is a separate debug-only emulator package.

```powershell
.\tools\prepare-local-release-candidate.ps1 `
  -WindowsResources 'C:\Program Files\Ember\app\resources' `
  -AndroidKeystoreDir 'D:\Programming\Ember-releases\signing'
```

The default output is `D:\Programming\Ember-releases\local-candidates`. The script never deletes an existing candidate folder, publishes files, updates `release/updates.json`, runs the installer, or launches the VPN helper. Review signing and real-device results before distributing a candidate.

## Current native Windows preview

The Rust/Slint build is a separate preview installer that installs side-by-side with the older Kotlin desktop app. It is not the default replacement, and whole-device networking still needs owner testing.

```powershell
Set-Location ember-rs
cargo test --workspace
cargo build --release -p ember-ui
cargo build --release --manifest-path ..\helper\Cargo.toml
powershell -File installer\stage.ps1 -Resources 'C:\Program Files\Ember\app\resources' -Out .\stage
& 'D:\Programming\tools\InnoSetup\ISCC.exe' /DVersion=2.0.4 `
  "/DSourceDir=$PWD\stage" "/DOutputDir=$PWD\Output" installer\ember-rs.iss
```

For direct downloads, sign the app and installer with a CA-trusted code-signing certificate whose publisher name is
`Ember`. Keep the `.pfx` outside the repository; `Sign-Files.ps1` reads its password from the environment, a matching
user-protected `.pwd` file, or an interactive prompt. It checks the publisher and, with `-RequireTrustedCertificate`,
rejects certificates that do not chain to a trusted code-signing authority. Sign our binaries before packaging and the
installer after Inno Setup finishes:

```powershell
$signArgs = @{ PfxPath = 'D:\keys\ember\ember-signing.pfx'; ExpectedPublisher = 'Ember'; RequireTrustedCertificate = $true }
& ..\tools\Sign-Files.ps1 -Files .\stage\Ember.exe, .\stage\resources\ember-helper.exe @signArgs
& 'D:\Programming\tools\InnoSetup\ISCC.exe' /DVersion=2.0.4 `
  "/DSourceDir=$PWD\stage" "/DOutputDir=$PWD\Output" installer\ember-rs.iss
& ..\tools\Sign-Files.ps1 -Files .\Output\Ember-Rust-2.0.4.exe @signArgs
```

Keep the stage and output directories task-specific if an existing `ember-rs/installer/stage` is in use. Do not run the installer or network helper during an agent task.

## Earlier Kotlin desktop installer

`tools/release.ps1` builds a signed direct-download installer and copies it to `Downloads\Programs`:

```powershell
.\tools\release.ps1 -Version 1.0.89 -PfxPath D:\keys\ember\ember-signing.pfx
```

The script requires a trusted Ember code-signing certificate. Build unsigned binaries directly with the platform build
commands for local development.

It runs the tests, builds the app folder, signs `Ember.exe` and `ember-helper.exe`, makes
the installer with Inno Setup, signs it, copies it to `Ember-<version>-servers.exe` (removing the older ones) and prints
its SHA-256. Nothing is published. Add the version's entry to `ui/WhatsNewPage.kt` first.

## Signing, and what it can and cannot do

Windows shows "Unknown publisher" and SmartScreen warns for programs without a signature from a certificate authority.

| Certificate | Cost | Effect |
|---|---|---|
| None | free | No verified publisher identity; SmartScreen reputation starts over for each changed file. |
| Self-signed (`tools/New-SigningCertificate.ps1`) | free | Local development only. Public users who do not trust it see the same SmartScreen warning as for unsigned files. |
| Code-signing certificate from a certificate authority (organisation or individual validation) | paid, yearly; identity checks | Shows the verified publisher name. SmartScreen reputation still builds over time. |

`tools/Sign-Files.ps1` accepts a `.pfx` file (password from `EMBER_PFX_PASSWORD` or asked) or a certificate in the Windows
store (`-CertThumbprint`). It expects the certificate publisher name to be `Ember`; pass `-RequireTrustedCertificate`
for direct downloads to reject self-signed or otherwise untrusted certificates. Keep the `.pfx` outside the repository;
`*.pfx` is ignored by git. Signatures are timestamped (`-TimestampServer`, default DigiCert's) so they stay valid after
the certificate expires.
