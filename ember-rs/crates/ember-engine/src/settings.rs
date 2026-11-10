//! The per-user settings file (`settings.properties`), in the Java `.properties` format so the Rust and Kotlin apps read
//! and write the same file. Port of `DesktopSettings.kt`; the keys, defaults and clean-up rules are the same.

use std::collections::BTreeMap;
use std::path::{Path, PathBuf};
use std::sync::Mutex;

pub const MTU_RANGE: std::ops::RangeInclusive<i32> = 1200..=1500;
pub const TCP_BUFFER_RANGE: std::ops::RangeInclusive<i32> = 65_536..=4_194_304;
pub const DEFAULT_TCP_BUFFER_BYTES: i32 = 524_288;
/// `HevSocks5TunnelConfig.TUN_MTU`.
pub const DEFAULT_MTU: i32 = 1400;

/// What the app rules do with the programs in the list.
pub mod app_rule_mode {
    pub const OFF: &str = "off";
    pub const SKIP: &str = "skip";
    pub const ONLY: &str = "only";
}

pub struct Settings {
    path: PathBuf,
    props: Mutex<BTreeMap<String, String>>,
    /// "fa" when Windows is set to Persian, else "en"; the language setting falls back to it.
    system_language: String,
}

impl Settings {
    /// Reads `path` if it exists. A missing or unreadable file means defaults.
    pub fn open(path: &Path, system_language: &str) -> Settings {
        let props = std::fs::read(path).map(|bytes| parse_properties(&latin1(&bytes))).unwrap_or_default();
        Settings { path: path.to_path_buf(), props: Mutex::new(props), system_language: system_language.to_string() }
    }

