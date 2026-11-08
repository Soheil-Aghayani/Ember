//! Share links (`vless://`, `vmess://`, `trojan://`, `ss://`, `hysteria2://`) and Xray JSON entries, and the Xray
//! outbound each one becomes. Port of `ShareLinks.kt`.

use std::collections::HashMap;

use serde_json::{json, Map, Value};

use crate::config_files::host_and_port;
use crate::error::ParseError;
use crate::node::Node;
use crate::util::{
    after, after_last, before, before_last, decode_base64_text, is_blank, non_blank, opt_int, opt_string, opt_string_or,
    url_decode,
};
use crate::{openvpn, warp_chain};

/// Prefix of a server that came from an Xray JSON subscription: the rest is its outbound as JSON.
pub const JSON_PREFIX: &str = "xrayjson:";

/// The server's name, kept inside the stored outbound so it survives a restart (Xray ignores unknown keys).
const NAME_KEY: &str = "ember_name";

const JSON_PROTOCOLS: [&str; 6] = ["vless", "vmess", "trojan", "shadowsocks", "hysteria", "wireguard"];

type Query = HashMap<String, String>;

/// Reads one share link, or the stored form of a file/JSON/WARP server, into a [`Node`].
pub fn parse_share_link(line: &str) -> Result<Node, ParseError> {
    let raw = line.trim();
    if raw.starts_with("vless://") {
        link(raw, "vless")
    } else if raw.starts_with("trojan://") {
        link(raw, "trojan")
    } else if raw.starts_with("vmess://") {
        vmess(raw)
    } else if raw.starts_with("ss://") {
        let s = ss(raw)?;
        Ok(Node::new(raw, "ss", &s.name, &s.host, s.port))
    } else if raw.starts_with("hysteria2://") {
        hysteria2(raw, "hysteria2")
    } else if raw.starts_with("hy2://") {
        hysteria2(raw, "hy2")
    } else if let Some(json) = raw.strip_prefix(JSON_PREFIX) {
        let outbound = parse_json_object(json)?;
        let name = opt_string(&outbound, NAME_KEY);
        from_xray_outbound(&outbound, &name, Some(raw)).ok_or(ParseError::Invalid("not an Xray server"))
    } else if raw.starts_with(openvpn::PREFIX) {
        openvpn::from_raw(raw).ok_or(ParseError::Invalid("bad OpenVPN server"))
    } else if raw.starts_with(warp_chain::PREFIX) {
        warp_chain::from_raw(raw).ok_or(ParseError::Invalid("bad WARP server"))
    } else {
        Err(ParseError::Unsupported)
    }
}

fn parse_json_object(text: &str) -> Result<Value, ParseError> {
    match serde_json::from_str::<Value>(text) {
        Ok(v) if v.is_object() => Ok(v),
        _ => Err(ParseError::Invalid("bad JSON")),
    }
}

/// Every Xray outbound `raw` needs: one for most servers, two for WARP carried through a server. The first is the one
/// to route to.
pub fn outbounds(raw: &str, address: &str, tag: &str) -> Result<Vec<Value>, ParseError> {
    if raw.starts_with(warp_chain::PREFIX) {
        warp_chain::outbounds(raw, address, tag)
    } else {
        Ok(vec![outbound(raw, address, tag)?])
    }
}

