//! Whole-device mode on Windows, Ember's side. An elevated helper runs hev-socks5-tunnel on a Wintun adapter, routes
//! traffic into it and applies the kill switch. Two helpers ship in the application folder: `ember-helper.exe`
//! (native, about a second to set up) and `ember-tunnel.ps1` (PowerShell, the fallback). This module only starts and
//! stops the helper and reads its status file; it never runs as administrator itself.
//!
//! The hev-socks5-tunnel YAML (`HevSocks5TunnelConfig.write` in Kotlin) is not ported here; the engine passes the path
//! of the file it wrote as `hev_config_path`.

use std::path::{Path, PathBuf};
use std::process::Child;
use std::thread::sleep;
use std::time::{Duration, Instant};

use serde_json::{json, Value};

use crate::privileged_task::PrivilegedTask;
use crate::{paths, proc, Error, Result};

pub const ADAPTER_NAME: &str = "EmberTun";
pub const TUN_ADDRESS: &str = "10.8.0.2";
pub const MAPDNS_ADDRESS: &str = "198.18.0.2";
pub const TUN_ADDRESS6: &str = "fd00:8:0:1::2";
const START_TIMEOUT: Duration = Duration::from_secs(120);
const HELPER_SILENT_TIMEOUT: Duration = Duration::from_secs(20);
const STOP_TIMEOUT: Duration = Duration::from_secs(20);

/// A helper start-up failure. `retry_with_fallback` is true when the helper itself ran and reported a problem.
struct HelperFailure {
    message: String,
    retry_with_fallback: bool,
}

impl HelperFailure {
    fn new(message: impl Into<String>, retry_with_fallback: bool) -> Self {
        Self { message: message.into(), retry_with_fallback }
    }
}

/// How the helper was launched: a process (PowerShell or scheduled task) or, for the native helper, just an outcome.
struct Launch {
    process: Option<Child>,
    failure: Option<String>,
    launched_at: Instant,
}

impl Launch {
    fn new(process: Option<Child>, failure: Option<String>) -> Self {
        Self { process, failure, launched_at: Instant::now() }
    }
}

/// What the status file says.
#[derive(Debug, PartialEq, Eq)]
pub enum Status {
    Up,
    Down,
    Starting,
    Blocked,
    Error(String),
    /// Anything else the helper wrote (for example the PowerShell helper's own steps).
    Other(String),
}

pub fn parse_status(text: &str) -> Status {
    let t = text.trim();
    if t == "UP" {
        Status::Up
    } else if t == "DOWN" {
        Status::Down
    } else if t == "STARTING" {
        Status::Starting
    } else if t.starts_with("BLOCKED") {
        Status::Blocked
    } else if let Some(rest) = t.strip_prefix("ERROR") {
        Status::Error(rest.strip_prefix(':').unwrap_or(rest).trim().to_string())
    } else {
        Status::Other(t.to_string())
    }
}

/// The helper's config file for whole-device mode.
#[allow(clippy::too_many_arguments)]
pub fn tunnel_config(
    state_dir: &Path,
    hev_config_path: &Path,
    edge_ips: &[String],
    exclude_cidrs: &[String],
    kill_switch: bool,
    ipv6: bool,
    parent_pid: u32,
) -> Value {
    let mut config = json!({
        "stateDir": state_dir.to_string_lossy(),
        "hevConfigPath": hev_config_path.to_string_lossy(),
        "adapterName": ADAPTER_NAME,
        "tunAddress": TUN_ADDRESS,
        "mapdnsAddress": MAPDNS_ADDRESS,
        "edgeIps": edge_ips,
        "excludeCidrs": exclude_cidrs,
        "killSwitch": kill_switch,
        "parentPid": parent_pid,
    });
    if ipv6 {
        config["tunAddress6"] = json!(TUN_ADDRESS6);
    }
    config
}

/// The helper's config file for kill-switch-only mode (another program, OpenVPN, carries the traffic).
pub fn guard_config(state_dir: &Path, edge_ips: &[String], parent_pid: u32) -> Value {
    json!({
        "stateDir": state_dir.to_string_lossy(),
        "guardOnly": true,
        "edgeIps": edge_ips,
        "excludeCidrs": Vec::<String>::new(),
        "parentPid": parent_pid,
    })
}

