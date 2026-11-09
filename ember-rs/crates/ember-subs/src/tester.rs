//! Testing servers through one Xray process (one local port per server) and asking them for a website. Port of
//! `NodeTester.kt` and `SiteChecker.kt`. Both must run while Ember is not connected, otherwise the traffic would go
//! through the tunnel.

use std::net::{Ipv4Addr, ToSocketAddrs};
use std::sync::atomic::{AtomicUsize, Ordering};
use std::sync::{mpsc, Mutex};
use std::time::{Duration, Instant};

use crate::error::ParseError;
use crate::http::Http;
use crate::node::Node;
use crate::runner::{free_ports, RunnerError, XrayRunner};
use crate::subscription::NodeResult;
use crate::xray_config::xray_config_many;

const PING_URL: &str = "http://cp.cloudflare.com/generate_204";
const SPEED_URL: &str = "https://speed.cloudflare.com/__down?bytes=30000000";
const SPEED_SECONDS: u64 = 4;
const PING_PARALLEL: usize = 16;
const SITE_PARALLEL: usize = 12;
const SPEED_TOP: usize = 12;
const RESOLVE_TIMEOUT: Duration = Duration::from_secs(6);
const START_TIMEOUT: Duration = Duration::from_secs(10);
const BROWSER_AGENT: &str =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130 Safari/537.36";

#[derive(Debug, thiserror::Error)]
pub enum TestError {
    #[error(transparent)]
    Runner(#[from] RunnerError),
    #[error(transparent)]
    Config(#[from] ParseError),
    #[error("No free local port was available.")]
    NoPorts,
}

// ---- helpers ----------------------------------------------------------------------------------------------------

/// An IPv4 address for `host`, or `None` when it cannot be looked up.
pub fn resolve_ipv4(host: &str) -> Option<Ipv4Addr> {
    (host, 0).to_socket_addrs().ok()?.find_map(|a| match a.ip() {
        std::net::IpAddr::V4(v4) => Some(v4),
        std::net::IpAddr::V6(_) => None,
    })
}

/// Looks up all `hosts` at the same time; one that takes longer than six seconds counts as not found.
pub fn resolve_all(hosts: &[String]) -> Vec<Option<Ipv4Addr>> {
    let (tx, rx) = mpsc::channel();
    for (i, host) in hosts.iter().enumerate() {
        let (tx, host) = (tx.clone(), host.clone());
        std::thread::spawn(move || {
            let _ = tx.send((i, resolve_ipv4(&host)));
        });
    }
    drop(tx);
    let mut out = vec![None; hosts.len()];
    let deadline = Instant::now() + RESOLVE_TIMEOUT;
    for _ in 0..hosts.len() {
        match rx.recv_timeout(deadline.saturating_duration_since(Instant::now())) {
            Ok((i, ip)) => out[i] = ip,
            Err(_) => break,
        }
    }
    out
}

/// Runs `work` for every item with at most `workers` at the same time; results keep the order of `items`.
fn parallel_map<T: Sync, R: Send>(items: &[T], workers: usize, work: impl Fn(usize, &T) -> R + Sync) -> Vec<R> {
    let next = AtomicUsize::new(0);
    let results: Mutex<Vec<Option<R>>> = Mutex::new((0..items.len()).map(|_| None).collect());
    std::thread::scope(|scope| {
        for _ in 0..workers.min(items.len()).max(1) {
            scope.spawn(|| loop {
                let i = next.fetch_add(1, Ordering::SeqCst);
                let Some(item) = items.get(i) else { break };
                let r = work(i, item);
                results.lock().unwrap_or_else(|e| e.into_inner())[i] = Some(r);
            });
        }
    });
    results.into_inner().unwrap_or_else(|e| e.into_inner()).into_iter().flatten().collect()
}

fn now_ms() -> i64 {
    std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).map_or(0, |d| d.as_millis() as i64)
}

/// Looks up the servers' names; returns (node, address) for those that resolved and reports those that did not.
fn resolve_nodes(candidates: &[Node], on_missing: impl Fn(&Node)) -> Vec<(Node, Ipv4Addr)> {
    let hosts: Vec<String> = candidates.iter().map(|n| n.host.clone()).collect();
    let mut reachable = Vec::new();
    for (node, ip) in candidates.iter().zip(resolve_all(&hosts)) {
        match ip {
            Some(ip) => reachable.push((node.clone(), ip)),
            None => on_missing(node),
        }
    }
    reachable
}

/// Starts one Xray for `reachable` (a local port each) and returns the (node, address, port) entries.
fn start_xray(xray: &XrayRunner, reachable: Vec<(Node, Ipv4Addr)>) -> Result<Vec<(Node, String, u16)>, TestError> {
    let ports = free_ports(reachable.len()).map_err(|_| TestError::NoPorts)?;
    let entries: Vec<(Node, String, u16)> =
        reachable.into_iter().zip(ports).map(|((node, ip), port)| (node, ip.to_string(), port)).collect();
    xray.start(&xray_config_many(&entries, &xray.log_file())?)?;
    xray.await_listening(entries[0].2, START_TIMEOUT)?;
    Ok(entries)
}

// ---- speed and delay --------------------------------------------------------------------------------------------

/// What [`test_nodes`] reports while it works.
pub struct TestEvents<'a> {
    pub progress: &'a (dyn Fn(&str) + Sync),
    pub result: &'a (dyn Fn(&Node, NodeResult) + Sync),
    /// The IPv4 address a server's name resolved to (kept for when a later lookup fails).
    pub address: &'a (dyn Fn(&Node, Ipv4Addr) + Sync),
}

