# ember-win: notes

Crate `ember-rs/crates/ember-win/` (branch `agent/rust-win`). Deps: `windows` 0.58 (only the features used), `serde_json`, `thiserror`. No async runtime: everything blocks, so the engine calls it from a worker thread. `unsafe` is in `tray.rs`, `single_instance.rs`, `network_identity.rs`, `openvpn.rs` (random bytes) and `windows_tunnel.rs` (ShellExecute), each block with a reason comment.

Checked: `cargo test -p ember-win` (45 tests pass) and `cargo clippy -p ember-win --all-targets` (clean), run from `ember-rs/`. Nothing in this crate was run against the network, registry, scheduled tasks or helper.

## Ported

| Rust module | Kotlin source | What |
|---|---|---|
| `paths` | `DesktopPaths.kt` | `data_dir()` = `%APPDATA%\Ember` (falls back to the profile), `resources_dir()` (`EMBER_RESOURCES_DIR` or the exe's folder), `find_file` |
| `autostart` | `Autostart.kt` | `is_enabled()`, `set_enabled()` via `reg.exe` on `HKCU\...\Run\Ember`, value `"<exe>" --minimized` |
| `network_identity` | `NetworkIdentity.kt` | `current()`, `display_name()`; SSID parsed from `netsh wlan show interfaces`; wired check with `GetAdaptersAddresses` and the same adapter-name filter |
| `privileged_task` | `PrivilegedTask.kt` | `PrivilegedTask`: `is_installed`, `is_current`, `run("Up"/"Restore")`, `install`, `uninstall`; the register script is a pure function |
| `app_routing` | `AppRouting.kt` | `build_rules()` (sing-box JSON, same keys and rules), `AppRuleMode`, `AppRoutingRunner` (task check/install, start, stop, running) |
| `openvpn` | `OpenVpn.kt` | `OpenVpnRunner`: `executable()`, `task_ready`, `install_task`, `session()` (management-port protocol, credentials callback, cancel flag); line parser and `LineReader` are pure |
| `windows_tunnel` | `WindowsTunnel.kt` | `WindowsTunnel`: `start`, `start_guard`, `stop`, `restore`, `status`, `is_blocked`, `is_stale`, `request_refresh`; config JSON builders, status parser, fallback to the PowerShell helper |
| `single_instance` | `Main.kt` | `acquire() -> Option<Guard>` (named mutex `Local\EmberDesktopSingleInstance`), `relaunch(args)` |
| `tray` | `Main.kt` | `Tray::spawn(tooltip, menu, on_event) -> (Tray, Receiver<TrayEvent>)`; menu described as `MenuEntry` data (items, checkmarks, separators, submenu for Servers), replaced with `set_menu`; left click = `Open`, menu click = `Command(id)` |
| `proc` | the `run`/`ProcessBuilder` helpers | run a program with timeout and no console window, Base64/`-EncodedCommand`, the elevated PowerShell hop, quote checks |

Task names, file names under `tunnel-state\`, `apps\`, `openvpn\`, the management port 17505, scheduled-task scripts and JSON keys are copied from the Kotlin code.

## Differences from Kotlin to know about

- Cancellation: `OpenVpnRunner::session` takes an `&AtomicBool` (Kotlin used coroutine cancel). Setting it shuts OpenVPN down and returns `Ok`.
- Progress and credentials are `&mut dyn FnMut` callbacks.
- `OpenVpnAuthFailed` is `Error::OpenVpnAuthFailed`.
- The hev-socks5-tunnel YAML (`HevSocks5TunnelConfig.write`, in `core/.../vpn/`, off limits) is **not ported**. `WindowsTunnel::start` takes the path of the file the engine wrote; `add_ipv6_to_hev_yaml` does the IPv6 edit. Someone must port `HevSocks5TunnelConfig.write` into the engine crate.
- No system-proxy code: the Kotlin app has none (`RUST_PORT.md` mentions it; nothing to port).
- Windows-only behaviour is behind `cfg(windows)`; other targets compile with stubs.
- `relaunch` while the single-instance guard is held makes the new copy exit at once. Drop the `Guard` first (or spawn, then exit quickly).
- Kotlin's `1223` branch in `elevateNative` was unreachable (`code > 32` first) and was dropped.
- `ember_logo.png` for the tray: the icon is taken from the exe's own icon resource (index 0). The engine/build must embed the icon in the exe (for example with `winres`/`embed-resource`); otherwise Windows' stock icon shows.

## Needs the owner to test (not run by an agent)

1. `autostart::set_enabled(true/false)` and `is_enabled()`: Run key appears and disappears (`reg query HKCU\Software\Microsoft\Windows\CurrentVersion\Run`).
2. `network_identity::current()` on Wi-Fi, on cable, offline, and with a VPN/Hyper-V/VMware adapter present.
3. `single_instance::acquire()`: second launch gets `None`; after the first exits a new one works.
4. Tray: icon appears, left click, right-click menu (items, checkmark, Servers submenu), menu closes when clicking elsewhere, icon returns after `explorer.exe` restarts, icon removed on drop, no ghost icon after exit. Dark mode and 150% scaling.
5. `PrivilegedTask::install/uninstall/is_current/run`: one prompt; tasks `\Ember\Tunnel Up` / `Tunnel Restore` exist and start `ember-helper.exe up|restore --config ...`.
6. `WindowsTunnel::start/stop/restore/start_guard`: UP reached, kill switch, fallback to `ember-tunnel.ps1` when the native helper reports an error, prompt declined message, stale/blocked detection after killing Ember. Read `helper.log` paths.
7. `AppRoutingRunner::install_task/start/stop`: `\Ember\Apps` task (S4U), adapter `EmberApps` appears, sing-box accepts the generated JSON (sing-box version in `tunnel\` must match the schema: `type: tcp` DNS server, `default_domain_resolver`).
8. `OpenVpnRunner`: install task, connect with and without credentials, wrong password (`OpenVpnAuthFailed`), cancel mid-connect, the leftover-OpenVPN cleanup, management password never left on disk after a run is not guaranteed (`management.pw` is rewritten per session exactly as in Kotlin).
9. `proc::elevated_powershell` produces the same prompt as the Kotlin app.

## Hand-over for the engine glue

- Call everything off the UI thread; `start`, `install*`, `session` block for seconds.
- Data layout is unchanged, so the Kotlin app and the Rust app can replace each other.