/// hev-socks5-tunnel's YAML (port of `HevSocks5TunnelConfig.write`, with the IPv6 line when asked for).
pub fn hev_config(socks_port: u16, mtu: i32, tcp_buffer: i32, ipv6: bool) -> String {
    let yaml = format!(
        "tunnel:
  name: '{ADAPTER_NAME}'
  mtu: {mtu}
  ipv4: '{TUN_ADDRESS}'
  icmp: 'reply'
socks5:
  port: {socks_port}
  address: '127.0.0.1'
  udp: 'udp'
mapdns:
  address: '{MAPDNS_ADDRESS}'
  port: 53
  network: '100.64.0.0'
  netmask: '255.192.0.0'
  cache-size: 10000
misc:
  task-stack-size: 86016
  connect-timeout: 20000
  tcp-read-write-timeout: 300000
  udp-read-write-timeout: 60000
  tcp-buffer-size: {tcp_buffer}
  udp-recv-buffer-size: 524288
  udp-copy-buffer-nums: 16
  max-session-count: 0
  limit-nofile: 65535
  log-level: 'warn'
"
    );
    if ipv6 {
        add_ipv6_to_hev_yaml(&yaml)
    } else {
        yaml
    }
}

/// hev needs its own IPv6 address to carry IPv6 packets; the helper gives the adapter the same one.
pub fn add_ipv6_to_hev_yaml(yaml: &str) -> String {
    if yaml.contains("ipv6:") {
        return yaml.to_string();
    }
    let ipv4 = format!("  ipv4: '{TUN_ADDRESS}'\n");
    yaml.replace(&ipv4, &format!("{ipv4}  ipv6: '{TUN_ADDRESS6}'\n"))
}

/// Arguments for the native helper: `up --config "<path>"`.
pub fn native_parameters(mode: &str, config: &Path) -> String {
    format!("{} --config \"{}\"", mode.to_lowercase(), config.display())
}

/// The `Start-Process` command that runs the PowerShell helper elevated.
pub fn powershell_elevate_command(helper: &Path, mode: &str, config: &Path) -> Result<String> {
    let (helper_s, config_s) = (helper.to_string_lossy(), config.to_string_lossy());
    if helper_s.contains('\'') || config_s.contains('\'') {
        return Err(Error::msg("Unsupported character in an installation path."));
    }
    let args = format!("'-NoProfile','-ExecutionPolicy','Bypass','-File','\"{helper_s}\"','-Mode','{mode}','-ConfigPath','\"{config_s}\"'");
    Ok(format!("Start-Process -FilePath powershell.exe -Verb RunAs -WindowStyle Hidden -ArgumentList {args}"))
}

/// What ShellExecute's return code means for the prompt: None on success.
pub fn shell_execute_failure(code: isize) -> Option<String> {
    match code {
        c if c > 32 => None,
        5 => Some("Administrator access was not allowed, so the tunnel could not start.".to_string()),
        c => Some(format!("Windows could not start the network helper (error {c}).")),
    }
}

/// The message for a launcher process that exited with a failure and left no status.
pub fn launcher_failure(detail: &str, exit_code: i32) -> String {
    let lower = detail.to_lowercase();
    if lower.contains("canceled") || lower.contains("cancelled") {
        "Administrator access was not allowed, so the tunnel could not start.".to_string()
    } else if detail.trim().is_empty() {
        format!("Windows could not start the network helper: exit code {exit_code}")
    } else {
        format!("Windows could not start the network helper: {}", detail.trim())
    }
}

pub struct WindowsTunnel {
    state_dir: PathBuf,
    status_file: PathBuf,
    stop_file: PathBuf,
    config_file: PathBuf,
    pub log_file: PathBuf,
    pub privileged: PrivilegedTask,
    resources_dir: Option<PathBuf>,
}

impl WindowsTunnel {
    pub fn new(data_dir: &Path) -> Result<Self> {
        let state_dir = data_dir.join("tunnel-state");
        std::fs::create_dir_all(&state_dir)?;
        Ok(Self {
            status_file: state_dir.join("status"),
            stop_file: state_dir.join("stop"),
            config_file: state_dir.join("config.json"),
            log_file: state_dir.join("helper.log"),
            privileged: PrivilegedTask::new(data_dir),
            resources_dir: paths::resources_dir(),
            state_dir,
        })
    }

