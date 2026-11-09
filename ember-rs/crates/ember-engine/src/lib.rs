//! The connection engine of the Rust app (port of `desktop/.../engine/EmberEngine.kt` and its settings and subscription
//! store). Plain Rust with threads: no async runtime. The UI reads a [`engine::Snapshot`] and is told by a callback when
//! it changes.
//!
//! Connects in proxy mode, whole-device mode, with app rules, or through OpenVPN (see `engine`). What changes the
//! computer goes through `host::Host`.

pub mod backup;
pub mod diagnostics;
pub mod engine;
pub mod host;
pub mod log;
pub mod settings;
pub mod skip;
pub mod store;

pub use engine::{Capabilities, ConnState, CredentialRequest, Engine, Snapshot, HTTP_PORT, LOCAL_BIND, SOCKS_PORT};
pub use settings::Settings;
pub use store::SubscriptionStore;

/// Milliseconds since the Unix epoch.
pub fn now_ms() -> i64 {
    std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).map(|d| d.as_millis() as i64).unwrap_or(0)
}
