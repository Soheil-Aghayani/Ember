//! The user's subscriptions and the servers last read from them, in one JSON file (`subscriptions.json`) in the same
//! layout the Kotlin app writes. Port of `SubscriptionStore` in `Subscriptions.kt`. Network calls block, so the UI calls
//! them from a worker thread.
//!
//! A subscription link holds the account's secret: only the host is ever logged.

use std::collections::HashMap;
use std::path::{Path, PathBuf};
use std::sync::{Mutex, MutexGuard};

use ember_subs::subscription::{
    fetch_subscription, parse_pasted, subscription_http, url_host, NodeResult, Subscription, SubscriptionUsage,
};
use ember_subs::{parse_share_link, Node};
use serde_json::{json, Map, Value};

use crate::log;

const TAG: &str = "Subscriptions";

#[derive(Default)]
struct Inner {
    subscriptions: Vec<Subscription>,
    results: HashMap<String, NodeResult>,
    /// The last IPv4 address each server's name resolved to, used when a later lookup fails.
    addresses: HashMap<String, String>,
}

pub struct SubscriptionStore {
    file: PathBuf,
    inner: Mutex<Inner>,
}

/// Eight hex characters, like the first eight of a UUID in the Kotlin app.
fn new_id() -> String {
    use std::hash::{BuildHasher, Hasher};
    let mut hasher = std::collections::hash_map::RandomState::new().build_hasher();
    hasher.write_i64(crate::now_ms());
    hasher.write_usize(std::process::id() as usize);
    format!("{:08x}", hasher.finish() as u32)
}

impl SubscriptionStore {
    pub fn open(file: &Path) -> SubscriptionStore {
        let store = SubscriptionStore { file: file.to_path_buf(), inner: Mutex::new(Inner::default()) };
        store.load();
        store
    }

