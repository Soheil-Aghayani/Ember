//! Port of `core/src/main/kotlin/ember/subs` (see ember-rs/briefs/codex-subs.md): share links, subscriptions, Xray
//! configuration, WireGuard/OpenVPN/WARP servers, node sorting and testing, website checks and the Xray process.
//!
//! Plain data and functions. Only [`http`], [`tester`] and [`runner`] touch the network or processes; a subscription
//! link is a secret, so no `Debug` output and no error text in this crate contains one (only hosts).

pub mod config_files;
mod error;
pub mod http;
mod node;
pub mod openvpn;
pub mod qr;
pub mod runner;
mod share;
pub mod sort;
pub mod subscription;
pub mod tester;
mod util;
pub mod warp;
pub mod warp_chain;
pub mod xray_config;

pub use error::ParseError;
pub use node::Node;
pub use share::{from_xray_json, from_xray_outbound, outbound, outbounds, parse_share_link, JSON_PREFIX};
pub use util::decode_base64;
