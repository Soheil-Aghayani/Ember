//! Owns the subscription servers and the Xray connection. Port of `EmberEngine.kt`, first slice: proxy mode only.
//!
//! Threads instead of coroutines. Every connection attempt gets a *generation* number; stopping, switching server or a
//! failure bumps it, and a worker or watcher whose generation is no longer current stops quietly without touching the
//! state. The UI reads a [`Snapshot`] and is told by the `on_change` callback (called from any thread) that it changed.
//!
//! Four ways to connect, as in the Kotlin app: proxy mode (Xray on local ports), whole-device (Xray plus the elevated
//! helper's tunnel and optional kill switch), app rules (Xray plus sing-box deciding per program) and OpenVPN. Everything
//! that changes the computer goes through [`Host`], so the sequence can be tested with a stand-in.
//!
//! Not here yet: stale-Xray clean-up. (There is no speed test on Home on purpose; the exit-country check is `check_exit`.)

use std::collections::{HashMap, HashSet};
use std::net::TcpListener;
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::mpsc::{channel, RecvTimeoutError, Sender};
use std::sync::{Arc, Mutex, MutexGuard};
use std::time::{Duration, Instant};

use ember_subs::http::Http;
use ember_subs::runner::XrayRunner;
use ember_subs::sort::pick_failover;
use ember_subs::subscription::AUTO_NODE;
use ember_subs::tester::{resolve_ipv4, test_nodes, TestEvents};
use ember_subs::xray_config::{xray_config, XraySettings};
use ember_subs::Node;
use ember_win::app_routing::AppRuleMode;
use ember_win::openvpn::OpenVpnCredentials;
use ember_win::windows_tunnel::{self, Status};

use crate::host::{self, AppRulesRequest, Host, HostError, TunnelRequest, WindowsHost};
use crate::log;
use crate::settings::Settings;
use crate::skip;
use crate::store::SubscriptionStore;

const TAG: &str = "EmberEngine";
pub const SOCKS_PORT: u16 = 10808;
pub const HTTP_PORT: u16 = 10809;
pub const LOCAL_BIND: &str = "127.0.0.1";
const MAX_FAILOVERS: u32 = 3;
const REFRESH_AFTER_MS: i64 = 12 * 60 * 60 * 1000;
const REFRESH_CHECK: Duration = Duration::from_secs(3 * 60 * 60);
const PROBE_URL: &str = "http://cp.cloudflare.com/generate_204";

#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub enum ConnState {
    #[default]
    Disconnected,
    Connecting,
    Connected,
}

/// Everything the UI shows about the connection, copied out under one lock so it is always consistent.
#[derive(Debug, Clone, Default)]
pub struct Snapshot {
    pub state: ConnState,
    /// Plain-language step shown while connecting.
    pub progress: Option<String>,
    pub last_error: Option<String>,
    pub connected_at_ms: Option<i64>,
    /// The server that is connected (or being connected to).
    pub connected_node: Option<Node>,
    /// The id of the server Connect uses (`"auto"` for the fastest one).
    pub selected_id: String,
    /// Plain-language step of a running server test.
    pub test_progress: Option<String>,
    /// A short message about something Ember did by itself (switching servers); show it once, then `clear_notice`.
    pub notice: Option<String>,
    /// The connection that is up (or coming up) routes the whole device.
    pub whole_device: bool,
    /// The connection decides per program whether to use the VPN (the Apps setting).
    pub app_rules: bool,
    /// The connection is an OpenVPN one: OpenVPN routes the traffic itself.
    pub using_openvpn: bool,
    /// Set while the kill switch is holding traffic after a crash or a dead tunnel (or while Ember reconnects).
    pub blocked_notice: Option<String>,
    /// Set while an OpenVPN server waits for a user name and password (or a key password); answer with `answer_credentials`.
    pub credential_request: Option<CredentialRequest>,
    /// A name for the network the computer is on, refreshed every few seconds.
    pub network: Option<String>,
    /// Two-letter country the internet sees for this connection (asked from Cloudflare once connected).
    pub exit_country: Option<String>,
    /// A running or finished "which servers reach this website" check: the site's name and what each server got.
    pub site_check: Option<SiteCheck>,
}

#[derive(Debug, Clone, Default)]
pub struct SiteCheck {
    pub label: String,
    pub results: HashMap<String, ember_subs::tester::SiteResult>,
}

/// What an OpenVPN server asks for.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct CredentialRequest {
    pub server_name: String,
    pub subject: String,
    /// True when a user name is asked for too (otherwise only a key's password).
    pub ask_user: bool,
}

#[derive(Default)]
struct Failover {
    count: u32,
    failed: HashSet<String>,
}

struct Shared {
    settings: Settings,
    store: SubscriptionStore,
    xray: XrayRunner,
    snapshot: Mutex<Snapshot>,
    generation: AtomicU64,
    failover: Mutex<Failover>,
    testing: AtomicBool,
    shutting_down: AtomicBool,
    /// Connect in proxy mode even when the settings ask for whole-device mode (tests and `--proxy-only` runs).
    proxy_only: bool,
    socks_port: u16,
    http_port: u16,
    data_dir: PathBuf,
    host: Arc<dyn Host>,
    /// Set to stop the running OpenVPN session; a new one is made for each connection.
    cancel: Mutex<Arc<AtomicBool>>,
    /// True while the kill switch runs for an OpenVPN connection (the helper must be stopped at teardown).
    openvpn_guard: AtomicBool,
    /// Whether the one-time administrator setup is in place, as last asked (asking launches `schtasks`).
    setup_cached: AtomicBool,
    /// Where the answer to a credential request goes.
    credential_answer: Mutex<Option<Sender<Option<OpenVpnCredentials>>>>,
    /// Typed this run, kept in memory only (never written to disk): `server id:subject` -> (user name, password).
    remembered: Mutex<HashMap<String, (Option<String>, String)>>,
    on_change: Box<dyn Fn() + Send + Sync>,
}

/// What this computer can do (the helper and programs are only there when the installation ships them).
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Capabilities {
    pub whole_device: bool,
    pub app_rules: bool,
    pub openvpn: bool,
}

#[derive(Clone)]
pub struct Engine {
    shared: Arc<Shared>,
}

fn lock<T>(m: &Mutex<T>) -> MutexGuard<'_, T> {
    m.lock().unwrap_or_else(|e| e.into_inner())
}

impl Engine {
    /// `xray_exe` is the packaged `xray.exe`. `proxy_only` makes Connect use proxy mode whatever the settings say.
    pub fn new(
        data_dir: &Path,
        xray_exe: Option<PathBuf>,
        system_language: &str,
        proxy_only: bool,
        on_change: impl Fn() + Send + Sync + 'static,
    ) -> std::io::Result<Engine> {
        Engine::with_ports(data_dir, xray_exe, system_language, proxy_only, (SOCKS_PORT, HTTP_PORT), on_change)
    }

    /// Like [`Engine::new`] with other local proxy ports (for tests that must not collide with a running Ember).
    pub fn with_ports(
        data_dir: &Path,
        xray_exe: Option<PathBuf>,
        system_language: &str,
        proxy_only: bool,
        ports: (u16, u16),
        on_change: impl Fn() + Send + Sync + 'static,
    ) -> std::io::Result<Engine> {
        let host = Arc::new(WindowsHost::new(data_dir));
        Engine::with_host(data_dir, xray_exe, system_language, proxy_only, ports, host, on_change)
    }

    /// The constructor everything goes through; `host` does whatever changes the computer.
    pub fn with_host(
        data_dir: &Path,
        xray_exe: Option<PathBuf>,
        system_language: &str,
        proxy_only: bool,
        (socks_port, http_port): (u16, u16),
        host: Arc<dyn Host>,
        on_change: impl Fn() + Send + Sync + 'static,
    ) -> std::io::Result<Engine> {
        std::fs::create_dir_all(data_dir)?;
        log::set_utc_offset_minutes(ember_win::clock::utc_offset_minutes());
        log::init(data_dir.join("ember.log"));
        let settings = Settings::open(&data_dir.join("settings.properties"), system_language);
        let store = SubscriptionStore::open(&data_dir.join("subscriptions.json"));
        let xray = XrayRunner::new(data_dir, xray_exe)?;
        let snapshot = Snapshot { selected_id: settings.selected_node_id(), ..Snapshot::default() };
        let shared = Arc::new(Shared {
            settings,
            store,
            xray,
            snapshot: Mutex::new(snapshot),
            generation: AtomicU64::new(0),
            failover: Mutex::new(Failover::default()),
            testing: AtomicBool::new(false),
            shutting_down: AtomicBool::new(false),
            proxy_only,
            socks_port,
            http_port,
            data_dir: data_dir.to_path_buf(),
            host,
            cancel: Mutex::new(Arc::new(AtomicBool::new(false))),
            openvpn_guard: AtomicBool::new(false),
            setup_cached: AtomicBool::new(false),
            credential_answer: Mutex::new(None),
            remembered: Mutex::new(HashMap::new()),
            on_change: Box::new(on_change),
        });
        shared.start_upkeep();
        shared.refresh_setup_state();
        shared.start_network_watch();
        shared.check_interrupted();
        Ok(Engine { shared })
    }

