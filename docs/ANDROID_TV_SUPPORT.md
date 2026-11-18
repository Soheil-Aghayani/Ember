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
- Runtime selection: `MainActivity` checks `PackageManager.FEATURE_LEANBACK` and selects TV navigation and the wide home layout.
- Launcher: the Android manifest keeps the phone `LAUNCHER` entry and adds `LEANBACK_LAUNCHER`.
- TV listing assets: 320 × 180 xhdpi banner at `android/src/main/res/drawable-xhdpi/tv_banner.png`; camera features remain optional because the TV flow does not scan QR codes.
- VPN and account behavior: shared Android service and `core/`; no duplicated TV networking engine.

## Remote-first behavior

- Home uses a wide two-column layout with a large connect orb, selected-server card, and a clear focus outline on remote-operable controls.
- TV navigation is a left rail for Home, Servers, Settings, and About. Server selection, Settings controls, and About actions expose D-pad focus; the Logs dialog is under About.
- The TV server menu hides camera scanning. Users can type or paste subscription links, open a server file, and share a selected server's QR code with a phone.
- Phone navigation and camera QR scanning remain touch-first and unchanged.

## Status and acceptance checks

TV navigation and home are implemented in the Android source. On 2026-11-19, both the normal debug APK and a separate debug-only TV preview APK assembled successfully. The TV preview uses the `.tvpreview` application ID suffix and forces the TV layout on a generic 1920 × 1080 Android emulator that does not advertise Leanback. D-pad navigation and Select were checked across Home, Servers, Settings and About, including the Add link and Logs dialogs. This confirms the preview UI path only; it does not replace validation on the physical BRAVIA BF1.

- [x] Assemble the normal debug APK and debug-only TV preview APK.
- [x] Check D-pad focus and Select navigation across Home, Servers, Settings and About on the generic emulator.
- [x] Open and dismiss the Add link and Logs dialogs with the remote; no subscription was added and no VPN was connected.
- [ ] Confirm Ember appears in the TV launcher with its banner and opens the TV layout.
- [ ] Repeat the remote navigation checks on the Sony BRAVIA BF1 Android TV OS 12.
- [ ] Have the owner validate real account sign-in, Android VPN permission, connect/disconnect, and traffic behavior on the TV.
