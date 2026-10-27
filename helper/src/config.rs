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
    mapdns_address: String,
    hev_config_path: PathBuf,
    edge_ips: Vec<String>,
    #[serde(default)]
    exclude_cidrs: Value,
    kill_switch: bool,
    parent_pid: i32,
}

pub struct UpConfig {
    pub adapter_name: String,
    pub tun_address: Ipv4Addr,
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