    pub fn settings(&self) -> &Settings {
        &self.shared.settings
    }

    pub fn store(&self) -> &SubscriptionStore {
        &self.shared.store
    }

    pub fn snapshot(&self) -> Snapshot {
        lock(&self.shared.snapshot).clone()
    }

    /// The server Connect would use: the one picked on the Servers page, or the fastest tested one in "fastest" mode.
    pub fn selected_node(&self) -> Option<Node> {
        self.shared.selected_node()
    }

    /// Picks the server Connect uses. While connected the connection moves to the new server straight away.
    pub fn choose_server(&self, id: &str) {
        let shared = &self.shared;
        shared.reset_failover();
        shared.settings.set_selected_node_id(id);
        shared.update(|s| s.selected_id = id.to_string());
        if shared.snapshot().state == ConnState::Disconnected {
            return;
        }
        let Some(node) = shared.selected_node() else { return };
        let shared = Arc::clone(shared);
        std::thread::spawn(move || {
            shared.generation.fetch_add(1, Ordering::SeqCst);
            shared.cancel_session();
            shared.teardown(false);
            shared.connect(node);
        });
    }

    pub fn toggle_favorite(&self, node: &Node) {
        let mut favorites = self.shared.settings.favorite_nodes();
        if !favorites.remove(&node.id) {
            favorites.insert(node.id.clone());
        }
        self.shared.settings.set_favorite_nodes(&favorites);
    }

    /// Connects to the picked server.
    pub fn connect_selected(&self) {
        self.shared.reset_failover();
        match self.shared.selected_node() {
            Some(node) => self.shared.connect(node),
            None => self.shared.update(|s| s.last_error = Some("Choose a server first.".to_string())),
        }
    }

    pub fn connect(&self, node: Node) {
        self.shared.connect(node);
    }

    /// Stops the connection. The clean-up (the helper restores routes and firewall rules) can take seconds, so it runs
    /// on its own thread; the state turns to disconnected when it is done.
    pub fn disconnect(&self) {
        let shared = Arc::clone(&self.shared);
        shared.reset_failover();
        shared.settings.set_was_connected(false);
        shared.generation.fetch_add(1, Ordering::SeqCst); // stops the worker and the watchers
        shared.cancel_session();
        std::thread::spawn(move || shared.teardown(false));
    }

    /// Answers the question in `Snapshot::credential_request`; `None` cancels the connection.
    pub fn answer_credentials(&self, answer: Option<OpenVpnCredentials>) {
        if let Some(tx) = lock(&self.shared.credential_answer).take() {
            let _ = tx.send(answer);
        }
    }

    /// Lifts the kill switch after a crash or a dead tunnel. Windows shows an administrator prompt (unless the one-time
    /// setup was done), so this runs on its own thread.
    pub fn restore_internet(&self) {
        let shared = Arc::clone(&self.shared);
        std::thread::spawn(move || match shared.host.tunnel_restore() {
            Ok(()) => shared.update(|s| s.blocked_notice = None),
            Err(e) => shared.update(|s| s.last_error = Some(if e.message.is_empty() { "Could not restore the network.".into() } else { e.message })),
        });
    }

    /// Marks the network the computer is on as trusted. False when it is offline.
    pub fn trust_current_network(&self) -> bool {
        let Some(id) = self.snapshot().network.or_else(|| self.shared.host.network_identity()) else { return false };
        let mut trusted = self.shared.settings.trusted_networks();
        if !trusted.contains(&id) {
            trusted.push(id);
        }
        self.shared.settings.set_trusted_networks(&trusted);
        true
    }

    pub fn untrust_network(&self, id: &str) {
        let trusted: Vec<String> = self.shared.settings.trusted_networks().into_iter().filter(|n| n != id).collect();
        self.shared.settings.set_trusted_networks(&trusted);
    }

    /// Whether the one-time administrator setup (no prompt on every connect) is in place, as last checked (at start and
    /// after `set_setup`).
    pub fn setup_installed(&self) -> bool {
        self.shared.setup_cached.load(Ordering::SeqCst)
    }

    /// Adds or removes the one-time setup. Windows shows one administrator prompt, so it runs on its own thread.
    pub fn set_setup(&self, enabled: bool) {
        let shared = Arc::clone(&self.shared);
        std::thread::spawn(move || {
            let result = if enabled { shared.host.setup_install() } else { shared.host.setup_remove() };
            match result {
                Err(e) => shared.update(|s| s.last_error = Some(e.message)),
                Ok(()) => shared.update(|_| {}),
            }
            shared.refresh_setup_state_now();
        });
    }

    /// What the tunnel, per-program rules and OpenVPN need on this computer, for the Settings page.
    pub fn capabilities(&self) -> Capabilities {
        Capabilities {
            whole_device: self.shared.host.tunnel_available(),
            app_rules: self.shared.host.app_rules_available(),
            openvpn: self.shared.host.openvpn_installed(),
        }
    }

    /// Measures the delay of `nodes`, and with `speed` their download speed too. Only while disconnected, or the test
    /// would use the tunnel.
    pub fn test_nodes(&self, nodes: Vec<Node>, speed: bool) {
        self.shared.test_nodes(nodes, speed);
    }

    /// Asks every server in `nodes` for `url` and records what each answered under `label`. Only while disconnected.
    pub fn check_site(&self, nodes: Vec<Node>, label: String, url: String) {
        self.shared.check_site(nodes, label, url);
    }

    pub fn clear_site_check(&self) {
        self.shared.update(|s| s.site_check = None);
    }

    pub fn cancel_test(&self) {
        self.shared.xray.stop(); // ends the test's blocking requests at once
    }

    pub fn clear_notice(&self) {
        self.shared.update(|s| s.notice = None);
    }

    pub fn clear_error(&self) {
        self.shared.update(|s| s.last_error = None);
    }

    /// Stops everything and waits for it (call when the app quits).
    pub fn shutdown(&self) {
        let shared = &self.shared;
        shared.shutting_down.store(true, Ordering::SeqCst);
        shared.settings.set_was_connected(false);
        shared.generation.fetch_add(1, Ordering::SeqCst);
        shared.cancel_session();
        shared.teardown(false);
    }
}

impl Shared {
    fn snapshot(&self) -> Snapshot {
        lock(&self.snapshot).clone()
    }

    fn update(&self, change: impl FnOnce(&mut Snapshot)) {
        change(&mut lock(&self.snapshot));
        (self.on_change)();
    }

    fn selected_node(&self) -> Option<Node> {
        let id = self.settings.selected_node_id();
        if id == AUTO_NODE {
            self.store.best_node()
        } else {
            self.store.node_by_id(&id)
        }
    }

    fn reset_failover(&self) {
        *lock(&self.failover) = Failover::default();
    }

    fn is_current(&self, generation: u64) -> bool {
        self.generation.load(Ordering::SeqCst) == generation
    }

    /// Sleeps up to `total`, in short slices, and says whether this generation is still the current one afterwards.
    fn sleep_current(&self, generation: u64, total: Duration) -> bool {
        let end = Instant::now() + total;
        while Instant::now() < end {
            if !self.is_current(generation) || self.shutting_down.load(Ordering::SeqCst) {
                return false;
            }
            std::thread::sleep(Duration::from_millis(200).min(end.saturating_duration_since(Instant::now())));
        }
        self.is_current(generation) && !self.shutting_down.load(Ordering::SeqCst)
    }

    fn refresh_setup_state_now(&self) {
        self.setup_cached.store(self.host.setup_installed(), Ordering::SeqCst);
        self.update(|_| {});
    }

    fn refresh_setup_state(self: &Arc<Self>) {
        let shared = Arc::clone(self);
        std::thread::spawn(move || shared.refresh_setup_state_now());
    }

    /// Tells a running OpenVPN session to stop, and gives the next connection a fresh flag.
    fn cancel_session(&self) {
        let mut slot = lock(&self.cancel);
        slot.store(true, Ordering::SeqCst);
        *slot = Arc::new(AtomicBool::new(false));
    }

    /// Stops everything and puts it all back to "disconnected". Safe to call twice. It can take seconds (the helper
    /// restores routes and firewall rules), so never call it from the UI thread. `helper_already_blocked` is set when the
    /// tunnel helper itself reported the failure: it is holding traffic and must not be asked to stop again here.
    fn teardown(&self, helper_already_blocked: bool) {
        let s = self.snapshot();
        if s.whole_device && !helper_already_blocked {
            if let Err(e) = self.host.tunnel_stop() {
                log::warn(TAG, &format!("stopping the tunnel helper failed: {}", e.message));
            }
        }
        if self.openvpn_guard.swap(false, Ordering::SeqCst) {
            if let Err(e) = self.host.tunnel_stop() {
                log::warn(TAG, &format!("stopping the kill switch failed: {}", e.message));
            }
        }
        if s.app_rules {
            self.host.app_rules_stop();
        }
        self.xray.stop();
        // a question that nobody will answer any more
        drop(lock(&self.credential_answer).take());
        self.update(|s| {
            s.connected_at_ms = None;
            s.exit_country = None;
            s.connected_node = None;
            s.progress = None;
            s.whole_device = false;
            s.app_rules = false;
            s.using_openvpn = false;
            s.credential_request = None;
            s.state = ConnState::Disconnected;
        });
    }

