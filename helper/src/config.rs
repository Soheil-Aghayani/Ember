use crate::{
    ip::{ipv4, Cidr},
    Result,
};
use serde::Deserialize;
use serde_json::Value;
use std::{
    fs,
    net::Ipv4Addr,
    path::{Path, PathBuf},
};

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct StateConfig {
    pub state_dir: PathBuf,
}

impl StateConfig {
    pub fn parse(value: &Value) -> Result<Self> {
        let state: Self = serde_json::from_value(value.clone()).map_err(|e| e.to_string())?;
        if !state.state_dir.is_dir() {
            return Err("stateDir is not a folder".into());
        }
        Ok(state)
    }
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct RawUpConfig {
    adapter_name: String,
    tun_address: String,
    #[serde(default)]
    tun_address6: Option<String>,
    mapdns_address: String,
    hev_config_path: PathBuf,
    edge_ips: Vec<String>,
    #[serde(default)]
    exclude_cidrs: Value,
    kill_switch: bool,
    parent_pid: i32,
}

/// What a kill switch on its own needs: no tunnel of ours, only the addresses that may leave the real network.
#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct RawGuardConfig {
    edge_ips: Vec<String>,
    #[serde(default)]
    exclude_cidrs: Value,
    parent_pid: i32,
}

pub struct GuardConfig {
    pub edge_ips: Vec<Ipv4Addr>,
    pub exclusions: Vec<Cidr>,
    pub parent_pid: i32,
}

impl GuardConfig {
    /// The config asks for a kill switch only (used while another program, such as OpenVPN, carries the traffic).
    pub fn requested(value: &Value) -> bool {
        value.get("guardOnly").and_then(Value::as_bool) == Some(true)
    }

    pub fn parse(value: &Value, log: impl FnMut(&str)) -> Result<Self> {
        let raw: RawGuardConfig = serde_json::from_value(value.clone()).map_err(|e| e.to_string())?;
        let edge_ips = raw
            .edge_ips
            .iter()
            .map(|s| ipv4(s).ok_or_else(|| format!("invalid address in config: {s}")))
            .collect::<Result<Vec<_>>>()?;
        if edge_ips.is_empty() {
            return Err("the kill switch needs at least one server address".into());
        }
        Ok(Self {
            edge_ips,
            exclusions: exclusions(&raw.exclude_cidrs, log),
            parent_pid: raw.parent_pid,
        })
    }
}

/// Adapters that a VPN program or tunnel makes (they carry the protected traffic and must not be blocked).
pub fn tunnel_like(name: &str) -> bool {
    let name = name.to_lowercase();
    ["openvpn", "wintun", "tap-", "wireguard", "ember", "sing-tun", "tun"]
        .iter()
        .any(|part| name.contains(part))
}

pub struct UpConfig {
    pub adapter_name: String,
    pub tun_address: Ipv4Addr,
    /// Set when the tunnel should also carry IPv6.
    pub tun_address6: Option<std::net::Ipv6Addr>,
    pub mapdns_address: Ipv4Addr,
    pub hev_config_path: PathBuf,
    pub edge_ips: Vec<Ipv4Addr>,
    pub exclusions: Vec<Cidr>,
    pub kill_switch: bool,
    pub parent_pid: i32,
}

pub fn read(path: &Path) -> Result<Value> {
    // PowerShell accepts UTF-8 files with a BOM, as well as its own ASCII journal.
    let text = fs::read_to_string(path).map_err(|e| e.to_string())?;
    serde_json::from_str(text.trim_start_matches('\u{feff}')).map_err(|e| e.to_string())
}

pub fn valid_adapter_name(name: &str) -> bool {
    (1..=32).contains(&name.len())
        && name
            .bytes()
            .all(|b| b.is_ascii_alphanumeric() || b == b'_' || b == b'-')
}

