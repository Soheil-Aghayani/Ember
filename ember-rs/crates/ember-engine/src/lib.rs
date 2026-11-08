//! The connection engine of the Rust app (port of `desktop/.../engine/EmberEngine.kt` and its settings and subscription
//! store). Plain Rust with threads: no async runtime. The UI reads a [`engine::Snapshot`] and is told by a callback when
//! it changes.
//!
//! This first slice connects in proxy mode only (Xray on a local SOCKS5 and HTTP port). Whole-device mode, app rules
//! and OpenVPN answer with a clear "not available in this build yet" until they are wired to `ember-win`.

pub mod diagnostics;
pub mod engine;
pub mod log;
pub mod settings;
pub mod store;

pub use engine::{ConnState, Engine, Snapshot, HTTP_PORT, LOCAL_BIND, SOCKS_PORT};
pub use settings::Settings;
pub use store::SubscriptionStore;

/// Milliseconds since the Unix epoch.
pub fn now_ms() -> i64 {
    std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).map(|d| d.as_millis() as i64).unwrap_or(0)
}