    /// How the settings and the server say this connection is made.
    fn mode(&self, node: &Node) -> Mode {
        if node.protocol == "openvpn" {
            return Mode::OpenVpn;
        }
        if !self.settings.whole_device() || self.proxy_only {
            return Mode::Proxy;
        }
        if AppRuleMode::parse(&self.settings.app_rule_mode()).is_some() && !self.settings.app_rule_apps().is_empty() {
            Mode::AppRules
        } else {
            Mode::WholeDevice
        }
    }

    /// Connects through one server: Xray runs it as a local SOCKS5 proxy; whole-device mode and app rules then send the
    /// computer's traffic into it, and OpenVPN does its own routing.
    fn connect(self: &Arc<Self>, node: Node) {
        if self.snapshot().state != ConnState::Disconnected {
            return;
        }
        let mode = self.mode(&node);
        if let Some(message) = self.cannot_connect(&node, mode) {
            self.update(|s| s.last_error = Some(message));
            return;
        }
        let generation = self.generation.fetch_add(1, Ordering::SeqCst) + 1;
        let cancel = Arc::clone(&lock(&self.cancel));
        let title = node.title();
        self.update(|s| {
            s.state = ConnState::Connecting;
            s.last_error = None;
            s.connected_node = Some(node.clone());
            s.whole_device = mode == Mode::WholeDevice;
            s.app_rules = mode == Mode::AppRules;
            s.using_openvpn = mode == Mode::OpenVpn;
            s.progress = Some(format!("Connecting to {title}..."));
        });
        let shared = Arc::clone(self);
        std::thread::spawn(move || {
            let started = Instant::now();
            let outcome = if mode == Mode::OpenVpn {
                shared.connect_openvpn(&node, generation, &cancel)
            } else {
                shared.connect_steps(&node, generation, mode)
            };
            match outcome {
                Ok(()) => log::info(
                    TAG,
                    &format!("connected via a {} server ({}) after {} ms", node.protocol, mode.label(), started.elapsed().as_millis()),
                ),
                Err(Stop::Cancelled) => {} // someone else owns the state now
                Err(Stop::Failed(message)) => {
                    log::error(TAG, &format!("connecting failed after {} ms: {message}", started.elapsed().as_millis()));
                    if shared.is_current(generation) {
                        shared.generation.fetch_add(1, Ordering::SeqCst);
                        shared.cancel_session();
                        shared.update(|s| s.last_error = Some(message));
                        shared.teardown(false);
                    }
                }
            }
        });
    }

    /// Why Connect cannot start right now, in words for the user; `None` when it can.
    fn cannot_connect(&self, node: &Node, mode: Mode) -> Option<String> {
        match mode {
            Mode::OpenVpn => {
                if !self.host.openvpn_installed() {
                    return Some("OpenVPN is not installed. Get it at openvpn.net/community-downloads.".to_string());
                }
                if ember_subs::openvpn::decode(&node.raw).is_none() {
                    return Some("This OpenVPN server could not be read. Add its file again.".to_string());
                }
                return None;
            }
            Mode::AppRules if !self.host.app_rules_available() => {
                return Some("App rules are not available in this build. Turn them off in Settings.".to_string());
            }
            Mode::WholeDevice if !self.host.tunnel_available() => {
                return Some("Whole-device mode is not available in this build. Turn it off in Settings to use proxy mode.".to_string());
            }
            _ => {}
        }
        if !self.xray.is_available() {
            return Some("The Xray program is missing from this installation.".to_string());
        }
        None
    }

    fn connect_steps(self: &Arc<Self>, node: &Node, generation: u64, mode: Mode) -> Result<(), Stop> {
        let check = || if self.is_current(generation) { Ok(()) } else { Err(Stop::Cancelled) };
        // A running speed test owns the Xray process; stop it first.
        if self.testing.load(Ordering::SeqCst) {
            self.xray.stop();
            let deadline = Instant::now() + Duration::from_secs(10);
            while self.testing.load(Ordering::SeqCst) && Instant::now() < deadline {
                std::thread::sleep(Duration::from_millis(100));
            }
        }
        check()?;
        let address = self.server_address(node)?;
        check()?;
        let routed = mode != Mode::Proxy;
        let http_port = (!routed).then_some(self.http_port);
        // Xray would fail to bind a port another program holds, and the wait below would then see *that* program listening
        // and carry on as if it were ours (another copy of Ember, another VPN client). Check first.
        for port in [Some(self.socks_port), http_port].into_iter().flatten() {
            if TcpListener::bind((LOCAL_BIND, port)).is_err() {
                return Err(Stop::Failed(format!(
                    "Port {port} on this computer is already in use. Another copy of Ember or another VPN or proxy program may be running; close it and try again."
                )));
            }
        }
        // names to reach directly, looked up now on the real network (the tunnel is not up yet)
        let skipped = if routed { skip::resolve(&self.settings.excluded_addresses(), skip::lookup_ipv4) } else { skip::SkipList::default() };
        let settings = XraySettings {
            address: address.clone(),
            socks_port: self.socks_port,
            http_port,
            log_file: self.xray.log_file(),
            verbose: self.settings.diagnostic_log(),
            bypass: if mode == Mode::AppRules { Vec::new() } else { skipped.domains.clone() },
        };
        let config = xray_config(node, &settings).map_err(|e| Stop::Failed(e.to_string()))?;
        self.xray.start(&config).map_err(|e| Stop::Failed(e.to_string()))?;
        // an antivirus scanning xray.exe on first launch can take well over 10 s
        self.xray.await_listening(self.socks_port, Duration::from_secs(30)).map_err(|e| Stop::Failed(e.to_string()))?;
        check()?;
        // Before anything on the computer is changed: does this server pass traffic at all?
        self.update(|s| s.progress = Some("Checking the server...".to_string()));
        let answered = probe(self.socks_port);
        log::info(TAG, &format!("server check: {}", answered.map(|ms| format!("{ms} ms")).unwrap_or_else(|| "failed".into())));
        check()?;
        if answered.is_none() {
            return Err(Stop::Failed("This server did not answer. Try another server.".to_string()));
        }
        self.start_routing(mode, &address, &skipped, generation)?;
        self.update(|s| {
            s.connected_at_ms = Some(crate::now_ms());
            s.progress = None;
            s.state = ConnState::Connected;
        });
        self.settings.set_was_connected(true);
        self.watch_xray(generation);
        self.watch_health(generation);
        self.check_exit(Some(self.socks_port), generation);
        if routed {
            self.check_routed_traffic(generation);
        }
        Ok(())
    }

    /// With Xray answering: sends the computer's traffic into it (whole-device tunnel or app rules). Nothing else for
    /// proxy mode. If the user disconnected while the helper was starting, it is stopped again here.
    fn start_routing(self: &Arc<Self>, mode: Mode, address: &str, skipped: &skip::SkipList, generation: u64) -> Result<(), Stop> {
        let progress = |text: &str| self.update(|s| s.progress = Some(text.to_string()));
        match mode {
            Mode::AppRules => {
                let domains: Vec<String> = skipped.domains.iter().map(|(name, _)| name.clone()).collect();
                let mode_text = self.settings.app_rule_mode();
                let request = AppRulesRequest {
                    mode: AppRuleMode::parse(&mode_text).unwrap_or(AppRuleMode::Skip),
                    apps: &self.settings.app_rule_apps(),
                    socks_port: self.socks_port,
                    skip_cidrs: &skipped.cidrs,
                    skip_domains: &domains,
                };
                let started = self.host.app_rules_start(&request, &mut { progress });
                if !self.is_current(generation) {
                    self.host.app_rules_stop();
                    return Err(Stop::Cancelled);
                }
                started.map_err(|e| Stop::Failed(e.message))?;
                self.watch_app_rules(generation);
            }
            Mode::WholeDevice => {
                let hev = host::hev_config_path(&self.data_dir);
                let yaml = windows_tunnel::hev_config(self.socks_port, self.settings.mtu(), self.settings.tcp_buffer_bytes(), self.settings.ipv6());
                std::fs::write(&hev, yaml).map_err(|e| Stop::Failed(format!("Could not write the tunnel settings: {e}")))?;
                let request = TunnelRequest {
                    hev_config: &hev,
                    edge_ips: &[address.to_string()],
                    exclude_cidrs: &skipped.cidrs,
                    kill_switch: self.settings.kill_switch(),
                    ipv6: self.settings.ipv6(),
                };
                let started = self.host.tunnel_start(&request, &mut { progress });
                if !self.is_current(generation) {
                    // the user pressed Disconnect while the helper was starting: it must not be left running
                    let _ = self.host.tunnel_stop();
                    return Err(Stop::Cancelled);
                }
                started.map_err(|e| Stop::Failed(e.message))?;
                self.watch_tunnel(generation);
            }
            Mode::Proxy | Mode::OpenVpn => {}
        }
        Ok(())
    }