    pub fn state_dir(&self) -> &Path {
        &self.state_dir
    }

    fn script(&self) -> Option<PathBuf> {
        self.privileged.script()
    }

    fn native_helper(&self) -> Option<PathBuf> {
        self.privileged.native_helper()
    }

    fn hev_present(&self) -> bool {
        self.resources_dir.as_ref().is_some_and(|d| paths::find_file(&d.join("tunnel"), "hev-socks5-tunnel.exe").is_some())
    }

    pub fn is_available(&self) -> bool {
        cfg!(windows) && (self.native_helper().is_some() || self.script().is_some()) && self.hev_present()
    }

    /// Asks the running helper to re-assert the route to the server (used after the network changes).
    pub fn request_refresh(&self) {
        let _ = std::fs::write(self.state_dir.join("refresh"), "1");
    }

    pub fn status(&self) -> Option<Status> {
        std::fs::read_to_string(&self.status_file).ok().map(|t| parse_status(&t))
    }

    /// True when a previous run left the kill switch holding traffic (Ember crashed, or the tunnel died).
    pub fn is_blocked(&self) -> bool {
        self.status() == Some(Status::Blocked)
    }

    /// True when the status file says a tunnel was up but no native helper is running any more, for example after
    /// Windows restarted or Ember was killed. The firewall rules of that run may still be in place.
    pub fn is_stale(&self) -> bool {
        match self.status() {
            None | Some(Status::Down | Status::Error(_) | Status::Blocked) => false,
            Some(_) => self.native_helper().is_some() && !native_helper_running(),
        }
    }

    /// Starts the helper and waits for it to report UP. Windows shows an administrator prompt unless the optional
    /// one-time setup was done. [on_progress] receives plain-language steps for the UI. The caller writes the hev YAML
    /// first (see [add_ipv6_to_hev_yaml] for the IPv6 line).
    pub fn start(
        &self,
        hev_config_path: &Path,
        edge_ips: &[String],
        exclude_cidrs: &[String],
        kill_switch: bool,
        ipv6: bool,
        on_progress: &mut dyn FnMut(&str),
    ) -> Result<()> {
        self.stop_stale_helper();
        let config = tunnel_config(&self.state_dir, hev_config_path, edge_ips, exclude_cidrs, kill_switch, ipv6, std::process::id());
        let native = self.native_helper();
        let ps = self.script();
        if native.is_none() && ps.is_none() {
            return Err(Error::msg("The network helper is missing from this installation."));
        }
        if let Some(native) = native {
            match self.start_with(&native, true, &config, on_progress) {
                Ok(()) => return Ok(()),
                Err(failure) => {
                    let Some(ps) = ps.as_ref().filter(|_| failure.retry_with_fallback) else {
                        return Err(Error::msg(failure.message));
                    };
                    // The fallback helper rewrites helper.log; keep what the native one wrote.
                    let _ = std::fs::copy(&self.log_file, self.state_dir.join("helper-native.log"));
                    on_progress("Trying the fallback helper…");
                    return self.start_with(ps, false, &config, on_progress).map_err(|f| Error::msg(f.message));
                }
            }
        }
        self.start_with(&ps.unwrap_or_default(), false, &config, on_progress).map_err(|f| Error::msg(f.message))
    }

    /// Starts only the kill switch (no tunnel of ours): the public internet is blocked on the real adapters except for
    /// [edge_ips], while another program (OpenVPN) carries the traffic. Needs the native helper.
    pub fn start_guard(&self, edge_ips: &[String], on_progress: &mut dyn FnMut(&str)) -> Result<()> {
        let native = self
            .native_helper()
            .ok_or_else(|| Error::msg("The kill switch needs Ember's network helper, which is missing from this installation."))?;
        self.stop_stale_helper();
        let config = guard_config(&self.state_dir, edge_ips, std::process::id());
        self.start_with(&native, true, &config, on_progress).map_err(|f| Error::msg(f.message))
    }

