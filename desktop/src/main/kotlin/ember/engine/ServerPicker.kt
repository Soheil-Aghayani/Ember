package ember.engine

import com.soheil.ember.data.model.ProxyCandidate
import com.soheil.ember.vpn.PingUtil
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

private const val SAMPLE_SIZE = 8

/** Chooses the server that answers fastest from a few random candidates (before the tunnel is up, over the real network). */
object ServerPicker {
    suspend fun fastest(candidates: List<ProxyCandidate>): ProxyCandidate? {
        if (candidates.isEmpty()) return null
        val sample = candidates.shuffled().take(SAMPLE_SIZE)
        val timed = coroutineScope {
            sample.map { candidate ->
                async { candidate to PingUtil.measureTcpLatencyMs(candidate.host, candidate.port) }
            }.awaitAll()
        }
        return timed.filter { it.second != null }.minByOrNull { it.second!! }?.first ?: sample.first()
    }
}