    /// The server's IPv4 address: looked up now, or the last one that worked when the name does not resolve.
    fn server_address(&self, node: &Node) -> Result<String, Stop> {
        match resolve_ipv4(&node.host) {
            Some(ip) => {
                self.store.remember_address(node, &ip.to_string());
                Ok(ip.to_string())
            }
            None => match self.store.remembered_address(node) {
                Some(remembered) => {
                    log::warn(TAG, "could not look up the server's name; using its last known address");
                    Ok(remembered)
                }
                None => Err(Stop::Failed(format!("{} has no IPv4 address, which this connection needs.", node.host))),
            },
        }
    }

    /// Connects through an installed OpenVPN. OpenVPN adds its own adapter and routes (as the profile says), so Xray, the
    /// Wintun tunnel and the usual kill switch are not involved; with the kill switch on, the helper blocks everything but
    /// the server first.
    fn connect_openvpn(self: &Arc<Self>, node: &Node, generation: u64, cancel: &Arc<AtomicBool>) -> Result<(), Stop> {
        let check = || if self.is_current(generation) { Ok(()) } else { Err(Stop::Cancelled) };
        let profile = ember_subs::openvpn::decode(&node.raw)
            .map(|(_, profile)| profile)
            .ok_or_else(|| Stop::Failed("This OpenVPN server could not be read. Add its file again.".to_string()))?;
        if self.testing.load(Ordering::SeqCst) {
            self.xray.stop();
            let deadline = Instant::now() + Duration::from_secs(10);
            while self.testing.load(Ordering::SeqCst) && Instant::now() < deadline {
                std::thread::sleep(Duration::from_millis(100));
            }
        }
        check()?;
        let progress = |text: &str| self.update(|s| s.progress = Some(text.to_string()));
        let mut session_profile = profile.clone();
        if self.settings.kill_switch() && self.host.tunnel_available() {
            // Block the real network except the server before OpenVPN starts, so nothing can leak if it drops.
            progress("Setting up the kill switch…");
            let mut pinned: HashMap<String, String> = HashMap::new();
            for remote in ember_subs::openvpn::remotes(&profile) {
                if let Some(ip) = resolve_ipv4(&remote) {
                    pinned.insert(remote, ip.to_string());
                }
            }
            if pinned.is_empty() {
                return Err(Stop::Failed("Could not look up the OpenVPN server's address for the kill switch.".to_string()));
            }
            session_profile = ember_subs::openvpn::pin_remotes(&profile, &pinned);
            let mut addresses: Vec<String> = pinned.values().cloned().collect();
            addresses.sort();
            addresses.dedup();
            let started = self.host.tunnel_guard(&addresses, &mut { progress });
            self.openvpn_guard.store(true, Ordering::SeqCst);
            if !self.is_current(generation) {
                return Err(Stop::Cancelled); // the disconnect that cancelled us stops the guard in its teardown
            }
            started.map_err(|e| Stop::Failed(e.message))?;
        }
        check()?;
        let title = node.title();
        let mut ask = |subject: &str, ask_user: bool| self.ask_credentials(node, &title, subject, ask_user, generation);
        let mut connected = || {
            self.update(|s| {
                s.connected_at_ms = Some(crate::now_ms());
                s.progress = None;
                s.state = ConnState::Connected;
            });
            // with the kill switch on, a crash or restart of Ember resumes the connection like whole-device mode
            self.settings.set_was_connected(self.openvpn_guard.load(Ordering::SeqCst));
            log::info(TAG, "OpenVPN is connected");
            self.check_exit(None, generation);
        };
        let result = self.host.openvpn_session(&session_profile, &mut ask, &mut { progress }, &mut connected, cancel);
        if !self.is_current(generation) {
            return Err(Stop::Cancelled);
        }
        match result {
            Ok(()) => {
                // OpenVPN ended by itself
                if self.snapshot().state == ConnState::Connected {
                    self.update(|s| s.last_error = Some("OpenVPN stopped.".to_string()));
                }
                self.generation.fetch_add(1, Ordering::SeqCst);
                self.teardown(false);
                Ok(())
            }
            Err(HostError { auth_failed, message }) => {
                if auth_failed {
                    lock(&self.remembered).retain(|key, _| !key.starts_with(&node.id));
                }
                Err(Stop::Failed(message))
            }
        }
    }

    /// Asks the user (through the snapshot) and waits for `Engine::answer_credentials`. None when they cancel or the
    /// connection ends first.
    fn ask_credentials(&self, node: &Node, title: &str, subject: &str, ask_user: bool, generation: u64) -> Option<OpenVpnCredentials> {
        let key = format!("{}:{subject}", node.id);
        if let Some((username, password)) = lock(&self.remembered).get(&key).cloned() {
            return Some(OpenVpnCredentials { username, password });
        }
        let (tx, rx) = channel();
        *lock(&self.credential_answer) = Some(tx);
        self.update(|s| {
            s.credential_request = Some(CredentialRequest { server_name: title.to_string(), subject: subject.to_string(), ask_user })
        });
        let answer = loop {
            match rx.recv_timeout(Duration::from_millis(300)) {
                Ok(answer) => break answer,
                Err(RecvTimeoutError::Timeout) if self.is_current(generation) && !self.shutting_down.load(Ordering::SeqCst) => {}
                Err(_) => break None,
            }
        };
        drop(lock(&self.credential_answer).take());
        self.update(|s| s.credential_request = None);
        if let Some(c) = &answer {
            lock(&self.remembered).insert(key, (c.username.clone(), c.password.clone()));
        }
        answer
    }

    /// Asks Cloudflare for a tiny page the way any program on the computer would (through the tunnel). When the server
    /// works but this fails, the tunnel is not passing the computer's traffic, and the user is told.
    fn check_routed_traffic(self: &Arc<Self>, generation: u64) {
        let shared = Arc::clone(self);
        std::thread::spawn(move || {
            if !shared.sleep_current(generation, Duration::from_millis(2500)) {
                return;
            }
            let direct = Http::direct(Duration::from_secs(8), Duration::from_secs(8));
            let routed = matches!(direct.get(PROBE_URL, &[]), Ok(r) if (200..400).contains(&r.status()));
            log::info(TAG, &format!("connection check: through the tunnel={}", if routed { "ok" } else { "failed" }));
            if !routed && shared.is_current(generation) {
                shared.update(|s| {
                    s.last_error = Some("The server works, but your computer's traffic is not getting through the tunnel. See the Logs page.".to_string())
                });
            }
        });
    }

    /// Ends the connection if the tunnel helper reports a failure or the kill switch has taken over.
    fn watch_tunnel(self: &Arc<Self>, generation: u64) {
        let shared = Arc::clone(self);
        std::thread::spawn(move || {
            while shared.sleep_current(generation, Duration::from_secs(2)) {
                let reason = match shared.host.tunnel_status() {
                    Some(Status::Blocked) => "The tunnel stopped, so the kill switch is holding internet access.".to_string(),
                    Some(Status::Error(text)) => text,
                    _ => continue,
                };
                log::error(TAG, &format!("tunnel helper reports: {reason}"));
                if shared.is_current(generation) {
                    shared.generation.fetch_add(1, Ordering::SeqCst);
                    shared.update(|s| {
                        s.blocked_notice = Some(reason.clone());
                        s.last_error = Some(format!("The tunnel stopped. {reason}"));
                    });
                    shared.teardown(true);
                }
                return;
            }
        });
    }

    /// Ends the connection if sing-box stops.
    fn watch_app_rules(self: &Arc<Self>, generation: u64) {
        let shared = Arc::clone(self);
        std::thread::spawn(move || {
            while shared.sleep_current(generation, Duration::from_secs(2)) {
                if !shared.host.app_rules_running() {
                    log::error(TAG, "the app-rules program stopped; closing the connection");
                    if shared.is_current(generation) {
                        shared.generation.fetch_add(1, Ordering::SeqCst);
                        shared.update(|s| s.last_error = Some("The app-rules program stopped unexpectedly.".to_string()));
                        shared.teardown(false);
                    }
                    return;
                }
            }
        });
    }

