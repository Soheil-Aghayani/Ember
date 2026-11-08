//! Owns the subscription servers and the Xray connection. Port of `EmberEngine.kt`, first slice: proxy mode only.
//!
//! Threads instead of coroutines. Every connection attempt gets a *generation* number; stopping, switching server or a
//! failure bumps it, and a worker or watcher whose generation is no longer current stops quietly without touching the
//! state. The UI reads a [`Snapshot`] and is told by the `on_change` callback (called from any thread) that it changed.
//!
//! Not in this slice (the engine says so instead of pretending): whole-device mode, app rules, OpenVPN, the kill
//! switch, the exit-country check, the speed test, trusted-network auto-connect and stale-Xray clean-up. Those need
//! `ember-win` wired in.

use std::collections::HashSet;
use std::net::TcpListener;
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::{Arc, Mutex, MutexGuard};
use std::time::{Duration, Instant};

use ember_subs::http::Http;
use ember_subs::runner::XrayRunner;
use ember_subs::sort::pick_failover;
use ember_subs::subscription::AUTO_NODE;
use ember_subs::tester::{resolve_ipv4, test_nodes, TestEvents};
use ember_subs::xray_config::{xray_config, XraySettings};
use ember_subs::Node;

use crate::log;
use crate::settings::Settings;
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
    /// Connect in proxy mode even when the settings ask for whole-device mode (which this build cannot do yet).
    proxy_only: bool,
    socks_port: u16,
    http_port: u16,
    on_change: Box<dyn Fn() + Send + Sync>,
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
        (socks_port, http_port): (u16, u16),
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
            on_change: Box::new(on_change),
        });
        shared.start_upkeep();
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
            shared.teardown();
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

    pub fn disconnect(&self) {
        let shared = &self.shared;
        shared.reset_failover();
        shared.settings.set_was_connected(false);
        shared.generation.fetch_add(1, Ordering::SeqCst); // stops the worker and the watchers
        shared.teardown();
    }

    /// Measures delay and download speed of `nodes`. Only while disconnected, or the test would use the tunnel.
    pub fn test_nodes(&self, nodes: Vec<Node>) {
        self.shared.test_nodes(nodes);
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

    /// Stops everything (call when the app quits).
    pub fn shutdown(&self) {
        let shared = &self.shared;
        shared.shutting_down.store(true, Ordering::SeqCst);
        shared.settings.set_was_connected(false);
        shared.generation.fetch_add(1, Ordering::SeqCst);
        shared.teardown();
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

    /// Stops Xray and puts everything back to "disconnected". Safe to call twice.
    fn teardown(&self) {
        self.xray.stop();
        self.update(|s| {
            s.connected_at_ms = None;
            s.connected_node = None;
            s.progress = None;
            s.state = ConnState::Disconnected;
        });
    }

    /// Connects through one server: Xray runs it as a local SOCKS5 proxy (and a local HTTP proxy).
    fn connect(self: &Arc<Self>, node: Node) {
        if self.snapshot().state != ConnState::Disconnected {
            return;
        }
        if let Some(message) = self.cannot_connect(&node) {
            self.update(|s| s.last_error = Some(message));
            return;
        }
        let generation = self.generation.fetch_add(1, Ordering::SeqCst) + 1;
        let title = node.title();
        self.update(|s| {
            s.state = ConnState::Connecting;
            s.last_error = None;
            s.connected_node = Some(node.clone());
            s.progress = Some(format!("Connecting to {title}..."));
        });
        let shared = Arc::clone(self);
        std::thread::spawn(move || {
            let started = Instant::now();
            match shared.connect_steps(&node, generation) {
                Ok(()) => log::info(
                    TAG,
                    &format!("connected via a {} server (proxy mode) after {} ms", node.protocol, started.elapsed().as_millis()),
                ),
                Err(Stop::Cancelled) => {} // someone else owns the state now
                Err(Stop::Failed(message)) => {
                    log::error(TAG, &format!("connecting failed after {} ms: {message}", started.elapsed().as_millis()));
                    if shared.is_current(generation) {
                        shared.generation.fetch_add(1, Ordering::SeqCst);
                        shared.update(|s| s.last_error = Some(message));
                        shared.teardown();
                    }
                }
            }
        });
    }

    /// Why Connect cannot start right now, in words for the user; `None` when it can.
    fn cannot_connect(&self, node: &Node) -> Option<String> {
        if node.protocol == "openvpn" {
            return Some("OpenVPN servers are not available in this build yet.".to_string());
        }
        if self.settings.whole_device() && !self.proxy_only {
            return Some(
                "Whole-device mode is not available in this build yet. Turn it off in Settings to use proxy mode.".to_string(),
            );
        }
        if !self.xray.is_available() {
            return Some("The Xray program is missing from this installation.".to_string());
        }
        None
    }

    fn connect_steps(self: &Arc<Self>, node: &Node, generation: u64) -> Result<(), Stop> {
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
        let address = match resolve_ipv4(&node.host) {
            Some(ip) => {
                self.store.remember_address(node, &ip.to_string());
                ip.to_string()
            }
            None => match self.store.remembered_address(node) {
                Some(remembered) => {
                    log::warn(TAG, "could not look up the server's name; using its last known address");
                    remembered
                }
                None => return Err(Stop::Failed(format!("{} has no IPv4 address, which this connection needs.", node.host))),
            },
        };
        check()?;
        // Xray would fail to bind a port another program holds, and the wait below would then see *that* program listening
        // and carry on as if it were ours (another copy of Ember, another VPN client). Check first.
        for port in [self.socks_port, self.http_port] {
            if TcpListener::bind((LOCAL_BIND, port)).is_err() {
                return Err(Stop::Failed(format!(
                    "Port {port} on this computer is already in use. Another copy of Ember or another VPN or proxy program may be running; close it and try again."
                )));
            }
        }
        let settings = XraySettings {
            address: address.clone(),
            socks_port: self.socks_port,
            http_port: Some(self.http_port),
            log_file: self.xray.log_file(),
            verbose: self.settings.diagnostic_log(),
            bypass: Vec::new(),
        };
        let config = xray_config(node, &settings).map_err(|e| Stop::Failed(e.to_string()))?;
        self.xray.start(&config).map_err(|e| Stop::Failed(e.to_string()))?;
        // an antivirus scanning xray.exe on first launch can take well over 10 s
        self.xray.await_listening(self.socks_port, Duration::from_secs(30)).map_err(|e| Stop::Failed(e.to_string()))?;
        check()?;
        // Does this server pass traffic at all?
        self.update(|s| s.progress = Some("Checking the server...".to_string()));
        let answered = probe(self.socks_port);
        log::info(TAG, &format!("server check: {}", answered.map(|ms| format!("{ms} ms")).unwrap_or_else(|| "failed".into())));
        check()?;
        if answered.is_none() {
            return Err(Stop::Failed("This server did not answer. Try another server.".to_string()));
        }
        self.update(|s| {
            s.connected_at_ms = Some(crate::now_ms());
            s.progress = None;
            s.state = ConnState::Connected;
        });
        self.settings.set_was_connected(true);
        self.watch_xray(generation);
        self.watch_health(generation);
        Ok(())
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
                        shared.teardown();
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
        self.teardown();
        self.connect(next);
    }

    fn test_nodes(self: &Arc<Self>, nodes: Vec<Node>) {
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
            let result = |node: &Node, r| shared.store.record_result(node, r);
            let address = |node: &Node, ip: std::net::Ipv4Addr| shared.store.remember_address(node, &ip.to_string());
            let outcome = test_nodes(&shared.xray, &nodes, &TestEvents { progress: &progress, result: &result, address: &address });
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
                        shared.test_nodes(all);
                    }
                }
                if !sleep(REFRESH_CHECK) {
                    return;
                }
            }
        });
    }
}