/// The Xray outbound for `raw`. `address` replaces the host (an IP the caller resolved, so the route to it can be pinned).
pub fn outbound(raw: &str, address: &str, tag: &str) -> Result<Value, ParseError> {
    if raw.starts_with("vless://") {
        let l = split(raw, "vless")?;
        let mut user = json!({"id": l.user_info, "encryption": l.query.get("encryption").map_or("none", String::as_str)});
        if let Some(flow) = non_blank(&l.query, "flow") {
            user["flow"] = json!(flow);
        }
        Ok(json!({
            "tag": tag, "protocol": "vless",
            "settings": {"vnext": [{"address": address, "port": l.port, "users": [user]}]},
            "streamSettings": stream(&l.query, "none", &l.host),
        }))
    } else if raw.starts_with("trojan://") {
        let l = split(raw, "trojan")?;
        Ok(json!({
            "tag": tag, "protocol": "trojan",
            "settings": {"servers": [{"address": address, "port": l.port, "password": l.user_info}]},
            "streamSettings": stream(&l.query, "tls", &l.host),
        }))
    } else if raw.starts_with("vmess://") {
        let j = vmess_json(raw)?;
        let tls = opt_string(&j, "tls");
        let mut query: Query = [
            ("type", opt_string_or(&j, "net", "tcp")),
            ("security", if !is_blank(&tls) && tls != "none" { "tls".into() } else { "none".into() }),
            ("sni", opt_string(&j, "sni")),
            ("host", opt_string(&j, "host")),
            ("path", opt_string(&j, "path")),
            ("alpn", opt_string(&j, "alpn")),
            ("fp", opt_string(&j, "fp")),
            ("headerType", opt_string(&j, "type")),
            ("serviceName", opt_string(&j, "path")),
        ]
        .into_iter()
        .map(|(k, v)| (k.to_string(), v))
        .filter(|(_, v)| !is_blank(v))
        .collect();
        // `optString("net", "tcp").ifBlank { "tcp" }` and the same for scy: blank values fall back
        query.entry("type".into()).or_insert_with(|| "tcp".into());
        let aid = opt_string_or(&j, "aid", "0").parse::<i32>().unwrap_or(0);
        let scy = opt_string_or(&j, "scy", "auto");
        let user = json!({"id": opt_string(&j, "id"), "alterId": aid, "security": if is_blank(&scy) { "auto".to_string() } else { scy }});
        let port: i32 = opt_string(&j, "port").parse().map_err(|_| ParseError::Invalid("bad port"))?;
        Ok(json!({
            "tag": tag, "protocol": "vmess",
            "settings": {"vnext": [{"address": address, "port": port, "users": [user]}]},
            "streamSettings": stream(&query, "none", &opt_string(&j, "add")),
        }))
    } else if raw.starts_with("ss://") {
        let s = ss(raw)?;
        Ok(json!({
            "tag": tag, "protocol": "shadowsocks",
            "settings": {"servers": [{"address": address, "port": s.port, "method": s.method, "password": s.password}]},
        }))
    } else if raw.starts_with("hysteria2://") || raw.starts_with("hy2://") {
        let l = split(raw, if raw.starts_with("hy2://") { "hy2" } else { "hysteria2" })?;
        let mut tls = json!({
            "serverName": non_blank(&l.query, "sni").unwrap_or(&l.host),
            "alpn": ["h3"],
        });
        if l.query.get("insecure").map(String::as_str) == Some("1") || l.query.get("allowInsecure").map(String::as_str) == Some("1") {
            tls["allowInsecure"] = json!(true);
        }
        Ok(json!({
            "tag": tag, "protocol": "hysteria",
            "settings": {"version": 2, "address": address, "port": l.port},
            "streamSettings": {
                "network": "hysteria", "security": "tls", "tlsSettings": tls,
                "hysteriaSettings": {"version": 2, "auth": l.user_info},
            },
        }))
    } else if let Some(json) = raw.strip_prefix(JSON_PREFIX) {
        let mut outbound = parse_json_object(json)?;
        pin_json_outbound(&mut outbound, address, tag);
        Ok(outbound)
    } else {
        Err(ParseError::Unsupported)
    }
}