    /// Watches which network the computer is on, for the whole life of the engine. When it joins a network that is not
    /// trusted (and the user turned on "connect on untrusted networks"), Ember connects once; it never reconnects on a
    /// network the user left on purpose. After sleep or a network change the helper re-points its route to the server.
    fn start_network_watch(self: &Arc<Self>) {
        let shared = Arc::clone(self);
        std::thread::spawn(move || {
            let mut previous: Option<String> = None;
            let mut last_tick = Instant::now();
            loop {
                let id = shared.host.network_identity();
                let resumed = last_tick.elapsed() > Duration::from_secs(25);
                last_tick = Instant::now();
                let joined_new = id.is_some() && id != previous;
                let changed = id != previous && previous.is_some() && id.is_some();
                if id != shared.snapshot().network {
                    shared.update(|s| s.network = id.clone());
                }
                previous = id.clone();
                let state = shared.snapshot();
                if joined_new
                    && shared.settings.auto_connect_untrusted()
                    && id.as_ref().is_some_and(|n| !shared.settings.trusted_networks().contains(n))
                    && state.state == ConnState::Disconnected
                    && shared.selected_node().is_some()
                {
                    log::info(TAG, "joined an untrusted network; connecting");
                    shared.reset_failover();
                    if let Some(node) = shared.selected_node() {
                        shared.connect(node);
                    }
                }
                if (resumed || changed) && state.state == ConnState::Connected && state.whole_device {
                    log::info(TAG, if resumed { "resumed from sleep; refreshing the route" } else { "network changed; refreshing the route" });
                    std::thread::sleep(Duration::from_secs(3)); // let Windows and the helper settle
                    shared.host.tunnel_refresh();
                }
                let end = Instant::now() + Duration::from_secs(5);
                while Instant::now() < end {
                    if shared.shutting_down.load(Ordering::SeqCst) {
                        return;
                    }
                    std::thread::sleep(Duration::from_millis(250));
                }
            }
        });
    }

    /// At start: did Ember go away while the kill switch or the tunnel was up? Reconnect to the same server rather than
    /// leave the computer cut off, or say what is still in place.
    fn check_interrupted(self: &Arc<Self>) {
        let blocked = self.host.tunnel_blocked();
        let stale = self.host.tunnel_stale();
        if (blocked || stale) && self.settings.was_connected() && self.selected_node().is_some() {
            self.update(|s| s.blocked_notice = Some("Ember was closed while connected. Reconnecting to keep you protected…".to_string()));
            let shared = Arc::clone(self);
            std::thread::spawn(move || {
                std::thread::sleep(Duration::from_millis(2500));
                for _ in 0..6 {
                    if shared.shutting_down.load(Ordering::SeqCst) || shared.snapshot().state != ConnState::Disconnected {
                        return;
                    }
                    let Some(node) = shared.selected_node() else { return };
                    log::info(TAG, "resuming the connection that was interrupted");
                    shared.connect(node);
                    while shared.snapshot().state == ConnState::Connecting {
                        std::thread::sleep(Duration::from_millis(500));
                    }
                    if shared.snapshot().state == ConnState::Connected {
                        shared.update(|s| s.blocked_notice = None);
                        return;
                    }
                    std::thread::sleep(Duration::from_secs(5));
                }
                shared.update(|s| s.blocked_notice = Some("Ember could not reconnect by itself. Internet access may still be blocked.".to_string()));
            });
        } else if blocked {
            self.update(|s| s.blocked_notice = Some("Ember closed while the kill switch was on, so internet access is still blocked.".to_string()));
        } else if stale {
            self.update(|s| {
                s.blocked_notice = Some(
                    "Ember was closed while the tunnel was on (or Windows restarted). Leftover network rules may still block or redirect traffic."
                        .to_string(),
                )
            });
        }
    }

    /// Ends the connection if the Xray process dies.
    fn watch_xray(self: &Arc<Self>, generation: u64) {
        let shared = Arc::clone(self);
        std::thread::spawn(move || {
            while shared.sleep_current(generation, Duration::from_secs(2)) {
                if !shared.xray.is_running() {
                    log::error(TAG, "Xray stopped; closing the connection");
                    if shared.is_current(generation) {
                        shared.generation.fetch_add(1, Ordering::SeqCst);
                        shared.update(|s| s.last_error = Some("The connection program stopped unexpectedly.".to_string()));
                        shared.teardown(false);
                    }
                    return;
                }
            }
        });
    }

    /// Xray can be running while the server behind it has stopped passing traffic. Every 15 seconds a tiny request goes
    /// through it; when three in a row fail the connection moves to the next-best server (if the user allows it).
    fn watch_health(self: &Arc<Self>, generation: u64) {
        if !self.settings.auto_failover() {
            return;
        }
        let shared = Arc::clone(self);
        std::thread::spawn(move || {
            if !shared.sleep_current(generation, Duration::from_secs(20)) {
                return; // let the connection settle
            }
            let mut failures = 0;
            let mut last_tick = Instant::now();
            while shared.sleep_current(generation, Duration::from_secs(15)) {
                let slept = last_tick.elapsed() > Duration::from_secs(45); // the computer was asleep: old numbers mean nothing
                last_tick = Instant::now();
                if slept {
                    failures = 0;
                    continue;
                }
                failures = if probe(shared.socks_port).is_some() { 0 } else { failures + 1 };
                if failures >= 3 {
                    shared.fail_over(generation);
                    return;
                }
            }
        });
    }

    fn fail_over(self: &Arc<Self>, generation: u64) {
        let Some(current) = self.snapshot().connected_node else { return };
        let next = {
            let mut failover = lock(&self.failover);
            failover.failed.insert(current.id.clone());
            if failover.count < MAX_FAILOVERS {
                let next = pick_failover(
                    &self.store.all_nodes(),
                    &current,
                    &failover.failed,
                    &self.store.results(),
                    &self.settings.favorite_nodes(),
                );
                if next.is_some() {
                    failover.count += 1;
                }
                next
            } else {
                None
            }
        };
        let Some(next) = next else {
            log::warn(TAG, &format!("{} stopped passing traffic and there is no other server to switch to", current.title()));
            self.update(|s| s.last_error = Some("This server stopped passing traffic. Pick another server.".to_string()));
            return;
        };
        if !self.is_current(generation) {
            return;
        }
        log::warn(TAG, &format!("{} stopped passing traffic; switching to {}", current.title(), next.title()));
        self.update(|s| s.notice = Some(format!("{} stopped answering. Switching to {}.", current.title(), next.title())));
        self.generation.fetch_add(1, Ordering::SeqCst);
        self.cancel_session();
        self.teardown(false);
        self.connect(next);
    }

    /// Asks Cloudflare which country the connection comes out in (through Xray's port, or directly when OpenVPN carries
    /// the traffic) and shows it instead of the server's own flag. Silent on failure.
    fn check_exit(self: &Arc<Self>, socks_port: Option<u16>, generation: u64) {
        let shared = Arc::clone(self);
        std::thread::spawn(move || {
            std::thread::sleep(Duration::from_millis(1500));
            if !shared.is_current(generation) {
                return;
            }
            let country = exit_country(socks_port);
            log::info(TAG, &format!("exit country: {}", country.as_deref().unwrap_or("unknown")));
            if shared.is_current(generation) && shared.snapshot().state == ConnState::Connected {
                shared.update(|s| s.exit_country = country);
            }
        });
    }

    fn check_site(self: &Arc<Self>, nodes: Vec<Node>, label: String, url: String) {
        let state = self.snapshot();
        if nodes.is_empty() || state.state != ConnState::Disconnected || state.test_progress.is_some() {
            return;
        }
        if !self.xray.is_available() {
            self.update(|s| s.last_error = Some("The Xray program is missing from this installation.".to_string()));
            return;
        }
        self.testing.store(true, Ordering::SeqCst);
        self.update(|s| {
            s.test_progress = Some("Starting…".to_string());
            s.site_check = Some(SiteCheck { label, results: HashMap::new() });
        });
        let shared = Arc::clone(self);
        std::thread::spawn(move || {
            let progress = |text: &str| shared.update(|s| s.test_progress = Some(text.to_string()));
            let result = |node: &Node, r| {
                shared.update(|s| {
                    if let Some(check) = s.site_check.as_mut() {
                        check.results.insert(node.id.clone(), r);
                    }
                })
            };
            if let Err(e) = ember_subs::tester::check_site(&shared.xray, &nodes, &url, &ember_subs::tester::SiteEvents { progress: &progress, result: &result }) {
                log::warn(TAG, &format!("the website check failed: {e}"));
                shared.update(|s| s.last_error = Some(e.to_string()));
            }
            shared.testing.store(false, Ordering::SeqCst);
            shared.update(|s| s.test_progress = None);
        });
    }

