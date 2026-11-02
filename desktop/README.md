# Ember for Windows

## Install
Run `Ember-<version>.exe`. Windows may show "Windows protected your PC" because the installer is not code-signed yet (see "Signing" below): choose **More info**, then **Run anyway**.

## Use
1. Open **Servers** and press **Add link**. Paste the subscription link from your provider.
2. Press **Test speed**. Ember measures the delay of every server, then the download speed of the quickest ones, and sorts the list. Testing works while you are not connected.
3. Click a server, then press **Connect** on Home.

The link is saved in `%APPDATA%\Ember\subscriptions.json`. It holds your account's secret, so do not share that file.

## Modes
- **Whole-device mode (default):** all apps go through Ember using a Wintun adapter and the bundled tun2socks engine. Needs administrator access (see below). IPv4 only; public IPv6 is blocked while connected.
- **Proxy mode (Settings):** Ember runs a local SOCKS5 proxy (`127.0.0.1:10808`) and HTTP proxy (`127.0.0.1:10809`). Only apps you point at it are protected. No administrator rights needed.
- **Kill switch (whole-device mode, on by default):** a Windows Firewall rule blocks public IPv4 on your normal adapters except the server and local networks, so if the tunnel drops nothing leaks.

## Administrator access
Whole-device mode changes routes and firewall rules, so Windows asks for administrator permission when you connect. Optional **Connect without Windows prompts** (Settings) registers two scheduled tasks once (one prompt) that run the helper from the installation folder with the highest privileges. Trade-off: programs already running as you can then start Ember's helper, which only does what its validated code allows. Turn the option off to remove the tasks.

## Network changes and sleep
After sleep, or when the network changes (Wi-Fi switch, cable), Xray reconnects by itself and the helper re-points its route to the server and the kill switch.

## Troubleshooting
- Logs: `%APPDATA%\Ember\ember.log` (also the Logs page) and `%APPDATA%\Ember\tunnel-state\helper.log`. The log never contains your subscription link.
- Internet blocked after a crash: open Ember and press **Restore internet**. Restarting Windows also clears routes, and a leftover firewall rule is removed the next time you connect.
- Uninstalling runs the cleanup, but if you uninstall while connected, press **Restore internet** first.

## Signing (not done yet)
Unsigned installers trigger SmartScreen. Fixing that needs a code-signing certificate; see `SIGNING.md`.

## Build
Built locally (see `AGENTS.md`): `gradle -p desktop createDistributable`, then Inno Setup for the installer. The tun2socks engine (hev-socks5-tunnel 2.19.1), Wintun 0.14.1 and Xray-core are downloaded at build time and verified against pinned SHA-256 checksums.