/// Tests every server: first how fast each answers, then (when `speed` is set) the download speed of the quickest ones
/// one after another so they do not share the bandwidth. Without `speed` only the delay is measured, which is quick and
/// uses almost no data.
pub fn test_nodes(xray: &XrayRunner, nodes: &[Node], speed: bool, events: &TestEvents) -> Result<(), TestError> {
    // OpenVPN servers do not run through Xray, so they cannot be tested this way.
    let candidates: Vec<Node> = nodes.iter().filter(|n| !n.is_info() && n.protocol != "openvpn").cloned().collect();
    (events.progress)("Looking up the servers…");
    let reachable = resolve_nodes(&candidates, |node| {
        (events.result)(node, NodeResult { ping_ms: None, mbps: None, tested_at_ms: now_ms() });
    });
    for (node, ip) in &reachable {
        (events.address)(node, *ip);
    }
    if reachable.is_empty() {
        return Ok(());
    }
    let outcome = run_tests(xray, reachable, speed, events);
    xray.stop();
    outcome
}

fn run_tests(xray: &XrayRunner, reachable: Vec<(Node, Ipv4Addr)>, speed_too: bool, events: &TestEvents) -> Result<(), TestError> {
    let entries = start_xray(xray, reachable)?;
    (events.progress)("Measuring delay…");
    let done = AtomicUsize::new(0);
    let pings = parallel_map(&entries, PING_PARALLEL, |i, (node, _, port)| {
        let ping = ping(*port);
        let n = done.fetch_add(1, Ordering::SeqCst) + 1;
        (events.progress)(&format!("Measuring delay… {n} of {}", entries.len()));
        (events.result)(node, NodeResult { ping_ms: ping, mbps: None, tested_at_ms: now_ms() });
        (i, ping)
    });
    if !speed_too {
        return Ok(());
    }
    let mut answered: Vec<(usize, Option<i32>)> = pings.into_iter().filter(|p| p.1.is_some()).collect();
    answered.sort_by_key(|p| p.1);
    answered.truncate(SPEED_TOP);
    for (n, (i, ping)) in answered.iter().enumerate() {
        (events.progress)(&format!("Measuring speed… {} of {}", n + 1, answered.len()));
        let (node, _, port) = &entries[*i];
        (events.result)(node, NodeResult { ping_ms: *ping, mbps: speed(*port), tested_at_ms: now_ms() });
    }
    Ok(())
}

fn probe_client(port: u16, seconds: u64) -> Http {
    Http::socks(port, Duration::from_secs(seconds), Duration::from_secs(seconds), Duration::from_secs(seconds + 2), true)
}

/// Round trip through the server in milliseconds: the best of two requests (the first one opens the connection).
fn ping(port: u16) -> Option<i32> {
    let client = probe_client(port, 7);
    (0..2)
        .filter_map(|_| {
            let start = Instant::now();
            let response = client.get(PING_URL, &[]).ok()?;
            let ok = (200..400).contains(&response.status());
            response.into_text(1 << 16).ok()?;
            ok.then(|| start.elapsed().as_millis() as i32)
        })
        .min()
}