    fn test_nodes(self: &Arc<Self>, nodes: Vec<Node>, speed: bool) {
        let nodes: Vec<Node> = nodes.into_iter().filter(|n| n.protocol != "openvpn").collect();
        let state = self.snapshot();
        if nodes.is_empty() || state.state != ConnState::Disconnected || state.test_progress.is_some() {
            return;
        }
        if !self.xray.is_available() {
            self.update(|s| s.last_error = Some("The Xray program is missing from this installation.".to_string()));
            return;
        }
        self.testing.store(true, Ordering::SeqCst);
        self.update(|s| s.test_progress = Some("Starting…".to_string()));
        let shared = Arc::clone(self);
        std::thread::spawn(move || {
            let progress = |text: &str| shared.update(|s| s.test_progress = Some(text.to_string()));
            // a delay-only pass keeps the download speed measured earlier for a server that still answers
            let result = |node: &Node, r: ember_subs::subscription::NodeResult| {
                let r = if speed || r.ping_ms.is_none() {
                    r
                } else {
                    ember_subs::subscription::NodeResult { mbps: shared.store.results().get(&node.id).and_then(|old| old.mbps), ..r }
                };
                shared.store.record_result(node, r);
            };
            let address = |node: &Node, ip: std::net::Ipv4Addr| shared.store.remember_address(node, &ip.to_string());
            let outcome = test_nodes(&shared.xray, &nodes, speed, &TestEvents { progress: &progress, result: &result, address: &address });
            if let Err(e) = outcome {
                log::warn(TAG, &format!("the server test failed: {e}"));
                shared.update(|s| s.last_error = Some(e.to_string()));
            }
            shared.store.save_results();
            shared.testing.store(false, Ordering::SeqCst);
            shared.update(|s| s.test_progress = None);
        });
    }

    /// Keeps the subscription links fresh (usage, new or removed servers) and, in "fastest server" mode, measures the
    /// servers once at start so the choice is based on real numbers. Runs quietly; failures only go to the log.
    fn start_upkeep(self: &Arc<Self>) {
        let shared = Arc::clone(self);
        std::thread::spawn(move || {
            let sleep = |d: Duration| {
                let end = Instant::now() + d;
                while Instant::now() < end {
                    if shared.shutting_down.load(Ordering::SeqCst) {
                        return false;
                    }
                    std::thread::sleep(Duration::from_millis(250));
                }
                true
            };
            if !sleep(Duration::from_secs(4)) {
                return;
            }
            let mut first = true;
            loop {
                let now = crate::now_ms();
                for s in shared.store.subscriptions().into_iter().filter(|s| !s.url.is_empty() && now - s.updated_at_ms > REFRESH_AFTER_MS) {
                    if let Err(e) = shared.store.refresh(&s.id) {
                        log::warn(TAG, &format!("could not refresh a subscription ({e})"));
                    }
                }
                if first {
                    first = false;
                    let all: Vec<Node> = shared.store.all_nodes().into_iter().filter(|n| !n.is_info()).collect();
                    let results = shared.store.results();
                    let newest = all.iter().filter_map(|n| results.get(&n.id)).map(|r| r.tested_at_ms).max().unwrap_or(0);
                    if shared.settings.selected_node_id() == AUTO_NODE
                        && !all.is_empty()
                        && now - newest > REFRESH_AFTER_MS / 2
                        && shared.snapshot().state == ConnState::Disconnected
                    {
                        log::info(TAG, "measuring the servers in the background (fastest-server mode)");
                        shared.test_nodes(all, false); // delay only: no download unless the user asks for a speed test
                    }
                }
                if !sleep(REFRESH_CHECK) {
                    return;
                }
            }
        });
    }
}

#[derive(Clone, Copy, PartialEq, Eq, Debug)]
enum Mode {
    Proxy,
    WholeDevice,
    AppRules,
    OpenVpn,
}

impl Mode {
    fn label(self) -> &'static str {
        match self {
            Mode::Proxy => "proxy mode",
            Mode::WholeDevice => "whole device",
            Mode::AppRules => "app rules",
            Mode::OpenVpn => "OpenVPN",
        }
    }
}

enum Stop {
    /// Another connect, a disconnect or a switch took over; leave the state alone.
    Cancelled,
    Failed(String),
}

/// The two-letter country code Cloudflare's trace page reports for this connection.
fn exit_country(socks_port: Option<u16>) -> Option<String> {
    if cfg!(test) {
        return None; // tests must not reach the real internet
    }
    let (connect, read) = (Duration::from_secs(8), Duration::from_secs(8));
    let http = match socks_port {
        Some(port) => Http::socks(port, connect, read, Duration::from_secs(15), false),
        None => Http::direct(connect, read),
    };
    let body = http.get("https://www.cloudflare.com/cdn-cgi/trace", &[]).ok()?.into_text(4096).ok()?;
    let code = body.lines().find_map(|l| l.strip_prefix("loc="))?.trim().to_ascii_lowercase();
    (code.len() == 2 && code.chars().all(|c| c.is_ascii_alphabetic())).then_some(code)
}

