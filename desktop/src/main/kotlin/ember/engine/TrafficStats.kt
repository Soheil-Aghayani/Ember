package ember.engine

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class TrafficReading(
    val downloadSpeed: Long = 0,
    val uploadSpeed: Long = 0,
    val downloaded: Long = 0,
    val uploaded: Long = 0,
    val available: Boolean = false,
)

class TrafficStats : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutable = MutableStateFlow(TrafficReading())
    val readings: StateFlow<TrafficReading> = mutable
    @Volatile private var process: Process? = null

    init {
        scope.launch {
            var first: Pair<Long, Long>? = null
            var previous: Pair<Long, Long>? = null
            var previousTime = 0L
            while (isActive) {
                val sample = readCounters()
                val now = System.nanoTime()
                if (sample != null && isActive) {
                    val prior = previous
                    if (first == null || (prior != null && (sample.first < prior.first || sample.second < prior.second))) {
                        first = sample
                        previous = null
                    }
                    val baseline = first ?: sample
                    val seconds = (now - previousTime) / 1_000_000_000.0
                    val last = previous
                    mutable.value = TrafficReading(
                        if (last != null && seconds > 0) ((sample.first - last.first) / seconds).toLong().coerceAtLeast(0) else 0,
                        if (last != null && seconds > 0) ((sample.second - last.second) / seconds).toLong().coerceAtLeast(0) else 0,
                        (sample.first - baseline.first).coerceAtLeast(0),
                        (sample.second - baseline.second).coerceAtLeast(0), true,
                    )
                    previous = sample
                    previousTime = now
                } else mutable.value = mutable.value.copy(downloadSpeed = 0, uploadSpeed = 0, available = false)
                delay(2_000)
            }
        }
    }

    private fun readCounters(): Pair<Long, Long>? = runCatching {
        val child = ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-Command",
            "Get-NetAdapterStatistics -Name EmberTun | Select-Object ReceivedBytes,SentBytes | ConvertTo-Json -Compress")
            .redirectError(ProcessBuilder.Redirect.DISCARD).start()
        process = child
        try {
            if (!scope.isActive || !child.waitFor(5, TimeUnit.SECONDS) || child.exitValue() != 0) return@runCatching null
            val json = JSONObject(child.inputStream.bufferedReader().use { it.readText() })
            json.getLong("ReceivedBytes") to json.getLong("SentBytes")
        } finally {
            if (child.isAlive) child.destroyForcibly()
            process = null
        }
    }.getOrNull()

    override fun close() {
        scope.cancel()
        process?.destroyForcibly()
    }
}
