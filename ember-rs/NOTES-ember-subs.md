# ember-subs: port notes

Crate `ember-rs/crates/ember-subs`, port of `core/.../ember/subs/*.kt` and `desktop/.../ember/subs/XrayRunner.kt`.
`cargo test -p ember-subs` (4 unit, 5 golden, 28 Kotlin-port tests) and `cargo clippy -p ember-subs --all-targets` pass.
Nothing here was run against a network or a real `xray.exe`; see "Not verified".

## Ported

| Kotlin | Rust |
|---|---|
| `ShareLinks.kt` (`ProxyNode`, parse, `outbound(s)`, Xray JSON, `decodeBase64`) | `node.rs` (`Node`), `share.rs` (`parse_share_link`, `outbound`, `outbounds`, `from_xray_json`, `from_xray_outbound`), `util.rs` |
| `Subscriptions.kt` (parser, headers, fetch, `bestNode`, pasted-text error mapping) | `subscription.rs` (`parse_subscription`, `parse_pasted`, `parse_user_info`, `parse_title`, `fetch_subscription`), `sort.rs` (`best_node`) |
| `XrayConfig.kt` | `xray_config.rs` (`xray_config(node, &XraySettings)`, `xray_config_many`) |
| `ConfigFiles.kt` | `config_files.rs` |
| `Warp.kt` (registration, configs, X25519, `register`) | `warp.rs` |
| `WarpChain.kt` | `warp_chain.rs` |
| `OpenVpnProfile.kt` | `openvpn.rs` |
| `NodeSort.kt` | `sort.rs` |
| `NodeTester.kt`, `SiteChecker.kt` | `tester.rs` (`test_nodes`, `check_site`, `normalize_site_url`, `SiteResult`, `SITE_PRESETS`) |
| `QrCodes.encode` | `qr.rs` (`qr_matrix`) |
| `XrayProcess.kt` (`freePorts`), `XrayRunner.kt` | `runner.rs` (`XrayRunner`, `free_ports`) |
| (network) | `http.rs`: `Http` on `ureq` 2 + rustls, direct or through a local SOCKS5 port |

Tests: every case of `ShareLinksTest.kt` is in `tests/kotlin_port.rs` in the same order. `tests/golden/*.json` hold
inputs and the outputs the Kotlin gives (worked out by reading the Kotlin; only node ids were computed, with Python
`hashlib`, as `sha256(raw)[:12]`). They cover node fields and flags, outbounds for every link kind and stream type,
Xray configs, OpenVPN preparing, subscription bodies and error messages, and the usage header.

## Not ported

- **`SubscriptionStore`** (the list on disk, `subscriptions.json`, remembered addresses, add/refresh/update/remove,
  `addWarp`, `addWarpChain` orchestration): it is state plus file IO, so it belongs to `ember-engine`. The pure pieces it
  uses are here (`parse_subscription`, `parse_pasted`, `fetch_subscription`, `warp::register`, `warp_chain::create`,
  `best_node`). Saved raw links keep the same ids, because an id is `sha256(raw)[:12]` and `raw` is stored as it was.
- **`QrCodes.decode`** (reading a code from a picture, ZXing): needs an image decoder and a QR reader crate
  (`image` + `rqrr`); not in the allowed list. `qr_matrix` returns the module grid; the UI draws it (quiet zone 2).
- **`XrayRunner.killStale`**: needs process enumeration; it fits `ember-win`.
- Logging (`AppLogger`): the crate has no logger. Hosts are never logged because nothing is.

## Behaviour differences

- **Key order / ids of new JSON-based servers.** Servers from an Xray JSON subscription and WireGuard files are stored as
  `xrayjson:<outbound JSON>`; the id hashes that text. `org.json` writes keys in `HashMap` order, `serde_json` in sorted
  order, so a node read *freshly* from such a source gets a different `raw` and id than the Kotlin app gave it. Nodes
  loaded from a saved file keep their stored `raw` and id (the parser never re-serialises it). Effect: after the Rust app
  refreshes a JSON/WireGuard subscription, favourites and test results for those servers start empty. Plain share links
  are unaffected. Matching Java's `HashMap` order is possible if this matters.
- **JSON reading is strict** (`serde_json`); `org.json` accepts some non-standard JSON (single quotes, unquoted keys,
  comments, trailing commas). A body that only `org.json` could read gives "No servers" here.
- `parse_subscription` returns `Result<Vec<Node>, ParseError>`, not a bare `Vec` (the Kotlin throws with user-facing
  messages; the texts are identical, see `ParseError`). `parse_share_link` returns `Err(Unsupported)` where Kotlin returned
  `null` (e.g. Hysteria2 with `obfs`).
- `Node.port` is `i32`, as in Kotlin (`vless://x@h:70000` parses; ports are not range-checked there either).
- `test_nodes` also skips OpenVPN servers (Kotlin `NodeTester` only skipped information rows and relied on the caller to
  drop OpenVPN; passing one would have thrown "Unsupported link type").
- `tester` runs on plain threads: a name lookup that takes over 6 s is abandoned (its thread finishes in the
  background); Kotlin cancelled the coroutine. HTTP redirects follow `ureq`'s default (5) where OkHttp followed 20.
- Errors from `Http` never contain the URL (ureq's own messages do, and a subscription link is a secret); they name only
  the host. `Node` and `Subscription` have a hand-written `Debug` that leaves out `raw` and `url`.
- Numbers like `upload=1e3` in `subscription-userinfo`: Kotlin `toDoubleOrNull` also accepts `1d`/`1f` suffixes and
  `NaN`/`Infinity` spellings; Rust's `f64` parser accepts `inf`/`nan` spellings and no suffixes. Irrelevant for real panels.
- `Character.isWhitespace` vs Unicode whitespace is mapped by hand where it matters (flag skipping, base64 cleaning);
  `trim()`/`ifBlank` use Rust's Unicode `trim`, which also trims U+00A0 (Kotlin's `trim` does too).

## Dependencies

Allowed ones used: `serde_json`, `base64`, `url`, `ureq` (features `tls` = rustls, `socks-proxy`), `qrcode` (no image
feature), `thiserror`. **Added, please confirm:** `getrandom` 0.3 for the WARP device key (OS randomness); it is already
in the tree through rustls/ring. No `serde` derive, no `sha2` (SHA-256 is 40 lines in `util.rs`, tested against known
answers) and no big-integer crate (X25519 is the 16-limb TweetNaCl form, tested against the RFC 7748 vectors).
`ember-rs/Cargo.lock` is created by cargo and is not part of this commit (outside the allowed paths).

## Not verified

- `fetch_subscription`, `warp::register`, `test_nodes`, `check_site` and `XrayRunner` were compiled and linted but never
  run against a network or `xray.exe` (this task forbids it, and fixtures cannot cover them). The owner should try a
  speed test and a website check from the new app. Details to look at: SOCKS5 to Xray passes host names to the proxy
  (ureq's `Domain` target, like OkHttp), `Http::socks` with `follow_redirects=false` hands 3xx back as `Ok`.
- The spawned Xray uses `CREATE_NO_WINDOW` via `std::os::windows::process::CommandExt` (no `unsafe`); the Kotlin used
  `CreateProcess` with `SW_HIDE`. Same visible result expected.