    fn lock(&self) -> MutexGuard<'_, Inner> {
        self.inner.lock().unwrap_or_else(|e| e.into_inner())
    }

    pub fn subscriptions(&self) -> Vec<Subscription> {
        self.lock().subscriptions.clone()
    }

    pub fn results(&self) -> HashMap<String, NodeResult> {
        self.lock().results.clone()
    }

    /// Every server of every subscription, information rows included.
    pub fn all_nodes(&self) -> Vec<Node> {
        self.lock().subscriptions.iter().flat_map(|s| s.nodes.iter().cloned()).collect()
    }

    pub fn node_by_id(&self, id: &str) -> Option<Node> {
        self.lock().subscriptions.iter().flat_map(|s| s.nodes.iter()).find(|n| n.id == id).cloned()
    }

    /// The tested server with the best speed (or, failing that, the lowest delay) across all subscriptions.
    pub fn best_node(&self) -> Option<Node> {
        let inner = self.lock();
        let nodes: Vec<Node> = inner.subscriptions.iter().flat_map(|s| s.nodes.iter().cloned()).collect();
        ember_subs::sort::best_node(&nodes, &inner.results)
    }

    pub fn remember_address(&self, node: &Node, ip: &str) {
        let changed = self.lock().addresses.insert(node.id.clone(), ip.to_string()).as_deref() != Some(ip);
        if changed {
            self.save();
        }
    }

    pub fn remembered_address(&self, node: &Node) -> Option<String> {
        self.lock().addresses.get(&node.id).cloned()
    }

    pub fn record_result(&self, node: &Node, result: NodeResult) {
        self.lock().results.insert(node.id.clone(), result);
    }

    pub fn save_results(&self) {
        self.save();
    }

    /// Adds a subscription link (https://...), or server links / a WireGuard or OpenVPN file / Xray JSON pasted as text.
    pub fn add(&self, url: &str, name: &str) -> Result<Subscription, String> {
        let clean = url.trim();
        if !clean.starts_with("https://") && !clean.starts_with("http://") {
            return self.add_pasted(clean, name);
        }
        let host = url_host(clean).ok_or_else(|| "That does not look like a link.".to_string())?;
        let fetched = fetch(clean)?;
        let subscription = Subscription {
            id: new_id(),
            name: if name.trim().is_empty() { fetched.title.clone().unwrap_or(host) } else { name.trim().to_string() },
            url: clean.to_string(),
            nodes: fetched.nodes,
            usage: fetched.usage,
            updated_at_ms: crate::now_ms(),
        };
        self.lock().subscriptions.push(subscription.clone());
        self.save();
        Ok(subscription)
    }

    /// Registers a free Cloudflare WARP device and keeps its WireGuard servers as a list of their own.
    pub fn add_warp(&self) -> Result<Subscription, String> {
        const NAME: &str = "Cloudflare WARP";
        if self.subscriptions().iter().any(|s| s.name == NAME) {
            return Err("Cloudflare WARP is already in your list.".to_string());
        }
        let http = ember_subs::http::Http::direct(std::time::Duration::from_secs(10), std::time::Duration::from_secs(20));
        let registration = ember_subs::warp::register(&http).map_err(|e| e.to_string())?;
        let nodes = ember_subs::warp::configs(&registration)
            .into_iter()
            .map(|(name, text)| ember_subs::config_files::wireguard(&text, &name).map_err(|e| e.to_string()))
            .collect::<Result<Vec<_>, _>>()?;
        let subscription = Subscription {
            id: new_id(),
            name: NAME.to_string(),
            url: String::new(),
            nodes,
            usage: None,
            updated_at_ms: crate::now_ms(),
        };
        self.lock().subscriptions.push(subscription.clone());
        self.save();
        Ok(subscription)
    }

    /// Server links pasted directly (one per line): kept as a list without a link to refresh.
    fn add_pasted(&self, text: &str, name: &str) -> Result<Subscription, String> {
        let nodes = parse_pasted(text, name.trim())?;
        let subscription = Subscription {
            id: new_id(),
            name: if name.trim().is_empty() { "Pasted servers".to_string() } else { name.trim().to_string() },
            url: String::new(),
            nodes,
            usage: None,
            updated_at_ms: crate::now_ms(),
        };
        self.lock().subscriptions.push(subscription.clone());
        self.save();
        Ok(subscription)
    }

    /// Reads the link again. Nothing happens for a subscription without a link.
    pub fn refresh(&self, id: &str) -> Result<(), String> {
        let Some(current) = self.lock().subscriptions.iter().find(|s| s.id == id && !s.url.is_empty()).cloned() else {
            return Ok(());
        };
        let fetched = fetch(&current.url)?;
        self.replace(Subscription { nodes: fetched.nodes, usage: fetched.usage, updated_at_ms: crate::now_ms(), ..current });
        Ok(())
    }

    /// Renames a subscription and, when `url` changed, reads the new link (the old one stays if the new one fails).
    pub fn update(&self, id: &str, name: &str, url: &str) -> Result<(), String> {
        let Some(current) = self.lock().subscriptions.iter().find(|s| s.id == id).cloned() else {
            return Ok(());
        };
        let clean_url = url.trim();
        let clean_name = if name.trim().is_empty() { current.name.clone() } else { name.trim().to_string() };
        if clean_url == current.url || current.url.is_empty() {
            self.replace(Subscription { name: clean_name, ..current });
            return Ok(());
        }
        if !clean_url.starts_with("https://") && !clean_url.starts_with("http://") {
            return Err("A subscription link starts with https://".to_string());
        }
        let fetched = fetch(clean_url)?;
        self.replace(Subscription {
            name: clean_name,
            url: clean_url.to_string(),
            nodes: fetched.nodes,
            usage: fetched.usage,
            updated_at_ms: crate::now_ms(),
            ..current
        });
        Ok(())
    }

    pub fn remove(&self, id: &str) {
        self.lock().subscriptions.retain(|s| s.id != id);
        self.save();
    }

    fn replace(&self, updated: Subscription) {
        for s in self.lock().subscriptions.iter_mut() {
            if s.id == updated.id {
                *s = updated.clone();
            }
        }
        self.save();
    }

    fn save(&self) {
        let text = {
            let inner = self.lock();
            to_json(&inner).to_string()
        };
        // write beside the file and rename, so a crash cannot leave half a list
        let temp = self.file.with_extension("json.tmp");
        let written = std::fs::write(&temp, text).and_then(|_| std::fs::rename(&temp, &self.file));
        if let Err(e) = written {
            log::warn(TAG, &format!("could not save the subscriptions ({e})"));
        }
    }

    fn load(&self) {
        let Ok(text) = std::fs::read_to_string(&self.file) else { return };
        match from_json(&text) {
            Ok(loaded) => *self.lock() = loaded,
            Err(e) => log::warn(TAG, &format!("could not read the saved subscriptions ({e})")),
        }
    }
}

