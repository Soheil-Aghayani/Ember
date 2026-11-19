# Android TV support profile

## Target device

| Field | Value |
|---|---|
| Brand | Sony |
| Model | BRAVIA BF1 |
| Operating system | Android TV OS 12 |
| Android compatibility | Covered by Ember's Android API 26 minimum; TV runtime behavior still needs device validation |

This profile comes from the owner's TV information image. Unique device identifiers are intentionally not recorded.

## App structure

- Package: `com.soheil.ember` (shared with the Android phone app).
- Runtime selection: the native bridge detects `PackageManager.FEATURE_LEANBACK`; Flutter selects the TV navigation and wide home layout. `-PtvPreview` forces this layout in a debug build.
- Launcher: the Android manifest keeps the phone `LAUNCHER` entry and adds `LEANBACK_LAUNCHER` through `.LauncherActivity`, targeting the Flutter `.MainActivity` by default.
- TV listing assets: 320 × 180 xhdpi banner at `android/src/main/res/drawable-xhdpi/tv_banner.png`; camera features remain optional because the TV flow does not scan QR codes.
- VPN and account behavior: shared Android service and `core/`; no duplicated TV networking engine.

## Remote-first behavior

- Flutter Home uses a wide layout with a connect orb, selected-server card, and focus styles for remote-operable controls.
- Flutter TV navigation uses a left rail; the phone layout uses touch navigation. Servers, Settings, About, and Logs are implemented in the Flutter module.
- The TV server menu hides camera scanning. Users can type or paste subscription links, open a server file, and share a selected server's QR code with a phone.
- The native Kotlin VPN engine and platform services are shared by phone and TV; Flutter owns presentation and navigation.

## Status and acceptance checks

The Flutter-backed normal Android debug APK and a separate debug-only TV preview APK both assembled on 2026-11-19. The preview uses the `.tvpreview` application ID suffix and sets the TV layout flag. No Flutter app was installed or launched on an emulator or physical device during this port. The earlier generic emulator D-pad checks covered the Compose interface and do not establish Flutter behavior. Physical BRAVIA BF1 validation remains pending.

- [x] Assemble the normal debug APK and debug-only TV preview APK.
- [ ] Check D-pad focus and Select navigation across Home, Servers, Settings and About on an Android TV emulator.
- [ ] Open and dismiss the Add link and Logs dialogs with a remote.
- [ ] Check the touch layout and QR camera flow on an Android phone.
- [ ] Confirm Ember appears in the TV launcher with its banner and opens the TV layout.
- [ ] Repeat the remote navigation checks on the Sony BRAVIA BF1 Android TV OS 12.
- [ ] Have the owner validate real account sign-in, Android VPN permission, connect/disconnect, and traffic behavior on the TV.
