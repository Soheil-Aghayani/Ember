package ember.subs

/** Favourites first; then repeat-probe reliability, low jitter, speed and delay. */
fun sortNodes(nodes: List<ProxyNode>, results: Map<String, NodeResult>, favorites: Set<String>): List<ProxyNode> =
    nodes.sortedWith(
        compareByDescending<ProxyNode> { it.id in favorites }
            .thenByDescending { results[it.id]?.successRate ?: if (results[it.id]?.pingMs != null) 1.0 else 0.0 }
            .thenBy { results[it.id]?.jitterMs ?: Int.MAX_VALUE }
            .thenByDescending { results[it.id]?.mbps ?: -1.0 }
            .thenBy { results[it.id]?.pingMs ?: Int.MAX_VALUE },
    )

/**
 * The server to switch to when [current] stops passing traffic: the best remaining one that has not just failed.
 * Servers a test found dead are left out; when nothing is known to answer, servers never tested are tried.
 */
fun pickFailover(
    all: List<ProxyNode>,
    current: ProxyNode,
    failed: Set<String>,
    results: Map<String, NodeResult>,
    favorites: Set<String>,
): ProxyNode? {
    val others = all.filter { !it.isInfo && it.protocol != "openvpn" && it.id != current.id && it.id !in failed }
    val pool = others.filter { results[it.id]?.pingMs != null }.ifEmpty { others.filter { results[it.id] == null } }
    return sortNodes(pool, results, favorites).firstOrNull()
}
