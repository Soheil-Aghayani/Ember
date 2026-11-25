# Ember Windows 2.0.3 and Android 2.7.4

## Windows 2.0.3

- Settings backups use a password-protected encrypted format that can be restored on Android; existing ZIP backups remain importable.
- Portable preview ZIP; it does not replace the earlier Kotlin Ember app. Whole-device networking remains experimental.

## Android phone and Android TV 2.7.4

- Mint is the default appearance, with Mint, System and Dark choices saved on the device.
- Page changes use a short fade and gentle slide; each page keeps its state for quicker navigation.
- Server selection considers repeated-ping reliability, median delay, jitter and success rate. Large speed tests can be skipped on a metered connection or low battery.
- Save server and app-routing profiles; preview subscription changes before applying them.
- Create and restore password-protected backups shared with Windows.
- Run local diagnostics for Internet, DNS, server reachability and IP outside or through Ember. The IP check requests Cloudflare's trace endpoint; the report stays on the device unless you choose to share it.
- See session usage in the Android home-screen widget and ongoing notification; widget usage refreshes every 30 seconds.
- Open Android VPN settings to enable its Always-on reconnect option.
- Android TV shows recent and favorite servers for D-pad selection.
- Check for app updates at launch and every six hours while Ember stays open.

The same signed arm64 APK supports Android phones and Android TV (Android 8.0 or newer). Phone UI launch and navigation, Mint appearance and TV preview/D-pad navigation were checked; no VPN tunnel was started. The TV-named API 34 AVD lacks `FEATURE_LEANBACK`, so release-time TV auto-detection was not verified. Physical phone behavior and Sony BRAVIA BF1 behavior still need owner verification.

## Verification

- Flutter static analysis reported no issues.
- The Android debug TV preview and signed release APK built successfully; Android APK signature verification passed.
- The Windows 2.0.3 portable ZIP is unchanged from the prepared candidate; no Windows code changed in this update.

## Windows quick start

Extract `Ember-Rust-2.0.3.zip` and run `Ember.exe`. The portable build is unsigned by a public code-signing authority, so Windows may show an unknown-publisher warning.
