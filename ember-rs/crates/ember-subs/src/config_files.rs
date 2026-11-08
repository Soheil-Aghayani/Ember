//! Servers from a configuration file the user picked: a WireGuard `.conf` becomes an Xray `wireguard` outbound (Xray
//! speaks WireGuard itself). Port of `ConfigFiles.kt`.

use std::collections::HashMap;

use serde_json::json;

use crate::error::{file_error, ParseError};
use crate::node::Node;
use crate::share::from_xray_outbound;
use crate::util::{after, after_last, before, before_last, is_blank, is_re_space, kt_lines};

/// The text after each line start with leading `\s*` skipped: what `(?im)^\s*…` is matched against.
fn line_tails(text: &str) -> impl Iterator<Item = &str> {
    let starts = std::iter::once(0).chain(text.match_indices(['\n', '\r']).map(|(i, _)| i + 1));
    starts.map(move |i| text[i..].trim_start_matches(is_re_space))
}

fn strip_prefix_ci<'a>(s: &'a str, prefix: &str) -> Option<&'a str> {
    let head = s.get(..prefix.len())?;
    head.eq_ignore_ascii_case(prefix).then(|| &s[prefix.len()..])
}

/// `\s+` followed by something: the rest after at least one whitespace char and any more whitespace.
fn after_spaces(s: &str) -> Option<&str> {
    s.starts_with(is_re_space).then(|| s.trim_start_matches(is_re_space))
}

/// Any file with an `[Interface]` section; an incomplete one gets a specific message from [`wireguard`].
pub fn is_wireguard(text: &str) -> bool {
    line_tails(text).any(|t| strip_prefix_ci(t, "[Interface]").is_some())
}

pub fn is_openvpn(text: &str) -> bool {
    let has_remote = line_tails(text)
        .any(|t| strip_prefix_ci(t, "remote").and_then(after_spaces).is_some_and(|rest| !rest.is_empty()));
    has_remote
        && line_tails(text).any(|t| {
            strip_prefix_ci(t, "client").is_some()
                || strip_prefix_ci(t, "dev").and_then(after_spaces).is_some_and(|r| {
                    strip_prefix_ci(r, "tun").is_some() || strip_prefix_ci(r, "tap").is_some()
                })
                || strip_prefix_ci(t, "<ca>").is_some()
                || strip_prefix_ci(t, "proto").and_then(after_spaces).is_some_and(|r| {
                    strip_prefix_ci(r, "udp").is_some() || strip_prefix_ci(r, "tcp").is_some()
                })
        })
}

fn list(value: Option<&String>) -> Vec<String> {
    value.map_or("", String::as_str).split(',').map(|s| s.trim().to_string()).filter(|s| !s.is_empty()).collect()
}

/// One server for the first `[Peer]`; its endpoint is the server. `name_hint` (the file name) names it.
pub fn wireguard(text: &str, name_hint: &str) -> Result<Node, ParseError> {
    let mut sections: Vec<(String, HashMap<String, String>)> = Vec::new();
    for line in kt_lines(text) {
        let clean = before(line, '#').trim();
        if clean.is_empty() {
            continue;
        }
        if clean.starts_with('[') && clean.ends_with(']') {
            sections.push((clean.trim_matches(['[', ']']).to_lowercase(), HashMap::new()));
        } else if let Some(value) = after(clean, '=') {
            if let Some(last) = sections.last_mut() {
                last.1.insert(before(clean, '=').trim().to_lowercase(), value.trim().to_string());
            }
        }
    }
    let find = |name: &str| sections.iter().find(|s| s.0 == name).map(|s| &s.1);
    let Some(iface) = find("interface") else { return file_error("The WireGuard file has no [Interface] section.") };
    let Some(peer) = find("peer") else { return file_error("The WireGuard file has no [Peer] section.") };
    let Some(private_key) = iface.get("privatekey") else { return file_error("The WireGuard file has no PrivateKey.") };
    let Some(endpoint) = peer.get("endpoint") else { return file_error("The WireGuard file has no Endpoint.") };
    let Some(public_key) = peer.get("publickey") else { return file_error("The WireGuard file has no peer PublicKey.") };
    let addresses = list(iface.get("address"));
    if addresses.is_empty() {
        return file_error("The WireGuard file has no Address.");
    }

    let mut allowed = list(peer.get("allowedips"));
    if allowed.is_empty() {
        allowed = vec!["0.0.0.0/0".into(), "::/0".into()];
    }
    let mut peer_json = json!({"publicKey": public_key, "endpoint": endpoint, "allowedIPs": allowed});
    if let Some(psk) = peer.get("presharedkey").filter(|s| !is_blank(s)) {
        peer_json["preSharedKey"] = json!(psk);
    }
    if let Some(keep) = peer.get("persistentkeepalive").and_then(|v| v.parse::<i32>().ok()).filter(|k| *k > 0) {
        peer_json["keepAlive"] = json!(keep);
    }
    let mut settings = json!({"secretKey": private_key, "address": addresses, "peers": [peer_json]});
    if let Some(mtu) = iface.get("mtu").and_then(|v| v.parse::<i32>().ok()) {
        settings["mtu"] = json!(mtu);
    }
    // not part of the WireGuard format: the three bytes Cloudflare WARP ties to a device
    if let Some(value) = iface.get("reserved").or_else(|| peer.get("reserved")) {
        let bytes: Vec<i32> = list(Some(value)).iter().filter_map(|v| v.parse::<i32>().ok()).filter(|b| (0..=255).contains(b)).collect();
        if bytes.len() == 3 {
            settings["reserved"] = json!(bytes);
        }
    }
    let outbound = json!({"protocol": "wireguard", "settings": settings});
    from_xray_outbound(&outbound, name_hint, None).ok_or_else(|| ParseError::File("The WireGuard endpoint is not valid.".into()))
}

/// Splits `host:port` and `[v6]:port`.
pub fn host_and_port(endpoint: &str) -> Option<(String, i32)> {
    let host = if let Some(inner) = endpoint.strip_prefix('[') {
        let end = endpoint.find(']').filter(|i| *i > 0)?;
        inner[..end - 1].to_string()
    } else {
        before_last(endpoint, ':').to_string()
    };
    let port = after_last(endpoint, ':').parse::<i32>().ok()?;
    (!is_blank(&host)).then_some((host, port))
}
