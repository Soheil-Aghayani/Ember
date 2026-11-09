//! Everything the engine asks the computer to do that changes it: the whole-device tunnel and kill switch, the per-program
//! rules, OpenVPN, the one-time administrator setup, and reading which network this is. The engine talks to this trait, so
//! its connect sequence can be tested with a recording stand-in that touches nothing; [`WindowsHost`] is the real one and
//! calls `ember-win`.

use std::path::{Path, PathBuf};
use std::sync::atomic::AtomicBool;

use ember_win::app_routing::{self, AppRoutingRunner, AppRuleMode};
use ember_win::openvpn::{OpenVpnCredentials, OpenVpnRunner};
use ember_win::windows_tunnel::{Status, WindowsTunnel};
use ember_win::Error;

/// A failure in words for the user. `auth_failed` is set when an OpenVPN server refused the user name or password.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct HostError {
    pub message: String,
    pub auth_failed: bool,
}

impl HostError {
    pub fn new(message: impl Into<String>) -> Self {
        Self { message: message.into(), auth_failed: false }
    }
}

impl From<Error> for HostError {
    fn from(e: Error) -> Self {
        HostError { auth_failed: matches!(e, Error::OpenVpnAuthFailed), message: e.to_string() }
    }
}

pub type HostResult = Result<(), HostError>;

/// What a whole-device connection needs from the helper.
pub struct TunnelRequest<'a> {
    pub hev_config: &'a Path,
    pub edge_ips: &'a [String],
    pub exclude_cidrs: &'a [String],
    pub kill_switch: bool,
    pub ipv6: bool,
}

/// What an app-rules connection needs from sing-box.
pub struct AppRulesRequest<'a> {
    pub mode: AppRuleMode,
    pub apps: &'a [String],
    pub socks_port: u16,
    pub skip_cidrs: &'a [String],
    pub skip_domains: &'a [String],
}

pub trait Host: Send + Sync {
    // ---- whole-device tunnel
    fn tunnel_available(&self) -> bool;
    fn tunnel_start(&self, request: &TunnelRequest, progress: &mut dyn FnMut(&str)) -> HostResult;
    /// Kill switch only (OpenVPN carries the traffic).
    fn tunnel_guard(&self, edge_ips: &[String], progress: &mut dyn FnMut(&str)) -> HostResult;
    fn tunnel_stop(&self) -> HostResult;
    fn tunnel_restore(&self) -> HostResult;
    fn tunnel_status(&self) -> Option<Status>;
    fn tunnel_blocked(&self) -> bool;
    fn tunnel_stale(&self) -> bool;
    fn tunnel_refresh(&self);

    // ---- per-program rules
    fn app_rules_available(&self) -> bool;
    fn app_rules_start(&self, request: &AppRulesRequest, progress: &mut dyn FnMut(&str)) -> HostResult;
    fn app_rules_running(&self) -> bool;
    fn app_rules_stop(&self);

    // ---- OpenVPN
    fn openvpn_installed(&self) -> bool;
    /// Runs OpenVPN with `profile` until it ends. `ask(subject, ask_user)` returns None when the user cancels.
    fn openvpn_session(
        &self,
        profile: &str,
        ask: &mut dyn FnMut(&str, bool) -> Option<OpenVpnCredentials>,
        progress: &mut dyn FnMut(&str),
        connected: &mut dyn FnMut(),
        cancel: &AtomicBool,
    ) -> HostResult;

    // ---- one-time administrator setup (no prompt on every connect)
    fn setup_installed(&self) -> bool;
    fn setup_install(&self) -> HostResult;
    fn setup_remove(&self) -> HostResult;

    // ---- the network
    /// A name for the network the computer is on (Wi-Fi name, or "wired"), None when offline.
    fn network_identity(&self) -> Option<String>;
}

/// The real thing. Nothing here runs unless a connection or a setting asks for it.
pub struct WindowsHost {
    tunnel: Option<WindowsTunnel>,
    app_rules: AppRoutingRunner,
    openvpn: OpenVpnRunner,
}

impl WindowsHost {
    pub fn new(data_dir: &Path) -> Self {
        Self { tunnel: WindowsTunnel::new(data_dir).ok(), app_rules: AppRoutingRunner::new(data_dir), openvpn: OpenVpnRunner::new(data_dir) }
    }

    fn tunnel(&self) -> Result<&WindowsTunnel, HostError> {
        self.tunnel.as_ref().ok_or_else(|| HostError::new("Ember could not prepare its network folder."))
    }
}

impl Host for WindowsHost {
    fn tunnel_available(&self) -> bool {
        self.tunnel.as_ref().is_some_and(|t| t.is_available())
    }

    fn tunnel_start(&self, request: &TunnelRequest, progress: &mut dyn FnMut(&str)) -> HostResult {
        self.tunnel()?
            .start(request.hev_config, request.edge_ips, request.exclude_cidrs, request.kill_switch, request.ipv6, progress)
            .map_err(Into::into)
    }