enum Stop {
    /// Another connect, a disconnect or a switch took over; leave the state alone.
    Cancelled,
    Failed(String),
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
        let engine = Engine::new(dir, with_xray.then_some(exe), "en", proxy_only, move || {
            counter.fetch_add(1, Ordering::SeqCst);
        })
        .unwrap();
        (engine, changes)
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
    fn whole_device_mode_is_refused_rather_than_quietly_downgraded() {
        // wholeDevice defaults to on, as in the Kotlin app
        let (engine, _) = engine(&temp("whole"), true, false);
        engine.connect(node("a.example.com"));
        let s = engine.snapshot();
        assert_eq!(s.state, ConnState::Disconnected);
        assert!(s.last_error.unwrap().contains("Whole-device mode is not available"));
        // switching it off in the settings file is enough
        engine.settings().set_whole_device(false);
        assert!(engine.shared.cannot_connect(&node("a.example.com")).is_none());
    }

    #[test]
    fn openvpn_servers_are_refused_for_now() {
        let (engine, _) = engine(&temp("ovpn"), true, true);
        let mut ovpn = node("a.example.com");
        ovpn.protocol = "openvpn".into();
        engine.connect(ovpn);
        assert!(engine.snapshot().last_error.unwrap().contains("OpenVPN"));
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