    fn lock(&self) -> std::sync::MutexGuard<'_, BTreeMap<String, String>> {
        self.props.lock().unwrap_or_else(|e| e.into_inner())
    }

    fn get(&self, key: &str) -> Option<String> {
        self.lock().get(key).cloned()
    }

    fn put(&self, key: &str, value: String) {
        let mut props = self.lock();
        props.insert(key.to_string(), value);
        // best effort, like the Kotlin app: a failed write must not stop the app
        let _ = std::fs::write(&self.path, store_properties(&props));
    }

    fn flag(&self, key: &str, default: bool) -> bool {
        match self.get(key) {
            Some(v) => v.eq_ignore_ascii_case("true"),
            None => default,
        }
    }

    fn list(&self, key: &str, separator: char) -> Vec<String> {
        self.get(key).unwrap_or_default().split(separator).map(|s| s.trim().to_string()).filter(|s| !s.is_empty()).collect()
    }

    pub fn keep_in_tray(&self) -> bool {
        self.flag("keepInTray", true)
    }
    pub fn set_keep_in_tray(&self, v: bool) {
        self.put("keepInTray", v.to_string());
    }

    /// A soft chime when the connection comes up or goes down.
    pub fn sounds(&self) -> bool {
        self.flag("sounds", true)
    }
    pub fn set_sounds(&self, v: bool) {
        self.put("sounds", v.to_string());
    }

    pub fn auto_connect(&self) -> bool {
        self.flag("autoConnect", false)
    }
    pub fn set_auto_connect(&self, v: bool) {
        self.put("autoConnect", v.to_string());
    }

    pub fn language(&self) -> String {
        match self.get("language") {
            Some(l) if l == "en" || l == "fa" => l,
            _ => if self.system_language == "fa" { "fa".into() } else { "en".into() },
        }
    }
    pub fn set_language(&self, v: &str) {
        self.put("language", if v == "fa" { "fa" } else { "en" }.to_string());
    }

    pub fn theme(&self) -> String {
        self.get("theme").unwrap_or_else(|| "System".into())
    }
    pub fn set_theme(&self, v: &str) {
        self.put("theme", v.to_string());
    }

    /// Sends the whole computer through the server; off means only programs that use the local proxy.
    pub fn whole_device(&self) -> bool {
        self.flag("wholeDevice", true)
    }
    pub fn set_whole_device(&self, v: bool) {
        self.put("wholeDevice", v.to_string());
    }

    pub fn mtu(&self) -> i32 {
        self.get("mtu").and_then(|v| v.trim().parse::<i32>().ok()).map(|v| v.clamp(*MTU_RANGE.start(), *MTU_RANGE.end())).unwrap_or(DEFAULT_MTU)
    }
    pub fn set_mtu(&self, v: i32) {
        self.put("mtu", v.clamp(*MTU_RANGE.start(), *MTU_RANGE.end()).to_string());
    }

    pub fn tcp_buffer_bytes(&self) -> i32 {
        self.get("tcpBufferBytes")
            .and_then(|v| v.trim().parse::<i32>().ok())
            .map(|v| v.clamp(*TCP_BUFFER_RANGE.start(), *TCP_BUFFER_RANGE.end()))
            .unwrap_or(DEFAULT_TCP_BUFFER_BYTES)
    }
    pub fn set_tcp_buffer_bytes(&self, v: i32) {
        self.put("tcpBufferBytes", v.clamp(*TCP_BUFFER_RANGE.start(), *TCP_BUFFER_RANGE.end()).to_string());
    }

    /// Addresses that skip the VPN in whole-device mode: IPv4 addresses, ranges (10.1.0.0/16) or domain names.
    pub fn excluded_addresses(&self) -> Vec<String> {
        self.list("excludedAddresses", ';')
    }
    pub fn set_excluded_addresses(&self, v: &[String]) {
        self.put("excludedAddresses", distinct(v.iter().map(|s| s.trim().to_string()).filter(|s| !s.is_empty() && !s.contains(';'))).join(";"));
    }

    pub fn app_rule_mode(&self) -> String {
        match self.get("appRuleMode") {
            Some(m) if m == app_rule_mode::SKIP || m == app_rule_mode::ONLY => m,
            _ => app_rule_mode::OFF.to_string(),
        }
    }
    pub fn set_app_rule_mode(&self, v: &str) {
        self.put("appRuleMode", v.to_string());
    }

    /// Program file names (firefox.exe) the app rules apply to.
    pub fn app_rule_apps(&self) -> Vec<String> {
        self.list("appRuleApps", ';')
    }
    pub fn set_app_rule_apps(&self, v: &[String]) {
        self.put(
            "appRuleApps",
            distinct(v.iter().map(|s| s.trim().to_string()).filter(|s| !s.is_empty() && !s.contains(';') && !s.contains('\\') && !s.contains('/'))).join(";"),
        );
    }

    pub fn auto_connect_untrusted(&self) -> bool {
        self.flag("autoConnectUntrusted", false)
    }
    pub fn set_auto_connect_untrusted(&self, v: bool) {
        self.put("autoConnectUntrusted", v.to_string());
    }

    /// Network ids where Ember does not connect by itself, for example the home Wi-Fi.
    pub fn trusted_networks(&self) -> Vec<String> {
        self.list("trustedNetworks", '\n')
    }
    pub fn set_trusted_networks(&self, v: &[String]) {
        self.put("trustedNetworks", distinct(v.iter().map(|s| s.trim().to_string()).filter(|s| !s.is_empty() && !s.contains('\n'))).join("\n"));
    }

    /// The server (`Node::id`) that Connect uses; `"auto"` for the fastest.
    pub fn selected_node_id(&self) -> String {
        self.get("selectedNodeId").unwrap_or_default()
    }
    pub fn set_selected_node_id(&self, v: &str) {
        self.put("selectedNodeId", v.to_string());
    }

    /// Servers the user starred; they sit at the top of every list.
    pub fn favorite_nodes(&self) -> std::collections::HashSet<String> {
        self.list("favoriteNodes", ';').into_iter().collect()
    }
    pub fn set_favorite_nodes(&self, v: &std::collections::HashSet<String>) {
        let mut ids: Vec<&String> = v.iter().filter(|s| !s.trim().is_empty() && !s.contains(';')).collect();
        ids.sort();
        self.put("favoriteNodes", ids.into_iter().cloned().collect::<Vec<_>>().join(";"));
    }

    /// Writes a detailed Xray log (it names the sites visited): only for tracking down a problem.
    pub fn diagnostic_log(&self) -> bool {
        self.flag("diagnosticLog", false)
    }
    pub fn set_diagnostic_log(&self, v: bool) {
        self.put("diagnosticLog", v.to_string());
    }

    pub fn ipv6(&self) -> bool {
        self.flag("ipv6", true)
    }
    pub fn set_ipv6(&self, v: bool) {
        self.put("ipv6", v.to_string());
    }

    /// True from a successful connect until the user disconnects: still true after a crash or a restart.
    pub fn was_connected(&self) -> bool {
        self.flag("wasConnected", false)
    }
    pub fn set_was_connected(&self, v: bool) {
        self.put("wasConnected", v.to_string());
    }

    pub fn admin_notice_seen(&self) -> bool {
        self.flag("adminNoticeSeen", false)
    }
    pub fn set_admin_notice_seen(&self, v: bool) {
        self.put("adminNoticeSeen", v.to_string());
    }

    /// Switch to the next-best server by itself when the connected one stops passing traffic.
    pub fn auto_failover(&self) -> bool {
        self.flag("autoFailover", true)
    }
    pub fn set_auto_failover(&self, v: bool) {
        self.put("autoFailover", v.to_string());
    }

    pub fn kill_switch(&self) -> bool {
        self.flag("killSwitch", true)
    }
    pub fn set_kill_switch(&self, v: bool) {
        self.put("killSwitch", v.to_string());
    }
}

