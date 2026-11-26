# App update feed

The shared app catalogue at `https://app.agseyl.ir` serves `/updates.json`. Ember checks it when the app starts and every six hours while it remains open. A notice appears only when the feed lists a higher version for that platform. The site version is the release authority; checks run quietly when the feed is unreachable or invalid.

The feed is static and contains no device identifiers. It does not push notifications to a closed app and does not download or install updates. Windows users open the Ember catalogue page from the tray menu; Android users open it from the in-app update notice.

## Release steps

1. Update the platform version in [`../release/updates.json`](../release/updates.json) only after that build and its download link are live on the catalogue.
2. Copy the file to the website root as `updates.json` alongside the catalogue `index.html`.
3. Keep the top-level `schemaVersion` at `1` unless clients are changed to understand a new schema.

The published catalogue versions are Windows `2.0.4` and Android `2.7.6`. Android phones and Android TV use the same arm64 APK. Add future apps under `apps` using the app's stable key and the platform key its client checks.

## Format

```json
{
  "schemaVersion": 1,
  "apps": {
    "ember": {
      "windows": { "version": "2.0.4" },
      "android": { "version": "2.7.6" }
    }
  }
}
```
