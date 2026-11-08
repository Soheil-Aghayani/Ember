//! An OpenVPN `.ovpn` profile as Ember keeps it: one self-contained text (certificates and keys inside), cleaned of the
//! options that would run programs on the computer, because OpenVPN is started with administrator rights.
//! Port of `OpenVpnProfile.kt`.

use std::collections::HashMap;

use crate::error::{file_error, ParseError};
use crate::node::Node;
use crate::util::{b64, decode_base64_text, is_re_space, kt_lines};

/// Stored form of a server: `ovpn:<name, base64>:<profile, base64>`.
pub const PREFIX: &str = "ovpn:";

/// Options that load code or run programs, take over OpenVPN's own control channel or logging, or pull in other files.
const FORBIDDEN: &[&str] = &[
    "plugin", "up", "down", "route-up", "route-pre-down", "ipchange", "learn-address", "client-connect", "client-disconnect",
    "tls-verify", "auth-user-pass-verify", "up-restart", "down-pre", "script-security", "log", "log-append", "daemon", "service",
    "writepid", "config", "cd", "chroot", "syslog", "askpass", "pkcs11-providers", "engine", "management",
];

/// `directive file` options whose file is moved inside the profile as `<directive>…</directive>`.
const FILE_OPTIONS: &[&str] = &["ca", "cert", "key", "dh", "tls-auth", "tls-crypt", "tls-crypt-v2", "crl-verify", "extra-certs"];

fn is_forbidden(directive: &str) -> bool {
    FORBIDDEN.contains(&directive)
}

fn tokens(trimmed: &str) -> Vec<&str> {
    trimmed.split(is_re_space).filter(|t| !t.is_empty()).collect()
}

/// `<name>` opening a block (not a closing tag): the lower-cased name.
fn block_open(trimmed: &str) -> Option<String> {
    (trimmed.starts_with('<') && trimmed.ends_with('>') && !trimmed.starts_with("</"))
        .then(|| trimmed.trim_matches(['<', '>']).to_lowercase())
}

fn is_comment(trimmed: &str) -> bool {
    trimmed.is_empty() || trimmed.starts_with('#') || trimmed.starts_with(';')
}

/// Returns `text` cleaned for use. Files the profile points to are read with `read_file` (given the name as written)
/// and put inside; a missing file is an error that names it.
pub fn prepare(text: &str, read_file: impl Fn(&str) -> Option<String>) -> Result<String, ParseError> {
    let mut out = String::new();
    let mut block: Option<String> = None;
    let mut dropping = false;
    for line in kt_lines(text.strip_prefix('\u{FEFF}').unwrap_or(text)) {
        let trimmed = line.trim();
        if let Some(open) = &block {
            if !dropping {
                out.push_str(line);
                out.push('\n');
            }
            if trimmed.eq_ignore_ascii_case(&format!("</{open}>")) {
                block = None;
                dropping = false;
            }
            continue;
        }
        if let Some(name) = block_open(trimmed) {
            dropping = is_forbidden(&name);
            if dropping {
                out.push_str(&format!("# removed <{name}>\n"));
            } else {
                out.push_str(line);
                out.push('\n');
            }
            block = Some(name);
            continue;
        }
        if is_comment(trimmed) {
            out.push_str(line);
            out.push('\n');
            continue;
        }
        let toks = tokens(trimmed);
        let directive = toks[0].strip_prefix("--").unwrap_or(toks[0]).to_lowercase();
        if is_forbidden(&directive) || directive.starts_with("management") {
            out.push_str(&format!("# removed: {directive}\n"));
        } else if directive == "pkcs12" {
            return file_error("The OpenVPN profile uses a .p12 certificate bundle, which Ember cannot read yet. Export the certificate and key as text (.crt and .key) or ask your provider for an embedded profile.");
        } else if directive == "auth-user-pass" {
            // Credentials are asked for when connecting; a file of them next to the profile is not read.
            out.push_str("auth-user-pass\n");
        } else if FILE_OPTIONS.contains(&directive.as_str()) && toks.len() >= 2 && toks[1] != "[inline]" {
            let name = toks[1].trim_matches(['"', '\'']);
            let Some(content) = read_file(name).map(|c| c.trim().to_string()) else {
                return file_error(format!("The OpenVPN profile uses the file \"{name}\". Add the profile with the file button so Ember can read the files next to it."));
            };
            out.push_str(&format!("<{directive}>\n{content}\n</{directive}>\n"));
            if directive == "tls-auth" && toks.len() >= 3 {
                out.push_str(&format!("key-direction {}\n", toks[2]));
            }
        } else {
            out.push_str(line);
            out.push('\n');
        }
    }
    Ok(format!("{}\n", out.trim_end()))
}

