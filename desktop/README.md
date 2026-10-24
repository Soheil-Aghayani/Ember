# Ember for Windows

## Install
Download `Ember-<version>.exe` from the draft release `desktop-test` on the Releases page and run it. Windows may show "Windows protected your PC" because the installer is not code-signed yet (see "Signing" below): choose **More info**, then **Run anyway**.

## Modes
- **Proxy mode (default):** Ember runs a local SOCKS5 proxy (`127.0.0.1:10808`) and HTTP proxy (`127.0.0.1:10809`). Only apps you point at it are protected. No administrator rights needed.
- **Whole-device mode (Settings):** all apps go through Ember using a Wintun adapter and the bundled tun2socks engine. Needs administrator access (see below). IPv4 only; public IPv6 is blocked while connected.
- **Kill switch (whole-device mode, on by default):** a Windows Firewall rule blocks public IPv4 on your normal adapters except Mozilla's server and local networks, so if the tunnel drops nothing leaks.

## Administrator access
Whole-device mode changes routes and firewall rules, so Windows asks for administrator permission when you connect. Optional **Connect without Windows prompts** (Settings) registers two scheduled tasks once (one prompt) that run `ember-tunnel.ps1` from the installation folder with the highest privileges. Trade-off: programs already running as you can then start Ember's helper, which only does what the validated script allows. Turn the option off to remove the tasks.

## Network changes and sleep
After sleep, or when the network changes (Wi-Fi switch, cable), Ember redials Mozilla's server and the helper re-points its route and the kill switch.

## Troubleshooting
- Logs: `%APPDATA%\Ember\ember.log` (also the Logs page) and `%APPDATA%\Ember\tunnel-state\helper.log`.
- Internet blocked after a crash: open Ember and press **Restore internet**. Without Ember, run as administrator:
  `powershell -ExecutionPolicy Bypass -File "C:\Program Files\Ember\app\resources\ember-tunnel.ps1" -Mode Restore -ConfigPath "%APPDATA%\Ember\tunnel-state\config.json"`
  Restarting Windows also clears the routes and firewall rules (they are not persistent).
- Uninstalling does not run cleanup. If you uninstall while connected, press **Restore internet** first or restart Windows afterwards.

## Signing (not done yet)
Unsigned installers trigger SmartScreen. Options: a standard code-signing certificate, or the free [SignPath Foundation](https://signpath.org/) programme for open-source projects (needs an application and a public repository). Once a certificate or SignPath project exists, add a signing step to the `desktop-windows` job in `.github/workflows/android-ci.yml`.

## Build
Only CI builds this app (`gradle -p desktop packageExe`). Installer versions are `1.0.<CI run number>`. The tun2socks engine (hev-socks5-tunnel 2.19.1) and Wintun 0.14.1 are downloaded at build time and verified against pinned SHA-256 checksums.