    fn start_with(&self, helper: &Path, native: bool, config: &Value, on_progress: &mut dyn FnMut(&str)) -> std::result::Result<(), HelperFailure> {
        let io = |e: std::io::Error| HelperFailure::new(e.to_string(), false);
        let _ = std::fs::remove_file(&self.status_file);
        let _ = std::fs::remove_file(&self.stop_file);
        let _ = std::fs::remove_file(&self.log_file);
        std::fs::write(&self.config_file, config.to_string()).map_err(io)?;

        if native && self.privileged.is_installed() && !self.privileged.is_current() {
            // The one-time setup was made by an older Ember and still starts the slow PowerShell helper: register it again.
            on_progress("Updating the one-time setup (one Windows prompt)…");
            let _ = self.privileged.install();
        }
        let no_prompt = self.privileged.is_installed() && self.privileged.is_current() && (native || self.native_helper().is_none());
        on_progress(if no_prompt { "Starting the secure network adapter…" } else { "Waiting for you to allow administrator access in the Windows prompt…" });
        let mut launch = if no_prompt {
            Launch::new(self.privileged.run("Up").ok(), None)
        } else {
            self.elevate(helper, native, "Up")
        };
        if let Some(failure) = launch.failure.take() {
            return Err(HelperFailure::new(failure, false));
        }

        let started = Instant::now();
        let mut reported_adapter = false;
        while started.elapsed() < START_TIMEOUT {
            let current = self.status();
            match &current {
                Some(Status::Up) => return Ok(()),
                Some(Status::Error(text)) => return Err(HelperFailure::new(text.clone(), true)),
                Some(Status::Starting) if !reported_adapter => {
                    reported_adapter = true;
                    on_progress("Setting up the secure network adapter…");
                }
                _ => {}
            }
            if current.is_none() {
                let exited = match launch.process.as_mut() {
                    Some(p) => p.try_wait().ok().flatten(),
                    None => None,
                };
                if let Some(code) = exited.map(|s| s.code().unwrap_or(-1)).filter(|c| *c != 0) {
                    let detail = launch.process.take().map(read_all).unwrap_or_default();
                    return Err(HelperFailure::new(launcher_failure(&detail, code), false));
                }
                let silent_since = if exited.is_some() { started } else { launch.launched_at };
                if (launch.process.is_none() || exited.is_some()) && silent_since.elapsed() > HELPER_SILENT_TIMEOUT {
                    return Err(HelperFailure::new(
                        format!("The network helper was allowed to run but did not report back. Details may be in {}", self.log_file.display()),
                        native,
                    ));
                }
            }
            sleep(Duration::from_millis(150));
        }
        Err(HelperFailure::new(format!("The network helper did not finish starting in time. Details may be in {}", self.log_file.display()), false))
    }

    /// A helper left over from an earlier connection (for example one holding the kill switch) must end first.
    fn stop_stale_helper(&self) {
        let Some(current) = self.status() else { return };
        if matches!(current, Status::Down | Status::Error(_)) {
            return;
        }
        let _ = std::fs::write(&self.stop_file, "stop");
        let deadline = Instant::now() + Duration::from_secs(10);
        while Instant::now() < deadline {
            if self.status() == Some(Status::Down) {
                return;
            }
            sleep(Duration::from_millis(300));
        }
    }

    /// Asks the helper to restore routes and firewall rules, and waits for it.
    pub fn stop(&self) -> Result<()> {
        let Some(current) = self.status() else { return Ok(()) };
        if matches!(current, Status::Down | Status::Error(_)) {
            return Ok(());
        }
        std::fs::write(&self.stop_file, "stop")?;
        let deadline = Instant::now() + STOP_TIMEOUT;
        while Instant::now() < deadline {
            if self.status() == Some(Status::Down) {
                return Ok(());
            }
            sleep(Duration::from_millis(150));
        }
        self.restore()
    }

    /// Undoes the kill switch and any leftovers. Windows shows an administrator prompt unless the one-time setup was done.
    pub fn restore(&self) -> Result<()> {
        let native = self.native_helper();
        let Some(helper) = native.clone().or_else(|| self.script()) else { return Ok(()) };
        std::fs::write(&self.config_file, json!({"stateDir": self.state_dir.to_string_lossy()}).to_string())?;
        std::fs::write(&self.stop_file, "stop")?;
        let _ = std::fs::remove_file(&self.status_file);
        if self.privileged.is_installed() && self.privileged.is_current() {
            let _ = self.privileged.run("Restore");
        } else if let Some(failure) = self.elevate(&helper, native.is_some(), "Restore").failure {
            return Err(Error::msg(failure));
        }
        let deadline = Instant::now() + STOP_TIMEOUT;
        while Instant::now() < deadline {
            if self.status() == Some(Status::Down) {
                return Ok(());
            }
            sleep(Duration::from_millis(150));
        }
        Err(Error::msg(
            "Restoring the network timed out. Run ember-helper.exe restore as administrator, or use Windows' firewall settings to remove the \"Ember\" rules.",
        ))
    }

