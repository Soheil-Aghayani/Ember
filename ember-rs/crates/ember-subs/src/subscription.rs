//! Reading subscription bodies and headers, and fetching a subscription. Port of the parsing half of
//! `Subscriptions.kt`; the store that keeps the list on disk belongs to the engine.

use std::collections::HashMap;
use std::fmt;
use std::time::Duration;

use crate::config_files::{is_openvpn, is_wireguard, wireguard};
use crate::error::ParseError;
use crate::http::{Http, HttpError};
use crate::node::Node;
use crate::share::{from_xray_json, parse_share_link};
use crate::util::{after, before, decode_base64_text, kt_lines};
use crate::openvpn;

const FETCH_AGENT: &str = "Ember/1.0 (v2rayN compatible)";
const MAX_BODY: u64 = 32 << 20;

/// Name of the subscription that holds the Cloudflare WARP servers.
pub const WARP_NAME: &str = "Cloudflare WARP";

/// Stored as the selected server when Connect should pick the fastest tested one.
pub const AUTO_NODE: &str = "auto";

/// Data use and expiry a panel reports in the `subscription-userinfo` header.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct SubscriptionUsage {
    pub uploaded: i64,
    pub downloaded: i64,
    pub total: i64,
    pub expires_at_epoch_seconds: Option<i64>,
}

impl SubscriptionUsage {
    pub fn used(&self) -> i64 {
        self.uploaded + self.downloaded
    }
}

/// What the last speed test measured for one server. `ping_ms` `None` means the server did not answer.
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct NodeResult {
    pub ping_ms: Option<i32>,
    pub mbps: Option<f64>,
    pub tested_at_ms: i64,
}

#[derive(Clone)]
pub struct Subscription {
    pub id: String,
    pub name: String,
    /// The link holds the account's secret: it is left out of `Debug`.
    pub url: String,
    pub nodes: Vec<Node>,
    pub usage: Option<SubscriptionUsage>,
    pub updated_at_ms: i64,
}

impl fmt::Debug for Subscription {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.debug_struct("Subscription")
            .field("id", &self.id)
            .field("name", &self.name)
            .field("host", &url_host(&self.url))
            .field("nodes", &self.nodes.len())
            .finish_non_exhaustive()
    }
}

/// The host of a web link (for logs and default names), or `None` when it is not one.
pub fn url_host(url: &str) -> Option<String> {
    url::Url::parse(url.trim()).ok()?.host_str().map(str::to_string).filter(|h| !h.is_empty())
}

fn distinct_by_id(nodes: impl IntoIterator<Item = Node>) -> Vec<Node> {
    let mut seen = std::collections::HashSet::new();
    nodes.into_iter().filter(|n| seen.insert(n.id.clone())).collect()
}

/// Servers in a subscription body: a WireGuard or OpenVPN file, an Xray JSON list, plain share links or the same in
/// base64. `name_hint` names single-file servers.
pub fn parse_subscription(body: &str, name_hint: &str) -> Result<Vec<Node>, ParseError> {
    let trimmed = body.trim();
    let text = trimmed.strip_prefix('\u{FEFF}').unwrap_or(trimmed);
    if is_wireguard(text) {
        return Ok(vec![wireguard(text, name_hint)?]);
    }
    if is_openvpn(text) {
        return Ok(vec![openvpn::node(text, name_hint)?]);
    }
    let looks_like_json = text.starts_with('{') || text.starts_with('[');
    if looks_like_json {
        let from_json = distinct_by_id(from_xray_json(text).unwrap_or_default());
        if !from_json.is_empty() {
            return Ok(from_json);
        }
    }
    let decoded;
    let lines: Vec<&str> = if text.contains("://") {
        kt_lines(text)
    } else {
        decoded = decode_base64_text(text).unwrap_or_default();
        kt_lines(&decoded)
    };
    let nodes = distinct_by_id(lines.into_iter().filter_map(|l| parse_share_link(l).ok()));
    if nodes.is_empty() {
        return Err(if looks_like_json { ParseError::JsonWithoutServers } else { ParseError::NoServers });
    }
    Ok(nodes)
}

/// Servers the user pasted (links, a WireGuard or OpenVPN file, Xray JSON). Specific messages for files Ember
/// recognises are kept; everything else gets the general hint.
pub fn parse_pasted(text: &str, name_hint: &str) -> Result<Vec<Node>, String> {
    parse_subscription(text, name_hint).map_err(|e| match &e {
        ParseError::File(m) if m.starts_with("The WireGuard") || m.starts_with("The OpenVPN") => m.clone(),
        _ => "That is neither a subscription link (https://…) nor server links, a WireGuard or OpenVPN file or Xray JSON.".to_string(),
    })
}

/// `upload=1; download=2; total=3; expire=1700000000`
pub fn parse_user_info(header: Option<&str>) -> Option<SubscriptionUsage> {
    let header = header.filter(|h| !h.trim().is_empty())?;
    let mut values: HashMap<String, i64> = HashMap::new();
    for part in header.split(';') {
        let key = if part.contains('=') { before(part, '=').trim().to_lowercase() } else { String::new() };
        let value = after(part, '=').unwrap_or("").trim().parse::<f64>().ok().map(|v| v as i64);
        if let (false, Some(value)) = (key.is_empty(), value) {
            values.insert(key, value);
        }
    }
    let total = *values.get("total")?;
    Some(SubscriptionUsage {
        uploaded: values.get("upload").copied().unwrap_or(0),
        downloaded: values.get("download").copied().unwrap_or(0),
        total,
        expires_at_epoch_seconds: values.get("expire").copied().filter(|e| *e > 0),
    })
}

/// `profile-title` is plain text or `base64:<text>`.
pub fn parse_title(header: Option<&str>) -> Option<String> {
    let header = header.filter(|h| !h.trim().is_empty())?;
    match header.strip_prefix("base64:") {
        Some(encoded) => decode_base64_text(encoded).ok(),
        None => Some(header.to_string()),
    }
}

/// A subscription as the server sent it.
#[derive(Debug, Clone)]
pub struct Fetched {
    pub nodes: Vec<Node>,
    pub usage: Option<SubscriptionUsage>,
    pub title: Option<String>,
}

#[derive(Debug, thiserror::Error)]
pub enum FetchError {
    #[error(transparent)]
    Http(#[from] HttpError),
    #[error(transparent)]
    Parse(#[from] ParseError),
}

/// An `Http` with the timeouts subscriptions are fetched with.
pub fn subscription_http() -> Http {
    Http::direct(Duration::from_secs(15), Duration::from_secs(30))
}

/// Downloads and reads a subscription link.
pub fn fetch_subscription(http: &Http, url: &str) -> Result<Fetched, FetchError> {
    let response = http.get(url, &[("User-Agent", FETCH_AGENT), ("Accept", "*/*")])?;
    if !(200..300).contains(&response.status()) {
        return Err(HttpError::Status(response.status()).into());
    }
    let usage = parse_user_info(response.header("subscription-userinfo"));
    let title = parse_title(response.header("profile-title"));
    let body = response
        .into_text(MAX_BODY)
        .map_err(|_| HttpError::Transport(format!("Could not read the answer of {}.", url_host(url).unwrap_or_default())))?;
    Ok(Fetched { nodes: parse_subscription(&body, "")?, usage, title })
}