fn distinct(items: impl Iterator<Item = String>) -> Vec<String> {
    let mut seen = std::collections::HashSet::new();
    items.filter(|s| seen.insert(s.clone())).collect()
}

// ---- Java .properties format ------------------------------------------------------------------------------------

/// `Properties.load(InputStream)` reads ISO-8859-1.
pub(crate) fn latin1(bytes: &[u8]) -> String {
    bytes.iter().map(|&b| b as char).collect()
}

fn is_ws(c: char) -> bool {
    c == ' ' || c == '\t' || c == '\u{c}'
}

/// Reads logical lines (a line ending in an odd number of backslashes continues on the next one).
fn logical_lines(text: &str) -> Vec<String> {
    let mut out = Vec::new();
    let mut current = String::new();
    let mut continuing = false;
    for physical in text.split('\n') {
        let physical = physical.strip_suffix('\r').unwrap_or(physical);
        let line = physical.trim_start_matches(is_ws); // a continuation line also loses its leading blanks
        let first = line.chars().next();
        if !continuing && (first.is_none() || first == Some('#') || first == Some('!')) {
            continue;
        }
        let backslashes = line.chars().rev().take_while(|&c| c == '\\').count();
        if backslashes % 2 == 1 {
            current.push_str(&line[..line.len() - 1]);
            continuing = true;
        } else {
            current.push_str(line);
            out.push(std::mem::take(&mut current));
            continuing = false;
        }
    }
    if continuing {
        out.push(current);
    }
    out
}

fn unescape(s: &str) -> String {
    let mut units: Vec<u16> = Vec::new();
    let mut chars = s.chars();
    while let Some(c) = chars.next() {
        if c != '\\' {
            let mut buf = [0u16; 2];
            units.extend_from_slice(c.encode_utf16(&mut buf));
            continue;
        }
        match chars.next() {
            Some('u') => {
                let hex: String = chars.by_ref().take(4).collect();
                units.push(u16::from_str_radix(&hex, 16).unwrap_or(0xFFFD));
            }
            Some('t') => units.push(b'\t' as u16),
            Some('n') => units.push(b'\n' as u16),
            Some('r') => units.push(b'\r' as u16),
            Some('f') => units.push(0x0c),
            Some(other) => {
                let mut buf = [0u16; 2];
                units.extend_from_slice(other.encode_utf16(&mut buf));
            }
            None => {}
        }
    }
    String::from_utf16_lossy(&units)
}

pub fn parse_properties(text: &str) -> BTreeMap<String, String> {
    let mut map = BTreeMap::new();
    for line in logical_lines(text) {
        let chars: Vec<char> = line.chars().collect();
        let mut i = 0;
        let mut escaped = false;
        while i < chars.len() {
            let c = chars[i];
            if !escaped && (is_ws(c) || c == '=' || c == ':') {
                break;
            }
            escaped = !escaped && c == '\\';
            i += 1;
        }
        let key: String = chars[..i].iter().collect();
        while i < chars.len() && is_ws(chars[i]) {
            i += 1;
        }
        if i < chars.len() && (chars[i] == '=' || chars[i] == ':') {
            i += 1;
        }
        while i < chars.len() && is_ws(chars[i]) {
            i += 1;
        }
        let value: String = chars[i..].iter().collect();
        map.insert(unescape(&key), unescape(&value));
    }
    map
}

fn escape(s: &str, is_key: bool) -> String {
    let mut out = String::new();
    for (index, c) in s.chars().enumerate() {
        match c {
            ' ' if is_key || index == 0 => out.push_str("\\ "),
            '\\' => out.push_str("\\\\"),
            '\t' => out.push_str("\\t"),
            '\n' => out.push_str("\\n"),
            '\r' => out.push_str("\\r"),
            '\u{c}' => out.push_str("\\f"),
            '=' | ':' | '#' | '!' => {
                out.push('\\');
                out.push(c);
            }
            c if (' '..='~').contains(&c) => out.push(c),
            c => {
                let mut buf = [0u16; 2];
                for unit in c.encode_utf16(&mut buf) {
                    out.push_str(&format!("\\u{unit:04X}"));
                }
            }
        }
    }
    out
}

