//! Xray configurations for one connection or for testing many servers at once. Port of `XrayConfig.kt`.

use std::path::{Path, PathBuf};

use serde_json::{json, Value};

use crate::error::ParseError;
use crate::node::Node;
use crate::share;

/// What a single-server configuration needs besides the node.
#[derive(Debug, Clone)]
pub struct XraySettings {
    /// Replaces the node's host (an IP the caller resolved, so the route to it can be pinned).
    pub address: String,
    pub socks_port: u16,
    /// A local HTTP proxy is added when given.
    pub http_port: Option<u16>,
    pub log_file: PathBuf,
    /// Logs why connections fail but also names the sites visited, so it is only for hunting a problem.
    pub verbose: bool,
    /// Hostnames the user wants to reach without the VPN, with the IPv4 addresses Ember looked up for them: Xray sends
    /// exactly those names straight out. Order is kept.
    pub bypass: Vec<(String, Vec<String>)>,
}

fn base(log_file: &Path, verbose: bool) -> Value {
    json!({"log": {"loglevel": if verbose { "info" } else { "warning" }, "error": log_file.to_string_lossy()}})
}

fn socks_inbound(tag: &str, port: u16) -> Value {
    json!({
        "tag": tag, "listen": "127.0.0.1", "port": port, "protocol": "socks",
        "settings": {"auth": "noauth", "udp": true, "ip": "127.0.0.1"},
    })
}

/// One server behind a local SOCKS5 port (and a local HTTP proxy when asked for).
pub fn xray_config(node: &Node, settings: &XraySettings) -> Result<Value, ParseError> {
    let mut inbounds = vec![socks_inbound("in", settings.socks_port)];
    if let Some(port) = settings.http_port {
        inbounds.push(json!({"tag": "http", "listen": "127.0.0.1", "port": port, "protocol": "http"}));
    }
    // QUIC (UDP 443) is dropped here: many servers carry no UDP, and a browser then waits for a QUIC attempt that never
    // answers before it falls back to TCP. Dropping it makes sites use TCP straight away.
    let mut outbounds = share::outbounds(&node.raw, &settings.address, "proxy")?;
    outbounds.push(json!({"tag": "block", "protocol": "blackhole"}));
    let mut rules = Vec::new();
    let mut config = base(&settings.log_file, settings.verbose);
    if !settings.bypass.is_empty() {
        outbounds.push(json!({"tag": "direct", "protocol": "freedom", "settings": {"domainStrategy": "UseIPv4"}}));
        let hosts: serde_json::Map<String, Value> =
            settings.bypass.iter().map(|(name, ips)| (format!("full:{name}"), json!(ips))).collect();
        // Only exact names are routed directly, and each one has its addresses here, so Xray never has to ask a
        // resolver (which would hand back a made-up address and loop through the tunnel).
        config["dns"] = json!({"hosts": hosts, "servers": ["localhost"]});
        let domains: Vec<String> = settings.bypass.iter().map(|(name, _)| format!("full:{name}")).collect();
        rules.push(json!({"type": "field", "domain": domains, "outboundTag": "direct"}));
    }
    rules.push(json!({"type": "field", "network": "udp", "port": "443", "outboundTag": "block"}));
    config["inbounds"] = json!(inbounds);
    config["outbounds"] = json!(outbounds);
    config["routing"] = json!({"domainStrategy": "AsIs", "rules": rules});
    Ok(config)
}

/// Each server gets its own local SOCKS5 port, so all of them can be tested at the same time. Entries are
/// (node, address, port).
pub fn xray_config_many(entries: &[(Node, String, u16)], log_file: &Path) -> Result<Value, ParseError> {
    let mut inbounds = Vec::new();
    let mut outbounds = Vec::new();
    let mut rules = Vec::new();
    for (i, (node, address, port)) in entries.iter().enumerate() {
        inbounds.push(socks_inbound(&format!("in{i}"), *port));
        outbounds.extend(share::outbounds(&node.raw, address, &format!("out{i}"))?);
        rules.push(json!({"type": "field", "inboundTag": [format!("in{i}")], "outboundTag": format!("out{i}")}));
    }
    let mut config = base(log_file, false);
    config["inbounds"] = json!(inbounds);
    config["outbounds"] = json!(outbounds);
    config["routing"] = json!({"domainStrategy": "AsIs", "rules": rules});
    Ok(config)
}
