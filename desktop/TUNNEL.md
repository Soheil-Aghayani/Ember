# Ember desktop: tunnel design

Status: design only. Nothing here is implemented yet. The desktop UI in `src/` is a shell, and its Connect button deliberately does nothing.

## How the Android app works today

```
Firefox account sign-in (FxaAuthRepository)
  -> proxy pass from Mozilla's Guardian service (GuardianClient)
  -> server list (ServerListClient)
  -> one HTTP/2 session to the chosen edge, bearer-token auth (H2UpstreamSession)
  -> LocalSocks5Server / LocalHttpProxyServer on 127.0.0.1, each CONNECT becomes an HTTP/2 stream
  -> whole-device capture: Android VpnService TUN + hev-socks5-tunnel feeding the local SOCKS5 server
```

The first four stages are plain JVM code. A grep of `android/src/main/java/com/soheil/ember/` shows that only a handful of files touch Android APIs. The Android-specific parts are the TUN capture (`vpn/tun`, `EmberVpnService`), encrypted storage (`TokenStore`, `SettingsStore`), `android.util.Log`/`Base64`, Conscrypt for ALPN, and `VpnService.protect()`.

So the desktop app does not need a second implementation of the protocol. It needs the same engine, with the Android edges swapped out.

## Plan

### Phase 0: extract a shared `core` module (prerequisite)
Create a JVM-only Gradle module `core/` that both `android/` and `desktop/` depend on. Move into it, unchanged where possible:
`data/model`, `ControlPlaneHttp`, `GuardianClient`, `ServerListClient`, `FxaAuthRepository`, `FastlyChallengeSolver`, `ByteFormat`, `vpn/upstream/*`, `vpn/socks/*`, `RelayDispatchers`, `PingUtil`, `ExitCheck`.

Replace the Android touchpoints with small interfaces implemented per platform:

| Android API today | In `core` | Android impl | Desktop impl |
|---|---|---|---|
| `android.util.Log` | `Logger` | `Log` | stdout + file |
| `EncryptedSharedPreferences` | `SecureStore` | as today | OS credential store (Windows Credential Manager, macOS Keychain, libsecret) |
| `android.util.Base64` | `java.util.Base64` | n/a | n/a |
| Conscrypt (ALPN) | TLS provider chosen at startup | Conscrypt | JDK 17 built-in ALPN |
| `VpnService.protect(socket)` | `SocketProtector` (nullable) | `protect` | none in phase 1 (see below) |
| `Build.VERSION`, `BuildConfig` | injected constants | as today | as today |

Risk: moving ~15 files can break the working Android app. Do it as its own commit and confirm the Android CI build and a manual connect still work before touching the desktop.

### Phase 1: proxy mode with system proxy (no admin rights)
- Desktop app runs `core`, starts `LocalSocks5Server` and `LocalHttpProxyServer` on 127.0.0.1.
- "Connect" also sets the OS proxy to the local HTTP proxy, and restores the previous value on Disconnect, on app exit and on crash recovery at next start.
  - Windows: `HKCU\Software\Microsoft\Windows\CurrentVersion\Internet Settings` (`ProxyEnable`, `ProxyServer`), then notify WinINet.
  - macOS: `networksetup -setwebproxy` / `-setsecurewebproxy` for the active service.
  - Linux: GNOME `gsettings org.gnome.system.proxy`, with a "proxy-only" fallback elsewhere.
- No exit-loop problem: the upstream connection goes straight to the Mozilla edge and does not use the OS proxy (set `ProxySelector.NO_PROXY` for the upstream socket).
- This is honest about its limits. It protects apps that honour the system proxy (browsers, most tools). It does **not** capture apps that ignore it. The UI must say "Browser and proxy-aware apps", not "Your whole device".

### Phase 2: whole-device capture (admin rights)
- Windows: Wintun adapter plus `hev-socks5-tunnel` (it supports Windows) feeding the local SOCKS5 server. This is the same architecture as Android.
- Routing: add the Mozilla edge IP as a direct route via the physical gateway first (that is the desktop version of `protect()`), then a default route via the TUN adapter. Restore routes on disconnect and on next start after a crash.
- Needs elevation. Use a small separate privileged helper (Windows service or UAC-elevated process) that only does adapter and route changes, so the UI itself never runs as admin.
- Kill switch is a separate, later decision (firewall rules block traffic when the tunnel drops). Not part of this phase.
- macOS and Linux follow after Windows works (utun / `/dev/net/tun`, `pfctl` / `ip route`).

### Phase 3: parity and polish
Split tunnelling (per-app is hard on desktop; per-route is feasible), DoH settings, logs page, tray icon, autostart, signed installers (MSI, DMG, DEB; the Gradle config already declares these targets).

## Order of work
1. Phase 0 (extraction), verified on Android.
2. Desktop sign-in plus live server list on top of `core` (no tunnel yet).
3. Phase 1 on Windows, then macOS and Linux.
4. Phase 2 on Windows.

## Open questions for the owner
- Is Phase 1 (proxy only) acceptable as the first desktop release? It is much safer to build and needs no admin rights.
- Windows first, or all three desktop OSes together?
- Is a kill switch wanted in the first whole-device release?