    fn elevate(&self, helper: &Path, native: bool, mode: &str) -> Launch {
        if native {
            shell_execute_runas(helper, &native_parameters(mode, &self.config_file))
        } else {
            match powershell_elevate_command(helper, mode, &self.config_file) {
                Ok(command) => {
                    // -EncodedCommand avoids every quoting problem between Ember and PowerShell.
                    let encoded = proc::encoded_command(&command);
                    let child = proc::command("powershell.exe")
                        .args(["-NoProfile", "-NonInteractive", "-EncodedCommand", &encoded])
                        .stdin(std::process::Stdio::null())
                        .stdout(std::process::Stdio::piped())
                        .stderr(std::process::Stdio::piped())
                        .spawn();
                    match child {
                        Ok(c) => Launch::new(Some(c), None),
                        Err(e) => Launch::new(None, Some(format!("Windows could not start the network helper: {e}"))),
                    }
                }
                Err(e) => Launch::new(None, Some(e.to_string())),
            }
        }
    }
}

fn read_all(mut child: Child) -> String {
    use std::io::Read;
    let mut bytes = Vec::new();
    if let Some(o) = child.stdout.as_mut() {
        let _ = o.read_to_end(&mut bytes);
    }
    if let Some(e) = child.stderr.as_mut() {
        let _ = e.read_to_end(&mut bytes);
    }
    proc::decode_output(&bytes).trim().to_string()
}

fn native_helper_running() -> bool {
    proc::run15("tasklist.exe", &["/fi", "imagename eq ember-helper.exe", "/fo", "csv", "/nh"]).1.to_lowercase().contains("ember-helper.exe")
}

/// Starts the native helper with the Windows administrator prompt (ShellExecute "runas"); returns when the prompt is answered.
#[cfg(windows)]
fn shell_execute_runas(helper: &Path, parameters: &str) -> Launch {
    use windows::core::{w, HSTRING};
    use windows::Win32::UI::Shell::ShellExecuteW;
    use windows::Win32::UI::WindowsAndMessaging::SW_HIDE;

    let file = HSTRING::from(helper.as_os_str());
    let params = HSTRING::from(parameters);
    let dir = HSTRING::from(helper.parent().map(Path::as_os_str).unwrap_or_default());
    // SAFETY: all string arguments are valid NUL-terminated wide strings that outlive the call; no window handle is passed.
    let result = unsafe { ShellExecuteW(None, w!("runas"), &file, &params, &dir, SW_HIDE) };
    Launch::new(None, shell_execute_failure(result.0 as isize))
}

#[cfg(not(windows))]
fn shell_execute_runas(_helper: &Path, _parameters: &str) -> Launch {
    Launch::new(None, Some("The tunnel is only available on Windows.".to_string()))
}

#[cfg(test)]
mod tests {
    use super::*;

    fn strings(v: &[&str]) -> Vec<String> {
        v.iter().map(|s| s.to_string()).collect()
    }

    #[test]
    fn status_lines_are_understood() {
        assert_eq!(parse_status("UP\r\n"), Status::Up);
        assert_eq!(parse_status("DOWN"), Status::Down);
        assert_eq!(parse_status("STARTING"), Status::Starting);
        assert_eq!(parse_status("BLOCKED: tunnel died"), Status::Blocked);
        assert_eq!(parse_status("ERROR: no wintun"), Status::Error("no wintun".into()));
        assert_eq!(parse_status("ERROR"), Status::Error(String::new()));
        assert_eq!(parse_status("ROUTES"), Status::Other("ROUTES".into()));
    }