fn fetch(url: &str) -> Result<ember_subs::subscription::Fetched, String> {
    // The link holds the account's secret: log only the host.
    log::info(TAG, &format!("fetching a subscription from {}", url_host(url).unwrap_or_default()));
    let fetched = fetch_subscription(&subscription_http(), url).map_err(|e| e.to_string())?;
    log::info(
        TAG,
        &format!("subscription read: {} entries, {} are information rows", fetched.nodes.len(), fetched.nodes.iter().filter(|n| n.is_info()).count()),
    );
    Ok(fetched)
}

fn to_json(inner: &Inner) -> Value {
    let subscriptions: Vec<Value> = inner
        .subscriptions
        .iter()
        .map(|s| {
            let mut o = json!({
                "id": s.id, "name": s.name, "url": s.url, "updatedAt": s.updated_at_ms,
                "nodes": s.nodes.iter().map(|n| n.raw.clone()).collect::<Vec<_>>(),
            });
            if let Some(u) = &s.usage {
                let mut usage = json!({"up": u.uploaded, "down": u.downloaded, "total": u.total});
                if let Some(e) = u.expires_at_epoch_seconds {
                    usage["expire"] = json!(e);
                }
                o["usage"] = usage;
            }
            o
        })
        .collect();
    let results: Map<String, Value> = inner
        .results
        .iter()
        .map(|(id, r)| (id.clone(), json!({"ping": r.ping_ms, "mbps": r.mbps, "at": r.tested_at_ms})))
        .collect();
    let addresses: Map<String, Value> = inner.addresses.iter().map(|(id, ip)| (id.clone(), json!(ip))).collect();
    json!({"subscriptions": subscriptions, "results": results, "addresses": addresses})
}

