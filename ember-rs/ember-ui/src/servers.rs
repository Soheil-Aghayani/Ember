//! Feeds the Servers page: turns the engine's subscriptions into rows, and only touches the window when something that
//! is shown has changed (a speed test reports progress many times a second, and replacing the list that often would
//! reset scrolling and hover).

use std::cell::RefCell;
use std::collections::HashSet;
use std::sync::{Arc, Mutex};

use ember_engine::{Engine, SiteCheck};
use ember_subs::subscription::{NodeResult, Subscription};
use ember_subs::Node;
use slint::{ModelRc, SharedString, VecModel};

use crate::strings::{fill, Strings};
use crate::{App, NodeRow, SubRow};

#[derive(Default)]
pub struct ServersUi {
    /// Cards that are open. Only the window thread touches it.
    pub expanded: RefCell<HashSet<String>>,
    /// Subscriptions being refreshed right now; worker threads add and remove ids.
    pub refreshing: Arc<Mutex<HashSet<String>>>,
    signature: RefCell<String>,
}

impl ServersUi {
    /// Makes the next [`apply`] rebuild the page even if the data is the same (a card was opened or closed).
    pub fn invalidate(&self) {
        self.signature.borrow_mut().clear();
    }
}

/// `1.5 GB`, as `formatBytes` in the Kotlin app.
pub fn format_bytes(bytes: i64) -> String {
    if bytes < 0 {
        return "\u{2014}".to_string();
    }
    if bytes < 1024 {
        return format!("{bytes} B");
    }
    const UNITS: [&str; 5] = ["KB", "MB", "GB", "TB", "PB"];
    let mut value = bytes as f64 / 1024.0;
    let mut index = 0;
    while value >= 1024.0 && index < UNITS.len() - 1 {
        value /= 1024.0;
        index += 1;
    }
    if value >= 100.0 {
        format!("{value:.0} {}", UNITS[index])
    } else {
        format!("{value:.1} {}", UNITS[index])
    }
}

fn node_row(
    node: &Node,
    result: Option<&NodeResult>,
    selected_id: &str,
    favorites: &HashSet<String>,
    fastest: f64,
    check: Option<&SiteCheck>,
) -> NodeRow {
    let code = node.flag_code();
    let image = code.as_deref().and_then(crate::flag);
    // while a website check shows, each server shows what the site answered, instead of its speed and delay
    if let Some(check) = check {
        let answer = check.results.get(&node.id);
        let (text, site) = match answer {
            Some(r) if r.ok() => (format!("{} \u{b7} {} ms", r.code.unwrap_or(0), r.ms.unwrap_or(0)), 1),
            Some(r) => (r.code.map(|c| c.to_string()).unwrap_or_default(), 2),
            None => (String::new(), 0),
        };
        return NodeRow {
            id: node.id.as_str().into(),
            title: node.title().into(),
            protocol: node.protocol.to_uppercase().into(),
            has_flag: image.is_some(),
            flag: image.unwrap_or_default(),
            has_result: answer.is_some(),
            no_answer: answer.is_some_and(|r| r.code.is_none()),
            speed: text.into(),
            ping: SharedString::new(),
            has_bar: false,
            bar: 0.0,
            starred: favorites.contains(&node.id),
            selected: false,
            has_qr: node.share_link().is_some(),
            site,
        };
    }
    let (speed, ping) = match result {
        Some(r) => (
            r.mbps.map(|m| format!("{m:.1} Mbps")).unwrap_or_default(),
            r.ping_ms.map(|p| format!("{p} ms")).unwrap_or_default(),
        ),
        None => (String::new(), String::new()),
    };
    let mbps = result.and_then(|r| r.mbps);
    NodeRow {
        id: node.id.as_str().into(),
        title: node.title().into(),
        protocol: node.protocol.to_uppercase().into(),
        has_flag: image.is_some(),
        flag: image.unwrap_or_default(),
        has_result: result.is_some(),
        no_answer: result.is_some_and(|r| r.ping_ms.is_none()),
        speed: speed.into(),
        ping: ping.into(),
        has_bar: mbps.is_some() && fastest > 0.0,
        bar: mbps.map(|m| (m / fastest).clamp(0.04, 1.0) as f32).unwrap_or(0.0),
        starred: favorites.contains(&node.id),
        selected: node.id == selected_id,
        has_qr: node.share_link().is_some(),
        site: 0,
    }
}

/// True for a panel's information line that only restates the usage (traffic left, days left, expiry).
fn repeats_usage(line: &str) -> bool {
    let text = line.to_lowercase();
    let has_number = text.chars().any(|c| c.is_ascii_digit());
    let words = ["traffic", "left", "remaining", "expire", "quota", "used", "volume", "gb", "tb", "day", "\u{62d}\u{62c}\u{645}", "\u{628}\u{627}\u{642}\u{6cc}", "\u{631}\u{648}\u{632}", "\u{627}\u{646}\u{642}\u{636}\u{627}", "\u{62a}\u{631}\u{627}\u{641}\u{6cc}\u{6a9}"];
    has_number && words.iter().any(|w| text.contains(w))
}

/// What the page shows beside the subscriptions themselves.
struct PageData<'a> {
    results: &'a std::collections::HashMap<String, NodeResult>,
    favorites: &'a HashSet<String>,
    selected_id: &'a str,
    testing: Option<&'a str>,
    check: Option<&'a SiteCheck>,
}

