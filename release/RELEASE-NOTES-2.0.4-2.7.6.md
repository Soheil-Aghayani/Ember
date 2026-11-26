# Ember Windows 2.0.4 and Android phone/TV 2.7.6

## Engine updates

- Xray-core is updated to the official 26.11.9 release on Windows and Android.
- Windows per-app routing uses sing-box 1.14.4, the current upstream release.

## Windows

- The native Windows package now includes Xray-core 26.11.9.
- The portable ZIP keeps Ember.exe and its runtime resources together.
- Whole-device networking remains experimental. The Windows executable is not signed by a publicly trusted code-signing authority.

## Android phone and Android TV

- The same signed arm64 APK serves Android phones and Android TV on Android 8.0 or newer.
- KitKat now has its Android theme, custom vector navigation icons, and clearer Settings text.
- Server actions are grouped for copying or sharing a server or cluster by link or QR, and removing a server from the device.
- Removed subscription servers stay hidden after the subscription refreshes.

## فارسی

- هستهٔ Xray در Windows و Android به انتشار رسمی 26.11.9 به‌روز شد.
- مسیریابی برنامه‌ها در Windows همچنان از sing-box نسخهٔ 1.14.4 استفاده می‌کند.
- Android تم کیت‌کت، آیکون‌های برداری اختصاصی و نوشته‌های خواناتری در تنظیمات دارد.
- لینک یا QR یک سرور یا کل خوشه را کپی و اشتراک‌گذاری کنید و سرورهای تکی را از دستگاه حذف کنید. سرورهای حذف‌شده پس از تازه‌سازی اشتراک هم پنهان می‌مانند.
- یک APK امضاشدهٔ ARM64 برای گوشی و Android TV ارائه می‌شود.

## Checks

- فایل‌های رسمی Xray با SHA-256 اعلام‌شده در GitHub تطبیق داده شدند.
- ساخت APK امضاشده و بررسی امضای آن انجام شد.
- عملکرد VPN روی گوشی فیزیکی و Sony BRAVIA BF1 هنوز روی خود دستگاه تأیید نشده است.
- نصب‌کنندهٔ Windows اجرا نشد؛ حالت تونل کامل Windows همچنان آزمایشی است.
