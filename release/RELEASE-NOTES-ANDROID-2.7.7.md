# Ember Android 2.7.7

## Android phone and Android TV

- Network diagnostics show a spinner and a clear waiting message while checks are running.
- Repeated taps are ignored until the current diagnostic finishes, preventing duplicate report dialogs and parallel checks.
- The diagnostic checks and privacy behavior are unchanged; the report remains on the device unless the user chooses to copy it.
- The signed arm64 APK supports Android 8.0 or newer and is shared by phones and Android TV.

## فارسی

- هنگام اجرای عیب‌یابی شبکه، نشانگر چرخان و پیام انتظار واضح نمایش داده می‌شود.
- تا پایان بررسی، لمس‌های تکراری نادیده گرفته می‌شوند تا گزارش‌های تکراری و بررسی‌های هم‌زمان باز نشوند.
- خود بررسی‌ها و حریم خصوصی آن‌ها تغییر نکرده‌اند؛ گزارش روی دستگاه می‌ماند، مگر اینکه کاربر خودش آن را کپی کند.
- APK امضاشدهٔ ARM64 برای Android 8.0 به بالا است و بین گوشی و Android TV مشترک است.

## Verification

- Signed Android release APK build and signature verification completed.
- The updated progress state was not visually checked on an emulator or physical device.
- Physical phone/TV VPN behavior remains unverified.
