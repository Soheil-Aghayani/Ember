package ember.subs

/** Favourites first, then the fastest measured download, then the lowest delay; untested servers last. */
fun sortNodes(nodes: List<ProxyNode>, results: Map<String, NodeResult>, favorites: Set<String>): List<ProxyNode> =
    nodes.sortedWith(
        compareByDescending<ProxyNode> { it.id in favorites }
            .thenByDescending { results[it.id]?.mbps ?: -1.0 }
            .thenBy { results[it.id]?.pingMs ?: Int.MAX_VALUE },
    )
