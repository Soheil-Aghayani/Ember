//! Ordering and picking servers from test results. Port of `NodeSort.kt` and `SubscriptionStore.bestNode`.

use std::cmp::Ordering;
use std::collections::{HashMap, HashSet};

use crate::node::Node;
use crate::subscription::NodeResult;

fn mbps_of(results: &HashMap<String, NodeResult>, id: &str) -> f64 {
    results.get(id).and_then(|r| r.mbps).unwrap_or(-1.0)
}

fn ping_of(results: &HashMap<String, NodeResult>, id: &str) -> i64 {
    results.get(id).and_then(|r| r.ping_ms).map_or(i64::from(i32::MAX), i64::from)
}

/// Favourites first, then the fastest measured download, then the lowest delay; untested servers last.
pub fn sort_nodes(nodes: &[Node], results: &HashMap<String, NodeResult>, favorites: &HashSet<String>) -> Vec<Node> {
    let mut sorted = nodes.to_vec();
    sorted.sort_by(|a, b| {
        favorites
            .contains(&b.id)
            .cmp(&favorites.contains(&a.id))
            .then_with(|| mbps_of(results, &b.id).total_cmp(&mbps_of(results, &a.id)))
            .then_with(|| ping_of(results, &a.id).cmp(&ping_of(results, &b.id)))
    });
    sorted
}

/// The server to switch to when `current` stops passing traffic: the best remaining one that has not just failed.
/// Servers a test found dead are left out; when nothing is known to answer, servers never tested are tried.
pub fn pick_failover(
    all: &[Node],
    current: &Node,
    failed: &HashSet<String>,
    results: &HashMap<String, NodeResult>,
    favorites: &HashSet<String>,
) -> Option<Node> {
    let others: Vec<Node> = all
        .iter()
        .filter(|n| !n.is_info() && n.protocol != "openvpn" && n.id != current.id && !failed.contains(&n.id))
        .cloned()
        .collect();
    let answering: Vec<Node> = others.iter().filter(|n| results.get(&n.id).is_some_and(|r| r.ping_ms.is_some())).cloned().collect();
    let pool = if answering.is_empty() { others.into_iter().filter(|n| !results.contains_key(&n.id)).collect() } else { answering };
    sort_nodes(&pool, results, favorites).into_iter().next()
}

/// The tested server with the best speed (or, failing that, the lowest delay) among `nodes` (all subscriptions').
/// OpenVPN servers cannot be measured by Xray, so they are never the "fastest" pick; with no test results the first
/// usable server is returned.
pub fn best_node(nodes: &[Node], results: &HashMap<String, NodeResult>) -> Option<Node> {
    let candidates: Vec<&Node> = nodes.iter().filter(|n| !n.is_info() && n.protocol != "openvpn").collect();
    let mut best: Option<&Node> = None;
    for node in candidates.iter().filter(|n| results.get(&n.id).is_some_and(|r| r.ping_ms.is_some())) {
        let better = match best {
            None => true,
            // maxWithOrNull keeps the first of equals
            Some(b) => compare_best(results, b, node) == Ordering::Less,
        };
        if better {
            best = Some(node);
        }
    }
    best.or_else(|| candidates.first().copied()).cloned()
}

fn compare_best(results: &HashMap<String, NodeResult>, a: &Node, b: &Node) -> Ordering {
    mbps_of(results, &a.id)
        .total_cmp(&mbps_of(results, &b.id))
        .then_with(|| (-ping_of(results, &a.id)).cmp(&(-ping_of(results, &b.id))))
}