    fn tunnel_guard(&self, edge_ips: &[String], progress: &mut dyn FnMut(&str)) -> HostResult {
        self.tunnel()?.start_guard(edge_ips, progress).map_err(Into::into)
    }

    fn tunnel_stop(&self) -> HostResult {
        self.tunnel()?.stop().map_err(Into::into)
    }

    fn tunnel_restore(&self) -> HostResult {
        self.tunnel()?.restore().map_err(Into::into)
    }

    fn tunnel_status(&self) -> Option<Status> {
        self.tunnel.as_ref()?.status()
    }

    fn tunnel_blocked(&self) -> bool {
        self.tunnel.as_ref().is_some_and(|t| t.is_blocked())
    }

    fn tunnel_stale(&self) -> bool {
        self.tunnel.as_ref().is_some_and(|t| t.is_stale())
    }

    fn tunnel_refresh(&self) {
        if let Some(t) = &self.tunnel {
            t.request_refresh();
        }
    }

    fn app_rules_available(&self) -> bool {
        self.app_rules.is_available()
    }

    fn app_rules_start(&self, request: &AppRulesRequest, progress: &mut dyn FnMut(&str)) -> HostResult {
        let exe = self.app_rules.exe().ok_or_else(|| HostError::new("App rules are not available in this build. Turn them off in Settings."))?;
        if !self.app_rules.task_ready(&exe) {
            progress("Waiting for Windows permission (one time)…");
            self.app_rules.install_task(&exe)?;
        }
        progress("Setting up app rules…");
        let config = app_routing::build_rules(
            request.mode,
            request.apps,
            request.socks_port,
            request.skip_cidrs,
            request.skip_domains,
            &self.app_rules.log_file,
        );
        self.app_rules.start(&config).map_err(Into::into)
    }

    fn app_rules_running(&self) -> bool {
        self.app_rules.running()
    }

    fn app_rules_stop(&self) {
        self.app_rules.stop();
    }

    fn openvpn_installed(&self) -> bool {
        OpenVpnRunner::executable().is_some()
    }

    fn openvpn_session(
        &self,
        profile: &str,
        ask: &mut dyn FnMut(&str, bool) -> Option<OpenVpnCredentials>,
        progress: &mut dyn FnMut(&str),
        connected: &mut dyn FnMut(),
        cancel: &AtomicBool,
    ) -> HostResult {
        let exe = OpenVpnRunner::executable()
            .ok_or_else(|| HostError::new("OpenVPN is not installed. Get it at openvpn.net/community-downloads."))?;
        if !self.openvpn.task_ready(&exe) {
            progress("Waiting for Windows permission (one time)…");
            self.openvpn.install_task(&exe)?;
        }
        self.openvpn.session(profile, ask, progress, connected, cancel).map_err(Into::into)
    }

    fn setup_installed(&self) -> bool {
        self.tunnel.as_ref().is_some_and(|t| t.privileged.is_installed() && t.privileged.is_current())
    }

    fn setup_install(&self) -> HostResult {
        self.tunnel()?.privileged.install().map_err(Into::into)
    }

    fn setup_remove(&self) -> HostResult {
        self.tunnel()?.privileged.uninstall().map_err(Into::into)
    }

    fn network_identity(&self) -> Option<String> {
        ember_win::network_identity::current()
    }
}

/// The folder the tunnel keeps its state in, next to the data folder (used to place the hev-socks5-tunnel YAML).
pub fn hev_config_path(data_dir: &Path) -> PathBuf {
    data_dir.join("hev-socks5-tunnel.yaml")
}

/// A stand-in that records what the engine asked for and touches nothing, for the engine's tests.
#[cfg(test)]
pub mod fake {
    use std::sync::atomic::Ordering;
    use std::sync::{Arc, Mutex};

    use super::*;

    /// A user name (when asked for) and a password, as typed.
    pub type Typed = (Option<String>, String);

    #[derive(Default)]
    pub struct FakeHost {
        pub calls: Mutex<Vec<String>>,
        pub tunnel_ok: AtomicBool,
        pub app_rules_ok: AtomicBool,
        pub openvpn_ok: AtomicBool,
        pub blocked: AtomicBool,
        pub stale: AtomicBool,
        pub app_rules_alive: AtomicBool,
        pub status: Mutex<Option<Status>>,
        pub network: Mutex<Option<String>>,
        /// Makes `tunnel_start` fail with this text.
        pub tunnel_error: Mutex<Option<String>>,
        /// What the OpenVPN session does: ask for these credentials first, then report connected and wait for cancel.
        pub ovpn_asks: Mutex<Option<(String, bool)>>,
        pub ovpn_fails_auth: AtomicBool,
        pub ovpn_got: Mutex<Option<Option<Typed>>>,
        pub ovpn_profile: Mutex<String>,
    }