    #[test]
    fn tunnel_config_has_the_keys_the_helper_reads() {
        let c = tunnel_config(Path::new(r"C:\s"), Path::new(r"C:\s\hev.yaml"), &strings(&["1.2.3.4"]), &strings(&["10.0.0.0/8"]), true, false, 42);
        assert_eq!(c["stateDir"], r"C:\s");
        assert_eq!(c["hevConfigPath"], r"C:\s\hev.yaml");
        assert_eq!(c["adapterName"], "EmberTun");
        assert_eq!(c["tunAddress"], "10.8.0.2");
        assert_eq!(c["mapdnsAddress"], "198.18.0.2");
        assert_eq!(c["edgeIps"], json!(["1.2.3.4"]));
        assert_eq!(c["excludeCidrs"], json!(["10.0.0.0/8"]));
        assert_eq!(c["killSwitch"], true);
        assert_eq!(c["parentPid"], 42);
        assert!(c.get("tunAddress6").is_none());
        let v6 = tunnel_config(Path::new("s"), Path::new("h"), &[], &[], false, true, 1);
        assert_eq!(v6["tunAddress6"], "fd00:8:0:1::2");
        assert_eq!(v6["killSwitch"], false);
    }

    #[test]
    fn guard_config_is_guard_only() {
        let c = guard_config(Path::new(r"C:\s"), &strings(&["9.9.9.9"]), 7);
        assert_eq!(c["guardOnly"], true);
        assert_eq!(c["excludeCidrs"], json!([]));
        assert!(c.get("hevConfigPath").is_none());
    }

    #[test]
    fn ipv6_line_is_added_once() {
        let yaml = "tunnel:\n  name: 'EmberTun'\n  ipv4: '10.8.0.2'\n  mtu: 1400\n";
        let with = add_ipv6_to_hev_yaml(yaml);
        assert!(with.contains("  ipv4: '10.8.0.2'\n  ipv6: 'fd00:8:0:1::2'\n  mtu: 1400"));
        assert_eq!(add_ipv6_to_hev_yaml(&with), with);
    }

    #[test]
    fn native_parameters_lowercase_the_mode() {
        assert_eq!(native_parameters("Up", Path::new(r"C:\s\config.json")), "up --config \"C:\\s\\config.json\"");
        assert_eq!(native_parameters("Restore", Path::new("c")), "restore --config \"c\"");
    }

    #[test]
    fn powershell_command_quotes_paths() {
        let c = powershell_elevate_command(Path::new(r"C:\E\ember-tunnel.ps1"), "Up", Path::new(r"C:\s\config.json")).unwrap();
        assert_eq!(
            c,
            "Start-Process -FilePath powershell.exe -Verb RunAs -WindowStyle Hidden -ArgumentList '-NoProfile','-ExecutionPolicy','Bypass','-File','\"C:\\E\\ember-tunnel.ps1\"','-Mode','Up','-ConfigPath','\"C:\\s\\config.json\"'"
        );
        assert!(powershell_elevate_command(Path::new(r"C:\it's.ps1"), "Up", Path::new("c")).is_err());
    }

    #[test]
    fn shell_execute_codes() {
        assert_eq!(shell_execute_failure(42), None);
        assert!(shell_execute_failure(5).unwrap().contains("not allowed"));
        assert_eq!(shell_execute_failure(2).unwrap(), "Windows could not start the network helper (error 2).");
    }

    #[test]
    fn launcher_failure_messages() {
        assert!(launcher_failure("The operation was canceled by the user.", 1).contains("not allowed"));
        assert_eq!(launcher_failure("", 3), "Windows could not start the network helper: exit code 3");
        assert_eq!(launcher_failure(" boom ", 1), "Windows could not start the network helper: boom");
    }

    #[test]
    fn hev_yaml_matches_the_kotlin_layout() {
        let y = hev_config(10808, 1400, 65536, false);
        assert!(y.starts_with("tunnel:
  name: 'EmberTun'
  mtu: 1400
  ipv4: '10.8.0.2'
  icmp: 'reply'
socks5:
  port: 10808
"));
        assert!(y.contains("mapdns:
  address: '198.18.0.2'
  port: 53
  network: '100.64.0.0'
  netmask: '255.192.0.0'
  cache-size: 10000
"));
        assert!(y.contains("  tcp-buffer-size: 65536
") && y.ends_with("  log-level: 'warn'
"));
        assert!(!y.contains("ipv6:"));
        let v6 = hev_config(10808, 1500, 32768, true);
        assert!(v6.contains("  ipv4: '10.8.0.2'
  ipv6: 'fd00:8:0:1::2'
") && v6.contains("mtu: 1500"));
    }
}
