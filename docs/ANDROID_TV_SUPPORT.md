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
- Runtime selection: `PackageManager.FEATURE_LEANBACK` selects TV-specific home and sign-in behavior.
- Launcher: the Android manifest keeps the phone `LAUNCHER` entry and adds `LEANBACK_LAUNCHER`.
- TV listing assets: 320 × 180 xhdpi banner at `android/src/main/res/drawable-xhdpi/tv_banner.png`; camera features remain optional because the TV flow does not scan QR codes.
- VPN and account behavior: shared Android service and `core/`; no duplicated TV networking engine.

## Remote-first behavior

- Home uses a wide two-column layout, large actions, Solar icons beside key action labels, and a visible focus ring on its actions. Location rows and TV back actions also show focus.
- Location selection, Settings, and Logs remain in the shared navigation graph. Settings and Logs still need remote traversal checks on the physical TV.
- Sign-in opens the one-time code form by default on TV. It accepts the code and passphrase from another signed-in Ember device, hides camera scanning, and keeps browser sign-in as an alternate route.
- Phone behavior remains touch-first and retains QR scanning.

## Status and acceptance checks

Implementation is in the Android source. The Android debug APK assembled successfully on 2026-10-29; package inspection confirmed the phone and Leanback launcher entries, application banner, and optional touchscreen/camera/Leanback features. This confirms packaging and compilation only. Do not describe Sony TV support as runtime-verified until the physical BRAVIA BF1 has been checked.

- [x] Assemble the debug APK and inspect its launcher metadata.
- [ ] Confirm Ember appears in the TV launcher with its banner and opens the TV layout.
- [ ] Navigate Home, Locations, Settings, Logs, and Back using only D-pad and Select; confirm focus remains visible.
- [ ] Enter a generated test transfer code using the TV input method. Do not use a real account credential during automated checks.
- [ ] Have the owner validate real account sign-in, Android VPN permission, connect/disconnect, and traffic behavior on the TV.