fn pin_json_outbound(outbound: &mut Value, address: &str, tag: &str) {
    let map = outbound.as_object_mut().expect("checked to be an object");
    map.remove(NAME_KEY);
    map.insert("tag".into(), json!(tag));
    let is_wireguard = map.get("protocol").and_then(Value::as_str) == Some("wireguard");
    // optJSONObject("settings") ?: create one
    if !map.get("settings").is_some_and(Value::is_object) {
        map.insert("settings".into(), Value::Object(Map::new()));
    }
    let settings = map.get_mut("settings").and_then(Value::as_object_mut).expect("just ensured");
    for list in ["vnext", "servers"] {
        if let Some(first) = settings.get_mut(list).and_then(Value::as_array_mut).and_then(|a| a.first_mut()).and_then(Value::as_object_mut)
        {
            first.insert("address".into(), json!(address));
        }
    }
    if is_wireguard {
        // `address` here is the tunnel's own address list; the server is the first peer's endpoint.
        if let Some(peer) = settings.get_mut("peers").and_then(Value::as_array_mut).and_then(|a| a.first_mut()).and_then(Value::as_object_mut)
        {
            let current = match peer.get("endpoint") {
                Some(Value::String(s)) => s.clone(),
                None | Some(Value::Null) => String::new(),
                Some(other) => other.to_string(),
            };
            let port = host_and_port(&current).map_or(51820, |p| p.1);
            let endpoint = if address.contains(':') { format!("[{address}]:{port}") } else { format!("{address}:{port}") };
            peer.insert("endpoint".into(), json!(endpoint));
        }
    } else if settings.contains_key("address") {
        settings.insert("address".into(), json!(address));
    }
}

/// A server from one entry of an Xray JSON subscription (the first outbound that is a real proxy).
pub fn from_xray_outbound(outbound: &Value, name: &str, original: Option<&str>) -> Option<Node> {
    let protocol = opt_string(outbound, "protocol");
    if !JSON_PROTOCOLS.contains(&protocol.as_str()) {
        return None;
    }
    let settings = outbound.get("settings").filter(|s| s.is_object())?;
    let object_at = |list: &str| settings.get(list).and_then(Value::as_array).and_then(|a| a.first()).filter(|v| v.is_object());
    if protocol == "wireguard" {
        let endpoint = object_at("peers").map(|p| opt_string(p, "endpoint")).unwrap_or_default();
        let (host, port) = host_and_port(&endpoint)?;
        return Some(Node::new(&original.map_or_else(|| stored_outbound(outbound, name), String::from), &protocol, name, &host, port));
    }
    let target = object_at("vnext").or_else(|| object_at("servers")).unwrap_or(settings);
    let host = opt_string(target, "address");
    if is_blank(&host) {
        return None;
    }
    let port = opt_int(target, "port", 0);
    let raw = original.map_or_else(|| stored_outbound(outbound, name), String::from);
    Some(Node::new(&raw, if protocol == "shadowsocks" { "ss" } else { &protocol }, name, &host, port))
}

fn stored_outbound(outbound: &Value, name: &str) -> String {
    let mut copy = outbound.clone();
    if !is_blank(name) {
        copy[NAME_KEY] = json!(name);
    }
    format!("{JSON_PREFIX}{copy}")
}

/// Servers from an Xray JSON subscription: an array of configs, or one config, each with `remarks` and `outbounds`.
pub fn from_xray_json(text: &str) -> Result<Vec<Node>, ParseError> {
    let value: Value = serde_json::from_str(text).map_err(|_| ParseError::Invalid("bad JSON"))?;
    let entries: Vec<Value> = if text.trim_start().starts_with('[') {
        value.as_array().ok_or(ParseError::Invalid("bad JSON"))?.iter().filter(|e| e.is_object()).cloned().collect()
    } else if value.is_object() {
        vec![value]
    } else {
        return Err(ParseError::Invalid("bad JSON"));
    };
    let mut nodes = Vec::new();
    for entry in &entries {
        // A bare outbound ({"protocol": "vless", "settings": …}) is a server on its own.
        if entry.get("protocol").is_some() && entry.get("outbounds").is_none() {
            nodes.extend(from_xray_outbound(entry, &opt_string(entry, "tag"), None));
            continue;
        }
        let Some(outbounds) = entry.get("outbounds").and_then(Value::as_array) else { continue };
        let mut name = opt_string(entry, "remarks");
        for key in ["remark", "ps"] {
            if is_blank(&name) {
                name = opt_string(entry, key);
            }
        }
        for outbound in outbounds {
            // getJSONObject throws for anything else, which fails the whole subscription
            if !outbound.is_object() {
                return Err(ParseError::Invalid("bad outbound"));
            }
            if let Some(node) = from_xray_outbound(outbound, &name, None) {
                nodes.push(node);
                break;
            }
        }
    }
    Ok(nodes)
}

