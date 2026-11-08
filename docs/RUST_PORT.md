# Rust port of the Windows app

Goal: the Windows app idles in tens of megabytes instead of 500+, with near-zero CPU. Android stays Kotlin and keeps
using `core/`; the Rust code is a separate, desktop-only implementation that must behave the same.

## Why (measured 2026-11-08)

| | RAM (working set) | Idle CPU |
|---|---|---|
| Kotlin app 1.0.101, high-end PC (Task Manager) | 560 MB | 2.1% |
| Slint prototype, software renderer (`ember-rs/proto`) | 25 MB | 0.25% |
| Slint prototype, FemtoVG (GPU) renderer | 82 MB | 0.32% |

The prototype is only the orb screen, so the final app will be larger. Budget: **under 80 MB working set** with the
window open on Home, under 40 MB when minimized to the tray.

## Stack

- UI: Slint, `backend-winit` + **`renderer-software`** (the GPU renderer costs +57 MB).
- The software renderer cannot clip a gradient to a circle, so the orb sphere is the pre-rendered art
  (`design/orb-off.png`, `orb-connecting.png`, `orb-on.png`, converted copies in `desktop/.../resources/art`) cross-faded
  by `lit`; rings and dots are `Path`/small rounded rectangles.
- No async runtime unless something needs it: std threads and channels; blocking `ureq` with rustls for HTTP.
- Xray stays a child process exactly as now (`xray.exe`, SOCKS on the same port).
- Windows APIs through the `windows` crate. `helper/` (existing Rust whole-device helper) is reused as it is.

## Layout (`ember-rs/`, one Cargo workspace)

| Crate | Ports | Owner |
|---|---|---|
| `crates/ember-subs` | `core/.../ember/subs/*.kt`: share links, subscription fetch and parse, Xray config, WARP, OpenVPN profile, node sort/test, site check | Codex |
| `crates/ember-win` | `desktop/.../engine/` Windows parts: autostart, system proxy, single instance, tray, OpenVPN runner, app routing, privileged task, network identity, WindowsTunnel client side | Antigravity |
| `crates/ember-engine` (later) | `EmberEngine.kt`, settings, backup, diagnostics, traffic stats, speed test, logs; glues the two above | Claude |
| `ember-ui` (the `proto` crate grows into it) | every page under `desktop/.../ui/`, theme tokens, strings EN/FA | Claude |

Dependency order: `ember-subs` and `ember-win` are independent of each other and of the UI. `ember-engine` uses both.
Public API of each crate is a small plain-data surface (structs and functions, no traits with one implementation).

## Rules for every agent (from `AGENTS.md`)

Local only, never push. Work in your own worktree on your own branch; commit there; do not merge to `main`. Never run
the installer, `ember-tunnel.ps1`, `netsh`, `route`, firewall or any command that changes this PC's network. Never log
a subscription link or put a real one in a test. Do not touch the files in rule 3 of `AGENTS.md`. Hand back when a
change needs a real device.

## Done means

1. `cargo test --workspace` passes; every behaviour that has a Kotlin test has the same case in Rust.
2. For `ember-subs`: golden tests, i.e. the same inputs as the Kotlin tests give the same outputs (server names, ports,
   generated Xray JSON field for field).
3. No `unsafe` outside `ember-win`, and each `unsafe` block there has a one-line reason.
4. Notes in the branch's `ember-rs/NOTES-<crate>.md`: what is ported, what is not, any behaviour difference.

## Order of work

1. Now, in parallel: `ember-subs` (Codex), `ember-win` (Antigravity), orb screen with art (Claude).
2. Then `ember-engine` and the Home / Servers pages; first runnable app that connects through Xray.
3. Then Settings, Logs, Backup, App rules, Trusted networks, Persian strings, tray.
4. Then installer (`ember.iss` pointing at the Rust exe), signing, side-by-side RAM check against 1.0.101.

Whole-device mode, app rules and OpenVPN cannot be verified by an agent; the owner tests those.