pub fn store_properties(map: &BTreeMap<String, String>) -> String {
    let mut out = String::from("#Ember settings\n");
    for (key, value) in map {
        out.push_str(&escape(key, true));
        out.push('=');
        out.push_str(&escape(value, false));
        out.push('\n');
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    fn temp(name: &str) -> PathBuf {
        let dir = std::env::temp_dir().join(format!("ember-engine-test-{}-{name}", std::process::id()));
        let _ = std::fs::remove_dir_all(&dir);
        std::fs::create_dir_all(&dir).unwrap();
        dir.join("settings.properties")
    }

    #[test]
    fn reads_a_file_written_by_java() {
        // what java.util.Properties.store writes: a date comment, escaped separators and \uXXXX for non-Latin-1
        let text = "#Sun Nov 08 20:00:00 UTC 2026\nlanguage=fa\ntrustedNetworks=wifi\\:Home\\nwifi\\:\\u0645\\u0646\nselectedNodeId=auto\nwholeDevice=false\n";
        let map = parse_properties(text);
        assert_eq!(map["language"], "fa");
        assert_eq!(map["trustedNetworks"], "wifi:Home\nwifi:من");
        assert_eq!(map["wholeDevice"], "false");
    }

    #[test]
    fn accepts_other_separators_and_continuations() {
        let map = parse_properties("a : 1\nb 2\n! comment\nc=one\\\n   two\n");
        assert_eq!(map["a"], "1");
        assert_eq!(map["b"], "2");
        assert_eq!(map["c"], "onetwo");
    }

    #[test]
    fn round_trips_awkward_values() {
        let mut map = BTreeMap::new();
        map.insert("k e:y".to_string(), " lead=ing:#! back\\slash\tand\nnewline ünï 😀".to_string());
        let text = store_properties(&map);
        assert!(text.is_ascii());
        assert_eq!(parse_properties(&text), map);
    }

    #[test]
    fn typed_settings_have_the_kotlin_defaults_and_ranges() {
        let path = temp("defaults");
        let s = Settings::open(&path, "en");
        assert!(s.keep_in_tray() && s.whole_device() && s.ipv6() && s.auto_failover() && s.kill_switch());
        assert!(!s.auto_connect() && !s.auto_connect_untrusted() && !s.diagnostic_log() && !s.was_connected());
        assert_eq!((s.mtu(), s.tcp_buffer_bytes(), s.theme().as_str()), (1400, 524_288, "System"));
        s.set_mtu(9000);
        assert_eq!(s.mtu(), 1500);
        s.set_tcp_buffer_bytes(1);
        assert_eq!(s.tcp_buffer_bytes(), 65_536);
        assert_eq!(s.app_rule_mode(), "off");
        s.set_app_rule_mode("bogus");
        assert_eq!(s.app_rule_mode(), "off");
    }

    #[test]
    fn language_falls_back_to_the_system_language() {
        let path = temp("language");
        assert_eq!(Settings::open(&path, "fa").language(), "fa");
        assert_eq!(Settings::open(&path, "de").language(), "en");
        let s = Settings::open(&path, "fa");
        s.set_language("en");
        assert_eq!(Settings::open(&path, "fa").language(), "en");
    }

    #[test]
    fn lists_are_cleaned_and_persist() {
        let path = temp("lists");
        let s = Settings::open(&path, "en");
        s.set_excluded_addresses(&["10.0.0.0/8".into(), " a.example ".into(), "a.example".into(), "bad;one".into(), "".into()]);
        s.set_app_rule_apps(&["firefox.exe".into(), "C:\\x.exe".into(), "dir/y.exe".into(), "firefox.exe".into()]);
        s.set_trusted_networks(&["wifi:Home".into(), "wifi:Home".into(), "with\nnewline".into()]);
        let again = Settings::open(&path, "en");
        assert_eq!(again.excluded_addresses(), ["10.0.0.0/8", "a.example"]);
        assert_eq!(again.app_rule_apps(), ["firefox.exe"]);
        assert_eq!(again.trusted_networks(), ["wifi:Home"]);
    }
}