struct Link {
    user_info: String,
    host: String,
    port: i32,
    query: Query,
    name: String,
}

fn split(raw: &str, scheme: &str) -> Result<Link, ParseError> {
    let mut rest = raw.strip_prefix(&format!("{scheme}://")).unwrap_or(raw);
    let name = url_decode(after(rest, '#').unwrap_or(""))?;
    rest = before(rest, '#');
    let query_text = after(rest, '?').unwrap_or("");
    rest = before(rest, '?');
    let at = rest.rfind('@').ok_or(ParseError::Invalid("no user info"))?;
    let user_info = url_decode(&rest[..at])?;
    let host_port = rest[at + 1..].trim_end_matches('/');
    let bad_port = || ParseError::Invalid("bad port");
    let (host, port) = if let Some(inner) = host_port.strip_prefix('[') {
        let end = inner.find(']').ok_or(ParseError::Invalid("bad host"))?;
        let port = after_marker(host_port, "]:").parse::<i32>().map_err(|_| bad_port())?;
        (inner[..end].to_string(), port)
    } else {
        (before_last(host_port, ':').to_string(), after_last(host_port, ':').parse::<i32>().map_err(|_| bad_port())?)
    };
    let mut query = Query::new();
    for part in query_text.split('&').filter(|p| !p.is_empty()) {
        query.insert(url_decode(before(part, '='))?, url_decode(after(part, '=').unwrap_or(""))?);
    }
    Ok(Link { user_info, host, port, query, name })
}

/// `substringAfter(marker)`: the whole text when the marker is missing.
fn after_marker<'a>(s: &'a str, marker: &str) -> &'a str {
    s.split_once(marker).map_or(s, |p| p.1)
}

fn link(raw: &str, scheme: &str) -> Result<Node, ParseError> {
    let l = split(raw, scheme)?;
    Ok(Node::new(raw, scheme, &l.name, &l.host, l.port))
}

fn hysteria2(raw: &str, scheme: &str) -> Result<Node, ParseError> {
    let l = split(raw, scheme)?;
    // Obfuscated servers need a transport Xray does not have.
    if non_blank(&l.query, "obfs").is_some() {
        return Err(ParseError::Unsupported);
    }
    Ok(Node::new(raw, "hysteria2", &l.name, &l.host, l.port))
}

fn vmess_json(raw: &str) -> Result<Value, ParseError> {
    let payload = before(raw.strip_prefix("vmess://").unwrap_or(raw), '#');
    parse_json_object(&decode_base64_text(payload)?)
}

fn vmess(raw: &str) -> Result<Node, ParseError> {
    let j = vmess_json(raw)?;
    // getString("add"): the key must exist
    let host = match j.get("add") {
        None | Some(Value::Null) => return Err(ParseError::Invalid("no address")),
        Some(_) => opt_string(&j, "add"),
    };
    let port: i32 = opt_string(&j, "port").parse().map_err(|_| ParseError::Invalid("bad port"))?;
    Ok(Node::new(raw, "vmess", &opt_string(&j, "ps"), &host, port))
}

struct Ss {
    method: String,
    password: String,
    host: String,
    port: i32,
    name: String,
}

