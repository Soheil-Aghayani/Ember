//! Per-program rules: sing-box (shipped with Ember) takes over the computer's traffic through a tunnel adapter, knows
//! which program each connection belongs to, and sends what should use the VPN into Xray's local SOCKS5 port.

use std::path::{Path, PathBuf};
use std::thread::sleep;
use std::time::{Duration, Instant};

use serde_json::{json, Value};

use crate::{paths, proc, Error, Result};

const TASK: &str = r"\Ember\Apps";
pub const ADAPTER: &str = "EmberApps";

/// How the apps in the list are treated (the strings are stored in the settings file).
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum AppRuleMode {
    /// The apps in the list go out directly; everything else uses the VPN.
    Skip,
    /// Only the apps in the list use the VPN; everything else goes out directly.
    Only,
}

impl AppRuleMode {
    pub fn parse(text: &str) -> Option<Self> {
        match text {
            "skip" => Some(Self::Skip),
            "only" => Some(Self::Only),
            _ => None,
        }
    }
}

/// The sing-box settings. Xray's own traffic (and sing-box's) must leave directly, or it would loop into the tunnel.
pub fn build_rules(mode: AppRuleMode, apps: &[String], socks_port: u16, skip_cidrs: &[String], skip_domains: &[String], log_file: &Path) -> Value {
    let mut names: Vec<String> = Vec::new();
    for app in apps {
        for name in [app.clone(), app.to_lowercase()] {
            if !names.contains(&name) {
                names.push(name);
            }
        }
    }
    let apps_go_direct = mode == AppRuleMode::Skip;
    let route = |matcher: Value, outbound: &str| {
        let mut obj = matcher;
        obj["action"] = json!("route");
        obj["outbound"] = json!(outbound);
        obj
    };

    let mut rules = vec![
        json!({"action": "sniff"}),
        json!({"protocol": "dns", "action": "hijack-dns"}),
        route(json!({"process_name": ["xray.exe", "sing-box.exe"]}), "direct"),
        route(json!({"ip_is_private": true}), "direct"),
    ];
    if !skip_cidrs.is_empty() {
        rules.push(route(json!({"ip_cidr": skip_cidrs}), "direct"));
    }
    if !skip_domains.is_empty() {
        rules.push(route(json!({"domain": skip_domains}), "direct"));
    }
    if !names.is_empty() {
        rules.push(route(json!({"process_name": names}), if apps_go_direct { "direct" } else { "proxy" }));
    }

    // Names are looked up the way the program that asks would reach the internet: a program that goes out directly
    // also resolves directly, one that uses the VPN resolves through it.
    let mut dns_rules = Vec::new();
    if !names.is_empty() {
        dns_rules.push(json!({"process_name": names, "server": if apps_go_direct { "local" } else { "remote" }}));
    }

    json!({
        "log": {"level": "warn", "output": log_file.to_string_lossy(), "timestamp": true},
        "dns": {
            "servers": [
                {"type": "tcp", "tag": "remote", "server": "1.1.1.1", "detour": "proxy"},
                {"type": "local", "tag": "local"}
            ],
            "rules": dns_rules,
            "final": if apps_go_direct { "remote" } else { "local" }
        },
        "inbounds": [{
            "type": "tun", "tag": "tun-in", "interface_name": ADAPTER,
            "address": ["172.19.0.1/30"],
            "auto_route": true, "strict_route": true, "stack": "mixed"
        }],
        "outbounds": [
            {"type": "socks", "tag": "proxy", "server": "127.0.0.1", "server_port": socks_port, "version": "5"},
            {"type": "direct", "tag": "direct"}
        ],
        "route": {
            "rules": rules,
            "final": if apps_go_direct { "proxy" } else { "direct" },
            "auto_detect_interface": true,
            "default_domain_resolver": {"server": "local"}
        }
    })
}

/// Starts sing-box for per-program rules. It needs administrator rights to make its adapter, so a scheduled task (one
/// Windows prompt, once) starts it. When it stops, Windows removes its adapter, routes and firewall rules by itself.
pub struct AppRoutingRunner {
    resources_dir: Option<PathBuf>,
    dir: PathBuf,
    pub config_file: PathBuf,
    pub log_file: PathBuf,
}

impl AppRoutingRunner {
    pub fn new(data_dir: &Path) -> Self {
        let dir = data_dir.join("apps");
        Self { resources_dir: paths::resources_dir(), config_file: dir.join("config.json"), log_file: dir.join("sing-box.log"), dir }
    }

    pub fn exe(&self) -> Option<PathBuf> {
        paths::find_file(&self.resources_dir.as_ref()?.join("tunnel"), "sing-box.exe")
    }

    pub fn is_available(&self) -> bool {
        self.exe().is_some()
    }

    /// True when the task exists and starts the sing-box this installation ships.
    pub fn task_ready(&self, exe: &Path) -> bool {
        let (_, xml) = proc::run15("schtasks.exe", &["/query", "/tn", TASK, "/xml"]);
        task_xml_ready(&xml, exe, &self.config_file)
    }

