# Releasing Ember (local build instructions)

## Current native Windows preview

The Rust/Slint build is a separate preview installer that installs side-by-side with the older Kotlin desktop app. It is not the default replacement, and whole-device networking still needs owner testing.

```powershell
Set-Location ember-rs
cargo test --workspace
cargo build --release -p ember-ui
cargo build --release --manifest-path ..\helper\Cargo.toml
powershell -File installer\stage.ps1 -Resources 'C:\Program Files\Ember\app\resources' -Out .\stage
& 'D:\Programming\tools\InnoSetup\ISCC.exe' /DVersion=2.0.1 `
  "/DSourceDir=$PWD\stage" "/DOutputDir=$PWD\Output" installer\ember-rs.iss
```

Keep the stage and output directories task-specific if an existing `ember-rs/installer/stage` is in use. Do not run the installer or network helper during an agent task.

## Earlier Kotlin desktop installer

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
