# Brief for Antigravity CLI: `ember-win` (Windows integration port)

You are porting Kotlin to Rust in the Ember repo. Read `AGENTS.md` and `docs/RUST_PORT.md` first.

**Worktree and branch:** `D:\Programming\FoxyVPN-rust-win`, branch `agent/rust-win` (already created from `agent/rust-port`).
Work only inside `ember-rs/crates/ember-win/`. Do not edit other crates, `core/`, `desktop/`, `android/` or `helper/`.

## What to port

From `desktop/src/main/kotlin/ember/engine/`:
`Autostart.kt`, `DesktopPaths.kt`, `NetworkIdentity.kt`, `PrivilegedTask.kt`, `AppRouting.kt` (the config-building part
is testable), `OpenVpn.kt`, `WindowsTunnel.kt` (the Ember side: how it starts and talks to the elevated helper), and
from `Main.kt`/`WindowChrome.kt` what the OS needs: single-instance guard, tray icon with show/connect/quit menu,
run-at-login registry key, relaunch.

## Hard safety limits (this is why the work is split off)

- **Nothing you write may be executed against this PC's network.** Do not run `ember-tunnel.ps1`, `netsh`, `route`,
  firewall changes, the installer, `helper/`, OpenVPN or any proxy-changing code. Only unit-test pure logic (building
  command lines, parsing output captured as fixtures, config generation) and compile.
- Anything that needs a real run (tunnel, app rules, OpenVPN, system proxy) is written with the Win32/registry call
  behind a small trait-free function boundary, covered by tests of the logic around it, and listed in
  `ember-rs/NOTES-ember-win.md` under "needs the owner to test".
- Do not change `desktop/appResources/windows-x64/ember-tunnel.ps1` or anything in `helper/`; call them as the Kotlin does.

## Shape of the crate

- Dependencies: `windows` (only the features you use), `thiserror`, `serde_json`. No async runtime. `unsafe` only here,
  each block with a one-line comment saying why it is sound.
- Plain functions and structs: e.g. `autostart::set(enabled)`, `single_instance::acquire() -> Option<Guard>`,
  `app_routing::build_rules(...)`, `paths::data_dir()`. Mirror the Kotlin names where sensible so the engine glue is easy.
- Paths and file formats must match the Kotlin app exactly (`%APPDATA%\Ember`, same JSON keys), so one can be
  replaced by the other without losing the user's data.

## Done

`cargo test -p ember-win` passes on logic that can run safely; `cargo clippy -p ember-win` clean;
`ember-rs/NOTES-ember-win.md` lists what is ported, what is untested, and what the owner must try. Commit on
`agent/rust-win`. Do not push; do not merge.