    pub fn running(&self) -> bool {
        proc::run15("tasklist.exe", &["/fi", "imagename eq sing-box.exe", "/fo", "csv", "/nh"]).1.to_lowercase().contains("sing-box.exe")
    }

    fn adapter_exists(&self) -> bool {
        proc::run15("netsh.exe", &["interface", "show", "interface", &format!("name={ADAPTER}")]).0 == 0
    }

    /// Registers the task. Windows shows one administrator prompt. Blocks until it exists.
    pub fn install_task(&self, exe: &Path) -> Result<()> {
        std::fs::create_dir_all(&self.dir)?;
        let script = build_install_script(exe, &self.config_file, &self.dir, &proc::current_user())?;
        proc::elevated_powershell(&script)?;
        let deadline = Instant::now() + Duration::from_secs(90);
        while Instant::now() < deadline {
            if self.task_ready(exe) {
                return Ok(());
            }
            sleep(Duration::from_millis(500));
        }
        Err(Error::msg("The one-time setup did not finish. Was the administrator prompt declined?"))
    }

    /// Writes [config], starts sing-box and returns once its adapter is there. Fails when it does not come up.
    pub fn start(&self, config: &Value) -> Result<()> {
        std::fs::create_dir_all(&self.dir)?;
        std::fs::write(&self.config_file, serde_json::to_string_pretty(config).map_err(|e| Error::msg(e.to_string()))?)?;
        let _ = std::fs::remove_file(&self.log_file);
        proc::run15("schtasks.exe", &["/run", "/tn", TASK]);
        let begun = Instant::now();
        loop {
            if self.adapter_exists() {
                sleep(Duration::from_secs(1)); // routes are added right after the adapter appears
                if self.running() {
                    return Ok(());
                }
            }
            let waited = begun.elapsed();
            if waited > Duration::from_secs(4) && !self.running() {
                return Err(Error::msg(format!("The app-rules program did not start.{}", self.log_hint())));
            }
            if waited > Duration::from_secs(25) {
                return Err(Error::msg(format!("The app-rules program did not set up its adapter.{}", self.log_hint())));
            }
            sleep(Duration::from_millis(400));
        }
    }

    pub fn stop(&self) {
        proc::run15("schtasks.exe", &["/end", "/tn", TASK]);
        let begun = Instant::now();
        while begun.elapsed() < Duration::from_secs(6) && self.running() {
            sleep(Duration::from_millis(300));
        }
    }

    fn log_hint(&self) -> String {
        std::fs::read_to_string(&self.log_file).map(|t| log_hint(&t)).unwrap_or_default()
    }
}

/// True when the task XML starts [exe] with [config] under the S4U principal this version registers.
pub fn task_xml_ready(xml: &str, exe: &Path, config: &Path) -> bool {
    let x = xml.to_lowercase();
    x.contains(&exe.to_string_lossy().to_lowercase()) && x.contains(&config.to_string_lossy().to_lowercase()) && x.contains("s4u")
}

/// The PowerShell that registers the `\Ember\Apps` task.
pub fn build_install_script(exe: &Path, config: &Path, dir: &Path, user: &str) -> Result<String> {
    let (exe_s, config_s, dir_s) = (exe.to_string_lossy(), config.to_string_lossy(), dir.to_string_lossy());
    proc::check_quotes(&[&exe_s, &dir_s, user])?;
    let arguments = format!("run -c \"{config_s}\" -D \"{dir_s}\"");
    Ok(format!(
        "$ErrorActionPreference = 'Stop'\n\
$action = New-ScheduledTaskAction -Execute '{exe_s}' -Argument '{arguments}' -WorkingDirectory '{dir_s}'\n\
$principal = New-ScheduledTaskPrincipal -UserId '{user}' -LogonType S4U -RunLevel Highest\n\
$settings = New-ScheduledTaskSettingsSet -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries -ExecutionTimeLimit ([TimeSpan]::Zero)\n\
Register-ScheduledTask -TaskName 'Apps' -TaskPath '\\Ember\\' -Action $action -Principal $principal -Settings $settings -Force | Out-Null"
    ))
}

/// The last two problem lines of sing-box's log, for the error shown to the user (like the Kotlin `logHint`).
pub fn log_hint(log: &str) -> String {
    let lines: Vec<&str> = log
        .lines()
        .filter(|l| {
            let l = l.to_lowercase();
            l.contains("fatal") || l.contains("error") || l.contains("fail")
        })
        .collect();
    let tail = &lines[lines.len().saturating_sub(2)..];
    if tail.is_empty() {
        return String::new();
    }
    let joined = tail.iter().map(|l| l.split_once(' ').map_or(*l, |(_, rest)| rest).trim()).collect::<Vec<_>>().join(" ");
    format!(" {}", joined.chars().take(220).collect::<String>())
}

