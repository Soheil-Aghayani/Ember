# Ember native tunnel helper

Windows-only Rust replacement for `desktop/appResources/windows-x64/ember-tunnel.ps1`.
It uses IP Helper/DNS APIs and Windows Firewall COM directly, with the same config,
route journal, status strings, and crash/kill-switch behavior. The app can select
either helper; this crate does not change the Kotlin app or packaging.

Build from this folder with `cargo build --release` on Windows (MSVC toolchain).
The executable is `target/release/ember-helper.exe`. Use Windows 10 build 19041
or newer for `SetInterfaceDnsSettings`. Place the bundled
`hev-socks5-tunnel.exe` inside `tunnel/` next to the helper, optionally nested.
The helper must be launched elevated by the app; it does not prompt for UAC itself.

```
ember-helper up --config <path>
ember-helper restore --config <path>
```

The JSON fields match the PowerShell helper: `stateDir`, `hevConfigPath`,
`adapterName`, `tunAddress`, `mapdnsAddress`, `edgeIps`, optional `excludeCidrs`,
`killSwitch`, and `parentPid`. Restore needs only an existing `stateDir`.
Invalid exclusions are logged and skipped; CIDRs are normalized and limited to
/8 through /32. Executables are never selected by config.

Files in `stateDir`:

- `status`: one line, `STARTING`, `UP`, `DOWN`, `BLOCKED: ...`, or `ERROR: ...`.
- `helper.log`: local `YYYY-MM-DDTHH:MM:SS` events and major-step elapsed times.
- `routes.json`: JSON array of `{ "prefix": "a.b.c.d/n", "ifIndex": N, "kind": "bypass" | "tun" }`, readable by either helper.
- `stop`: create to stop and clean up. A stale stop file is removed at startup.
- `refresh`: create to request bypass-route and kill-switch refresh.

On parent loss with the kill switch enabled, firewall rules and the route journal
are deliberately retained. An elevated `restore` clears this blocked state.
Cleanup is best effort, logs failures, and continues through routes, firewall,
and tunnel processes. Like the script, it terminates all processes named
`hev-socks5-tunnel.exe`; use a dedicated bundle/session.

`cargo test --lib` runs pure validation/range/journal/signature tests on any OS.
`cargo check --target x86_64-pc-windows-msvc` checks the Windows integration.
Neither command invokes the helper. Elevated end-to-end validation and timing
must be done separately on a disposable Windows test machine: the 1–2 second
startup target is not a measured guarantee.

API references: [Rust for Windows](https://docs.rs/crate/windows/0.62.2),
[SetInterfaceDnsSettings](https://learn.microsoft.com/en-us/windows/win32/api/netioapi/nf-netioapi-setinterfacednssettings).
