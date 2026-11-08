//! Cloudflare WARP carried through one of the user's own servers (Xray's `dialerProxy`), for networks that drop
//! WireGuard to Cloudflare. Port of `WarpChain.kt`.
//!
//! The node is stored as `warpchain:<name>:<carrier link>:<WARP outbound>` (each part in base64). Its host and port are
//! the carrier's, so lookups, checks and the kill switch all deal with the server the network really sees.

use serde_json::{json, Value};

use crate::error::{file_error, ParseError};
use crate::node::Node;
use crate::share::{self, parse_share_link};
use crate::util::{b64, decode_base64_text};

pub const PREFIX: &str = "warpchain:";

/// WARP's usual address; used as a number so no name lookup is needed on the way.
const ENDPOINT: &str = "162.159.192.1:2408";

/// A chained node for `carrier`, from the WireGuard outbound `warp` of a registered WARP device.
pub fn create(carrier: &Node, warp: &Value) -> Result<Node, ParseError> {
    let mut wire_guard = warp.clone();
    let map = wire_guard.as_object_mut().ok_or(ParseError::Invalid("not an outbound"))?;
    map.remove("ember_name");
    map.remove("tag");
    let peer = map
        .get_mut("settings")
        .and_then(|s| s.get_mut("peers"))
        .and_then(Value::as_array_mut)
        .and_then(|p| p.first_mut())
        .and_then(Value::as_object_mut)
        .ok_or(ParseError::Invalid("no WARP peer"))?;
    peer.insert("endpoint".into(), json!(ENDPOINT));
    // keep the carrier's flag in front of the name so the list shows where the traffic goes through
    let title = carrier.title();
    let flags = carrier.name.find(&title).map_or("", |i| &carrier.name[..i]);
    let name = format!("{flags}WARP via {title}");
    let parts: Vec<String> = [name.as_str(), carrier.raw.as_str(), &wire_guard.to_string()].iter().map(|p| b64(p)).collect();
    let raw = format!("{PREFIX}{}", parts.join(":"));
    Ok(Node::new(&raw, "warp", &name, &carrier.host, carrier.port))
}

fn parts(raw: &str) -> Option<[String; 3]> {
    let decoded: Vec<String> =
        raw.strip_prefix(PREFIX).unwrap_or(raw).split(':').map(decode_base64_text).collect::<Result<_, _>>().ok()?;
    decoded.try_into().ok()
}

/// Rebuilds a stored node (same raw text, so the same id).
pub fn from_raw(raw: &str) -> Option<Node> {
    let [name, carrier_raw, _] = parts(raw)?;
    let carrier = parse_share_link(&carrier_raw).ok()?;
    Some(Node::new(raw, "warp", &name, &carrier.host, carrier.port))
}

/// The two Xray outbounds: WARP (named `tag`) and the carrier it dials through, which connects to `address`.
pub fn outbounds(raw: &str, address: &str, tag: &str) -> Result<Vec<Value>, ParseError> {
    let Some([_, carrier_raw, warp]) = parts(raw) else { return file_error("This WARP server could not be read.") };
    let carrier_tag = format!("{tag}-carrier");
    let mut wire_guard: Value = serde_json::from_str(&warp).map_err(|_| ParseError::Invalid("bad WARP outbound"))?;
    wire_guard["tag"] = json!(tag);
    wire_guard["streamSettings"] = json!({"sockopt": {"dialerProxy": carrier_tag}});
    Ok(vec![wire_guard, share::outbound(&carrier_raw, address, &carrier_tag)?])
}