/// Directives of the profile (name lower-cased, then its arguments), leaving out comments and certificate blocks.
fn directives(profile: &str) -> Vec<Vec<String>> {
    let mut block: Option<String> = None;
    let mut result = Vec::new();
    for line in kt_lines(profile) {
        let trimmed = line.trim();
        if let Some(open) = &block {
            if trimmed.eq_ignore_ascii_case(&format!("</{open}>")) {
                block = None;
            }
        } else if let Some(name) = block_open(trimmed) {
            block = Some(name);
        } else if !is_comment(trimmed) {
            let toks = tokens(trimmed);
            let mut d = vec![toks[0].strip_prefix("--").unwrap_or(toks[0]).to_lowercase()];
            d.extend(toks[1..].iter().map(|t| t.to_string()));
            result.push(d);
        }
    }
    result
}

/// The server names or addresses of every `remote` line.
pub fn remotes(profile: &str) -> Vec<String> {
    let mut seen: Vec<String> = Vec::new();
    for d in directives(profile) {
        if d[0] == "remote" && d.len() >= 2 && !seen.contains(&d[1]) {
            seen.push(d[1].clone());
        }
    }
    seen
}

/// `profile` with each `remote` host replaced by the address in `addresses`. Used while the kill switch is on, which
/// only lets those addresses through, so OpenVPN must not need a name lookup of its own.
pub fn pin_remotes(profile: &str, addresses: &HashMap<String, String>) -> String {
    let mut block: Option<String> = None;
    let mut lines = Vec::new();
    for line in kt_lines(profile) {
        let trimmed = line.trim();
        if let Some(open) = &block {
            if trimmed.eq_ignore_ascii_case(&format!("</{open}>")) {
                block = None;
            }
            lines.push(line.to_string());
        } else if let Some(name) = block_open(trimmed) {
            block = Some(name);
            lines.push(line.to_string());
        } else {
            let toks = tokens(trimmed);
            let ip = (toks.len() >= 2 && toks[0].eq_ignore_ascii_case("remote")).then(|| addresses.get(toks[1])).flatten();
            match ip {
                Some(ip) => {
                    let mut parts = vec!["remote", ip.as_str()];
                    parts.extend(&toks[2..]);
                    lines.push(parts.join(" "));
                }
                None => lines.push(line.to_string()),
            }
        }
    }
    lines.join("\n")
}

/// True when connecting needs a user name and password from the user.
pub fn needs_credentials(profile: &str) -> bool {
    directives(profile).iter().any(|d| d[0] == "auth-user-pass")
}

/// Host and port of the first `remote` line (port from the line, else a `port` line, else 1194).
fn first_remote(profile: &str) -> Option<(String, i32)> {
    let lines = directives(profile);
    let remote = lines.iter().find(|d| d[0] == "remote" && d.len() >= 2)?;
    let port = remote
        .get(2)
        .and_then(|p| p.parse::<i32>().ok())
        .or_else(|| lines.iter().find(|d| d[0] == "port").and_then(|d| d.get(1)).and_then(|p| p.parse::<i32>().ok()))
        .unwrap_or(1194);
    Some((remote[1].clone(), port))
}

/// The server for `text` (already [`prepare`]d): its first `remote` line names the host and port. `name_hint` (the
/// file name) names it.
pub fn node(text: &str, name_hint: &str) -> Result<Node, ParseError> {
    let profile = prepare(text, |_| None)?;
    let Some((host, port)) = first_remote(&profile) else { return file_error("The OpenVPN profile has no remote server line.") };
    let name = if name_hint.trim().is_empty() { host.clone() } else { name_hint.to_string() };
    Ok(Node::new(&encode(&name, &profile), "openvpn", &name, &host, port))
}

pub fn encode(name: &str, profile: &str) -> String {
    format!("{PREFIX}{}:{}", b64(name), b64(profile))
}

/// Rebuilds a stored server (same raw text, so the same id).
pub fn from_raw(raw: &str) -> Option<Node> {
    let (name, profile) = decode(raw)?;
    let (host, port) = first_remote(&profile)?;
    Some(Node::new(raw, "openvpn", &name, &host, port))
}

/// The name and profile text inside a stored server.
pub fn decode(raw: &str) -> Option<(String, String)> {
    let mut parts = raw.strip_prefix(PREFIX).unwrap_or(raw).split(':');
    let name = decode_base64_text(parts.next()?).ok()?;
    let profile = decode_base64_text(parts.next()?).ok()?;
    Some((name, profile))
}