fn from_json(text: &str) -> Result<Inner, String> {
    let root: Value = serde_json::from_str(text).map_err(|e| e.to_string())?;
    let mut inner = Inner::default();
    for o in root.get("subscriptions").and_then(Value::as_array).into_iter().flatten() {
        let nodes = o
            .get("nodes")
            .and_then(Value::as_array)
            .map(|a| a.iter().filter_map(Value::as_str).filter_map(|raw| parse_share_link(raw).ok()).collect())
            .unwrap_or_default();
        let usage = o.get("usage").map(|u| SubscriptionUsage {
            uploaded: u.get("up").and_then(Value::as_i64).unwrap_or(0),
            downloaded: u.get("down").and_then(Value::as_i64).unwrap_or(0),
            total: u.get("total").and_then(Value::as_i64).unwrap_or(0),
            expires_at_epoch_seconds: u.get("expire").and_then(Value::as_i64).filter(|e| *e > 0),
        });
        inner.subscriptions.push(Subscription {
            id: o.get("id").and_then(Value::as_str).ok_or("a subscription has no id")?.to_string(),
            name: o.get("name").and_then(Value::as_str).unwrap_or_default().to_string(),
            url: o.get("url").and_then(Value::as_str).unwrap_or_default().to_string(),
            nodes,
            usage,
            updated_at_ms: o.get("updatedAt").and_then(Value::as_i64).unwrap_or(0),
        });
    }
    for (id, ip) in root.get("addresses").and_then(Value::as_object).into_iter().flatten() {
        if let Some(ip) = ip.as_str() {
            inner.addresses.insert(id.clone(), ip.to_string());
        }
    }
    for (id, r) in root.get("results").and_then(Value::as_object).into_iter().flatten() {
        inner.results.insert(
            id.clone(),
            NodeResult {
                ping_ms: r.get("ping").and_then(Value::as_i64).filter(|p| *p >= 0).map(|p| p as i32),
                mbps: r.get("mbps").and_then(Value::as_f64),
                tested_at_ms: r.get("at").and_then(Value::as_i64).unwrap_or(0),
            },
        );
    }
    Ok(inner)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn temp(name: &str) -> PathBuf {
        let dir = std::env::temp_dir().join(format!("ember-engine-test-{}-{name}", std::process::id()));
        let _ = std::fs::remove_dir_all(&dir);
        std::fs::create_dir_all(&dir).unwrap();
        dir.join("subscriptions.json")
    }

    // Test links use example.com and made-up ids only.
    const LINK_A: &str = "vless://11111111-2222-3333-4444-555555555555@a.example.com:443?type=tcp&security=tls#Alpha";
    const LINK_B: &str = "vless://11111111-2222-3333-4444-555555555555@b.example.com:443?type=tcp&security=tls#Beta";

    #[test]
    fn pasted_links_become_a_subscription_that_survives_a_restart() {
        let path = temp("pasted");
        let store = SubscriptionStore::open(&path);
        let added = store.add(&format!("{LINK_A}\n{LINK_B}"), "").unwrap();
        assert_eq!((added.name.as_str(), added.nodes.len(), added.url.as_str()), ("Pasted servers", 2, ""));
        let first = added.nodes[0].clone();
        store.record_result(&first, NodeResult { ping_ms: Some(42), mbps: Some(18.5), tested_at_ms: 1000 });
        store.remember_address(&first, "203.0.113.9");
        store.save_results();

        let again = SubscriptionStore::open(&path);
        assert_eq!(again.subscriptions().len(), 1);
        assert_eq!(again.node_by_id(&first.id).unwrap().host, "a.example.com");
        assert_eq!(again.results()[&first.id].ping_ms, Some(42));
        assert_eq!(again.remembered_address(&first).as_deref(), Some("203.0.113.9"));
        assert_eq!(again.best_node().unwrap().id, first.id);
    }

    #[test]
    fn reads_the_layout_the_kotlin_app_writes() {
        let path = temp("kotlin");
        let text = format!(
            r#"{{"subscriptions":[{{"id":"ab12cd34","name":"Work","url":"https://panel.example.com/sub/x","updatedAt":1700000000000,"nodes":[{a:?},{b:?}],"usage":{{"up":10,"down":20,"total":100,"expire":1800000000}}}}],"results":{{"deadbeef":{{"ping":null,"mbps":null,"at":5}},"cafebabe":{{"ping":30,"mbps":12.5,"at":6}}}},"addresses":{{"x":"198.51.100.1"}}}}"#,
            a = LINK_A,
            b = LINK_B
        );
        std::fs::write(&path, text).unwrap();
        let store = SubscriptionStore::open(&path);
        let subs = store.subscriptions();
        assert_eq!(subs[0].name, "Work");
        assert_eq!(subs[0].nodes.len(), 2);
        assert_eq!(subs[0].usage.as_ref().unwrap().used(), 30);
        assert_eq!(subs[0].usage.as_ref().unwrap().expires_at_epoch_seconds, Some(1_800_000_000));
        let results = store.results();
        assert_eq!(results["deadbeef"].ping_ms, None);
        assert_eq!(results["cafebabe"].mbps, Some(12.5));
    }

    #[test]
    fn unreadable_files_mean_an_empty_list_and_nothing_is_lost_on_a_bad_add() {
        let path = temp("bad");
        std::fs::write(&path, "not json").unwrap();
        let store = SubscriptionStore::open(&path);
        assert!(store.subscriptions().is_empty());
        assert!(store.add("hello world", "").is_err());
        assert!(store.subscriptions().is_empty());
        store.add(LINK_A, "Mine").unwrap();
        let id = store.subscriptions()[0].id.clone();
        store.update(&id, "Renamed", "").unwrap();
        assert_eq!(store.subscriptions()[0].name, "Renamed");
        store.remove(&id);
        assert!(SubscriptionStore::open(&path).subscriptions().is_empty());
    }
}