fn sub_row(sub: &Subscription, s: &Strings, ui: &ServersUi, d: &PageData) -> SubRow {
    let PageData { results, favorites, selected_id, testing, check } = *d;
    let servers: Vec<Node> = sub.nodes.iter().filter(|n| !n.is_info()).cloned().collect();
    // a panel's own "traffic left" or "days left" line only repeats the usage bar below it, so it is dropped when the bar is there
    let has_usage = sub.usage.as_ref().is_some_and(|u| u.total > 0);
    let mut info: Vec<&str> = Vec::new();
    for line in sub.nodes.iter().filter(|n| n.is_info()).map(|n| n.name.as_str()) {
        if !(has_usage && repeats_usage(line)) && !info.contains(&line) {
            info.push(line);
        }
    }
    let sorted = ember_subs::sort::sort_nodes(&servers, results, favorites);
    let fastest = sorted.iter().filter_map(|n| results.get(&n.id).and_then(|r| r.mbps)).fold(0.0, f64::max);
    let nodes: Vec<NodeRow> = sorted.iter().map(|n| node_row(n, results.get(&n.id), selected_id, favorites, fastest, check)).collect();

    let usage = sub.usage.as_ref().filter(|u| u.total > 0);
    let usage_text = usage
        .map(|u| {
            let mut text = fill(s.usage_of, &[("used", &format_bytes(u.used())), ("total", &format_bytes(u.total))]);
            let days = u.expires_at_epoch_seconds.map(|e| (e - ember_engine::now_ms() / 1000) / 86_400);
            if let Some(days) = days.filter(|d| *d >= 0) {
                text.push_str(" \u{b7} ");
                text.push_str(&fill(s.days_left, &[("0", &days.to_string())]));
            }
            text
        })
        .unwrap_or_default();
    SubRow {
        id: sub.id.as_str().into(),
        name: sub.name.as_str().into(),
        url: sub.url.as_str().into(),
        status: testing.map(str::to_string).unwrap_or_else(|| fill(s.servers_count, &[("0", &servers.len().to_string())])).into(),
        has_usage: usage.is_some(),
        usage_fraction: usage.map(|u| (u.used() as f32 / u.total as f32).clamp(0.0, 1.0)).unwrap_or(0.0),
        usage_text: usage_text.into(),
        info: info.join("\n").into(),
        can_refresh: !sub.url.is_empty(),
        refreshing: lock(&ui.refreshing).contains(&sub.id),
        expanded: ui.expanded.borrow().contains(&sub.id),
        nodes: ModelRc::new(VecModel::from(nodes)),
    }
}

fn lock<T>(m: &Mutex<T>) -> std::sync::MutexGuard<'_, T> {
    m.lock().unwrap_or_else(|e| e.into_inner())
}

/// A short text that changes whenever anything the page shows changes.
fn signature(rows: &[SubRow], testing: Option<&str>) -> String {
    use slint::Model;
    let mut text = String::new();
    for r in rows {
        text.push_str(&format!("{}|{}|{}|{}|{}|{}|{};", r.id, r.name, r.status, r.usage_text, r.expanded, r.refreshing, r.info));
        for n in r.nodes.iter() {
            text.push_str(&format!("{}|{}|{}|{}|{}|{}|{:.2}|{}|{};", n.id, n.title, n.speed, n.ping, n.starred, n.selected, n.bar, n.site, n.has_result));
        }
    }
    text.push_str(testing.unwrap_or(""));
    text
}

/// Copies the engine's subscriptions into the page, if they changed.
pub fn apply(app: &App, engine: &Engine, s: &Strings, ui: &ServersUi) {
    let snap = engine.snapshot();
    let store = engine.store();
    let results = store.results();
    let favorites = engine.settings().favorite_nodes();
    let testing = snap.test_progress.as_deref();
    let data = PageData { results: &results, favorites: &favorites, selected_id: &snap.selected_id, testing, check: snap.site_check.as_ref() };
    let rows: Vec<SubRow> = store
        .subscriptions()
        .iter()
        .map(|sub| sub_row(sub, s, ui, &data))
        .collect();
    app.set_site_label(snap.site_check.as_ref().map(|c| c.label.as_str()).unwrap_or("").into());
    let new_signature = signature(&rows, testing);
    if *ui.signature.borrow() == new_signature {
        return;
    }
    *ui.signature.borrow_mut() = new_signature;
    app.set_subs(ModelRc::new(VecModel::from(rows)));
}

/// What a successful add says.
pub fn added_message(s: &Strings, count: usize) -> SharedString {
    if count == 1 { s.servers_added_one.to_string() } else { fill(s.servers_added, &[("0", &count.to_string())]) }.into()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn formats_bytes_like_the_kotlin_app() {
        assert_eq!(format_bytes(-1), "\u{2014}");
        assert_eq!(format_bytes(0), "0 B");
        assert_eq!(format_bytes(1023), "1023 B");
        assert_eq!(format_bytes(1024), "1.0 KB");
        assert_eq!(format_bytes(1_572_864), "1.5 MB");
        assert_eq!(format_bytes(150 * 1024 * 1024), "150 MB");
        assert_eq!(format_bytes(5 * 1024 * 1024 * 1024), "5.0 GB");
    }
}
