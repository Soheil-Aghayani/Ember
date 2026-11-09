# Windows features in the Rust app: what is wired, what is tested, what the owner must try

Branch `agent/rust-port`. Nothing below was run against this computer's network: no helper, no `netsh`, no `route`, no
firewall change, no scheduled task, no OpenVPN. The engine's connect sequence is tested with a recording stand-in
(`ember-engine/src/host.rs`, `fake`), so the *order* and the *arguments* are checked; whether Windows does what they ask
is for the owner to check.

## Wired

| Feature | Where | Notes |
|---|---|---|
| Whole-device tunnel (Xray + `ember-helper.exe` + hev-socks5-tunnel) | `engine.rs` `start_routing`, `host.rs` | Kill switch, IPv6, MTU, TCP buffer and the skip list are passed to the helper as in Kotlin. The hev YAML is written to `<data>\hev-socks5-tunnel.yaml` (`ember-win` `hev_config`, same text as `HevSocks5TunnelConfig.write`). |
| Kill switch | same | Also for OpenVPN (`tunnel_guard`, server addresses pinned into the profile). |
| App rules (sing-box) | same | Mode off / skip / only and the program list; needs whole-device mode like Kotlin. |
| OpenVPN | `engine.rs` `connect_openvpn` | Credential dialog on Home (typed values live in memory only, forgotten after "auth failed"). |
| "Connect without Windows prompts" | `host.rs` `setup_*` | One-time scheduled tasks. |
| Skip the VPN, Trusted networks, Apps pages | `settings.slint` | Skip list takes a pasted link and keeps the host name. |
| Connect on untrusted networks | `engine.rs` `start_network_watch` | Also re-points the helper's route after sleep or a network change. |
| Resume after a crash / "Restore internet" | `engine.rs` `check_interrupted`, `restore_internet` | Notice card on Home. |
| Tray, keep in tray, start with Windows, one instance | `main.rs`, `ember-win` | Tray menu clicks and the icon were not looked at (see below). |

## Scheduled tasks use their own folder

The Rust build registers `\EmberRust\Tunnel Up`, `\EmberRust\Tunnel Restore`, `\EmberRust\Apps` and `\EmberRust\OpenVPN`.
The Kotlin app uses `\Ember\...`. Sharing the names would make each app re-register the other's tasks (each task
points at its own data folder). The two apps still cannot run together: one single-instance mutex and the same local
ports.

## Where the helper comes from

`ember-ui.exe` looks for `ember-helper.exe`, `ember-tunnel.ps1` and `tunnel\` (hev-socks5-tunnel, Wintun, sing-box)
next to itself, or in the folder named by `EMBER_RESOURCES_DIR`. A bare `cargo build` has none of them, so the
Whole-device switch is dimmed with "Not available in this build". To try it before there is an installer:
set `EMBER_RESOURCES_DIR` to the resources folder of an installed Kotlin Ember.

## Not done

- Reading a QR code out of a picture (file import takes JSON, WireGuard, OpenVPN and text files), and the "WARP through a
  server of mine" chain (the Servers page adds plain WARP only).
- Signing the installer, and building it: `installer\stage.ps1` collects the files and `installer\ember-rs.iss` is the
  Inno Setup script, but Inno Setup is not installed on this computer, so no installer has been built yet.

## Needs the owner to test

1. Settings > Connection > Protection: with `EMBER_RESOURCES_DIR` set, turn Whole-device on, Connect. One Windows prompt;
   Home says "Whole device"; a browser shows the server's address; Disconnect restores the network.
2. Kill switch on: end `ember-ui.exe` in Task Manager while connected. Internet stays blocked; start Ember again: it
   reconnects by itself, or "Restore internet" on Home lifts the block.
3. "Connect without Windows prompts": one prompt, then connecting asks nothing. Turning it off asks once.
4. Apps: add `firefox.exe`, mode "VPN only for the apps below", Connect (whole-device on): only Firefox uses the VPN.
5. Skip the VPN: add a site you can reach directly, Connect: that site shows your real address.
6. An OpenVPN file added to Servers: Connect asks for the user name and password; a wrong password asks again.
7. Trusted networks: "Connect automatically on networks I don't trust" on, join another Wi-Fi: Ember connects once.
8. Tray: the icon appears, left click opens, the menu has Open / Connect / Quit, the label flips to Disconnect when
   connected; closing the window keeps Ember running; "Start with Windows" adds and removes the Run entry.
9. Check both apps' scheduled tasks in Task Scheduler: `\Ember` and `\EmberRust` stay separate.