    impl FakeHost {
        pub fn all_available() -> Arc<FakeHost> {
            let h = FakeHost::default();
            for flag in [&h.tunnel_ok, &h.app_rules_ok, &h.openvpn_ok, &h.app_rules_alive] {
                flag.store(true, Ordering::SeqCst);
            }
            Arc::new(h)
        }

        pub fn calls(&self) -> Vec<String> {
            self.calls.lock().unwrap().clone()
        }

        fn note(&self, text: impl Into<String>) {
            self.calls.lock().unwrap().push(text.into());
        }
    }

    impl Host for FakeHost {
        fn tunnel_available(&self) -> bool {
            self.tunnel_ok.load(Ordering::SeqCst)
        }
        fn tunnel_start(&self, r: &TunnelRequest, progress: &mut dyn FnMut(&str)) -> HostResult {
            progress("starting the fake tunnel");
            self.note(format!(
                "tunnel_start edge={:?} exclude={:?} kill={} ipv6={} yaml={}",
                r.edge_ips,
                r.exclude_cidrs,
                r.kill_switch,
                r.ipv6,
                r.hev_config.file_name().unwrap().to_string_lossy()
            ));
            match self.tunnel_error.lock().unwrap().clone() {
                Some(e) => Err(HostError::new(e)),
                None => Ok(()),
            }
        }
        fn tunnel_guard(&self, edge_ips: &[String], _: &mut dyn FnMut(&str)) -> HostResult {
            self.note(format!("tunnel_guard {edge_ips:?}"));
            Ok(())
        }
        fn tunnel_stop(&self) -> HostResult {
            self.note("tunnel_stop");
            Ok(())
        }
        fn tunnel_restore(&self) -> HostResult {
            self.note("tunnel_restore");
            self.blocked.store(false, Ordering::SeqCst);
            Ok(())
        }
        fn tunnel_status(&self) -> Option<Status> {
            match &*self.status.lock().unwrap() {
                Some(Status::Up) => Some(Status::Up),
                Some(Status::Blocked) => Some(Status::Blocked),
                Some(Status::Error(e)) => Some(Status::Error(e.clone())),
                _ => None,
            }
        }
        fn tunnel_blocked(&self) -> bool {
            self.blocked.load(Ordering::SeqCst)
        }
        fn tunnel_stale(&self) -> bool {
            self.stale.load(Ordering::SeqCst)
        }
        fn tunnel_refresh(&self) {
            self.note("tunnel_refresh");
        }
        fn app_rules_available(&self) -> bool {
            self.app_rules_ok.load(Ordering::SeqCst)
        }
        fn app_rules_start(&self, r: &AppRulesRequest, _: &mut dyn FnMut(&str)) -> HostResult {
            self.note(format!("app_rules_start {:?} {:?} skip={:?} domains={:?}", r.mode, r.apps, r.skip_cidrs, r.skip_domains));
            Ok(())
        }
        fn app_rules_running(&self) -> bool {
            self.app_rules_alive.load(Ordering::SeqCst)
        }
        fn app_rules_stop(&self) {
            self.note("app_rules_stop");
        }
        fn openvpn_installed(&self) -> bool {
            self.openvpn_ok.load(Ordering::SeqCst)
        }
        fn openvpn_session(
            &self,
            profile: &str,
            ask: &mut dyn FnMut(&str, bool) -> Option<OpenVpnCredentials>,
            _: &mut dyn FnMut(&str),
            connected: &mut dyn FnMut(),
            cancel: &AtomicBool,
        ) -> HostResult {
            self.note("openvpn_session");
            *self.ovpn_profile.lock().unwrap() = profile.to_string();
            if let Some((subject, ask_user)) = self.ovpn_asks.lock().unwrap().clone() {
                let got = ask(&subject, ask_user);
                *self.ovpn_got.lock().unwrap() = Some(got.as_ref().map(|c| (c.username.clone(), c.password.clone())));
                if got.is_none() {
                    return Err(HostError::new("Cancelled."));
                }
            }
            if self.ovpn_fails_auth.load(Ordering::SeqCst) {
                return Err(HostError { message: "The server refused the user name or password.".into(), auth_failed: true });
            }
            connected();
            while !cancel.load(Ordering::SeqCst) {
                std::thread::sleep(std::time::Duration::from_millis(20));
            }
            Ok(())
        }
        fn setup_installed(&self) -> bool {
            let calls = self.calls.lock().unwrap();
            let last = calls.iter().rposition(|c| c == "setup_install" || c == "setup_remove");
            last.is_some_and(|i| calls[i] == "setup_install")
        }
        fn setup_install(&self) -> HostResult {
            self.note("setup_install");
            Ok(())
        }
        fn setup_remove(&self) -> HostResult {
            self.note("setup_remove");
            Ok(())
        }
        fn network_identity(&self) -> Option<String> {
            self.network.lock().unwrap().clone()
        }
    }
}