pub fn exclusions(value: &Value, mut log: impl FnMut(&str)) -> Vec<Cidr> {
    let entries = match value {
        Value::Null => return Vec::new(),
        Value::Array(entries) => entries.clone(),
        other => vec![other.clone()],
    };
    entries
        .iter()
        .filter_map(|v| {
            let text = v
                .as_str()
                .map(str::to_owned)
                .unwrap_or_else(|| v.to_string());
            let cidr = Cidr::parse(&text, 8, true);
            if cidr.is_none() {
                log(&format!("ignored invalid exclusion: {text}"));
            }
            cidr
        })
        .collect()
}

impl UpConfig {
    pub fn parse(value: &Value, state: &Path, log: impl FnMut(&str)) -> Result<Self> {
        let raw: RawUpConfig = serde_json::from_value(value.clone()).map_err(|e| e.to_string())?;
        if !valid_adapter_name(&raw.adapter_name) {
            return Err("invalid adapterName".into());
        }
        let exclusions = exclusions(&raw.exclude_cidrs, log);
        let parse_ip = |s: &str| ipv4(s).ok_or_else(|| format!("invalid address in config: {s}"));
        let tun_address = parse_ip(&raw.tun_address)?;
        let mapdns_address = parse_ip(&raw.mapdns_address)?;
        let edge_ips = raw
            .edge_ips
            .iter()
            .map(|s| parse_ip(s))
            .collect::<Result<Vec<_>>>()?;
        if !raw.hev_config_path.is_file() {
            return Err("hev config not found".into());
        }
        let state = state.canonicalize().map_err(|e| e.to_string())?;
        let hev_config_path = raw
            .hev_config_path
            .canonicalize()
            .map_err(|e| e.to_string())?;
        // Resolve junctions/symlinks too, so data-folder checks cannot be escaped.
        if hev_config_path.parent() != Some(state.as_path())
            && hev_config_path.parent() != state.parent()
        {
            return Err("hev config is outside the Ember data folder".into());
        }
        Ok(Self {
            adapter_name: raw.adapter_name,
            tun_address,
            tun_address6: raw.tun_address6.as_deref().and_then(|s| s.parse().ok()),
            mapdns_address,
            hev_config_path,
            edge_ips,
            exclusions,
            kill_switch: raw.kill_switch,
            parent_pid: raw.parent_pid,
        })
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    #[test]
    fn adapter_allowlist() {
        for s in ["Ember", "tun_1-test", &"a".repeat(32)] {
            assert!(valid_adapter_name(s));
        }
        for s in ["", "Ember VPN", "a/b", "x\n", "é", &"a".repeat(33)] {
            assert!(!valid_adapter_name(s));
        }
    }

    #[test]
    fn invalid_exclusions_are_logged_and_skipped() {
        let mut logs = Vec::new();
        let routes = exclusions(
            &json!([
                "23.235.42.11/8",
                "1.2.3.4",
                "0.0.0.0/0",
                "999.1.2.3",
                "::1",
                42
            ]),
            |s| logs.push(s.to_owned()),
        );
        assert_eq!(
            routes.iter().map(ToString::to_string).collect::<Vec<_>>(),
            ["23.0.0.0/8", "1.2.3.4/32"]
        );
        assert_eq!(logs.len(), 4);
        assert_eq!(logs[0], "ignored invalid exclusion: 0.0.0.0/0");
        assert!(exclusions(&Value::Null, |_| panic!()).is_empty());
    }

    #[test]
    fn a_guard_config_needs_a_server_address_and_ignores_bad_exclusions() {
        let ok = json!({"guardOnly": true, "edgeIps": ["203.0.113.7"], "excludeCidrs": ["0.0.0.0/0", "10.1.2.3/24"], "parentPid": 7});
        assert!(GuardConfig::requested(&ok));
        let parsed = GuardConfig::parse(&ok, |_| {}).unwrap();
        assert_eq!(parsed.edge_ips[0].to_string(), "203.0.113.7");
        assert_eq!(parsed.exclusions.len(), 1);
        assert!(!GuardConfig::requested(&json!({"edgeIps": ["203.0.113.7"]})));
        assert!(GuardConfig::parse(&json!({"edgeIps": [], "parentPid": 7}), |_| {}).is_err());
        assert!(GuardConfig::parse(&json!({"edgeIps": ["::1"], "parentPid": 7}), |_| {}).is_err());
    }

    #[test]
    fn tunnel_adapters_are_recognised_by_name() {
        for name in ["OpenVPN Wintun", "OpenVPN TAP-Windows6", "EmberTun", "EmberApps", "WireGuard Tunnel"] {
            assert!(tunnel_like(name), "{name}");
        }
        for name in ["Ethernet", "Ethernet 2", "Wi-Fi", "Local Area Connection* 2", "Bluetooth Network Connection"] {
            assert!(!tunnel_like(name), "{name}");
        }
    }

    #[test]
    fn restore_needs_only_state_dir() {
        let dir = std::env::current_dir().unwrap();
        assert_eq!(
            StateConfig::parse(&json!({"stateDir": dir}))
                .unwrap()
                .state_dir,
            dir
        );
        assert!(StateConfig::parse(&json!({"stateDir": dir.join("not-a-state-folder")})).is_err());
    }

    #[test]
    fn up_validates_addresses_and_restricts_config_to_the_data_folder() {
        // Fixtures stay inside this crate, including when tests run from another cwd.
        let root = PathBuf::from(env!("CARGO_MANIFEST_DIR"))
            .join("target")
            .join(format!(
                "config-test-{}-{}",
                std::process::id(),
                std::time::SystemTime::now()
                    .duration_since(std::time::UNIX_EPOCH)
                    .unwrap()
                    .as_nanos()
            ));
        let state = root.join("state");
        let outside = root.join("outside");
        fs::create_dir_all(&state).unwrap();
        fs::create_dir(&outside).unwrap();
        let local = state.join("hev.yml");
        let parent = root.join("hev.yml");
        let unrelated = outside.join("hev.yml");
        for file in [&local, &parent, &unrelated] {
            fs::write(file, "test config").unwrap();
        }
        let mut value = json!({
            "adapterName": "Ember", "tunAddress": "198.18.0.1", "mapdnsAddress": "198.18.0.2",
            "hevConfigPath": local, "edgeIps": ["23.235.42.11"], "parentPid": 1234,
            "killSwitch": true, "excludeCidrs": ["192.168.8.7/24"]
        });
        let parsed = UpConfig::parse(&value, &state, |_| panic!()).unwrap();
        assert_eq!(parsed.exclusions[0].to_string(), "192.168.8.0/24");
        value["hevConfigPath"] = json!(parent);
        assert!(UpConfig::parse(&value, &state, |_| panic!()).is_ok());
        value["hevConfigPath"] = json!(unrelated);
        assert_eq!(
            UpConfig::parse(&value, &state, |_| {}).err().unwrap(),
            "hev config is outside the Ember data folder"
        );
        value["hevConfigPath"] = json!(root.join("missing.yml"));
        assert_eq!(
            UpConfig::parse(&value, &state, |_| {}).err().unwrap(),
            "hev config not found"
        );
        value["hevConfigPath"] = json!(local);
        for field in ["tunAddress", "mapdnsAddress"] {
            let original = value[field].clone();
            value[field] = json!("256.1.2.3");
            assert_eq!(
                UpConfig::parse(&value, &state, |_| {}).err().unwrap(),
                "invalid address in config: 256.1.2.3"
            );
            value[field] = original;
        }
        value["edgeIps"] = json!(["1.2.3.4", "::1"]);
        assert_eq!(
            UpConfig::parse(&value, &state, |_| {}).err().unwrap(),
            "invalid address in config: ::1"
        );
        for file in [&local, &parent, &unrelated] {
            fs::remove_file(file).unwrap();
        }
        for dir in [&state, &outside, &root] {
            fs::remove_dir(dir).unwrap();
        }
    }
}
