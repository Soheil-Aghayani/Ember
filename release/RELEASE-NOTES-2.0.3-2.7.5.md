# Ember Windows 2.0.3 and Android 2.7.5

## Android phone and Android TV 2.7.5

- Page changes use one coordinated, restrained fade, scale, and slide transition per page, with a shorter exit and no spring overshoot.
- Navigation remains responsive during rapid page changes, and inactive pages stop drawing and accepting input after they leave.
- Android's reduced-motion accessibility setting disables the transition.
- The Mint background, Orb, server behavior, and VPN engine are unchanged.

The same signed arm64 APK serves Android phones and Android TV on Android 8.0 or newer. This release changes Flutter navigation only.

## Verification

- The signed Android release APK was built with the project Gradle wrapper and its signature was verified.
- The 2.7.5 page transition was not visually confirmed in an emulator because the emulator stopped responding during debug APK installation.
- Physical phone and Sony BRAVIA BF1 behavior remain pending owner verification.
