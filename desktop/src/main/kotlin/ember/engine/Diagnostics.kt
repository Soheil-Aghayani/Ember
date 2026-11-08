package ember.engine

/**
 * The text a user pastes when something goes wrong on their computer: what runs where, then the recent log. Links to
 * servers, account ids and IP addresses are removed first; server and site names stay, because they are what explains most failures.
 */
object Diagnostics {
    private val link = Regex("""\b(vless|vmess|trojan|ss|hysteria2|hy2|wireguard|socks5?)://\S+""", RegexOption.IGNORE_CASE)
    private val uuid = Regex("""\b[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\b""", RegexOption.IGNORE_CASE)
    private val ipv4 = Regex("""\b\d{1,3}(\.\d{1,3}){3}\b""")
    private val longToken = Regex("""\b[A-Za-z0-9_\-+/=]{32,}\b""") // keys and tokens

    fun redact(text: String): String = text
        .replace(link) { "${it.groupValues[1]}://<removed>" }
        .replace(uuid, "<id>")
        .replace(ipv4, "<ip>")
        .replace(longToken, "<token>")

    fun report(version: String?, lines: List<String>): String = buildString {
        appendLine("Ember ${version ?: "development build"}")
        appendLine("Windows: ${System.getProperty("os.name")} ${System.getProperty("os.version")} (${System.getProperty("os.arch")})")
        appendLine("Java: ${System.getProperty("java.version")}, ${Runtime.getRuntime().availableProcessors()} cores, up to ${Runtime.getRuntime().maxMemory() / 1_048_576} MB heap")
        appendLine("Graphics: ${System.getenv("SKIKO_RENDER_API") ?: System.getProperty("skiko.renderApi") ?: "default"}")
        appendLine("--- last ${lines.size} log lines (oldest first) ---")
        lines.forEach { appendLine(redact(it)) }
    }
}