#[cfg(test)]
mod tests {
    use super::*;

    fn strings(v: &[&str]) -> Vec<String> {
        v.iter().map(|s| s.to_string()).collect()
    }

    fn build(mode: AppRuleMode, apps: &[&str]) -> Value {
        build_rules(mode, &strings(apps), 10808, &strings(&["1.2.3.0/24"]), &strings(&["example.com"]), Path::new(r"C:\log.txt"))
    }

    #[test]
    fn skip_mode_sends_apps_direct_and_the_rest_to_the_proxy() {
        let c = build(AppRuleMode::Skip, &["Steam.exe"]);
        assert_eq!(c["route"]["final"], "proxy");
        assert_eq!(c["dns"]["final"], "remote");
        let rules = c["route"]["rules"].as_array().unwrap();
        let last = rules.last().unwrap();
        assert_eq!(last["process_name"], json!(["Steam.exe", "steam.exe"]));
        assert_eq!(last["outbound"], "direct");
        assert_eq!(c["dns"]["rules"][0]["server"], "local");
    }

    #[test]
    fn only_mode_sends_apps_to_the_proxy() {
        let c = build(AppRuleMode::Only, &["chrome.exe"]);
        assert_eq!(c["route"]["final"], "direct");
        assert_eq!(c["dns"]["final"], "local");
        let last = c["route"]["rules"].as_array().unwrap().last().unwrap().clone();
        assert_eq!(last["process_name"], json!(["chrome.exe"])); // lower-case duplicate removed
        assert_eq!(last["outbound"], "proxy");
        assert_eq!(c["dns"]["rules"][0]["server"], "remote");
    }

    #[test]
    fn fixed_rules_come_first_in_order() {
        let c = build(AppRuleMode::Skip, &[]);
        let rules = c["route"]["rules"].as_array().unwrap();
        assert_eq!(rules[0], json!({"action": "sniff"}));
        assert_eq!(rules[1]["action"], "hijack-dns");
        assert_eq!(rules[2]["process_name"], json!(["xray.exe", "sing-box.exe"]));
        assert_eq!(rules[3]["ip_is_private"], true);
        assert_eq!(rules[4]["ip_cidr"], json!(["1.2.3.0/24"]));
        assert_eq!(rules[5]["domain"], json!(["example.com"]));
        assert_eq!(rules.len(), 6); // no app rule without apps
        assert!(c["dns"]["rules"].as_array().unwrap().is_empty());
    }

    #[test]
    fn inbound_outbound_and_log_are_set() {
        let c = build(AppRuleMode::Skip, &[]);
        assert_eq!(c["inbounds"][0]["interface_name"], ADAPTER);
        assert_eq!(c["outbounds"][0]["server_port"], 10808);
        assert_eq!(c["outbounds"][0]["version"], "5");
        assert_eq!(c["log"]["output"], r"C:\log.txt");
    }

    #[test]
    fn mode_strings() {
        assert_eq!(AppRuleMode::parse("skip"), Some(AppRuleMode::Skip));
        assert_eq!(AppRuleMode::parse("only"), Some(AppRuleMode::Only));
        assert_eq!(AppRuleMode::parse("off"), None);
    }

    #[test]
    fn task_xml_check() {
        let exe = Path::new(r"C:\Ember\tunnel\sing-box.exe");
        let cfg = Path::new(r"C:\Users\a\AppData\Roaming\Ember\apps\config.json");
        let xml = r"<Exec><Command>C:\EMBER\tunnel\sing-box.exe</Command><Arguments>run -c C:\Users\a\AppData\Roaming\Ember\apps\config.json</Arguments></Exec><LogonType>S4U</LogonType>";
        assert!(task_xml_ready(xml, exe, cfg));
        assert!(!task_xml_ready(&xml.replace("S4U", "Password"), exe, cfg));
        assert!(!task_xml_ready("", exe, cfg));
    }

    #[test]
    fn install_script_registers_the_apps_task() {
        let s = build_install_script(Path::new(r"C:\E\sing-box.exe"), Path::new(r"C:\D\config.json"), Path::new(r"C:\D"), r"PC\a").unwrap();
        assert!(s.contains("-Argument 'run -c \"C:\\D\\config.json\" -D \"C:\\D\"'"));
        assert!(s.contains("-LogonType S4U -RunLevel Highest"));
        assert!(s.contains("-TaskName 'Apps' -TaskPath '\\Ember\\'"));
        assert!(build_install_script(Path::new(r"C:\E's.exe"), Path::new("c"), Path::new("d"), "u").is_err());
    }

    #[test]
    fn log_hint_takes_last_two_problem_lines() {
        let log = "+0000 INFO ok\n+0001 ERROR first\n+0002 WARN fine\n+0003 FATAL second\n+0004 error third\n";
        assert_eq!(log_hint(log), " FATAL second error third");
        assert_eq!(log_hint("all fine"), "");
    }
}