/// Milliseconds a small request through Xray's local port took, or `None` when it failed.
fn probe(socks_port: u16) -> Option<u128> {
    let http = Http::socks(socks_port, Duration::from_secs(8), Duration::from_secs(8), Duration::from_secs(10), false);
    let start = Instant::now();
    match http.get(PROBE_URL, &[]) {
        Ok(response) if (200..400).contains(&response.status()) => Some(start.elapsed().as_millis()),
        Ok(response) => {
            log::warn(TAG, &format!("connection check failed: HTTP {}", response.status()));
            None
        }
        Err(e) => {
            log::warn(TAG, &format!("connection check failed: {e}"));
            None
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::host::fake::FakeHost;
    use std::sync::atomic::AtomicUsize;

    fn temp(name: &str) -> PathBuf {
        let dir = std::env::temp_dir().join(format!("ember-engine-test-{}-{name}", std::process::id()));
        let _ = std::fs::remove_dir_all(&dir);
        std::fs::create_dir_all(&dir).unwrap();
        dir
    }

    // Test servers use reserved names only (example.com, the .invalid TLD) and made-up ids.
    fn node(host: &str) -> Node {
        ember_subs::parse_share_link(&format!("vless://11111111-2222-3333-4444-555555555555@{host}:443?type=tcp&security=tls#Test")).unwrap()
    }

    fn engine(dir: &Path, with_xray: bool, proxy_only: bool) -> (Engine, Arc<AtomicUsize>) {
        let exe = dir.join("xray.exe");
        if with_xray {
            std::fs::write(&exe, b"not a real program").unwrap(); // only has to exist: the tests stop before Xray starts
        }
        let changes = Arc::new(AtomicUsize::new(0));
        let counter = Arc::clone(&changes);
        let engine = Engine::with_host(dir, with_xray.then_some(exe), "en", proxy_only, (SOCKS_PORT, HTTP_PORT), FakeHost::all_available(), move || {
            counter.fetch_add(1, Ordering::SeqCst);
        })
        .unwrap();
        (engine, changes)
    }

    /// An engine whose host is the recording stand-in, returned so a test can read what was asked of it.
    fn engine_with_host(dir: &Path, host: Arc<FakeHost>) -> Engine {
        let exe = dir.join("xray.exe");
        std::fs::write(&exe, b"not a real program").unwrap();
        Engine::with_host(dir, Some(exe), "en", false, (SOCKS_PORT, HTTP_PORT), host, || {}).unwrap()
    }

    fn ovpn_node(name: &str) -> Node {
        let profile = "client\ndev tun\nproto udp\nremote vpn.example.com 1194\nauth-user-pass\n";
        ember_subs::openvpn::node(profile, name).unwrap()
    }

    fn wait_for(engine: &Engine, what: impl Fn(&Snapshot) -> bool) -> Snapshot {
        let deadline = Instant::now() + Duration::from_secs(20);
        loop {
            let snapshot = engine.snapshot();
            if what(&snapshot) || Instant::now() > deadline {
                return snapshot;
            }
            std::thread::sleep(Duration::from_millis(50));
        }
    }

    #[test]
    fn a_missing_xray_is_reported_and_nothing_starts() {
        let (engine, changes) = engine(&temp("noxray"), false, true);
        engine.connect(node("a.example.com"));
        let s = engine.snapshot();
        assert_eq!(s.state, ConnState::Disconnected);
        assert!(s.last_error.unwrap().contains("Xray program is missing"));
        assert!(changes.load(Ordering::SeqCst) > 0);
    }

    #[test]
    fn the_mode_follows_the_settings_and_the_server() {
        let dir = temp("mode");
        let host = FakeHost::all_available();
        let engine = engine_with_host(&dir, host);
        let n = node("a.example.com");
        let shared = &engine.shared;
        engine.settings().set_whole_device(false);
        assert_eq!(shared.mode(&n), Mode::Proxy);
        engine.settings().set_whole_device(true);
        assert_eq!(shared.mode(&n), Mode::WholeDevice);
        engine.settings().set_app_rule_mode("skip");
        assert_eq!(shared.mode(&n), Mode::WholeDevice, "a rule mode without programs is not app rules");
        engine.settings().set_app_rule_apps(&["firefox.exe".to_string()]);
        assert_eq!(shared.mode(&n), Mode::AppRules);
        assert_eq!(shared.mode(&ovpn_node("O")), Mode::OpenVpn);
    }

    #[test]
    fn what_this_computer_lacks_is_said_in_plain_words() {
        let dir = temp("lacks");
        let host = FakeHost::all_available();
        let engine = engine_with_host(&dir, Arc::clone(&host));
        let n = node("a.example.com");
        host.tunnel_ok.store(false, Ordering::SeqCst);
        engine.connect(n.clone());
        assert!(engine.snapshot().last_error.unwrap().contains("Whole-device mode is not available"));
        engine.clear_error();
        engine.settings().set_app_rule_mode("only");
        engine.settings().set_app_rule_apps(&["a.exe".to_string()]);
        host.app_rules_ok.store(false, Ordering::SeqCst);
        engine.connect(n.clone());
        assert!(engine.snapshot().last_error.unwrap().contains("App rules are not available"));
        engine.clear_error();
        host.openvpn_ok.store(false, Ordering::SeqCst);
        engine.connect(ovpn_node("O"));
        assert!(engine.snapshot().last_error.unwrap().contains("OpenVPN is not installed"));
        assert_eq!(engine.snapshot().state, ConnState::Disconnected);
        assert!(host.calls().is_empty(), "nothing on the computer may be touched when a connection is refused");
        // proxy mode needs none of it
        engine.settings().set_whole_device(false);
        assert!(engine.shared.cannot_connect(&n, Mode::Proxy).is_none());
    }

    #[test]
    fn whole_device_routing_starts_the_helper_with_the_settings_and_the_server_address() {
        let dir = temp("routing");
        let host = FakeHost::all_available();
        let engine = engine_with_host(&dir, Arc::clone(&host));
        engine.settings().set_kill_switch(true);
        engine.settings().set_ipv6(true);
        engine.settings().set_mtu(1380);
        let skipped = skip::SkipList { cidrs: vec!["10.0.0.0/8".into(), "192.0.2.7/32".into()], domains: Vec::new() };
        engine.shared.start_routing(Mode::WholeDevice, "203.0.113.5", &skipped, engine.shared.generation.load(Ordering::SeqCst)).ok().unwrap();
        let calls = host.calls();
        assert_eq!(
            calls,
            [r#"tunnel_start edge=["203.0.113.5"] exclude=["10.0.0.0/8", "192.0.2.7/32"] kill=true ipv6=true yaml=hev-socks5-tunnel.yaml"#]
        );
        let yaml = std::fs::read_to_string(dir.join("hev-socks5-tunnel.yaml")).unwrap();
        assert!(yaml.contains("mtu: 1380") && yaml.contains("ipv6: 'fd00:8:0:1::2'") && yaml.contains(&format!("port: {SOCKS_PORT}")));
    }

    #[test]
    fn a_helper_that_fails_to_start_gives_its_message_and_a_cancelled_start_is_stopped_again() {
        let dir = temp("routing-fail");
        let host = FakeHost::all_available();
        let engine = engine_with_host(&dir, Arc::clone(&host));
        *host.tunnel_error.lock().unwrap() = Some("Windows did not allow it.".into());
        let current = engine.shared.generation.load(Ordering::SeqCst);
        let Err(Stop::Failed(message)) = engine.shared.start_routing(Mode::WholeDevice, "203.0.113.5", &skip::SkipList::default(), current) else {
            panic!("expected a failure")
        };
        assert_eq!(message, "Windows did not allow it.");
        // the user pressed Disconnect while the helper was starting
        *host.tunnel_error.lock().unwrap() = None;
        host.calls.lock().unwrap().clear();
        let stale = current.wrapping_sub(1);
        assert!(matches!(engine.shared.start_routing(Mode::WholeDevice, "203.0.113.5", &skip::SkipList::default(), stale), Err(Stop::Cancelled)));
        assert_eq!(host.calls().last().map(String::as_str), Some("tunnel_stop"));
    }

    #[test]
    fn app_rules_pass_the_mode_the_programs_and_the_skip_list() {
        let dir = temp("apps");
        let host = FakeHost::all_available();
        let engine = engine_with_host(&dir, Arc::clone(&host));
        engine.settings().set_app_rule_mode("only");
        engine.settings().set_app_rule_apps(&["firefox.exe".to_string(), "chrome.exe".to_string()]);
        let skipped = skip::SkipList { cidrs: vec!["192.0.2.7/32".into()], domains: vec![("bank.example.com".into(), vec!["192.0.2.7".into()])] };
        let current = engine.shared.generation.load(Ordering::SeqCst);
        engine.shared.start_routing(Mode::AppRules, "203.0.113.5", &skipped, current).ok().unwrap();
        assert_eq!(host.calls(), [r#"app_rules_start Only ["firefox.exe", "chrome.exe"] skip=["192.0.2.7/32"] domains=["bank.example.com"]"#]);
    }

    #[test]
    fn proxy_mode_changes_nothing_on_the_computer() {
        let dir = temp("proxy-routing");
        let host = FakeHost::all_available();
        let engine = engine_with_host(&dir, Arc::clone(&host));
        let current = engine.shared.generation.load(Ordering::SeqCst);
        engine.shared.start_routing(Mode::Proxy, "203.0.113.5", &skip::SkipList::default(), current).ok().unwrap();
        assert!(host.calls().is_empty());
    }

    fn wait_state(engine: &Engine, state: ConnState) -> Snapshot {
        wait_for(engine, |s| s.state == state)
    }

    #[test]
    fn openvpn_asks_for_credentials_remembers_them_for_the_run_and_stops_cleanly() {
        let dir = temp("ovpn-ask");
        let host = FakeHost::all_available();
        *host.ovpn_asks.lock().unwrap() = Some(("Auth".to_string(), true));
        let engine = engine_with_host(&dir, Arc::clone(&host));
        engine.settings().set_kill_switch(false); // the test server name cannot be looked up
        let n = ovpn_node("Office");
        engine.connect(n.clone());
        let asked = wait_for(&engine, |s| s.credential_request.is_some());
        let request = asked.credential_request.unwrap();
        assert_eq!((request.server_name.as_str(), request.subject.as_str(), request.ask_user), ("Office", "Auth", true));
        assert!(asked.using_openvpn && !asked.whole_device);
        engine.answer_credentials(Some(OpenVpnCredentials { username: Some("me".into()), password: "secret".into() }));
        let up = wait_state(&engine, ConnState::Connected);
        assert_eq!(up.state, ConnState::Connected);
        assert!(up.credential_request.is_none());
        assert_eq!(host.ovpn_got.lock().unwrap().clone(), Some(Some((Some("me".to_string()), "secret".to_string()))));
        engine.disconnect();
        let down = wait_state(&engine, ConnState::Disconnected);
        assert!(!down.using_openvpn && down.last_error.is_none());
        // the second connection does not ask again
        *host.ovpn_got.lock().unwrap() = None;
        engine.connect(n);
        wait_state(&engine, ConnState::Connected);
        assert_eq!(host.ovpn_got.lock().unwrap().clone(), Some(Some((Some("me".to_string()), "secret".to_string()))));
        engine.disconnect();
        wait_state(&engine, ConnState::Disconnected);
    }

    #[test]
    fn cancelling_the_credentials_ends_the_attempt_without_leaving_a_question_behind() {
        let dir = temp("ovpn-cancel");
        let host = FakeHost::all_available();
        *host.ovpn_asks.lock().unwrap() = Some(("Auth".to_string(), true));
        let engine = engine_with_host(&dir, Arc::clone(&host));
        engine.settings().set_kill_switch(false); // the test server name cannot be looked up
        engine.connect(ovpn_node("Office"));
        wait_for(&engine, |s| s.credential_request.is_some());
        engine.answer_credentials(None);
        let s = wait_state(&engine, ConnState::Disconnected);
        assert_eq!(s.last_error.as_deref(), Some("Cancelled."));
        assert!(s.credential_request.is_none() && !s.using_openvpn);
    }

    #[test]
    fn a_refused_password_is_forgotten_so_the_next_try_asks_again() {
        let dir = temp("ovpn-auth");
        let host = FakeHost::all_available();
        *host.ovpn_asks.lock().unwrap() = Some(("Auth".to_string(), true));
        host.ovpn_fails_auth.store(true, Ordering::SeqCst);
        let engine = engine_with_host(&dir, Arc::clone(&host));
        engine.settings().set_kill_switch(false); // the test server name cannot be looked up
        let n = ovpn_node("Office");
        engine.connect(n.clone());
        wait_for(&engine, |s| s.credential_request.is_some());
        engine.answer_credentials(Some(OpenVpnCredentials { username: Some("me".into()), password: "wrong".into() }));
        let s = wait_state(&engine, ConnState::Disconnected);
        assert!(s.last_error.unwrap().contains("refused the user name or password"));
        engine.connect(n);
        let again = wait_for(&engine, |s| s.credential_request.is_some());
        assert!(again.credential_request.is_some(), "the wrong password must not be reused");
        engine.answer_credentials(None);
        wait_state(&engine, ConnState::Disconnected);
    }

    #[test]
    fn the_openvpn_kill_switch_pins_the_server_address_and_is_lifted_on_disconnect() {
        let dir = temp("ovpn-guard");
        let host = FakeHost::all_available();
        let engine = engine_with_host(&dir, Arc::clone(&host));
        engine.settings().set_kill_switch(true);
        // a literal address needs no name lookup
        let profile = "client\ndev tun\nremote 203.0.113.9 1194\n";
        engine.connect(ember_subs::openvpn::node(profile, "Literal").unwrap());
        wait_state(&engine, ConnState::Connected);
        assert!(host.calls().iter().any(|c| c == r#"tunnel_guard ["203.0.113.9"]"#), "{:?}", host.calls());
        assert!(engine.settings().was_connected(), "with the guard on, a crash must resume the connection");
        engine.disconnect();
        wait_state(&engine, ConnState::Disconnected);
        assert_eq!(host.calls().iter().filter(|c| *c == "tunnel_stop").count(), 1, "the guard is stopped exactly once");
    }

    #[test]
    fn a_tunnel_error_reported_by_the_helper_ends_the_connection_and_says_why() {
        let dir = temp("watch-tunnel");
        let host = FakeHost::all_available();
        let engine = engine_with_host(&dir, Arc::clone(&host));
        let generation = engine.shared.generation.fetch_add(1, Ordering::SeqCst) + 1;
        engine.shared.update(|s| {
            s.state = ConnState::Connected;
            s.whole_device = true;
        });
        engine.shared.watch_tunnel(generation);
        *host.status.lock().unwrap() = Some(Status::Error("Adapter vanished.".into()));
        let s = wait_state(&engine, ConnState::Disconnected);
        assert_eq!(s.blocked_notice.as_deref(), Some("Adapter vanished."));
        assert_eq!(s.last_error.as_deref(), Some("The tunnel stopped. Adapter vanished."));
        assert!(!host.calls().iter().any(|c| c == "tunnel_stop"), "the helper already holds traffic; it must not be asked to stop");
    }

    #[test]
    fn restore_internet_runs_the_restore_and_clears_the_notice() {
        let dir = temp("restore");
        let host = FakeHost::all_available();
        host.blocked.store(true, Ordering::SeqCst);
        let engine = engine_with_host(&dir, Arc::clone(&host));
        assert!(engine.snapshot().blocked_notice.unwrap().contains("kill switch was on"));
        engine.restore_internet();
        let s = wait_for(&engine, |s| s.blocked_notice.is_none());
        assert!(s.blocked_notice.is_none());
        assert!(host.calls().iter().any(|c| c == "tunnel_restore"));
    }

    #[test]
    fn leftovers_from_a_crash_are_reported_when_there_is_nothing_to_resume() {
        let host = FakeHost::all_available();
        host.stale.store(true, Ordering::SeqCst);
        let engine = engine_with_host(&temp("stale"), Arc::clone(&host));
        assert!(engine.snapshot().blocked_notice.unwrap().contains("Leftover network rules"));
        let host = FakeHost::all_available();
        host.blocked.store(true, Ordering::SeqCst);
        let engine = engine_with_host(&temp("blocked"), host);
        assert!(engine.snapshot().blocked_notice.unwrap().contains("kill switch was on"));
        let engine = engine_with_host(&temp("clean"), FakeHost::all_available());
        assert!(engine.snapshot().blocked_notice.is_none());
    }

    #[test]
    fn joining_an_untrusted_network_connects_once_and_a_trusted_one_does_not() {
        let dir = temp("trust");
        let host = FakeHost::all_available();
        *host.network.lock().unwrap() = Some("wifi:Home".to_string());
        let engine = engine_with_host(&dir, Arc::clone(&host));
        engine.settings().set_auto_connect_untrusted(true);
        wait_for(&engine, |s| s.network.is_some());
        assert_eq!(engine.snapshot().network.as_deref(), Some("wifi:Home"));
        assert!(engine.trust_current_network());
        assert_eq!(engine.settings().trusted_networks(), ["wifi:Home"]);
        engine.untrust_network("wifi:Home");
        assert!(engine.settings().trusted_networks().is_empty());
    }

    #[test]
    fn the_one_time_setup_is_installed_and_removed_through_the_host() {
        let dir = temp("setup");
        let host = FakeHost::all_available();
        let engine = engine_with_host(&dir, Arc::clone(&host));
        assert!(!engine.setup_installed());
        engine.set_setup(true);
        wait_for(&engine, |_| engine.setup_installed());
        assert!(engine.setup_installed());
        assert!(host.calls().iter().any(|c| c == "setup_install"));
        engine.set_setup(false);
        wait_for(&engine, |_| !engine.setup_installed());
        assert!(!engine.setup_installed());
        assert!(host.calls().iter().any(|c| c == "setup_remove"));
    }

    #[test]
    fn a_server_that_cannot_be_looked_up_ends_disconnected_with_a_plain_error() {
        let (engine, _) = engine(&temp("lookup"), true, true);
        engine.connect(node("no-such-host.invalid"));
        assert_eq!(engine.snapshot().state, ConnState::Connecting);
        let s = wait_for(&engine, |s| s.state == ConnState::Disconnected);
        assert_eq!(s.state, ConnState::Disconnected);
        assert!(s.last_error.unwrap().contains("no-such-host.invalid has no IPv4 address"));
        assert!(s.connected_node.is_none() && s.progress.is_none());
    }

    #[test]
    fn disconnect_during_a_connect_wins_and_leaves_no_error() {
        let (engine, _) = engine(&temp("cancel"), true, true);
        engine.connect(node("no-such-host.invalid"));
        engine.disconnect();
        std::thread::sleep(Duration::from_millis(800)); // the worker finds out it was cancelled
        let s = engine.snapshot();
        assert_eq!(s.state, ConnState::Disconnected);
        assert!(s.last_error.is_none(), "a cancelled attempt must not report an error: {:?}", s.last_error);
    }

    #[test]
    fn choosing_a_server_is_remembered() {
        let dir = temp("choose");
        let (engine, _) = engine(&dir, false, true);
        engine.choose_server("auto");
        assert_eq!(engine.snapshot().selected_id, "auto");
        drop(engine);
        let (again, _) = engine_in(&dir);
        assert_eq!(again.snapshot().selected_id, "auto");
    }

    fn engine_in(dir: &Path) -> (Engine, ()) {
        (Engine::new(dir, None, "en", true, || {}).unwrap(), ())
    }

    /// Starts the real `xray.exe` (set `EMBER_TEST_XRAY` to its path) against a server that cannot answer: a closed port on
    /// this computer, so no traffic leaves it. Covers start, listen, the server check, failure and stop.
    #[test]
    fn real_xray_starts_and_is_stopped_again_when_the_server_does_not_answer() {
        let Ok(exe) = std::env::var("EMBER_TEST_XRAY") else { return };
        let dir = temp("realxray");
        let ports = ember_subs::runner::free_ports(2).unwrap();
        let engine = Engine::with_ports(&dir, Some(PathBuf::from(exe)), "en", true, (ports[0], ports[1]), || {}).unwrap();
        let unreachable = ember_subs::parse_share_link("vless://11111111-2222-3333-4444-555555555555@127.0.0.1:9?type=tcp&security=none#Closed").unwrap();
        engine.connect(unreachable);
        let seen_connecting = engine.snapshot().state == ConnState::Connecting;
        let s = wait_for(&engine, |s| s.state == ConnState::Disconnected);
        assert!(seen_connecting);
        assert_eq!(s.state, ConnState::Disconnected);
        assert_eq!(s.last_error.as_deref(), Some("This server did not answer. Try another server."));
        assert!(!engine.shared.xray.is_running(), "Xray must be stopped after a failed connect");
        // the logger is shared by every test in this process, so look in its in-memory entries
        assert!(log::entries().iter().any(|e| e.message.contains("server check: failed")));
    }

    #[test]
    fn a_port_that_another_program_holds_stops_the_connection_before_xray_starts() {
        let dir = temp("portbusy");
        let busy = TcpListener::bind((LOCAL_BIND, 0)).unwrap();
        let busy_port = busy.local_addr().unwrap().port();
        let exe = dir.join("xray.exe");
        std::fs::write(&exe, b"not a real program").unwrap();
        let engine = Engine::with_ports(&dir, Some(exe), "en", true, (busy_port, busy_port.wrapping_add(1)), || {}).unwrap();
        // resolve a real name so the worker gets as far as the port check
        engine.connect(node("localhost"));
        let s = wait_for(&engine, |s| s.state == ConnState::Disconnected);
        let error = s.last_error.unwrap();
        assert!(error.contains(&format!("Port {busy_port} on this computer is already in use")), "{error}");
        assert!(!engine.shared.xray.is_running());
    }

    #[test]
    fn connect_selected_without_a_server_asks_for_one() {
        let (engine, _) = engine(&temp("none"), true, true);
        engine.connect_selected();
        assert_eq!(engine.snapshot().last_error.as_deref(), Some("Choose a server first."));
    }
}