/// Download speed in megabits per second over about [`SPEED_SECONDS`] seconds, or `None` when nothing came through.
fn speed(port: u16) -> Option<f64> {
    use std::io::Read;
    let response = probe_client(port, 12).get(SPEED_URL, &[("User-Agent", "Mozilla/5.0")]).ok()?;
    if !(200..300).contains(&response.status()) {
        return None;
    }
    let mut reader = response.into_reader();
    let mut buffer = vec![0u8; 64 * 1024];
    let start = Instant::now();
    let mut received = 0u64;
    while start.elapsed() < Duration::from_secs(SPEED_SECONDS) {
        match reader.read(&mut buffer) {
            Ok(0) => break,
            Ok(n) => received += n as u64,
            Err(_) => return None,
        }
    }
    let seconds = start.elapsed().as_secs_f64();
    (received >= 50_000 && seconds > 0.0).then(|| received as f64 * 8.0 / seconds / 1_000_000.0)
}

// ---- website check ----------------------------------------------------------------------------------------------

/// What one server got when asked for a website. `code` `None` means no answer. A site that does not want the server's
/// address (a region block or a banned address range) answers 403 or 451, so those count as "does not work"; anything
/// else that is not a server error (even 401 or 404) means the site was reached.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct SiteResult {
    pub code: Option<u16>,
    pub ms: Option<i32>,
}

impl SiteResult {
    pub fn ok(&self) -> bool {
        self.code.is_some_and(|c| c != 403 && c != 451 && c < 500)
    }
}

/// Websites people most often ask about, each with an address that answers 403 when the server's address is refused.
pub const SITE_PRESETS: [(&str, &str); 3] = [
    ("Spotify", "https://apresolve.spotify.com/?type=spclient"),
    ("YouTube", "https://www.youtube.com/"),
    ("ChatGPT", "https://api.openai.com/v1/models"),
];

/// A web address from what the user typed ("open.spotify.com" or a full link), or `None` when it is not one.
pub fn normalize_site_url(input: &str) -> Option<String> {
    let text = input.trim();
    if text.is_empty() {
        return None;
    }
    let full = if text.contains("://") { text.to_string() } else { format!("https://{text}") };
    let parsed = url::Url::parse(&full).ok()?;
    if !matches!(parsed.scheme().to_lowercase().as_str(), "http" | "https") {
        return None;
    }
    parsed.host_str().filter(|h| h.contains('.')).map(|_| full)
}

/// What [`check_site`] reports while it works.
pub struct SiteEvents<'a> {
    pub progress: &'a (dyn Fn(&str) + Sync),
    pub result: &'a (dyn Fn(&Node, SiteResult) + Sync),
}

/// Asks every server for one website, all at the same time through one Xray process, so the user can see which
/// servers the site accepts.
pub fn check_site(xray: &XrayRunner, nodes: &[Node], url: &str, events: &SiteEvents) -> Result<(), TestError> {
    let candidates: Vec<Node> = nodes.iter().filter(|n| !n.is_info() && n.protocol != "openvpn").cloned().collect();
    (events.progress)("Looking up the servers…");
    let reachable = resolve_nodes(&candidates, |node| (events.result)(node, SiteResult { code: None, ms: None }));
    if reachable.is_empty() {
        return Ok(());
    }
    let outcome = (|| {
        let entries = start_xray(xray, reachable)?;
        let done = AtomicUsize::new(0);
        parallel_map(&entries, SITE_PARALLEL, |_, (node, _, port)| {
            let result = ask(*port, url);
            let n = done.fetch_add(1, Ordering::SeqCst) + 1;
            (events.progress)(&format!("Asking the servers… {n} of {}", entries.len()));
            (events.result)(node, result);
        });
        Ok(())
    })();
    xray.stop();
    outcome
}

fn ask(port: u16, url: &str) -> SiteResult {
    let client = Http::socks(port, Duration::from_secs(10), Duration::from_secs(10), Duration::from_secs(14), false);
    let start = Instant::now();
    match client.get(url, &[("User-Agent", BROWSER_AGENT)]) {
        Ok(response) => SiteResult { code: Some(response.status()), ms: Some(start.elapsed().as_millis() as i32) },
        Err(_) => SiteResult { code: None, ms: None },
    }
}