fn ss(raw: &str) -> Result<Ss, ParseError> {
    let mut rest = raw.strip_prefix("ss://").unwrap_or(raw);
    let name = url_decode(after(rest, '#').unwrap_or(""))?;
    rest = before(rest, '#');
    if rest.contains("plugin=") {
        return Err(ParseError::Invalid("plugins are not supported"));
    }
    rest = before(rest, '?');
    let (method, password, host_port);
    if rest.contains('@') {
        let user_info = before_last(rest, '@');
        let plain = match decode_base64_text(user_info) {
            Ok(text) if text.contains(':') => text,
            _ => url_decode(user_info)?,
        };
        method = before(&plain, ':').to_string();
        password = after(&plain, ':').unwrap_or(&plain).to_string();
        host_port = after_last(rest, '@').trim_end_matches('/').to_string();
    } else {
        let plain = decode_base64_text(rest)?;
        method = before(&plain, ':').to_string();
        password = before_last(after(&plain, ':').unwrap_or(&plain), '@').to_string();
        host_port = after_last(&plain, '@').to_string();
    }
    let host = before_last(&host_port, ':').trim_matches(['[', ']']).to_string();
    let port = after_last(&host_port, ':').parse::<i32>().map_err(|_| ParseError::Invalid("bad port"))?;
    Ok(Ss { method, password, host, port, name })
}

fn stream(query: &Query, default_security: &str, server_host: &str) -> Value {
    let network = match query.get("type").map_or("tcp", String::as_str) {
        "h2" => "http",
        "splithttp" => "xhttp",
        other => other,
    };
    let security = query.get("security").map_or(default_security, String::as_str);
    let mut s = json!({"network": network, "security": security});
    let sni = non_blank(query, "sni").or_else(|| non_blank(query, "host")).unwrap_or(server_host);
    let fingerprint = non_blank(query, "fp").unwrap_or("chrome");
    let insecure = query.get("allowInsecure").map(String::as_str) == Some("1") || query.get("insecure").map(String::as_str) == Some("1");
    match security {
        "tls" => {
            let mut tls = json!({"serverName": sni, "fingerprint": fingerprint});
            if let Some(alpn) = non_blank(query, "alpn") {
                tls["alpn"] = json!(alpn.split(',').collect::<Vec<_>>());
            }
            if insecure {
                tls["allowInsecure"] = json!(true);
            }
            s["tlsSettings"] = tls;
        }
        "reality" => {
            let get = |k: &str| query.get(k).map_or("", String::as_str);
            s["realitySettings"] = json!({
                "serverName": sni, "fingerprint": fingerprint,
                "publicKey": get("pbk"), "shortId": get("sid"), "spiderX": get("spx"),
            });
        }
        _ => {}
    }
    let host = non_blank(query, "host");
    let path = non_blank(query, "path").unwrap_or("/");
    match network {
        "tcp" => {
            if query.get("headerType").map(String::as_str) == Some("http") {
                s["tcpSettings"] = json!({"header": {"type": "http", "request": {
                    "path": [path],
                    "headers": {"Host": [host.unwrap_or(server_host)]},
                }}});
            }
        }
        "ws" => {
            let mut ws = json!({"path": path});
            if let Some(h) = host {
                ws["headers"] = json!({"Host": h});
            }
            s["wsSettings"] = ws;
        }
        "grpc" => {
            s["grpcSettings"] = json!({
                "serviceName": query.get("serviceName").map_or("", String::as_str),
                "multiMode": query.get("mode").map(String::as_str) == Some("multi"),
            });
        }
        "http" => {
            let mut http = json!({"path": path});
            if let Some(h) = host {
                http["host"] = json!(h.split(',').collect::<Vec<_>>());
            }
            s["httpSettings"] = http;
        }
        "httpupgrade" => {
            let mut v = json!({"path": path});
            if let Some(h) = host {
                v["host"] = json!(h);
            }
            s["httpupgradeSettings"] = v;
        }
        "xhttp" => {
            let mut v = json!({"path": path, "mode": query.get("mode").map_or("auto", String::as_str)});
            if let Some(h) = host {
                v["host"] = json!(h);
            }
            s["xhttpSettings"] = v;
        }
        _ => {}
    }
    s
}
