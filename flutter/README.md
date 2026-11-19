# Ember Android UI

This Flutter add-to-app module provides Ember's phone and Android TV interface. Android remains the platform host for VPN permissions, foreground service, app routing, notifications, the home-screen widget, the quick-settings tile, and native import/share actions.

## Build

Use Flutter 3.50 with Dart 3.14 or newer. From this directory, fetch Dart dependencies:

```powershell
flutter pub get
```

From the repository root, build the arm64 Android debug APK:

```powershell
.\gradlew.bat :android:assembleDebug
```

Build a debug-only TV preview with `.\gradlew.bat :android:assembleDebug -PtvPreview`. The preview forces the TV layout and uses the `.tvpreview` application ID suffix. Both Gradle variants write `android/build/outputs/apk/debug/android-debug.apk`; copy the first one before building the second if both are needed.

The checked local build uses the root wrapper pinned to Gradle 9.5.0, Android Gradle Plugin 9.3.1, Android SDK 36, CMake 3.22.1, host NDK 26.1.10909125, and Flutter's NDK 28.2.13676358.

## Ownership and bridge

- Dart screens, navigation, theme, localization, focus treatment, and visual assets live in `lib/` and `assets/`.
- `EmberApi` in `lib/ember_api.dart` calls the Kotlin host over method and event channels.
- `android/src/main/java/com/soheil/ember/MainActivity.kt` adapts platform actions and exposes state updates. `AndroidEngine`, `XrayVpnService`, and the existing data store remain authoritative.
- Subscription links can be passed from an entry field to the native importer. The host does not return saved secret URLs in Flutter state snapshots.
- The Compose UI is retained as a rollback entry point via `-PcomposeUi`; the default launcher opens Flutter.

## Validation status

`flutter analyze` and both Android debug builds completed successfully on 2026-11-19. The APKs have not been installed or launched on a device. Phone touch/QR behavior, Android TV D-pad behavior, and the Sony BRAVIA BF1 remain to be checked. Debug APKs are not signed release artifacts; the existing signing workflow has not been validated for this Flutter port.
